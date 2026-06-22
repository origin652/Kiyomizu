package hifumi.kiyomizu

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

object MemoryService {
    private const val maxUpstreamResponseBytes = 2 * 1024 * 1024
    private const val primaryUserUri = "person://user/primary"
    private const val kiyomizuUri = "person://self/kiyomizu"
    private const val defaultDeepRecallWeakThreshold = 1.2
    private const val defaultDeepRecallDirectThreshold = 2.2
    private const val defaultNormalRecallThreshold = 1.0

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastRequestAtMs = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
    private val lastDeepRecallAtMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val lastDeepRecallCandidates = java.util.concurrent.atomic.AtomicInteger(0)
    private val lastDeepRecallClues = java.util.concurrent.atomic.AtomicInteger(0)

    private val deepRecallPatterns = listOf(
        Regex("""\bdo you remember\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcan you remember\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcan you recall\b""", RegexOption.IGNORE_CASE),
        Regex("""\bhelp me recall\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwhat did i tell you\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwhat do you remember\b""", RegexOption.IGNORE_CASE),
        Regex("""你还记得"""),
        Regex("""帮我回忆"""),
        Regex("""回忆一下"""),
        Regex("""之前我跟你说过"""),
        Regex("""你能不能想起来"""),
        Regex("""覚えてる"""),
        Regex("""思い出して""")
    )

    data class RecalledMemory(
        val memory: DatabaseService.MemoryNodeRecord,
        val score: Double,
        val associated: Boolean = false,
        val channel: String = "normal"
    )

    data class DeepRecallResult(
        val direct: List<RecalledMemory>,
        val weak: List<RecalledMemory>,
        val conflict: List<RecalledMemory>,
        val reconstruction: String? = null
    )

    data class CompanionMemoryContext(
        val recalled: List<RecalledMemory>,
        val personContext: List<RecalledMemory>,
        val deepRecall: DeepRecallResult? = null,
        val dreamTraces: List<RecalledMemory> = emptyList()
    )

    private data class SummaryNodePayload(
        val uri: String?,
        val kind: String,
        val content: String,
        val keywords: List<String>,
        val aliases: List<String>,
        val entities: List<String>,
        val topics: List<String>,
        val triggerPhrases: List<String>,
        val disclosure: String,
        val priority: Double,
        val confidence: Double,
        val strength: Double,
        val emotionValence: Double,
        val emotionArousal: Double,
        val scopeHint: String?,
        val personUri: String?,
        val projectUri: String?,
        val source: String,
        val rawEvidence: String?,
        val novelty: Double,
        val explicitRemember: Boolean
    )

    private data class SummaryEdgePayload(
        val fromUri: String,
        val toUri: String,
        val relation: String,
        val weight: Double
    )

    private data class RecallContext(
        val query: String,
        val normalizedQuery: String,
        val queryTerms: List<String>,
        val people: Set<String>,
        val projectTerms: Set<String>,
        val deepRecall: Boolean
    )

    private data class ScoredNode(
        val node: DatabaseService.MemoryNodeRecord,
        val score: Double,
        val associated: Boolean = false,
        val channel: String = "normal"
    )

    private data class DreamOperationPayload(
        val type: String,
        val sourceUri: String?,
        val targetUri: String?,
        val kind: String,
        val content: String?,
        val keywords: List<String>,
        val topics: List<String>,
        val sourceUris: List<String>,
        val confidence: Double,
        val priority: Double,
        val reason: String?
    )

    private data class DreamModelResult(
        val dreamSummary: String,
        val dreamJournal: String,
        val symbols: List<String>,
        val emotions: List<String>,
        val operations: List<DreamOperationPayload>,
        val reflectionMood: String?,
        val reflectionNote: String?
    )

    private suspend fun HttpResponse.boundedBodyAsText(maxBytes: Int = maxUpstreamResponseBytes): String {
        val text = bodyAsText()
        if (text.toByteArray(Charsets.UTF_8).size > maxBytes) {
            throw IllegalStateException("upstream response exceeded $maxBytes bytes")
        }
        return text
    }

    fun touchLastRequest() {
        lastRequestAtMs.set(System.currentTimeMillis())
    }

    fun startDecayJob(appScope: CoroutineScope) {
        appScope.launch {
            while (isActive) {
                val intervalMs = Config.memoryDecayIntervalHours * 3600 * 1000L
                delay(intervalMs.coerceAtLeast(60000L))
                if (!Config.memoryEnabled) continue
                try {
                    DatabaseService.decayGraphMemoryNodes(Config.memoryThreshold)
                    DatabaseService.decayAllMemories(Config.memoryDecayRate, Config.memoryThreshold)
                    DatabaseService.decayIntimacy(Config.intimacyDecayRate)
                    DatabaseService.expireMemoryObservations()
                    DatabaseService.compressExpiredRecycleBin()

                    val state = DatabaseService.getRelationshipState()
                    val now = Instant.now().epochSecond
                    if (now - state.lastInteractionAt < 86400) {
                        generateAndSaveReflection()
                    }
                } catch (e: Exception) {
                    System.err.println("Error in companion decay job: ${e.message}")
                }
            }
        }
    }

    fun startConsolidationJob(appScope: CoroutineScope) {
        appScope.launch {
            while (isActive) {
                delay(3600 * 1000L)
                if (!Config.memoryEnabled) continue
                try {
                    if (shouldRunAutoDream()) {
                        runDream(mode = "auto", dryRun = false)
                    }
                } catch (e: Exception) {
                    System.err.println("Error in companion dream job: ${e.message}")
                }
            }
        }
    }

    fun lastConsolidationSummary(): JsonObject = buildJsonObject {
        val latest = DatabaseService.getLatestDreamRun()
        put("at", latest?.finishedAt ?: latest?.startedAt ?: 0L)
        put("status", latest?.status ?: "never")
        put("mode", latest?.mode ?: "")
        put("input_nodes", latest?.inputNodeCount ?: 0)
        put("merged", latest?.mergedCount ?: 0)
        put("archived", latest?.archivedCount ?: 0)
        put("tombstoned", latest?.tombstonedCount ?: 0)
        put("created_dream", latest?.createdDreamCount ?: 0)
        put("created_consolidated", latest?.createdConsolidatedCount ?: 0)
        put("skipped", latest?.skippedCount ?: 0)
        put("dream_summary", latest?.dreamSummary ?: "")
        put("dream_journal", latest?.dreamJournal ?: "")
        put("next_allowed_at", latest?.nextAllowedAt ?: 0L)
    }

