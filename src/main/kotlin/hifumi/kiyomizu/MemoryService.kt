package hifumi.kiyomizu

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import java.net.URI
import java.time.Instant

object MemoryService {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private const val duplicateMemorySimilarityThreshold = 0.92
    private const val maxUpstreamResponseBytes = 2 * 1024 * 1024 // 2 MiB cap for memory-service upstream replies

    // Last proxy request timestamp (epoch ms). Maintained by Main.kt's proxy path to
    // drive sleep/idle detection for offline consolidation.
    private val lastRequestAtMs = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
    // Last successful consolidation timestamp (epoch ms) + counters, surfaced to the UI.
    private val lastConsolidationAtMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val lastConsolidationNodes = java.util.concurrent.atomic.AtomicInteger(0)
    private val lastConsolidationEdges = java.util.concurrent.atomic.AtomicInteger(0)

    fun touchLastRequest() {
        lastRequestAtMs.set(System.currentTimeMillis())
    }

    fun lastConsolidationAt(): Long = lastConsolidationAtMs.get()
    fun lastConsolidationSummary(): JsonObject = buildJsonObject {
        put("at", lastConsolidationAtMs.get())
        put("nodes", lastConsolidationNodes.get())
        put("edges", lastConsolidationEdges.get())
    }

    private suspend fun HttpResponse.boundedBodyAsText(maxBytes: Int = maxUpstreamResponseBytes): String {
        val channel = bodyAsChannel()
        val buffer = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (!channel.isClosedForRead) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read < 0) break
            if (read == 0) continue
            if (total + read > maxBytes) {
                throw IllegalStateException("upstream response exceeded $maxBytes bytes")
            }
            out.write(buffer, 0, read)
            total += read
        }
        return out.toString(Charsets.UTF_8.name())
    }

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

    /**
     * Sleep/offline consolidation loop (hipocampal-replay analog). When the proxy has
     * been idle (no traffic) for at least [Config.memoryConsolidationIdleMinutes], run a
     * single consolidation pass over recently-reactivated, affect-salient episodic
     * memories, abstracting them into higher-order semantic nodes and linking them in
     * the association graph. Decoupled from the decay job; safe to interrupt; re-runnable.
     */
    fun startConsolidationJob(appScope: CoroutineScope) {
        appScope.launch {
            while (isActive) {
                delay(60_000L) // poll once per minute
                if (!Config.memoryEnabled) continue
                try {
                    val idleMs = System.currentTimeMillis() - lastRequestAtMs.get()
                    val idleTarget = Config.memoryConsolidationIdleMinutes * 60_000L
                    if (idleMs < idleTarget) continue
                    // Avoid re-triggering within the same idle window.
                    if (lastConsolidationAtMs.get() > lastRequestAtMs.get()) continue
                    consolidateOnce()
                } catch (e: Exception) {
                    System.err.println("Error in consolidation job: ${e.message}")
                }
            }
        }
    }

    private suspend fun consolidateOnce() {
        val idleSeconds = (Config.memoryConsolidationIdleMinutes * 2L * 60L)
        val candidates = DatabaseService.getConsolidationCandidates(idleSeconds, limit = 20)
        if (candidates.isEmpty()) return

        val existingSemantic = DatabaseService.getAllMemoriesForSearch().filter { it.type == "semantic" }
        val tauBase = Config.memoryDecayTauHours
        var nodesCreated = 0
        var edgesCreated = 0

        // Cluster candidates by vector similarity, then abstract each cluster.
        val clustered = clusterBySimilarity(candidates, 0.65)
        for (cluster in clustered) {
            try {
                val episodicIds = cluster.map { it.id }
                val clusterText = cluster.joinToString("\n") { "- ${it.content}" }
                val abstraction = fetchSemanticAbstraction(clusterText) ?: continue
                val vector = fetchEmbedding(abstraction) ?: continue

                val dup = findDuplicateMemory(existingSemantic, abstraction, vector, "semantic", Config.memorySemanticDedupThreshold)
                val semanticId: Int
                if (dup != null) {
                    val (existing, _) = dup
                    semanticId = existing.id
                    val salience = cluster.map { emotionalSalience(it.emotionValence, it.emotionArousal) }.average()
                    val boost = Config.memoryRecoveryAmount * (1.0 + Config.memorySalienceK * salience)
                    DatabaseService.updateMemoryAccessAndStrength(existing.id, boost, Config.memoryMaxStrength)
                    DatabaseService.addRelatedIds(existing.id, episodicIds)
                    println("Consolidation merged cluster into semantic id=${existing.id} (+${episodicIds.size} links)")
                } else {
                    val avgV = cluster.map { it.emotionValence }.average()
                    val avgA = cluster.map { it.emotionArousal }.average()
                    semanticId = DatabaseService.insertMemory(
                        content = abstraction,
                        vector = vector,
                        type = "semantic",
                        emotionTag = "consolidated",
                        initialStrength = Config.memoryInitialStrength,
                        emotionValence = avgV,
                        emotionArousal = avgA,
                        relatedIds = episodicIds
                    )
                    nodesCreated++
                    println("Consolidation created semantic node id=$semanticId from ${episodicIds.size} episodic memories")
                }

                // Bidirectional edges: link episodic memories to the semantic node and to each other.
                for (eid in episodicIds) {
                    DatabaseService.addRelatedIds(eid, listOf(semanticId))
                    edgesCreated++
                }
                // Inter-link episodic memories within the cluster.
                for (eid in episodicIds) {
                    DatabaseService.addRelatedIds(eid, episodicIds - eid)
                }
            } catch (e: Exception) {
                System.err.println("Consolidation of a cluster failed: ${e.message}")
            }
        }

        lastConsolidationAtMs.set(System.currentTimeMillis())
        lastConsolidationNodes.set(nodesCreated)
        lastConsolidationEdges.set(edgesCreated)
        println("Consolidation pass done: clusters=${clustered.size}, newNodes=$nodesCreated, links=$edgesCreated (tau_base=${tauBase}h)")
    }

    private fun clusterBySimilarity(memories: List<DatabaseService.MemoryRecord>, threshold: Double): List<List<DatabaseService.MemoryRecord>> {
        val clusters = mutableListOf<MutableList<DatabaseService.MemoryRecord>>()
        for (m in memories) {
            val home = clusters.firstOrNull { group ->
                group.any { cosineSimilarity(it.vector, m.vector) >= threshold }
            }
            if (home != null) home.add(m) else clusters.add(mutableListOf(m))
        }
        return clusters
    }

    /**
     * Ask the summarization model to abstract a cluster of episodic memories into one
     * higher-order semantic statement. Reuses the summary model HTTP plumbing.
     */
    private suspend fun fetchSemanticAbstraction(clusterText: String): String? {
        val url = Config.memorySummaryUrl
        val key = Config.memorySummaryKey
        val model = Config.memorySummaryModel
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

        val prompt = """
            You are consolidating an AI companion's episodic memories during an offline "sleep" phase.
            Below are several related atomic episodic memories. Abstract them into ONE higher-order semantic statement
            that captures the durable pattern/fact they collectively imply about the user or the relationship.
            Keep it short, atomic, and in the same language as the memories. Output ONLY the single statement, no JSON, no markdown.
            Episodic memories:
            $clusterText
        """.trimIndent()

        return try {
            val response = ProxyService.client.post(finalUrl) {
                header("Content-Type", "application/json")
                if (isGeminiDirect) header("x-goog-api-key", key) else header("Authorization", "Bearer $key")
                val requestBody = if (isGeminiDirect) {
                    buildJsonObject {
                        put("contents", buildJsonArray {
                            add(buildJsonObject {
                                put("role", "user")
                                put("parts", buildJsonArray { add(buildJsonObject { put("text", prompt) }) })
                            })
                        })
                    }
                } else {
                    buildJsonObject {
                        put("model", model)
                        put("messages", buildJsonArray {
                            add(buildJsonObject { put("role", "user"); put("content", prompt) })
                        })
                    }
                }
                setBody(requestBody.toString())
            }
            val resText = response.boundedBodyAsText()
            val jsonEl = Json.parseToJsonElement(resText) as? JsonObject ?: return null
            if (isGeminiDirect) {
                jsonEl["candidates"]?.jsonArray?.getOrNull(0)?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray?.getOrNull(0)?.jsonObject
                    ?.get("text")?.jsonPrimitive?.contentOrNull?.trim()
            } else {
                jsonEl["choices"]?.jsonArray?.getOrNull(0)?.jsonObject
                    ?.get("message")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.contentOrNull?.trim()
            }?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            System.err.println("Error fetching semantic abstraction: ${e.message}")
            null
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
        candidateType: String,
        threshold: Double = duplicateMemorySimilarityThreshold
    ): Pair<DatabaseService.MemoryRecord, Double>? {
        val normalizedCandidate = normalizeMemoryContent(candidateContent)
        return existing.asSequence()
            .filter { it.type == candidateType }
            .map { memory ->
                val exactTextMatch = normalizeMemoryContent(memory.content) == normalizedCandidate
                val similarity = if (exactTextMatch) 1.0 else cosineSimilarity(candidateVector, memory.vector)
                memory to similarity
            }
            .filter { it.second >= threshold }
            .maxByOrNull { it.second }
    }

    /**
     * Emotional salience (amygdala-style amplification): arousal dominates, and valence
     * far from neutral (0.5) adds weight. salience = arousal * (0.5 + |valence - 0.5|).
     */
    fun emotionalSalience(valence: Double, arousal: Double): Double {
        val v = valence.coerceIn(0.0, 1.0)
        val a = arousal.coerceIn(0.0, 1.0)
        return a * (0.5 + Math.abs(v - 0.5))
    }

    /**
     * Effective Ebbinghaus decay time-constant, expanded by emotional salience so that
     * high-arousal / extreme-valence memories forget more slowly.
     */
    private fun effectiveTauHours(memory: DatabaseService.MemoryRecord): Double {
        val base = Config.memoryDecayTauHours.coerceAtLeast(1.0)
        val k = Config.memorySalienceK
        val salience = emotionalSalience(memory.emotionValence, memory.emotionArousal)
        return base * (1.0 + k * salience)
    }

    /**
     * Lazy Ebbinghaus decayed strength: stored strength * exp(-elapsedHours / tau_eff).
     * Computed at read time instead of via periodic writes.
     */
    fun lazyStrength(memory: DatabaseService.MemoryRecord, nowEpochSecond: Long = Instant.now().epochSecond): Double {
        val tauHours = effectiveTauHours(memory)
        val elapsedHours = ((nowEpochSecond - memory.lastAccessedAt).coerceAtLeast(0)) / 3600.0
        return memory.strength * Math.exp(-elapsedHours / tauHours)
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

            val resText = response.boundedBodyAsText()
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

            val resText = response.boundedBodyAsText()
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
            
            val resText = response.boundedBodyAsText()
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
                    val emotionValence = mObj["emotion_valence"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.5
                    val emotionArousal = mObj["emotion_arousal"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.3
                    val importance = mObj["importance"]?.jsonPrimitive?.doubleOrNull ?: 0.5

                    val vector = fetchEmbedding(content)
                    if (vector != null) {
                        val duplicate = findDuplicateMemory(existingMemories, content, vector, type)
                        if (duplicate != null) {
                            val (existingMemory, similarity) = duplicate
                            val salience = emotionalSalience(emotionValence, emotionArousal)
                            val strengthDelta = importance * Config.memoryRecoveryAmount * (1.0 + Config.memorySalienceK * salience)
                            DatabaseService.updateMemoryAccessAndStrength(
                                id = existingMemory.id,
                                strengthDelta = strengthDelta,
                                maxStrength = Config.memoryMaxStrength
                            )
                            println(
                                "Merged duplicate memory into id=${existingMemory.id} " +
                                    "[type=$type, similarity=${"%.3f".format(similarity)}]"
                            )
                        } else {
                            val newId = DatabaseService.insertMemory(
                                content = content,
                                vector = vector,
                                type = type,
                                emotionTag = emotionTag,
                                initialStrength = importance * Config.memoryInitialStrength,
                                emotionValence = emotionValence,
                                emotionArousal = emotionArousal
                            )
                            existingMemories.add(
                                DatabaseService.MemoryRecord(
                                    id = newId,
                                    content = content,
                                    vector = vector,
                                    type = type,
                                    emotionTag = emotionTag,
                                    strength = importance * Config.memoryInitialStrength,
                                    createdAt = 0,
                                    lastAccessedAt = 0,
                                    relatedIds = emptyList(),
                                    accessCount = 0,
                                    emotionValence = emotionValence,
                                    emotionArousal = emotionArousal
                                )
                            )
                            println("Inserted memory [type=$type, valence=${"%.2f".format(emotionValence)}, arousal=${"%.2f".format(emotionArousal)}]")
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

    data class RecalledMemory(
        val memory: DatabaseService.MemoryRecord,
        val associated: Boolean
    )

    suspend fun recallMemories(userQuery: String): List<RecalledMemory> {
        if (!Config.memoryEnabled) return emptyList()
        val recallLimit = Config.maxRecalledMemories.coerceAtLeast(0)
        if (recallLimit == 0) return emptyList()
        val queryVector = fetchEmbedding(userQuery) ?: return emptyList()

        val allMemories = DatabaseService.getAllMemoriesForSearch()
        if (allMemories.isEmpty()) return emptyList()
        val now = Instant.now().epochSecond

        // Score by Ebbinghaus-lazy strength (recency + affect-modulated forgetting).
        val scored = allMemories.map { memory ->
            val similarity = cosineSimilarity(queryVector, memory.vector)
            val score = similarity * lazyStrength(memory, now)
            memory to score
        }

        val sorted = scored.filter { it.second > 0.3 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(recallLimit)

        // Association-graph spread: one hop along related_ids, deduped, capped.
        val spread = Config.memoryAssociationSpread.coerceAtLeast(0)
        val primaryIds = sorted.map { it.id }.toMutableSet()
        val associated = LinkedHashMap<Int, DatabaseService.MemoryRecord>()
        if (spread > 0) {
            val byId = allMemories.associateBy { it.id }
            for (primary in sorted) {
                for (rid in primary.relatedIds) {
                    if (associated.size >= spread) break
                    if (rid in primaryIds || associated.containsKey(rid)) continue
                    byId[rid]?.let { associated[rid] = it }
                }
                if (associated.size >= spread) break
            }
        }

        // Reinforce both primary and associated memories (re-activation).
        for (m in sorted) {
            DatabaseService.updateMemoryAccessAndStrength(
                id = m.id,
                strengthDelta = Config.memoryRecoveryAmount,
                maxStrength = Config.memoryMaxStrength
            )
        }
        for (m in associated.values) {
            DatabaseService.updateMemoryAccessAndStrength(
                id = m.id,
                strengthDelta = Config.memoryRecoveryAmount * 0.5,
                maxStrength = Config.memoryMaxStrength
            )
        }

        val result = sorted.map { RecalledMemory(it, associated = false) } +
            associated.values.map { RecalledMemory(it, associated = true) }
        if (associated.isNotEmpty()) {
            println("Recalled ${sorted.size} primary + ${associated.size} associated memories via association graph.")
        }
        return result
    }
}
