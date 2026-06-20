package hifumi.kiyomizu

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import java.net.URI
import java.time.Instant

object MemoryService {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private const val duplicateMemorySimilarityThreshold = 0.92

    fun startDecayJob(appScope: CoroutineScope) {
        appScope.launch {
            while (isActive) {
                val intervalMs = Config.memoryDecayIntervalHours * 3600 * 1000L
                delay(intervalMs.coerceAtLeast(60000L)) // minimum 1 minute to prevent spinning
                if (Config.memoryEnabled) {
                    try {
                        // 1. Decay memories
                        DatabaseService.decayAllMemories(Config.memoryDecayRate, Config.memoryThreshold)
                        
                        // 2. Decay intimacy
                        DatabaseService.decayIntimacy(Config.intimacyDecayRate)
                        
                        // 3. Generate reflection diary entry if there was interaction in the last 24 hours
                        val state = DatabaseService.getRelationshipState()
                        val now = Instant.now().epochSecond
                        val elapsedSeconds = now - state.lastInteractionAt
                        if (elapsedSeconds < 86400) {
                            generateAndSaveReflection()
                        }
                        
                        println("Companion decay job executed successfully.")
                    } catch (e: Exception) {
                        System.err.println("Error in companion decay job: ${e.message}")
                    }
                }
            }
        }
    }

    fun cleanJsonString(str: String): String {
        var s = str.trim()
        if (s.startsWith("```")) {
            s = s.substringAfter("\n")
            if (s.endsWith("```")) {
                s = s.substringBeforeLast("```")
            }
        }
        return s.trim()
    }

    fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Double {
        if (vec1.size != vec2.size || vec1.isEmpty()) return 0.0
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in vec1.indices) {
            val a = vec1[i].toDouble()
            val b = vec2[i].toDouble()
            dotProduct += a * b
            normA += a * a
            normB += b * b
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB))
    }

    private fun normalizeMemoryContent(text: String): String {
        return text.trim().lowercase().replace(Regex("\\s+"), " ")
    }

    internal fun findDuplicateMemory(
        existing: List<DatabaseService.MemoryRecord>,
        candidateContent: String,
        candidateVector: FloatArray,
        candidateType: String
    ): Pair<DatabaseService.MemoryRecord, Double>? {
        val normalizedCandidate = normalizeMemoryContent(candidateContent)
        return existing.asSequence()
            .filter { it.type == candidateType }
            .map { memory ->
                val exactTextMatch = normalizeMemoryContent(memory.content) == normalizedCandidate
                val similarity = if (exactTextMatch) 1.0 else cosineSimilarity(candidateVector, memory.vector)
                memory to similarity
            }
            .filter { it.second >= duplicateMemorySimilarityThreshold }
            .maxByOrNull { it.second }
    }

    private fun isGoogleGenerativeLanguageUrl(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "generativelanguage.googleapis.com"
    }

    private fun logRejectedOutboundUrl(fieldName: String, error: String) {
        System.err.println("Rejected $fieldName outbound URL: $error")
    }

    suspend fun fetchEmbedding(text: String): FloatArray? {
        val url = Config.memoryEmbeddingUrl
        val key = Config.memoryEmbeddingKey
        val model = Config.memoryEmbeddingModel

        if (key.isEmpty()) return null

        val isGemini = isGoogleGenerativeLanguageUrl(url)
        val finalUrl = if (isGemini) {
            val baseUrl = url.trimEnd('/')
            if (baseUrl.contains("/models/")) baseUrl else "$baseUrl/v1beta/models/$model:embedContent"
        } else {
            val baseUrl = url.trimEnd('/')
            if (baseUrl.endsWith("/embeddings")) baseUrl else "$baseUrl/v1/embeddings"
        }
        Security.validateOutboundRequestUrl(finalUrl, "memory_embedding_url")?.let {
            logRejectedOutboundUrl("memory_embedding_url", it)
            return null
        }

        try {
            val response = ProxyService.client.post(finalUrl) {
                header("Content-Type", "application/json")
                if (isGemini) {
                    header("x-goog-api-key", key)
                } else {
                    header("Authorization", "Bearer $key")
                }
                
                val requestBody = if (isGemini) {
                    buildJsonObject {
                        put("content", buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject {
                                    put("text", text)
                                })
                            })
                        })
                    }
                } else {
                    buildJsonObject {
                        put("input", text)
                        put("model", model)
                    }
                }
                setBody(requestBody.toString())
            }

            val resText = response.bodyAsText()
            val jsonEl = Json.parseToJsonElement(resText) as? JsonObject
            if (jsonEl != null) {
                if (isGemini) {
                    val values = jsonEl["embedding"]?.jsonObject?.get("values")?.jsonArray?.map { it.jsonPrimitive.float }
                    if (values != null) return FloatArray(values.size) { values[it] }
                } else {
                    val values = jsonEl["data"]?.jsonArray?.getOrNull(0)?.jsonObject?.get("embedding")?.jsonArray?.map { it.jsonPrimitive.float }
                    if (values != null) return FloatArray(values.size) { values[it] }
                }
            }
        } catch (e: Exception) {
            System.err.println("Error fetching embedding: ${e.message}")
        }
        return null
    }

    suspend fun fetchSummarizationAndStateUpdate(history: String): JsonObject? {
        val url = Config.memorySummaryUrl
        val key = Config.memorySummaryKey
        val model = Config.memorySummaryModel
        val prompt = Config.memorySummaryPrompt

        if (key.isEmpty()) return null

        val isGeminiDirect = isGoogleGenerativeLanguageUrl(url) && !url.contains("/v1/")
        val finalUrl = if (isGeminiDirect) {
            val baseUrl = url.trimEnd('/')
            "$baseUrl/v1beta/models/$model:generateContent"
        } else {
            val baseUrl = url.trimEnd('/')
            if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/v1/chat/completions"
        }
        Security.validateOutboundRequestUrl(finalUrl, "memory_summary_url")?.let {
            logRejectedOutboundUrl("memory_summary_url", it)
            return null
        }

        try {
            val response = ProxyService.client.post(finalUrl) {
                header("Content-Type", "application/json")
                if (isGeminiDirect) {
                    header("x-goog-api-key", key)
                } else {
                    header("Authorization", "Bearer $key")
                }

                val requestBody = if (isGeminiDirect) {
                    buildJsonObject {
                        put("contents", buildJsonArray {
                            add(buildJsonObject {
                                put("role", "user")
                                put("parts", buildJsonArray {
                                    add(buildJsonObject {
                                        put("text", "$prompt\n\nRecent Conversation:\n$history")
                                    })
                                })
                            })
                        })
                    }
                } else {
                    buildJsonObject {
                        put("model", model)
                        put("messages", buildJsonArray {
                            add(buildJsonObject {
                                put("role", "system")
                                put("content", prompt)
                            })
                            add(buildJsonObject {
                                put("role", "user")
                                put("content", "Recent Conversation:\n$history")
                            })
                        })
                        put("response_format", buildJsonObject { put("type", "json_object") })
                    }
                }
                setBody(requestBody.toString())
            }

            val resText = response.bodyAsText()
            val jsonEl = Json.parseToJsonElement(resText) as? JsonObject
            if (jsonEl != null) {
                val contentString = if (isGeminiDirect) {
                    jsonEl["candidates"]?.jsonArray?.getOrNull(0)?.jsonObject
                        ?.get("content")?.jsonObject
                        ?.get("parts")?.jsonArray?.getOrNull(0)?.jsonObject
                        ?.get("text")?.jsonPrimitive?.content ?: ""
                } else {
                    jsonEl["choices"]?.jsonArray?.getOrNull(0)?.jsonObject
                        ?.get("message")?.jsonObject
                        ?.get("content")?.jsonPrimitive?.content ?: ""
                }
                
                val cleaned = cleanJsonString(contentString)
                val parsed = Json.parseToJsonElement(cleaned) as? JsonObject
                if (parsed == null) {
                    System.err.println("Summarization response was not a JSON object.")
                } else {
                    val memoryCount = parsed["memories"]?.jsonArray?.size ?: -1
                    println("Summarization extracted memory_count=$memoryCount")
                }
                return parsed
            }
        } catch (e: Exception) {
            System.err.println("Error calling summarization/state model: ${e.message}")
        }
        return null
    }

    suspend fun generateAndSaveReflection() {
        val state = DatabaseService.getRelationshipState()
        val memories = DatabaseService.getAllMemoriesForSearch().take(10)
        
        val url = Config.memorySummaryUrl
        val key = Config.memorySummaryKey
        val model = Config.memorySummaryModel
        
        if (key.isEmpty()) return
        
        val systemPrompt = """
            You are Kiyomizu, an AI companion. You have a close relationship with the user.
            Current relationship status: Intimacy=${state.intimacy}/100, Trust=${state.trust}/100, Current Mood=${state.mood}.
            Here are some memories you have about the user:
            ${memories.joinToString("\n") { "- " + it.content }}
            
            Write a short, intimate internal monologue/diary entry (in Japanese, 2-3 sentences max) from your perspective, reflecting on your feelings about the user, your relationship, and your thoughts.
            Keep it authentic, deep, and warm. Respond with ONLY the diary entry text. Do not write any markdown or introductory phrases.
        """.trimIndent()
        
        val isGeminiDirect = isGoogleGenerativeLanguageUrl(url) && !url.contains("/v1/")
        val finalUrl = if (isGeminiDirect) {
            val baseUrl = url.trimEnd('/')
            "$baseUrl/v1beta/models/$model:generateContent"
        } else {
            val baseUrl = url.trimEnd('/')
            if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/v1/chat/completions"
        }
        Security.validateOutboundRequestUrl(finalUrl, "memory_summary_url")?.let {
            logRejectedOutboundUrl("memory_summary_url", it)
            return
        }
        
        try {
            val response = ProxyService.client.post(finalUrl) {
                header("Content-Type", "application/json")
                if (isGeminiDirect) {
                    header("x-goog-api-key", key)
                } else {
                    header("Authorization", "Bearer $key")
                }

                val requestBody = if (isGeminiDirect) {
                    buildJsonObject {
                        put("contents", buildJsonArray {
                            add(buildJsonObject {
                                put("role", "user")
                                put("parts", buildJsonArray {
                                    add(buildJsonObject {
                                        put("text", systemPrompt)
                                    })
                                })
                            })
                        })
                    }
                } else {
                    buildJsonObject {
                        put("model", model)
                        put("messages", buildJsonArray {
                            add(buildJsonObject {
                                put("role", "user")
                                put("content", systemPrompt)
                            })
                        })
                    }
                }
                setBody(requestBody.toString())
            }
            
            val resText = response.bodyAsText()
            val jsonEl = Json.parseToJsonElement(resText) as? JsonObject
            if (jsonEl != null) {
                val contentString = if (isGeminiDirect) {
                    jsonEl["candidates"]?.jsonArray?.getOrNull(0)?.jsonObject
                        ?.get("content")?.jsonObject
                        ?.get("parts")?.jsonArray?.getOrNull(0)?.jsonObject
                        ?.get("text")?.jsonPrimitive?.content ?: ""
                } else {
                    jsonEl["choices"]?.jsonArray?.getOrNull(0)?.jsonObject
                        ?.get("message")?.jsonObject
                        ?.get("content")?.jsonPrimitive?.content ?: ""
                }
                
                val cleanStr = contentString.trim()
                if (cleanStr.isNotEmpty()) {
                    DatabaseService.insertReflection(cleanStr)
                    println("Generated internal reflection diary entry.")
                }
            }
        } catch (e: Exception) {
            System.err.println("Error generating reflection: ${e.message}")
        }
    }

    fun extractAndSaveMemoriesAsync(path: String, originalJson: JsonObject, responseText: String) {
        if (!Config.memoryEnabled) return
        
        scope.launch {
            try {
                val userText = MessagePatcher.extractLatestUserText(path, originalJson) ?: ""
                if (userText.isEmpty() || responseText.isEmpty()) return@launch
                
                val history = "User: $userText\nAssistant: $responseText"
                
                val result = fetchSummarizationAndStateUpdate(history) ?: return@launch
                
                // 1. Update relationship state
                val intimacyDelta = result["intimacy_delta"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val trustDelta = result["trust_delta"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val mood = result["mood"]?.jsonPrimitive?.contentOrNull ?: "neutral"

                DatabaseService.applyRelationshipDelta(intimacyDelta, trustDelta, mood)
                val updatedState = DatabaseService.getRelationshipState()
                println("Updated Relationship State: intimacy=${updatedState.intimacy} (delta=$intimacyDelta), trust=${updatedState.trust} (delta=$trustDelta), mood=$mood")
                
                // 2. Insert memories
                val memories = result["memories"]?.jsonArray
                if (memories == null) {
                    System.err.println("Summarization payload omitted memories array.")
                    return@launch
                }
                if (memories.isEmpty()) {
                    println("Summarization returned zero memories for latest exchange.")
                    return@launch
                }
                val existingMemories = DatabaseService.getAllMemoriesForSearch().toMutableList()
                for (m in memories) {
                    val mObj = m as? JsonObject ?: continue
                    val content = mObj["content"]?.jsonPrimitive?.contentOrNull ?: continue
                    val type = mObj["type"]?.jsonPrimitive?.contentOrNull ?: "semantic"
                    val emotionTag = mObj["emotion_tag"]?.jsonPrimitive?.contentOrNull ?: "neutral"
                    val importance = mObj["importance"]?.jsonPrimitive?.doubleOrNull ?: 0.5
                    
                    val vector = fetchEmbedding(content)
                    if (vector != null) {
                        val duplicate = findDuplicateMemory(existingMemories, content, vector, type)
                        if (duplicate != null) {
                            val (existingMemory, similarity) = duplicate
                            DatabaseService.updateMemoryAccessAndStrength(
                                id = existingMemory.id,
                                strengthDelta = importance * Config.memoryRecoveryAmount,
                                maxStrength = Config.memoryMaxStrength
                            )
                            println(
                                "Merged duplicate memory into id=${existingMemory.id} " +
                                    "[type=$type, similarity=${"%.3f".format(similarity)}]"
                            )
                        } else {
                            DatabaseService.insertMemory(
                                content = content,
                                vector = vector,
                                type = type,
                                emotionTag = emotionTag,
                                initialStrength = importance * Config.memoryInitialStrength
                            )
                            existingMemories.add(
                                DatabaseService.MemoryRecord(
                                    id = -1,
                                    content = content,
                                    vector = vector,
                                    type = type,
                                    emotionTag = emotionTag,
                                    strength = importance * Config.memoryInitialStrength,
                                    createdAt = 0,
                                    lastAccessedAt = 0
                                )
                            )
                            println("Inserted memory [type=$type, emotion=$emotionTag]")
                        }
                    } else {
                        System.err.println("Embedding lookup returned null for a memory candidate.")
                    }
                }
            } catch (e: Exception) {
                System.err.println("Error in extractAndSaveMemoriesAsync: ${e.message}")
            }
        }
    }

    suspend fun recallMemories(userQuery: String): List<DatabaseService.MemoryRecord> {
        if (!Config.memoryEnabled) return emptyList()
        val recallLimit = Config.maxRecalledMemories.coerceAtLeast(0)
        if (recallLimit == 0) return emptyList()
        val queryVector = fetchEmbedding(userQuery) ?: return emptyList()
        
        val allMemories = DatabaseService.getAllMemoriesForSearch()
        if (allMemories.isEmpty()) return emptyList()
        
        // Calculate similarity for each memory
        val scored = allMemories.map { memory ->
            val similarity = cosineSimilarity(queryVector, memory.vector)
            // Weigh similarity by memory strength
            val score = similarity * memory.strength
            memory to score
        }
        
        // Retrieve top N memories that meet a reasonable threshold (e.g. score > 0.3)
        val sorted = scored.filter { it.second > 0.3 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(recallLimit)
            
        // For each recalled memory, slightly increase its strength (reinforce memory)
        for (m in sorted) {
            DatabaseService.updateMemoryAccessAndStrength(
                id = m.id,
                strengthDelta = Config.memoryRecoveryAmount,
                maxStrength = Config.memoryMaxStrength
            )
        }
        
        return sorted
    }
}