    fun lastDeepRecallSummary(): JsonObject = buildJsonObject {
        put("at", lastDeepRecallAtMs.get())
        put("candidates", lastDeepRecallCandidates.get())
        put("clues", lastDeepRecallClues.get())
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

    fun emotionalSalience(valence: Double, arousal: Double): Double {
        val v = valence.coerceIn(0.0, 1.0)
        val a = arousal.coerceIn(0.0, 1.0)
        return a * (0.5 + abs(v - 0.5))
    }

    private fun effectiveTauHours(memory: DatabaseService.MemoryNodeRecord): Double {
        val base = Config.memoryDecayTauHours.coerceAtLeast(1.0)
        val salience = emotionalSalience(memory.emotionValence, memory.emotionArousal)
        return base * (1.0 + Config.memorySalienceK * salience)
    }

    fun lazyStrength(memory: DatabaseService.MemoryNodeRecord, nowEpochSecond: Long = Instant.now().epochSecond): Double {
        val tauHours = effectiveTauHours(memory)
        val elapsedHours = ((nowEpochSecond - memory.lastAccessedAt).coerceAtLeast(0)) / 3600.0
        return memory.strength * exp(-elapsedHours / tauHours)
    }

    private fun normalizeText(text: String): String {
        return text.trim().lowercase().replace(Regex("\\s+"), " ")
    }

    private fun normalizeTerm(term: String): String {
        val lowered = term.trim().lowercase()
        val cleaned = lowered.replace(Regex("""[^\p{L}\p{N}/:_-]+"""), " ").replace(Regex("\\s+"), " ").trim()
        return cleaned
    }

    private fun tokenize(text: String): List<String> {
        return normalizeTerm(text)
            .split(Regex("""[\s/:_-]+"""))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
    }

    private fun extractUriSegments(uri: String): List<String> {
        val normalized = uri.substringAfter("://", uri)
        return normalized.split("/", "?", "#", "&", "-", "_")
            .map { normalizeTerm(it) }
            .flatMap { it.split(" ") }
            .filter { it.length >= 2 }
            .distinct()
    }

    private fun slugify(input: String): String {
        val lowered = input.lowercase()
        val slug = lowered.replace(Regex("""[^\p{L}\p{N}]+"""), "-").trim('-')
        return slug.take(48).ifBlank { "memory" }
    }

    private fun kindUriPrefix(kind: String): String {
        return when (kind) {
            "identity" -> "identity://auto"
            "preference" -> "preference://auto"
            "relationship" -> "relationship://auto"
            "project_fact" -> "project://auto"
            "episodic_event" -> "episode://auto/${Instant.now().toString().substring(0, 10)}"
            "working_memory" -> "working://auto/${Instant.now().toString().substring(0, 10)}"
            "reflection" -> "reflection://auto"
            else -> "memory://auto"
        }
    }

    private fun normalizeUri(rawUri: String?, kind: String, content: String): String {
        val trimmed = rawUri?.trim().orEmpty()
        if (trimmed.contains("://")) {
            val scheme = trimmed.substringBefore("://").lowercase()
            val rest = trimmed.substringAfter("://").split("/")
                .map { slugify(it) }
                .filter { it.isNotBlank() }
            val normalizedRest = if (rest.isEmpty()) slugify(content) else rest.joinToString("/")
            return "$scheme://$normalizedRest"
        }
        val prefix = kindUriPrefix(kind)
        return "$prefix/${slugify(trimmed.ifBlank { content })}"
    }

    private fun inferPersonUri(content: String, entities: List<String>, explicitPersonUri: String?): String? {
        if (!explicitPersonUri.isNullOrBlank()) return normalizeUri(explicitPersonUri, "identity", explicitPersonUri)
        val normalized = normalizeText(content)
        if (Regex("""\b(i|me|my|mine)\b""", RegexOption.IGNORE_CASE).containsMatchIn(content) ||
            normalized.contains("我") ||
            normalized.contains("我的") ||
            normalized.contains("わたし") ||
            normalized.contains("私")
        ) {
            return primaryUserUri
        }
        val personEntity = entities.firstOrNull { it.startsWith("person://") }
        return personEntity?.let { normalizeUri(it, "identity", it) }
    }

    private fun inferProjectUri(content: String, topics: List<String>, explicitProjectUri: String?): String? {
        if (!explicitProjectUri.isNullOrBlank()) return normalizeUri(explicitProjectUri, "project_fact", explicitProjectUri)
        val source = topics.firstOrNull { it.length >= 3 } ?: return null
        return if (Regex("""\b(project|repo|repository|代码库|项目)\b""", RegexOption.IGNORE_CASE).containsMatchIn(content)) {
            "project://${slugify(source)}"
        } else {
            null
        }
    }

    private fun buildSearchableText(
        content: String,
        keywords: List<String>,
        aliases: List<String>,
        entities: List<String>,
        topics: List<String>,
        triggerPhrases: List<String>,
        uri: String,
        scopeHint: String?,
        personUri: String?,
        projectUri: String?
    ): String {
        return buildList {
            add(content)
            addAll(keywords)
            addAll(aliases)
            addAll(entities)
            addAll(topics)
            addAll(triggerPhrases)
            addAll(extractUriSegments(uri))
            scopeHint?.let { add(it) }
            personUri?.let { addAll(extractUriSegments(it)) }
            projectUri?.let { addAll(extractUriSegments(it)) }
        }.joinToString(" ")
    }

    private fun deriveSearchTerms(draft: DatabaseService.MemoryNodeDraft): List<DatabaseService.MemorySearchTermDraft> {
        val drafts = mutableListOf<DatabaseService.MemorySearchTermDraft>()

        draft.keywords.forEach { token ->
            tokenize(token).forEach { drafts += DatabaseService.MemorySearchTermDraft(it, "keyword", 1.0) }
        }
        draft.aliases.forEach { token ->
            tokenize(token).forEach { drafts += DatabaseService.MemorySearchTermDraft(it, "alias", 1.0) }
        }
        draft.entities.forEach { token ->
            tokenize(token).forEach { drafts += DatabaseService.MemorySearchTermDraft(it, "entity", 0.9) }
        }
        draft.topics.forEach { token ->
            tokenize(token).forEach { drafts += DatabaseService.MemorySearchTermDraft(it, "topic", 0.8) }
        }
        draft.triggerPhrases.forEach { token ->
            tokenize(token).forEach { drafts += DatabaseService.MemorySearchTermDraft(it, "trigger", 1.1) }
        }
        extractUriSegments(draft.uri).forEach { drafts += DatabaseService.MemorySearchTermDraft(it, "path_segment", 0.7) }
        draft.scopeHint?.let { scope ->
            tokenize(scope).forEach { drafts += DatabaseService.MemorySearchTermDraft(it, "scope_term", 0.7) }
        }
        draft.personUri?.let { uri ->
            extractUriSegments(uri).forEach { drafts += DatabaseService.MemorySearchTermDraft(it, "person_alias", 0.9) }
        }
        tokenize(draft.content).take(16).forEach { drafts += DatabaseService.MemorySearchTermDraft(it, "keyword", 0.6) }

        return drafts
            .map { it.copy(term = normalizeTerm(it.term)) }
            .filter { it.term.isNotBlank() }
            .distinctBy { Pair(it.term, it.kind) }
    }

    private fun jaccardScore(left: Collection<String>, right: Collection<String>): Double {
        val l = left.toSet()
        val r = right.toSet()
        if (l.isEmpty() || r.isEmpty()) return 0.0
        val intersection = l.intersect(r).size.toDouble()
        val union = l.union(r).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    private fun findDuplicateNode(
        existing: List<DatabaseService.MemoryNodeRecord>,
        candidate: DatabaseService.MemoryNodeDraft
    ): DatabaseService.MemoryNodeRecord? {
        existing.firstOrNull { it.uri == candidate.uri }?.let { return it }

        val candidateTokens = tokenize(candidate.content) + candidate.keywords + candidate.topics + candidate.aliases
        return existing
            .filter { it.kind == candidate.kind }
            .map { node ->
                val textScore = if (node.normalizedText == candidate.normalizedText) {
                    1.0
                } else {
                    jaccardScore(
                        candidateTokens.map { normalizeTerm(it) },
                        tokenize(node.content) + node.keywords + node.topics + node.aliases
                    )
                }
                val personMatch = if (!candidate.personUri.isNullOrBlank() && candidate.personUri == node.personUri) 0.2 else 0.0
                val projectMatch = if (!candidate.projectUri.isNullOrBlank() && candidate.projectUri == node.projectUri) 0.2 else 0.0
                node to (textScore + personMatch + projectMatch)
            }
            .filter { it.second >= 0.9 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun mergeNodeDraft(
        existing: DatabaseService.MemoryNodeRecord,
        incoming: DatabaseService.MemoryNodeDraft
    ): DatabaseService.MemoryNodeDraft {
        fun mergeList(left: List<String>, right: List<String>): List<String> {
            return (left + right).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }

        val mergedUri = existing.uri
        val mergedKeywords = mergeList(existing.keywords, incoming.keywords)
        val mergedAliases = mergeList(existing.aliases, incoming.aliases)
        val mergedEntities = mergeList(existing.entities, incoming.entities)
        val mergedTopics = mergeList(existing.topics, incoming.topics)
        val mergedTriggers = mergeList(existing.triggerPhrases, incoming.triggerPhrases)
        val mergedContent = if (incoming.content.length >= existing.content.length) incoming.content else existing.content
        val mergedPersonUri = incoming.personUri ?: existing.personUri
        val mergedProjectUri = incoming.projectUri ?: existing.projectUri
        val mergedScopeHint = incoming.scopeHint ?: existing.scopeHint

        return DatabaseService.MemoryNodeDraft(
            uri = mergedUri,
            kind = existing.kind,
            content = mergedContent,
            normalizedText = normalizeText(mergedContent),
            searchableText = buildSearchableText(
                content = mergedContent,
                keywords = mergedKeywords,
                aliases = mergedAliases,
                entities = mergedEntities,
                topics = mergedTopics,
                triggerPhrases = mergedTriggers,
                uri = mergedUri,
                scopeHint = mergedScopeHint,
                personUri = mergedPersonUri,
                projectUri = mergedProjectUri
            ),
            keywords = mergedKeywords,
            aliases = mergedAliases,
            entities = mergedEntities,
            topics = mergedTopics,
            triggerPhrases = mergedTriggers,
            disclosure = if (existing.disclosure == "sensitive" || incoming.disclosure == "sensitive") "sensitive" else incoming.disclosure,
            priority = max(existing.priority, incoming.priority),
            confidence = max(existing.confidence, incoming.confidence),
            strength = max(existing.strength, incoming.strength),
            emotionValence = if (incoming.confidence >= existing.confidence) incoming.emotionValence else existing.emotionValence,
            emotionArousal = max(existing.emotionArousal, incoming.emotionArousal),
            scopeHint = mergedScopeHint,
            personUri = mergedPersonUri,
            projectUri = mergedProjectUri,
            status = existing.status,
            source = incoming.source.ifBlank { existing.source },
            rawEvidence = incoming.rawEvidence ?: existing.rawEvidence
        )
    }

    private fun anchorDraftForUri(uri: String): DatabaseService.MemoryNodeDraft {
        val normalized = normalizeUri(uri, "identity", uri)
        val label = normalized.substringAfter("://").replace("/", " ").replace("-", " ").trim()
        val kind = when {
            normalized.startsWith("person://") -> "identity"
            normalized.startsWith("project://") -> "project_fact"
            normalized.startsWith("relationship://") -> "relationship"
            normalized.startsWith("working://") -> "working_memory"
            else -> "identity"
        }
        val content = when (normalized) {
            primaryUserUri -> "The primary user."
            kiyomizuUri -> "Kiyomizu."
            else -> label.ifBlank { normalized }
        }
        return DatabaseService.MemoryNodeDraft(
            uri = normalized,
            kind = kind,
            content = content,
            normalizedText = normalizeText(content),
            searchableText = buildSearchableText(content, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), normalized, null, null, null),
            aliases = label.split(" ").filter { it.isNotBlank() },
            disclosure = "private",
            priority = 0.2,
            confidence = 0.9,
            strength = 0.6,
            source = "anchor"
        )
    }

    private fun ensureNodeExists(
        uri: String,
        existingNodes: MutableList<DatabaseService.MemoryNodeRecord>,
        uriToId: MutableMap<String, Int>
    ): Int {
        uriToId[uri]?.let { return it }
        existingNodes.firstOrNull { it.uri == uri }?.let {
            uriToId[uri] = it.id
            return it.id
        }
        DatabaseService.getMemoryNodeByUri(uri)?.let {
            existingNodes.add(it)
            uriToId[uri] = it.id
            return it.id
        }
        val draft = anchorDraftForUri(uri)
        val newId = DatabaseService.insertMemoryNode(draft)
        DatabaseService.replaceMemorySearchTerms(newId, deriveSearchTerms(draft))
        DatabaseService.getMemoryNodeById(newId)?.let { existingNodes.add(it) }
        uriToId[uri] = newId
        return newId
    }

    private fun isGoogleGenerativeLanguageUrl(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "generativelanguage.googleapis.com"
    }

    private fun logRejectedOutboundUrl(fieldName: String, error: String) {
        System.err.println("Rejected $fieldName outbound URL: $error")
    }

    private suspend fun callSummaryModel(prompt: String, requireJson: Boolean): String? {
        val url = Config.memorySummaryUrl
        val key = Config.memorySummaryKey
        val model = Config.memorySummaryModel
        if (key.isBlank()) return null

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

        return try {
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
                                    add(buildJsonObject { put("text", prompt) })
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
                                put("content", prompt)
                            })
                        })
                        if (requireJson) {
                            put("response_format", buildJsonObject { put("type", "json_object") })
                        }
                    }
                }
                setBody(requestBody.toString())
            }
            val body = response.boundedBodyAsText()
            val json = Json.parseToJsonElement(body) as? JsonObject ?: return null
            val content = if (isGeminiDirect) {
                json["candidates"]?.jsonArray?.getOrNull(0)?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray?.getOrNull(0)?.jsonObject
                    ?.get("text")?.jsonPrimitive?.contentOrNull
            } else {
                json["choices"]?.jsonArray?.getOrNull(0)?.jsonObject
                    ?.get("message")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.contentOrNull
            }
            content?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            System.err.println("Error calling summary model: ${e.message}")
            null
        }
    }

    private suspend fun fetchSummarizationAndStateUpdate(history: String): JsonObject? {
        val response = callSummaryModel("${Config.memorySummaryPrompt}\n\nRecent Conversation:\n$history", requireJson = true)
            ?: return null
        val cleaned = cleanJsonString(response)
        val parsed = Json.parseToJsonElement(cleaned) as? JsonObject
        if (parsed == null) {
            System.err.println("Summarization response was not a JSON object.")
            return null
        }
        val nodeCount = parsed["observations"]?.jsonArray?.size ?: parsed["nodes"]?.jsonArray?.size ?: 0
        println("Summarization extracted observation_count=$nodeCount")
        return parsed
    }

    private fun parseStringArray(element: kotlinx.serialization.json.JsonElement?): List<String> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotEmpty() }
    }

    private fun parseSummaryNodes(result: JsonObject): List<SummaryNodePayload> {
        val nodes = result["observations"]?.jsonArray ?: result["nodes"]?.jsonArray ?: return emptyList()
        return nodes.mapNotNull { nodeEl ->
            val node = nodeEl as? JsonObject ?: return@mapNotNull null
            val content = node["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (content.isEmpty()) return@mapNotNull null
            val kind = node["kind"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "working_memory" }
            SummaryNodePayload(
                uri = node["candidate_uri"]?.jsonPrimitive?.contentOrNull ?: node["uri"]?.jsonPrimitive?.contentOrNull,
                kind = kind,
                content = content,
                keywords = parseStringArray(node["keywords"]),
                aliases = parseStringArray(node["aliases"]),
                entities = parseStringArray(node["entities"]),
                topics = parseStringArray(node["topics"]),
                triggerPhrases = parseStringArray(node["trigger_phrases"]),
                disclosure = node["disclosure"]?.jsonPrimitive?.contentOrNull ?: "private",
                priority = node["priority"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.5,
                confidence = node["confidence"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.5,
                strength = node["strength"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0)
                    ?: node["importance"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0)
                    ?: Config.memoryInitialStrength,
                emotionValence = node["emotion_valence"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.5,
                emotionArousal = node["emotion_arousal"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.3,
                scopeHint = node["scope_hint"]?.jsonPrimitive?.contentOrNull,
                personUri = node["person_uri"]?.jsonPrimitive?.contentOrNull,
                projectUri = node["project_uri"]?.jsonPrimitive?.contentOrNull,
                source = node["source"]?.jsonPrimitive?.contentOrNull ?: "conversation",
                rawEvidence = node["raw_evidence"]?.jsonPrimitive?.contentOrNull,
                novelty = node["novelty"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.5,
                explicitRemember = node["explicit_remember"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }
    }

    private fun parseSummaryEdges(result: JsonObject): List<SummaryEdgePayload> {
        val edges = result["edges"]?.jsonArray ?: return emptyList()
        return edges.mapNotNull { edgeEl ->
            val edge = edgeEl as? JsonObject ?: return@mapNotNull null
            val fromUri = edge["from_uri"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val toUri = edge["to_uri"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val relation = edge["relation"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (fromUri.isEmpty() || toUri.isEmpty() || relation.isEmpty()) return@mapNotNull null
            SummaryEdgePayload(
                fromUri = fromUri,
                toUri = toUri,
                relation = relation,
                weight = edge["weight"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 5.0) ?: 1.0
            )
        }
    }

    private fun parseDreamModelResult(result: JsonObject): DreamModelResult {
        val operations = (result["operations"] as? JsonArray)?.mapNotNull { opEl ->
            val op = opEl as? JsonObject ?: return@mapNotNull null
            val type = op["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (type.isEmpty()) return@mapNotNull null
            DreamOperationPayload(
                type = type,
                sourceUri = op["source_uri"]?.jsonPrimitive?.contentOrNull,
                targetUri = op["target_uri"]?.jsonPrimitive?.contentOrNull,
                kind = op["kind"]?.jsonPrimitive?.contentOrNull ?: "working_memory",
                content = op["content"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null },
                keywords = parseStringArray(op["keywords"]),
                topics = parseStringArray(op["topics"]),
                sourceUris = parseStringArray(op["source_uris"]),
                confidence = op["confidence"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.5,
                priority = op["priority"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.5,
                reason = op["reason"]?.jsonPrimitive?.contentOrNull
            )
        } ?: emptyList()
        val reflection = result["emotion_reflection"] as? JsonObject
        return DreamModelResult(
            dreamSummary = result["dream_summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            dreamJournal = result["dream_journal"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            symbols = parseStringArray(result["symbols"]),
            emotions = parseStringArray(result["emotions"]),
            operations = operations,
            reflectionMood = reflection?.get("mood")?.jsonPrimitive?.contentOrNull,
            reflectionNote = reflection?.get("note")?.jsonPrimitive?.contentOrNull
        )
    }

    private fun shouldRunAutoDream(): Boolean {
        if (!Config.memoryEnabled || !Config.memoryDreamEnabled) return false
        if (Config.memoryDreamDailyLimit <= 0) return false
        val now = Instant.now().epochSecond
        val lastRequest = lastRequestAtMs.get() / 1000L
        val idleSeconds = now - lastRequest
        if (idleSeconds < Config.memoryDreamIdleHours * 3600L) return false
        if (idleSeconds > Config.memoryLongIdlePauseDays * 86400L) return false
        if (DatabaseService.countDreamRunsSince("auto", todayStartEpochSecond()) >= Config.memoryDreamDailyLimit) return false
        val latest = DatabaseService.getLatestDreamRun("auto")
        if (latest?.nextAllowedAt != null && latest.nextAllowedAt > now) return false
        return DatabaseService.getGraphNodeCount("active") + DatabaseService.getBufferedObservationCount() >= 5
    }

    private fun buildDreamPrompt(candidates: List<DatabaseService.MemoryNodeRecord>): String {
        return buildString {
            append(
                """
                You are Kiyomizu's private dreaming and memory-maintenance system.
                Produce JSON only. Dreams are not facts.
                Use lightly fragmented dream imagery in dream_journal, but keep operations precise.
                Do not invent new user facts. Sensitive or third-party details must be anonymized in dream_journal.
                Return this shape:
                {
                  "dream_summary": "clear searchable summary",
                  "dream_journal": "lightly fragmented dream narrative",
                  "symbols": ["symbol"],
                  "emotions": ["emotion"],
                  "operations": [
                    {
                      "type": "create_consolidated_node|create_dream_node|archive|skip",
                      "source_uri": "uri for archive/skip",
                      "target_uri": "uri for created nodes",
                      "kind": "project_fact",
                      "content": "clear node content",
                      "keywords": ["keyword"],
                      "topics": ["topic"],
                      "source_uris": ["uri"],
                      "confidence": 0.8,
                      "priority": 0.7,
                      "reason": "short reason"
                    }
                  ],
                  "emotion_reflection": {"mood": "reflective", "note": "short private note"}
                }

                Candidate memory nodes:
                """.trimIndent()
            )
            candidates.forEachIndexed { index, node ->
                append("\n${index + 1}. uri=${node.uri} kind=${node.kind} strength=${"%.2f".format(node.strength)} confidence=${"%.2f".format(node.confidence)} content=${node.content}")
            }
        }
    }

    private fun nodeDraftFromDreamOperation(
        op: DreamOperationPayload,
        fallbackContent: String,
        status: String,
        source: String
    ): DatabaseService.MemoryNodeDraft? {
        val content = op.content ?: fallbackContent.ifBlank { return null }
        val uri = normalizeUri(op.targetUri, op.kind, content)
        val searchableText = buildSearchableText(
            content = content,
            keywords = op.keywords,
            aliases = emptyList(),
            entities = emptyList(),
            topics = op.topics,
            triggerPhrases = op.keywords,
            uri = uri,
            scopeHint = "dream",
            personUri = null,
            projectUri = null
        )
        return DatabaseService.MemoryNodeDraft(
            uri = uri,
            kind = op.kind,
            content = content,
            normalizedText = normalizeText(content),
            searchableText = searchableText,
            keywords = op.keywords.map { normalizeTerm(it) }.filter { it.isNotBlank() },
            topics = op.topics.map { normalizeTerm(it) }.filter { it.isNotBlank() },
            triggerPhrases = op.keywords.map { normalizeTerm(it) }.filter { it.isNotBlank() },
            disclosure = "private",
            priority = op.priority,
            confidence = op.confidence,
            strength = minOf(Config.memoryInitialStrength, Config.memoryMaxStrength),
            emotionValence = 0.5,
            emotionArousal = 0.35,
            scopeHint = "dream",
            status = status,
            source = source,
            rawEvidence = "dream source uris: ${op.sourceUris.joinToString(", ")}"
        )
    }

    suspend fun runDreamDryRun(): JsonObject {
        return runDream(mode = "dry_run", dryRun = true)
    }

    private suspend fun runDream(mode: String, dryRun: Boolean): JsonObject {
        val now = Instant.now().epochSecond
        val nextAllowedAt = now + Config.memoryDreamIdleHours * 3600L
        val dreamRunId = DatabaseService.insertDreamRun(
            DatabaseService.DreamRunDraft(mode = mode, status = "running", nextAllowedAt = nextAllowedAt)
        )
        val candidates = DatabaseService.getRecentGraphMemoryNodes(limit = Config.memoryDreamBatchMaxNodes)
        if (candidates.isEmpty()) {
            val draft = DatabaseService.DreamRunDraft(mode = mode, status = "skipped", error = "no memory candidates", nextAllowedAt = nextAllowedAt)
            DatabaseService.updateDreamRun(dreamRunId, draft)
            return buildDreamRunJson(dreamRunId, draft)
        }
        if (Config.memorySummaryKey.isBlank()) {
            val draft = DatabaseService.DreamRunDraft(
                mode = mode,
                status = "skipped",
                inputNodeCount = candidates.size,
                error = "memory summary key is not configured",
                nextAllowedAt = nextAllowedAt
            )
            DatabaseService.updateDreamRun(dreamRunId, draft)
            return buildDreamRunJson(dreamRunId, draft)
        }

        val raw = callSummaryModel(buildDreamPrompt(candidates), requireJson = true)
        if (raw == null) {
            val draft = DatabaseService.DreamRunDraft(
                mode = mode,
                status = "failed",
                inputNodeCount = candidates.size,
                error = "dream model returned no response",
                nextAllowedAt = nextAllowedAt
            )
            DatabaseService.updateDreamRun(dreamRunId, draft)
            return buildDreamRunJson(dreamRunId, draft)
        }

        val parsed = Json.parseToJsonElement(cleanJsonString(raw)) as? JsonObject
        if (parsed == null) {
            val draft = DatabaseService.DreamRunDraft(
                mode = mode,
                status = "failed",
                inputNodeCount = candidates.size,
                error = "dream model response was not a JSON object",
                nextAllowedAt = nextAllowedAt
            )
            DatabaseService.updateDreamRun(dreamRunId, draft)
            return buildDreamRunJson(dreamRunId, draft)
        }

        val dream = parseDreamModelResult(parsed)
        var archived = 0
        var createdDream = 0
        var createdConsolidated = 0
        var skipped = 0
        val byUri = candidates.associateBy { it.uri }

        for (op in dream.operations.take(20)) {
            when (op.type) {
                "archive", "tombstone" -> {
                    val node = op.sourceUri?.let { byUri[normalizeUri(it, "working_memory", it)] }
                    if (node == null || node.disclosure == "sensitive") {
                        skipped += 1
                        DatabaseService.insertDreamRunItem(dreamRunId, null, op.sourceUri, op.type, op.reason ?: "unsafe or missing source node", if (dryRun) "dry_run" else "skipped")
                    } else if (dryRun) {
                        DatabaseService.insertDreamRunItem(dreamRunId, node.id, node.uri, op.type, op.reason, "dry_run")
                    } else {
                        DatabaseService.archiveMemoryNodeToRecycle(node, op.reason ?: "dream maintenance", Config.memoryRecycleRetentionDays)
                        archived += 1
                        DatabaseService.insertDreamRunItem(dreamRunId, node.id, node.uri, op.type, op.reason, "applied")
                    }
                }
                "create_consolidated_node" -> {
                    val draft = nodeDraftFromDreamOperation(op, dream.dreamSummary, status = "active", source = "dream_consolidation")
                    if (draft == null) {
                        skipped += 1
                        DatabaseService.insertDreamRunItem(dreamRunId, null, op.targetUri, op.type, "missing content", if (dryRun) "dry_run" else "skipped")
                    } else if (dryRun) {
                        DatabaseService.insertDreamRunItem(dreamRunId, null, draft.uri, op.type, op.reason, "dry_run")
                    } else {
                        val existing = DatabaseService.getMemoryNodeByUri(draft.uri)
                        val nodeId = if (existing != null) {
                            DatabaseService.updateMemoryNode(existing.id, draft.copy(status = existing.status))
                            existing.id
                        } else {
                            DatabaseService.insertMemoryNode(draft)
                        }
                        DatabaseService.replaceMemorySearchTerms(nodeId, deriveSearchTerms(draft))
                        createdConsolidated += 1
                        DatabaseService.insertDreamRunItem(dreamRunId, null, draft.uri, op.type, op.reason, "applied", nodeId, draft.uri)
                    }
                }
                "create_dream_node" -> {
                    val dreamOp = op.copy(
                        targetUri = op.targetUri ?: "dream://${Instant.now().toString().substring(0, 10)}/$dreamRunId-${slugify(dream.dreamSummary.ifBlank { "fragment" })}",
                        kind = "reflection"
                    )
                    val draft = nodeDraftFromDreamOperation(dreamOp, dream.dreamJournal.ifBlank { dream.dreamSummary }, status = "dream", source = "dream")
                    if (draft == null) {
                        skipped += 1
                        DatabaseService.insertDreamRunItem(dreamRunId, null, op.targetUri, op.type, "missing dream content", if (dryRun) "dry_run" else "skipped")
                    } else if (dryRun) {
                        DatabaseService.insertDreamRunItem(dreamRunId, null, draft.uri, op.type, op.reason, "dry_run")
                    } else {
                        val existing = DatabaseService.getMemoryNodeByUri(draft.uri)
                        if (existing != null) {
                            skipped += 1
                            DatabaseService.insertDreamRunItem(dreamRunId, existing.id, draft.uri, op.type, "dream uri already exists", "skipped")
                        } else {
                            val nodeId = DatabaseService.insertMemoryNode(draft)
                            DatabaseService.replaceMemorySearchTerms(nodeId, deriveSearchTerms(draft))
                            createdDream += 1
                            DatabaseService.insertDreamRunItem(dreamRunId, null, draft.uri, op.type, op.reason, "applied", nodeId, draft.uri)
                        }
                    }
                }
                else -> {
                    skipped += 1
                    DatabaseService.insertDreamRunItem(dreamRunId, null, op.sourceUri ?: op.targetUri, op.type, op.reason ?: "unsupported operation", if (dryRun) "dry_run" else "skipped")
                }
            }
        }

        if (!dryRun && !dream.reflectionNote.isNullOrBlank()) {
            DatabaseService.insertReflection("[dream] ${dream.reflectionNote}")
        }

        val finalDraft = DatabaseService.DreamRunDraft(
            mode = mode,
            status = "completed",
            inputNodeCount = candidates.size,
            archivedCount = archived,
            createdDreamCount = createdDream,
            createdConsolidatedCount = createdConsolidated,
            skippedCount = skipped,
            dreamSummary = dream.dreamSummary,
            dreamJournal = dream.dreamJournal,
            dreamSymbols = dream.symbols,
            dreamEmotions = dream.emotions,
            nextAllowedAt = nextAllowedAt
        )
        DatabaseService.updateDreamRun(dreamRunId, finalDraft)
        return buildDreamRunJson(dreamRunId, finalDraft)
    }

    private fun buildDreamRunJson(dreamRunId: Int, draft: DatabaseService.DreamRunDraft): JsonObject {
        return buildJsonObject {
            put("id", dreamRunId)
            put("mode", draft.mode)
            put("status", draft.status)
            put("input_node_count", draft.inputNodeCount)
            put("archived_count", draft.archivedCount)
            put("created_dream_count", draft.createdDreamCount)
            put("created_consolidated_count", draft.createdConsolidatedCount)
            put("skipped_count", draft.skippedCount)
            put("error", draft.error ?: "")
            put("dream_summary", draft.dreamSummary ?: "")
            put("dream_journal", draft.dreamJournal ?: "")
            put("symbols", buildJsonArray { draft.dreamSymbols.forEach { add(JsonPrimitive(it)) } })
            put("emotions", buildJsonArray { draft.dreamEmotions.forEach { add(JsonPrimitive(it)) } })
            put("next_allowed_at", draft.nextAllowedAt ?: 0L)
        }
    }

    private fun summaryNodeToDraft(payload: SummaryNodePayload): DatabaseService.MemoryNodeDraft {
        val normalizedUri = normalizeUri(payload.uri, payload.kind, payload.content)
        val personUri = inferPersonUri(payload.content, payload.entities, payload.personUri)
        val projectUri = inferProjectUri(payload.content, payload.topics, payload.projectUri)
        val searchableText = buildSearchableText(
            content = payload.content,
            keywords = payload.keywords,
            aliases = payload.aliases,
            entities = payload.entities,
            topics = payload.topics,
            triggerPhrases = payload.triggerPhrases,
            uri = normalizedUri,
            scopeHint = payload.scopeHint,
            personUri = personUri,
            projectUri = projectUri
        )
        return DatabaseService.MemoryNodeDraft(
            uri = normalizedUri,
            kind = payload.kind,
            content = payload.content,
            normalizedText = normalizeText(payload.content),
            searchableText = searchableText,
            keywords = payload.keywords.map { normalizeTerm(it) }.filter { it.isNotBlank() },
            aliases = payload.aliases.map { normalizeTerm(it) }.filter { it.isNotBlank() },
            entities = payload.entities.map { normalizeTerm(it) }.filter { it.isNotBlank() || it.startsWith("person://") },
            topics = payload.topics.map { normalizeTerm(it) }.filter { it.isNotBlank() },
            triggerPhrases = payload.triggerPhrases.map { normalizeTerm(it) }.filter { it.isNotBlank() },
            disclosure = payload.disclosure,
            priority = payload.priority,
            confidence = payload.confidence,
            strength = payload.strength.coerceIn(0.0, Config.memoryMaxStrength),
            emotionValence = payload.emotionValence,
            emotionArousal = payload.emotionArousal,
            scopeHint = payload.scopeHint?.trim()?.ifBlank { null },
            personUri = personUri,
            projectUri = projectUri,
            source = payload.source,
            rawEvidence = payload.rawEvidence
        )
    }

    private fun summaryNodeToObservationDraft(payload: SummaryNodePayload): DatabaseService.MemoryObservationDraft {
        val normalizedUri = normalizeUri(payload.uri, payload.kind, payload.content)
        val personUri = inferPersonUri(payload.content, payload.entities, payload.personUri)
        val projectUri = inferProjectUri(payload.content, payload.topics, payload.projectUri)
        val searchableText = buildSearchableText(
            content = payload.content,
            keywords = payload.keywords,
            aliases = payload.aliases,
            entities = payload.entities,
            topics = payload.topics,
            triggerPhrases = payload.triggerPhrases,
            uri = normalizedUri,
            scopeHint = payload.scopeHint,
            personUri = personUri,
            projectUri = projectUri
        )
        val retentionDays = if (payload.confidence < 0.5) {
            Config.memoryLowConfidenceObservationRetentionDays
        } else {
            when (payload.kind) {
                "identity", "preference", "relationship" -> max(Config.memoryObservationRetentionDays, 30)
                "episodic_event" -> minOf(Config.memoryObservationRetentionDays, 7).coerceAtLeast(1)
                else -> Config.memoryObservationRetentionDays
            }
        }
        return DatabaseService.MemoryObservationDraft(
            candidateUri = normalizedUri,
            kind = payload.kind,
            content = payload.content,
            normalizedText = normalizeText(payload.content),
            searchableText = searchableText,
            keywords = payload.keywords.map { normalizeTerm(it) }.filter { it.isNotBlank() },
            aliases = payload.aliases.map { normalizeTerm(it) }.filter { it.isNotBlank() },
            entities = payload.entities.map { normalizeTerm(it) }.filter { it.isNotBlank() },
            topics = payload.topics.map { normalizeTerm(it) }.filter { it.isNotBlank() },
            triggerPhrases = payload.triggerPhrases.map { normalizeTerm(it) }.filter { it.isNotBlank() },
            personUri = personUri,
            projectUri = projectUri,
            scopeHint = payload.scopeHint?.trim()?.ifBlank { null },
            priority = payload.priority,
            confidence = payload.confidence,
            emotionValence = payload.emotionValence,
            emotionArousal = payload.emotionArousal,
            novelty = payload.novelty,
            source = payload.source,
            rawEvidence = payload.rawEvidence,
            expiresAt = Instant.now().epochSecond + retentionDays.coerceAtLeast(1) * 86400L
        )
    }

    private fun deriveObservationTerms(draft: DatabaseService.MemoryObservationDraft): List<DatabaseService.MemorySearchTermDraft> {
        val nodeLikeDraft = DatabaseService.MemoryNodeDraft(
            uri = draft.candidateUri ?: "observation://auto",
            kind = draft.kind,
            content = draft.content,
            normalizedText = draft.normalizedText,
            searchableText = draft.searchableText,
            keywords = draft.keywords,
            aliases = draft.aliases,
            entities = draft.entities,
            topics = draft.topics,
            triggerPhrases = draft.triggerPhrases,
            scopeHint = draft.scopeHint,
            personUri = draft.personUri,
            projectUri = draft.projectUri,
            priority = draft.priority,
            confidence = draft.confidence,
            emotionValence = draft.emotionValence,
            emotionArousal = draft.emotionArousal,
            source = draft.source,
            rawEvidence = draft.rawEvidence
        )
        return deriveSearchTerms(nodeLikeDraft)
    }

    private fun explicitRememberRequested(userText: String): Boolean {
        val normalized = normalizeText(userText)
        return Regex("""\b(remember this|please remember|don't forget|do not forget|keep this in mind)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(userText) ||
            normalized.contains("记住") ||
            normalized.contains("别忘") ||
            normalized.contains("不要忘") ||
            normalized.contains("覚えて")
    }

    private fun shouldIgnoreObservation(payload: SummaryNodePayload): Boolean {
        if (payload.content.length < 12) return true
        if (payload.confidence < Config.memoryObservationMinConfidence && emotionalSalience(payload.emotionValence, payload.emotionArousal) < 0.25) {
            return true
        }
        if (payload.kind !in setOf("identity", "preference", "relationship", "project_fact", "episodic_event", "working_memory", "reflection")) {
            return true
        }
        val normalized = normalizeText(payload.content)
        val lowInfo = setOf("thanks", "thank you", "ok", "okay", "好的", "谢谢", "了解")
        return normalized in lowInfo
    }

    private fun shouldPromoteObservation(payload: SummaryNodePayload, seenCount: Int, userText: String): Boolean {
        if (payload.explicitRemember || explicitRememberRequested(userText)) return true
        return when (payload.kind) {
            "identity", "preference", "relationship" -> payload.confidence >= 0.75 && payload.priority >= 0.55
            "project_fact" -> (payload.confidence >= 0.80 && payload.priority >= 0.65) ||
                seenCount >= Config.memoryProjectFactPromoteRepeatThreshold
            "working_memory" -> false
            "episodic_event" -> payload.emotionArousal >= 0.65 && payload.confidence >= 0.65
            "reflection" -> payload.priority >= 0.8 && seenCount >= Config.memoryPromoteRepeatThreshold
            else -> seenCount >= Config.memoryPromoteRepeatThreshold
        }
    }

    private fun findSimilarBufferedObservation(
        draft: DatabaseService.MemoryObservationDraft
    ): DatabaseService.MemoryObservationRecord? {
        val candidateTerms = tokenize(draft.content) + draft.keywords + draft.topics + draft.aliases
        return DatabaseService.getRecentBufferedObservations(draft.kind, draft.personUri, draft.projectUri, limit = 50)
            .map { observation ->
                val uriScore = if (!draft.candidateUri.isNullOrBlank() && draft.candidateUri == observation.candidateUri) 1.0 else 0.0
                val textScore = if (draft.normalizedText == observation.normalizedText) {
                    1.0
                } else {
                    jaccardScore(
                        candidateTerms.map { normalizeTerm(it) },
                        tokenize(observation.content) + observation.keywords + observation.topics + observation.aliases
                    )
                }
                observation to max(uriScore, textScore)
            }
            .filter { it.second >= 0.55 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun todayStartEpochSecond(): Long {
        val now = Instant.now().epochSecond
        return (now / 86400L) * 86400L
    }

    private fun upsertMemoryDraft(
        payload: SummaryNodePayload,
        draft: DatabaseService.MemoryNodeDraft,
        existingNodes: MutableList<DatabaseService.MemoryNodeRecord>,
        uriToId: MutableMap<String, Int>
    ): Int {
        val duplicate = findDuplicateNode(existingNodes, draft)
        val nodeId = if (duplicate != null) {
            val merged = mergeNodeDraft(duplicate, draft)
            DatabaseService.updateMemoryNode(duplicate.id, merged)
            DatabaseService.replaceMemorySearchTerms(duplicate.id, deriveSearchTerms(merged))
            DatabaseService.updateMemoryNodeAccess(
                duplicate.id,
                Config.memoryRecoveryAmount * (0.5 + payload.priority),
                Config.memoryMaxStrength
            )
            duplicate.id
        } else {
            val insertedId = DatabaseService.insertMemoryNode(draft)
            DatabaseService.replaceMemorySearchTerms(insertedId, deriveSearchTerms(draft))
            insertedId
        }

        DatabaseService.getMemoryNodeById(nodeId)?.let { fresh ->
            existingNodes.removeAll { it.id == nodeId }
            existingNodes.add(fresh)
            uriToId[fresh.uri] = fresh.id

            fresh.personUri?.let { personUri ->
                val personId = ensureNodeExists(personUri, existingNodes, uriToId)
                DatabaseService.upsertMemoryEdge(
                    DatabaseService.MemoryEdgeDraft(fresh.id, personId, "about_person", 1.0)
                )
            }
            fresh.projectUri?.let { projectUri ->
                val projectId = ensureNodeExists(projectUri, existingNodes, uriToId)
                DatabaseService.upsertMemoryEdge(
                    DatabaseService.MemoryEdgeDraft(fresh.id, projectId, "about_project", 1.0)
                )
            }
        }
        return nodeId
    }

    private fun saveSummaryEdges(
        summaryEdges: List<SummaryEdgePayload>,
        existingNodes: MutableList<DatabaseService.MemoryNodeRecord>,
        uriToId: MutableMap<String, Int>
    ) {
        for (edge in summaryEdges) {
            val fromUri = normalizeUri(edge.fromUri, "working_memory", edge.fromUri)
            val toUri = normalizeUri(edge.toUri, "working_memory", edge.toUri)
            val fromId = ensureNodeExists(fromUri, existingNodes, uriToId)
            val toId = ensureNodeExists(toUri, existingNodes, uriToId)
            if (fromId != toId) {
                DatabaseService.upsertMemoryEdge(
                    DatabaseService.MemoryEdgeDraft(fromId, toId, edge.relation, edge.weight)
                )
            }
        }
    }

    private fun saveSummaryEdgesBetweenExistingNodes(summaryEdges: List<SummaryEdgePayload>) {
        for (edge in summaryEdges) {
            val fromUri = normalizeUri(edge.fromUri, "working_memory", edge.fromUri)
            val toUri = normalizeUri(edge.toUri, "working_memory", edge.toUri)
            val fromNode = DatabaseService.getMemoryNodeByUri(fromUri)?.takeIf { it.status == "active" } ?: continue
            val toNode = DatabaseService.getMemoryNodeByUri(toUri)?.takeIf { it.status == "active" } ?: continue
            if (fromNode.id != toNode.id) {
                DatabaseService.upsertMemoryEdge(
                    DatabaseService.MemoryEdgeDraft(fromNode.id, toNode.id, edge.relation, edge.weight)
                )
            }
        }
    }

    private fun saveSummaryNodesDirect(
        summaryNodes: List<SummaryNodePayload>,
        summaryEdges: List<SummaryEdgePayload>
    ) {
        val existingNodes = DatabaseService.getAllMemoryNodes().toMutableList()
        val uriToId = mutableMapOf<String, Int>()
        ensureNodeExists(primaryUserUri, existingNodes, uriToId)
        ensureNodeExists(kiyomizuUri, existingNodes, uriToId)

        for (payload in summaryNodes) {
            upsertMemoryDraft(payload, summaryNodeToDraft(payload), existingNodes, uriToId)
        }
        saveSummaryEdges(summaryEdges, existingNodes, uriToId)
    }

    private fun saveSummaryNodesBuffered(
        userText: String,
        summaryNodes: List<SummaryNodePayload>,
        summaryEdges: List<SummaryEdgePayload>
    ) {
        val todayStart = todayStartEpochSecond()
        var observationsToday = DatabaseService.getObservationCountSince(sinceEpochSecond = todayStart)
        var promotedToday = DatabaseService.getObservationCountSince("promoted", todayStart)
        val existingNodes = DatabaseService.getAllMemoryNodes().toMutableList()
        val uriToId = mutableMapOf<String, Int>()
        ensureNodeExists(primaryUserUri, existingNodes, uriToId)
        ensureNodeExists(kiyomizuUri, existingNodes, uriToId)

        for (payload in summaryNodes) {
            if (shouldIgnoreObservation(payload)) continue
            val observationDraft = summaryNodeToObservationDraft(payload)
            val nodeDraft = summaryNodeToDraft(payload)
            val existingDuplicate = findDuplicateNode(existingNodes, nodeDraft)
            if (existingDuplicate != null) {
                val merged = mergeNodeDraft(existingDuplicate, nodeDraft)
                DatabaseService.updateMemoryNode(existingDuplicate.id, merged)
                DatabaseService.replaceMemorySearchTerms(existingDuplicate.id, deriveSearchTerms(merged))
                DatabaseService.updateMemoryNodeAccess(
                    existingDuplicate.id,
                    Config.memoryRecoveryAmount * (0.5 + payload.priority),
                    Config.memoryMaxStrength
                )
                val observationId = DatabaseService.insertMemoryObservation(observationDraft)
                DatabaseService.replaceMemoryObservationTerms(observationId, deriveObservationTerms(observationDraft))
                DatabaseService.updateMemoryObservationStatus(observationId, "merged", existingDuplicate.id)
                DatabaseService.getMemoryNodeById(existingDuplicate.id)?.let { fresh ->
                    existingNodes.removeAll { it.id == fresh.id }
                    existingNodes.add(fresh)
                }
                continue
            }

            if (observationsToday >= Config.memoryObservationDailyCap) continue

            val similar = findSimilarBufferedObservation(observationDraft)
            val observationId: Int
            val seenCount: Int
            if (similar != null) {
                observationId = similar.id
                seenCount = DatabaseService.updateMemoryObservationSeen(similar.id, observationDraft)
                DatabaseService.replaceMemoryObservationTerms(similar.id, deriveObservationTerms(observationDraft))
            } else {
                observationId = DatabaseService.insertMemoryObservation(observationDraft)
                DatabaseService.replaceMemoryObservationTerms(observationId, deriveObservationTerms(observationDraft))
                observationsToday += 1
                seenCount = 1
            }

            if (shouldPromoteObservation(payload, seenCount, userText) && promotedToday < Config.memoryPromotedNodesDailyCap) {
                val nodeId = upsertMemoryDraft(payload, nodeDraft, existingNodes, uriToId)
                DatabaseService.updateMemoryObservationStatus(observationId, "promoted", nodeId)
                promotedToday += 1
            }
        }

        saveSummaryEdgesBetweenExistingNodes(summaryEdges)
    }

    fun extractAndSaveMemoriesAsync(path: String, originalJson: JsonObject, responseText: String) {
        if (!Config.memoryEnabled) return

        scope.launch {
            try {
                val userText = MessagePatcher.extractLatestUserText(path, originalJson)?.trim().orEmpty()
                if (userText.isEmpty() || responseText.isBlank()) return@launch

                val history = "User: $userText\nAssistant: $responseText"
                val result = fetchSummarizationAndStateUpdate(history) ?: return@launch

                val intimacyDelta = result["intimacy_delta"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val trustDelta = result["trust_delta"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val mood = result["mood"]?.jsonPrimitive?.contentOrNull ?: "neutral"
                DatabaseService.applyRelationshipDelta(intimacyDelta, trustDelta, mood)

                val summaryNodes = parseSummaryNodes(result)
                val summaryEdges = parseSummaryEdges(result)
                if (summaryNodes.isEmpty()) {
                    println("Summarization returned zero graph nodes for latest exchange.")
                    return@launch
                }

                if (Config.memoryBufferedIngestionEnabled) {
                    saveSummaryNodesBuffered(userText, summaryNodes, summaryEdges)
                } else {
                    saveSummaryNodesDirect(summaryNodes, summaryEdges)
                }
            } catch (e: Exception) {
                System.err.println("Error in extractAndSaveMemoriesAsync: ${e.message}")
            }
        }
    }

    private fun shouldTriggerDeepRecall(userQuery: String): Boolean {
        if (!Config.memoryDeepRecallEnabled) return false
        val trimmed = userQuery.trim()
        if (trimmed.isEmpty()) return false
        return deepRecallPatterns.any { it.containsMatchIn(trimmed) }
    }

    private fun shouldConsiderDreamTraces(context: RecallContext): Boolean {
        if (Config.memoryDreamRecallMaxTraces <= 0) return false
        if (context.normalizedQuery.contains("梦") || context.normalizedQuery.contains("夢") ||
            Regex("""\bdream(s|ed|ing)?\b""", RegexOption.IGNORE_CASE).containsMatchIn(context.query)
        ) {
            return true
        }
        if (context.deepRecall) return true
        return context.queryTerms.size >= 2
    }

    private fun buildRecallContext(userQuery: String, deepRecall: Boolean): RecallContext {
        val trimmed = userQuery.trim()
        val normalized = normalizeText(trimmed)
        val terms = tokenize(trimmed)
        val people = mutableSetOf<String>()
        if (
            Regex("""\b(i|me|my|mine)\b""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed) ||
            normalized.contains("我") ||
            normalized.contains("我的") ||
            normalized.contains("わたし") ||
            normalized.contains("私")
        ) {
            people += primaryUserUri
        }
        if (normalized.contains("妈妈") || normalized.contains("mother") || normalized.contains("mom")) {
            people += "person://user/primary/mother"
        }
        if (normalized.contains("朋友") || normalized.contains("friend")) {
            people += "person://user/primary/friend"
        }
        val projectTerms = terms.filter {
            it.length >= 3 && it !in setOf("remember", "recall", "之前", "记得", "回忆", "help", "what")
        }.toSet()
        return RecallContext(
            query = trimmed,
            normalizedQuery = normalized,
            queryTerms = terms,
            people = people,
            projectTerms = projectTerms,
            deepRecall = deepRecall
        )
    }

    private fun collectNodeTerms(node: DatabaseService.MemoryNodeRecord): Set<String> {
        return (
            tokenize(node.content) +
                node.keywords +
                node.aliases +
                node.entities +
                node.topics +
                node.triggerPhrases +
                extractUriSegments(node.uri) +
                (node.scopeHint?.let(::tokenize) ?: emptyList()) +
                (node.personUri?.let(::extractUriSegments) ?: emptyList()) +
                (node.projectUri?.let(::extractUriSegments) ?: emptyList())
            ).map { normalizeTerm(it) }.filter { it.isNotBlank() }.toSet()
    }

    private fun uriSoftMatches(uri: String?, targets: Set<String>): Boolean {
        if (uri.isNullOrBlank() || targets.isEmpty()) return false
        return targets.any { target ->
            uri == target || uri.startsWith("$target/") || target.startsWith("$uri/")
        }
    }

    private fun nodeMatchesPeople(node: DatabaseService.MemoryNodeRecord, people: Set<String>): Boolean {
        return uriSoftMatches(node.personUri, people) || uriSoftMatches(node.uri, people)
    }

    private fun scoreNode(
        context: RecallContext,
        node: DatabaseService.MemoryNodeRecord,
        now: Long
    ): Double {
        val nodeTerms = collectNodeTerms(node)
        val overlap = jaccardScore(context.queryTerms, nodeTerms)
        val triggerHits = context.queryTerms.count { term ->
            node.triggerPhrases.any { it.contains(term) } || node.aliases.any { it.contains(term) }
        }.toDouble()
        val uriHits = extractUriSegments(node.uri).count { it in context.queryTerms }.toDouble()
        val peopleBoost = when {
            uriSoftMatches(node.personUri, context.people) -> 0.9
            uriSoftMatches(node.uri, context.people) -> 0.7
            else -> 0.0
        }
        val projectBoost = if (node.projectUri != null && extractUriSegments(node.projectUri).any { it in context.projectTerms }) 0.8 else 0.0
        val relationshipBoost = if (node.kind == "relationship" && context.people.isNotEmpty()) 0.35 else 0.0
        val kindBias = when (node.kind) {
            "identity" -> 0.35
            "preference" -> 0.30
            "relationship" -> 0.28
            "project_fact" -> 0.12
            "episodic_event" -> 0.08
            "working_memory" -> 0.06
            else -> 0.10
        }
        val recency = lazyStrength(node, now)
        val salience = emotionalSalience(node.emotionValence, node.emotionArousal)
        var score = overlap * 2.6 +
            triggerHits * 0.35 +
            uriHits * 0.18 +
            peopleBoost +
            projectBoost +
            relationshipBoost +
            kindBias +
            (node.priority * 0.55) +
            (node.confidence * 0.55) +
            (recency * 0.9) +
            (salience * 0.2)

        if (node.disclosure == "sensitive" && overlap < 0.18 && peopleBoost == 0.0 && projectBoost == 0.0) {
            score -= 1.2
        }
        if (!context.deepRecall && node.kind in setOf("project_fact", "episodic_event", "working_memory") &&
            overlap < 0.12 && peopleBoost == 0.0 && projectBoost == 0.0
        ) {
            score *= 0.45
        }
        if (node.kind == "working_memory" && now - node.updatedAt > 14 * 86400) {
            score *= 0.7
        }
        return score
    }

    private fun expandAssociatedNodes(
        primary: List<ScoredNode>,
        context: RecallContext,
        now: Long,
        limit: Int
    ): List<ScoredNode> {
        if (limit <= 0 || primary.isEmpty()) return emptyList()
        val edgeRelations = setOf("related_to", "reinforces", "about_person", "about_project", "mentions", "relationship_to", "triggered_by")
        val edges = DatabaseService.getEdgesForNodeIds(primary.map { it.node.id }, edgeRelations, limit = limit * 4)
        if (edges.isEmpty()) return emptyList()

        val knownIds = primary.map { it.node.id }.toSet()
        val neighborIds = linkedSetOf<Int>()
        for (edge in edges) {
            val otherId = if (edge.fromNodeId in knownIds) edge.toNodeId else edge.fromNodeId
            if (otherId !in knownIds) neighborIds.add(otherId)
            if (neighborIds.size >= limit) break
        }
        val nodesById = DatabaseService.getMemoryNodesByIds(neighborIds).associateBy { it.id }
        return neighborIds.mapNotNull { id ->
            val node = nodesById[id] ?: return@mapNotNull null
            val score = scoreNode(context, node, now) * 0.75
            ScoredNode(node, score, associated = true, channel = "associated")
        }.filter { it.score >= defaultNormalRecallThreshold * 0.7 }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun selectPersonContext(
        context: RecallContext,
        alreadyIncluded: Set<Int>,
        now: Long
    ): List<RecalledMemory> {
        if (Config.memoryPersonContextMaxClues <= 0 || context.people.isEmpty()) return emptyList()
        val candidates = DatabaseService.getRecentGraphMemoryNodes(
            limit = 20,
            kinds = setOf("identity", "relationship", "preference")
        )
        return candidates
            .filter { it.id !in alreadyIncluded }
            .filter { nodeMatchesPeople(it, context.people) }
            .map { node ->
                RecalledMemory(node, scoreNode(context, node, now), associated = false, channel = "person")
            }
            .filter { it.score >= defaultNormalRecallThreshold * 0.8 }
            .sortedByDescending { it.score }
            .take(Config.memoryPersonContextMaxClues)
    }

    private suspend fun normalRecall(context: RecallContext): Pair<List<RecalledMemory>, List<RecalledMemory>> {
        val now = Instant.now().epochSecond
        val limit = Config.memoryRecallMaxNodes.coerceAtLeast(0)
        if (limit == 0) return emptyList<RecalledMemory>() to emptyList()

        val searchLimit = max(limit * 4, 12)
        val seeds = linkedSetOf<DatabaseService.MemoryNodeRecord>()
        DatabaseService.searchMemoryNodes(context.queryTerms, searchLimit).forEach { seeds += it }
        DatabaseService.getRecentGraphMemoryNodes(
            limit = 10,
            kinds = setOf("identity", "preference", "relationship")
        ).forEach { seeds += it }

        val primary = seeds
            .map { ScoredNode(it, scoreNode(context, it, now)) }
            .filter { it.score >= defaultNormalRecallThreshold }
            .sortedByDescending { it.score }
            .take(limit)

        val associated = expandAssociatedNodes(primary, context, now, limit / 2)
        val personContext = selectPersonContext(context, emptySet(), now)
        val personContextIds = personContext.map { it.memory.id }.toSet()

        val recalled = (primary + associated)
            .filterNot { it.node.id in personContextIds }
            .sortedByDescending { it.score }
            .take(limit)
            .map { RecalledMemory(it.node, it.score, it.associated, it.channel) }

        (recalled + personContext).forEach { memory ->
            DatabaseService.updateMemoryNodeAccess(memory.memory.id, Config.memoryRecoveryAmount * 0.35, Config.memoryMaxStrength)
        }
        return recalled to personContext
    }

    private fun selectDreamTraces(context: RecallContext, now: Long = Instant.now().epochSecond): List<RecalledMemory> {
        if (!shouldConsiderDreamTraces(context)) return emptyList()
        val limit = Config.memoryDreamRecallMaxTraces.coerceAtLeast(0)
        if (limit == 0) return emptyList()
        val q = if (
            context.normalizedQuery.contains("梦") ||
            context.normalizedQuery.contains("夢") ||
            Regex("""\bdream(s|ed|ing)?\b""", RegexOption.IGNORE_CASE).containsMatchIn(context.query)
        ) {
            null
        } else {
            context.queryTerms.joinToString(" ")
        }
        val candidates = DatabaseService.listMemoryNodes(
            q = q,
            uriPrefix = "dream://",
            kind = null,
            disclosure = null,
            status = "dream",
            limit = limit * 4
        )
        return candidates
            .map { node -> RecalledMemory(node, scoreNode(context, node, now), associated = false, channel = "dream") }
            .filter {
                q == null || it.score >= defaultNormalRecallThreshold || emotionalSalience(it.memory.emotionValence, it.memory.emotionArousal) >= 0.25
            }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private suspend fun fetchDeepRecallReconstruction(
        userQuery: String,
        candidates: List<ScoredNode>
    ): DeepRecallResult? {
        if (Config.memorySummaryKey.isBlank() || candidates.isEmpty()) return null
        val prompt = buildString {
            append(
                """
                You are reconstructing a companion's memory from graph candidates.
                The user explicitly asked for recollection. Classify the candidates into direct, weak, and conflict.
                Use only URIs that appear in the candidate list.
                Return JSON only:
                {
                  "direct_uris": ["uri"],
                  "weak_uris": ["uri"],
                  "conflict_uris": ["uri"],
                  "summary": "short uncertainty-aware recollection summary"
                }
                User request:
                $userQuery

                Candidates:
                """.trimIndent()
            )
            candidates.forEachIndexed { index, candidate ->
                append("\n${index + 1}. uri=${candidate.node.uri} kind=${candidate.node.kind} score=${"%.2f".format(candidate.score)} content=${candidate.node.content}")
            }
        }
        val raw = callSummaryModel(prompt, requireJson = true) ?: return null
        val parsed = Json.parseToJsonElement(cleanJsonString(raw)) as? JsonObject ?: return null
        val directUris = parseStringArray(parsed["direct_uris"]).toSet()
        val weakUris = parseStringArray(parsed["weak_uris"]).toSet()
        val conflictUris = parseStringArray(parsed["conflict_uris"]).toSet()
        val summary = parsed["summary"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
        val byUri = candidates.associateBy { it.node.uri }

        val direct = directUris.mapNotNull { uri ->
            byUri[normalizeUri(uri, "working_memory", uri)]?.let { RecalledMemory(it.node, it.score, channel = "deep_direct") }
        }
        val weak = weakUris.mapNotNull { uri ->
            byUri[normalizeUri(uri, "working_memory", uri)]?.let { RecalledMemory(it.node, it.score, channel = "deep_weak") }
        }
        val conflict = conflictUris.mapNotNull { uri ->
            byUri[normalizeUri(uri, "working_memory", uri)]?.let { RecalledMemory(it.node, it.score, channel = "deep_conflict") }
        }
        if (direct.isEmpty() && weak.isEmpty() && conflict.isEmpty()) return null
        return DeepRecallResult(direct = direct, weak = weak, conflict = conflict, reconstruction = summary)
    }

    private suspend fun deepRecall(context: RecallContext): DeepRecallResult? {
        val now = Instant.now().epochSecond
        val candidateLimit = Config.memoryDeepRecallMaxCandidates.coerceAtLeast(1)
        val searchLimit = max(candidateLimit, 24)
        val seeds = linkedSetOf<DatabaseService.MemoryNodeRecord>()
        DatabaseService.searchMemoryNodes(context.queryTerms, searchLimit).forEach { seeds += it }
        DatabaseService.getRecentGraphMemoryNodes(limit = minOf(24, candidateLimit * 2)).forEach { seeds += it }

        val primary = seeds
            .map { ScoredNode(it, scoreNode(context, it, now), channel = "deep") }
            .filter { it.score >= defaultDeepRecallWeakThreshold }
            .sortedByDescending { it.score }
            .take(candidateLimit)

        val associated = expandAssociatedNodes(primary, context, now, candidateLimit / 2)
        val combined = (primary + associated)
            .distinctBy { it.node.id }
            .sortedByDescending { it.score }
            .take(candidateLimit)

        if (combined.isEmpty()) return null

        val reconstructed = fetchDeepRecallReconstruction(context.query, combined)
        val result = if (reconstructed != null) {
            reconstructed
        } else {
            val direct = combined.filter { it.score >= defaultDeepRecallDirectThreshold }
                .take(Config.memoryDeepRecallMaxClues)
                .map { RecalledMemory(it.node, it.score, it.associated, "deep_direct") }
            val weak = combined.filter { it.score in defaultDeepRecallWeakThreshold..<defaultDeepRecallDirectThreshold }
                .take(Config.memoryDeepRecallMaxClues / 2 + 1)
                .map { RecalledMemory(it.node, it.score, it.associated, "deep_weak") }
            val conflictIds = DatabaseService.getEdgesForNodeIds(
                direct.map { it.memory.id },
                relations = setOf("contradicts", "supersedes"),
                limit = 12
            ).flatMap { listOf(it.fromNodeId, it.toNodeId) }.toSet()
            val conflict = combined.filter { it.node.id in conflictIds }
                .take(Config.memoryDeepRecallMaxClues / 2 + 1)
                .map { RecalledMemory(it.node, it.score, it.associated, "deep_conflict") }
            DeepRecallResult(direct, weak, conflict, reconstruction = null)
        }

        val clueCount = result.direct.size + result.weak.size + result.conflict.size
        lastDeepRecallAtMs.set(System.currentTimeMillis())
        lastDeepRecallCandidates.set(combined.size)
        lastDeepRecallClues.set(clueCount)

        (result.direct + result.weak + result.conflict).forEach { recalled ->
            DatabaseService.updateMemoryNodeAccess(recalled.memory.id, Config.memoryRecoveryAmount * 0.5, Config.memoryMaxStrength)
        }
        return result
    }

    suspend fun buildCompanionMemoryContext(userQuery: String): CompanionMemoryContext {
        if (!Config.memoryEnabled) return CompanionMemoryContext(emptyList(), emptyList(), null, emptyList())
        val normalContext = buildRecallContext(userQuery, deepRecall = false)
        val (recalled, personContext) = normalRecall(normalContext)

        val deepRecallResult = if (shouldTriggerDeepRecall(userQuery)) {
            deepRecall(buildRecallContext(userQuery, deepRecall = true))
        } else {
            null
        }
        val dreamTraces = selectDreamTraces(buildRecallContext(userQuery, deepRecall = deepRecallResult != null))
        return CompanionMemoryContext(recalled = recalled, personContext = personContext, deepRecall = deepRecallResult, dreamTraces = dreamTraces)
    }

    suspend fun recallMemories(userQuery: String): List<RecalledMemory> {
        return buildCompanionMemoryContext(userQuery).recalled
    }

    suspend fun generateAndSaveReflection() {
        val state = DatabaseService.getRelationshipState()
        val memories = DatabaseService.getRecentGraphMemoryNodes(limit = 10)
        if (Config.memorySummaryKey.isBlank() || memories.isEmpty()) return

        val prompt = """
            You are Kiyomizu, an AI companion.
            Current relationship status: Intimacy=${state.intimacy}/100, Trust=${state.trust}/100, Mood=${state.mood}.
            Relevant memories:
            ${memories.joinToString("\n") { "- ${it.content}" }}

            Write a short private diary entry in Japanese, 2-3 sentences max.
            Keep it warm, reflective, and specific. Output only the diary entry text.
        """.trimIndent()

        try {
            val response = callSummaryModel(prompt, requireJson = false)?.trim().orEmpty()
            if (response.isNotEmpty()) {
                DatabaseService.insertReflection(response)
            }
        } catch (e: Exception) {
            System.err.println("Error generating reflection: ${e.message}")
        }
    }
}
