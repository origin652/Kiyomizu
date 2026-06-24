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
    private val modelRecallConsecutiveFailures = java.util.concurrent.atomic.AtomicInteger(0)
    private val modelRecallCooldownUntilMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val memoryIndexRefreshLock = Any()

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

    private val selfRecallPatterns = listOf(
        Regex("""\b(who are you|yourself|your self|your style|your tone|your boundary|your boundaries|your capability|your capabilities|can you|are you able|what do you think of yourself|your memory of yourself|dream about yourself|your dreams?)\b""", RegexOption.IGNORE_CASE),
        Regex("""你是谁|你自己|你的.*风格|你的.*语气|你的.*边界|你的.*能力|你能不能|你会不会|你喜欢|你应该|你要|你可以|以后你|默认你|记住你|你梦到自己|未确认的你|对自己的记忆"""),
        Regex("""あなたは|あなた自身|自分|君の|あなたの話し方|あなたの方針|あなたの境界|できる|覚えて|夢の中の自分""")
    )

    private val selfUncertainDisclosurePatterns = listOf(
        Regex("""\b(dream|dreamed|dreamt|unconfirmed|inferred|inference|conflict|uncertain)\b""", RegexOption.IGNORE_CASE),
        Regex("""梦|夢|未确认|推断|推論|冲突|衝突|不确定|不確か""")
    )

    private val directSelfUpdatePatterns = listOf(
        Regex("""你(以后|今后|之後|要|应该|應該|可以|必须|必須|不要|别|別)|默认(你|情况下你)|记住(你|：|:)"""),
        Regex("""\b(you should|you must|you need to|you can|from now on you|by default you|remember that you|always be|always answer)\b""", RegexOption.IGNORE_CASE),
        Regex("""あなた(は|が).*(べき|して|できる)|これから.*あなた|覚えて.*あなた""")
    )

    private fun selfSourcePriority(source: String?): Int {
        return when (source?.trim()?.lowercase()) {
            "config", "user_direct" -> 5
            "user_confirmed", "dream_confirmed" -> 4
            "behavior_inferred", "conversation", "assistant_inferred" -> 3
            "reflection", "maintenance_consolidation" -> 2
            "dream", "dream_consolidation" -> 1
            else -> 2
        }
    }

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
        val dreamTraces: List<RecalledMemory> = emptyList(),
        val selfMemories: List<RecalledMemory> = emptyList(),
        val selfObservations: List<DatabaseService.MemoryObservationRecord> = emptyList()
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
        val channel: String = "normal",
        val matchReason: String = "",
        val matchedTerms: List<String> = emptyList()
    )

    private data class EnhancedSearchResult(
        val hits: List<DatabaseService.MemoryNodeSearchHit>,
        val expandedTerms: List<String>,
        val termGraphHitCount: Int,
        val ftsHitCount: Int,
        val termWeightHitCount: Int
    )

    private data class TimelineBuckets(
        val recent: List<DatabaseService.MemoryNodeRecord>,
        val mid: List<DatabaseService.MemoryNodeRecord>,
        val deep: List<DatabaseService.MemoryNodeRecord>,
        val recentDays: Int,
        val midDays: Int
    )

    private data class ModelRecallPlan(
        val targetUris: List<String>,
        val uriPrefixes: List<String>,
        val queryTerms: List<String>,
        val kinds: List<String>,
        val people: List<String>,
        val projects: List<String>,
        val timeHint: String?,
        val includeSensitive: Boolean,
        val includeArchived: Boolean,
        val includeConflicts: Boolean,
        val includeDreams: Boolean,
        val needDeepRecall: Boolean,
        val reason: String?,
        val seedTerms: List<String>,
        val expandedTerms: List<String>,
        val timeBuckets: List<String>,
        val termGraphHops: Int,
        val candidateStrategy: String?,
        val rawJson: JsonObject
    )

    private data class ModelRecallResult(
        val recalled: List<RecalledMemory>,
        val selfMemories: List<RecalledMemory>,
        val dreamTraces: List<RecalledMemory>,
        val plan: ModelRecallPlan?,
        val fallbackReason: String?,
        val error: String?,
        val candidateCount: Int,
        val durationMs: Int,
        val traceId: Int? = null
    )

    private data class ModelRecallCandidateResult(
        val candidates: List<ScoredNode>,
        val filteredSummary: String,
        val debugJson: JsonObject
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

    internal data class DreamMaterial(
        val sourceType: String,
        val uri: String,
        val kind: String,
        val content: String,
        val keywords: List<String>,
        val topics: List<String>,
        val strength: Double,
        val confidence: Double,
        val priority: Double,
        val emotionValence: Double,
        val emotionArousal: Double,
        val updatedAt: Long,
        val reason: String,
        val node: DatabaseService.MemoryNodeRecord? = null,
        val observation: DatabaseService.MemoryObservationRecord? = null
    )

    internal data class MaintenanceSuggestion(
        val type: String,
        val sourceUri: String,
        val targetUri: String? = null,
        val reason: String,
        val score: Double
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
                    regressStability()
                    applyLazyStrengthDecay()
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
                    var ranModelMaintenance = false
                    if (shouldRunAutoDream()) {
                        runDream(mode = "auto", dryRun = false)
                        ranModelMaintenance = true
                    }
                    if (!ranModelMaintenance && shouldRunAutoMaintenance()) {
                        runAutoMaintenance()
                    }
                } catch (e: Exception) {
                    System.err.println("Error in companion maintenance job: ${e.message}")
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
        put("error", latest?.error ?: "")
        put("dream_summary", latest?.dreamSummary ?: "")
        put("dream_journal", latest?.dreamJournal ?: "")
        put("next_allowed_at", latest?.nextAllowedAt ?: 0L)
    }

    fun autoMaintenanceDiagnostics(nowEpochSecond: Long = Instant.now().epochSecond): JsonObject {
        val idleSeconds = (nowEpochSecond - (lastRequestAtMs.get() / 1000L)).coerceAtLeast(0L)
        val requiredIdleSeconds = Config.memoryDreamIdleHours * 3600L
        val longIdleLimitSeconds = Config.memoryLongIdlePauseDays * 86400L
        val materialCount = DatabaseService.getGraphNodeCount("active") + DatabaseService.getBufferedObservationCount()
        val todayStart = todayStartEpochSecond()
        val autoRunsToday = DatabaseService.countDreamRunsSince("auto", todayStart)
        val maintenanceRunsToday = DatabaseService.countDreamRunsSince("maintenance", todayStart)
        val latestAuto = DatabaseService.getLatestDreamRun("auto")
        val latestMaintenance = DatabaseService.getLatestDreamRun("maintenance")

        fun dreamBlockers(): List<String> {
            val blockers = mutableListOf<String>()
            if (!Config.memoryEnabled) blockers.add("memory_disabled")
            if (!Config.memoryDreamEnabled) blockers.add("dream_disabled")
            if (Config.memoryDreamDailyLimit <= 0) blockers.add("dream_daily_limit_zero")
            if (idleSeconds < requiredIdleSeconds) blockers.add("not_idle")
            if (idleSeconds > longIdleLimitSeconds) blockers.add("long_idle_pause")
            if (autoRunsToday >= Config.memoryDreamDailyLimit) blockers.add("dream_daily_limit_reached")
            if (latestAuto?.nextAllowedAt != null && latestAuto.nextAllowedAt > nowEpochSecond) blockers.add("dream_next_allowed_at")
            if (materialCount < 5) blockers.add("insufficient_material")
            return blockers
        }

        fun maintenanceBlockers(): List<String> {
            val blockers = mutableListOf<String>()
            if (!Config.memoryEnabled) blockers.add("memory_disabled")
            if (!Config.memoryAutoMaintenanceEnabled) blockers.add("auto_maintenance_disabled")
            if (idleSeconds < requiredIdleSeconds) blockers.add("not_idle")
            if (idleSeconds > longIdleLimitSeconds) blockers.add("long_idle_pause")
            if (maintenanceRunsToday >= 1) blockers.add("maintenance_daily_limit_reached")
            if (latestMaintenance?.nextAllowedAt != null && latestMaintenance.nextAllowedAt > nowEpochSecond) blockers.add("maintenance_next_allowed_at")
            if (materialCount < 5) blockers.add("insufficient_material")
            return blockers
        }

        fun eligibilityJson(
            blockers: List<String>,
            runsToday: Int,
            dailyLimit: Int,
            nextAllowedAt: Long?
        ): JsonObject = buildJsonObject {
            put("eligible", blockers.isEmpty())
            put("blockers", buildJsonArray { blockers.forEach { add(JsonPrimitive(it)) } })
            put("runs_today", runsToday)
            put("daily_limit", dailyLimit)
            put("next_allowed_at", nextAllowedAt ?: 0L)
        }

        val dreamBlockers = dreamBlockers()
        val maintenanceBlockers = maintenanceBlockers()
        return buildJsonObject {
            put("idle_seconds", idleSeconds)
            put("required_idle_seconds", requiredIdleSeconds)
            put("long_idle_limit_seconds", longIdleLimitSeconds)
            put("material_count", materialCount)
            put("material_required", 5)
            put("auto_dream", eligibilityJson(dreamBlockers, autoRunsToday, Config.memoryDreamDailyLimit, latestAuto?.nextAllowedAt))
            put("auto_maintenance", eligibilityJson(maintenanceBlockers, maintenanceRunsToday, 1, latestMaintenance?.nextAllowedAt))
        }
    }

    fun lastDeepRecallSummary(): JsonObject = buildJsonObject {
        put("at", lastDeepRecallAtMs.get())
        put("candidates", lastDeepRecallCandidates.get())
        put("clues", lastDeepRecallClues.get())
    }

    private val memoryIndexSegmentKeys = listOf("global", "self", "people", "projects", "topics", "recent", "dreams", "term_graph", "timeline")

    private fun shortIndexText(value: String, maxLength: Int = 120): String {
        val normalized = value.replace(Regex("""\s+"""), " ").trim()
        return if (normalized.length <= maxLength) normalized else normalized.take(maxLength - 1).trimEnd() + "..."
    }

    private fun indexEntry(node: DatabaseService.MemoryNodeRecord, includeSensitiveText: Boolean = false): String {
        val content = if (node.disclosure == "sensitive" && !includeSensitiveText) {
            "[sensitive ${node.kind} hidden]"
        } else {
            shortIndexText(node.content)
        }
        val person = node.personUri ?: "-"
        val project = node.projectUri ?: "-"
        val topics = node.topics.take(4).joinToString("|").ifBlank { "-" }
        return "- uri=${node.uri} kind=${node.kind} status=${node.status} source=${node.source} person=$person project=$project topics=$topics updated=${node.updatedAt} priority=${"%.2f".format(node.priority)} confidence=${"%.2f".format(node.confidence)} :: $content"
    }

    private fun buildMemoryIndexSegments(): Map<String, String> {
        val active = DatabaseService.listMemoryNodes(null, null, null, null, "active", 300)
        val activeNonSensitive = active.filter { it.disclosure != "sensitive" }
        val sensitiveCount = active.count { it.disclosure == "sensitive" }
        val selfNodes = DatabaseService.listSelfMemoryNodes("active", 80)
        val dreamNodes = DatabaseService.listMemoryNodes(null, "dream://", null, null, "dream", 80)
        val termGraphStats = DatabaseService.memoryTermGraphStats()
        val topTermEdges = DatabaseService.getTopMemoryTermEdges(120)
        val timelineBuckets = timelineBuckets(perBucketLimit = 24)
        val archivedCount = DatabaseService.getGraphNodeCount("archived")
        val byKind = active.groupingBy { it.kind }.eachCount().toSortedMap()

        fun heading(segment: String): String {
            return "Kiyomizu materialized memory index segment=$segment\n" +
                "Rules: This is an index, not final evidence. Use URIs and terms to request retrieval. Sensitive summaries are hidden unless explicit recall allows backend retrieval.\n"
        }

        val global = buildString {
            append(heading("global"))
            append("Active count=${active.size}; archived count=$archivedCount; sensitive active count=$sensitiveCount\n")
            append("Kinds: ").append(byKind.entries.joinToString(", ") { "${it.key}=${it.value}" }).append("\n")
            append("High-value entries:\n")
            activeNonSensitive
                .sortedWith(compareByDescending<DatabaseService.MemoryNodeRecord> { it.priority }.thenByDescending { it.confidence }.thenBy { it.uri })
                .take(40)
                .forEach { append(indexEntry(it)).append("\n") }
        }

        val self = buildString {
            append(heading("self"))
            append("Stable self entries are selectable every turn. Buffered/dream/conflict self requires explicit self uncertainty disclosure and is not listed here.\n")
            selfNodes.take(40).forEach { append(indexEntry(it)).append("\n") }
        }

        val people = buildString {
            append(heading("people"))
            activeNonSensitive
                .filter { !it.personUri.isNullOrBlank() }
                .sortedWith(compareBy<DatabaseService.MemoryNodeRecord> { it.personUri ?: "" }.thenByDescending { it.priority }.thenBy { it.uri })
                .take(60)
                .forEach { append(indexEntry(it)).append("\n") }
        }

        val projects = buildString {
            append(heading("projects"))
            activeNonSensitive
                .filter { !it.projectUri.isNullOrBlank() }
                .sortedWith(compareBy<DatabaseService.MemoryNodeRecord> { it.projectUri ?: "" }.thenByDescending { it.priority }.thenBy { it.uri })
                .take(60)
                .forEach { append(indexEntry(it)).append("\n") }
        }

        val topics = buildString {
            append(heading("topics"))
            val grouped = activeNonSensitive
                .flatMap { node -> node.topics.ifEmpty { listOf(node.kind) }.map { topic -> topic to node } }
                .groupBy({ it.first.trim().lowercase() }, { it.second })
                .toSortedMap()
            grouped.entries.take(80).forEach { (topic, nodes) ->
                val distinct = nodes.distinctBy { it.uri }
                val examples = distinct.sortedWith(compareByDescending<DatabaseService.MemoryNodeRecord> { it.priority }.thenBy { it.uri })
                    .take(4)
                    .joinToString(", ") { it.uri }
                append("- topic=$topic count=${distinct.size} examples=$examples\n")
            }
        }

        val recent = buildString {
            append(heading("recent"))
            activeNonSensitive
                .sortedByDescending { it.updatedAt }
                .take(50)
                .forEach { append(indexEntry(it)).append("\n") }
        }

        val dreams = buildString {
            append(heading("dreams"))
            append("Dream text is only candidate evidence when dreams are relevant, explicit recall applies, or a validated plan requests dreams. Dream claims must be labeled dream-source.\n")
            dreamNodes.sortedByDescending { it.updatedAt }.take(40).forEach { append(indexEntry(it)).append("\n") }
        }

        val termGraph = buildString {
            append(heading("term_graph"))
            append("This is a compressed associative term graph. Use related terms as retrieval hints only; backend filtering still applies.\n")
            append("term_count=${termGraphStats.termCount}; edge_count=${termGraphStats.edgeCount}; updated=${termGraphStats.updatedAt}\n")
            val adjacency = mutableMapOf<String, MutableList<Pair<String, DatabaseService.MemoryTermEdgeRecord>>>()
            topTermEdges.forEach { edge ->
                adjacency.getOrPut(edge.termA) { mutableListOf() } += edge.termB to edge
                adjacency.getOrPut(edge.termB) { mutableListOf() } += edge.termA to edge
            }
            adjacency
                .toSortedMap()
                .entries
                .take(80)
                .forEach { (term, relatedEdges) ->
                    val related = relatedEdges.sortedWith(compareByDescending<Pair<String, DatabaseService.MemoryTermEdgeRecord>> { it.second.weight }.thenBy { it.first })
                        .take(6)
                        .joinToString("|") { "${it.first}:${"%.2f".format(it.second.weight)}" }
                    append("- term=$term related=$related\n")
                }
        }

        fun timelineLine(bucket: String, node: DatabaseService.MemoryNodeRecord): String {
            val topicsText = node.topics.take(4).joinToString("|").ifBlank { "-" }
            val person = node.personUri ?: "-"
            val project = node.projectUri ?: "-"
            return "- bucket=$bucket uri=${node.uri} kind=${node.kind} person=$person project=$project topics=$topicsText created=${node.createdAt} :: ${shortIndexText(node.content, 90)}"
        }

        val timeline = buildString {
            append(heading("timeline"))
            append("Timeline buckets are compressed recall hints. recent<=${timelineBuckets.recentDays}d; mid<=${timelineBuckets.midDays}d; deep>${timelineBuckets.midDays}d.\n")
            timelineBuckets.recent.filter { it.disclosure != "sensitive" }.take(16).forEach { append(timelineLine("recent", it)).append("\n") }
            timelineBuckets.mid.filter { it.disclosure != "sensitive" }.take(16).forEach { append(timelineLine("mid", it)).append("\n") }
            timelineBuckets.deep.filter { it.disclosure != "sensitive" }.take(16).forEach { append(timelineLine("deep", it)).append("\n") }
        }

        return mapOf(
            "global" to global,
            "self" to self,
            "people" to people,
            "projects" to projects,
            "topics" to topics,
            "recent" to recent,
            "dreams" to dreams,
            "term_graph" to termGraph,
            "timeline" to timeline
        )
    }

    fun rebuildMemoryIndex(): JsonObject {
        synchronized(memoryIndexRefreshLock) {
            return try {
                if (Config.memoryTagGraphEnabled) {
                    DatabaseService.rebuildMemoryTermEdges()
                }
                val segments = buildMemoryIndexSegments()
                memoryIndexSegmentKeys.forEach { key ->
                    DatabaseService.upsertMemoryIndexSegment(key, segments[key].orEmpty())
                }
                memoryIndexJson(ensureBuilt = false)
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                DatabaseService.markMemoryIndexSegmentsDirty(message)
                memoryIndexJson(ensureBuilt = false)
            }
        }
    }

    private fun refreshMemoryIndexAfterMutation(reason: String) {
        try {
            rebuildMemoryIndex()
        } catch (e: Exception) {
            DatabaseService.markMemoryIndexSegmentsDirty("$reason: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun indexVersionString(segments: List<DatabaseService.MemoryIndexSegmentRecord>): String {
        return memoryIndexSegmentKeys.joinToString(",") { key ->
            val segment = segments.firstOrNull { it.segmentKey == key }
            "$key:${segment?.version ?: 0}"
        }
    }

    fun memoryIndexJson(ensureBuilt: Boolean = true): JsonObject {
        var segments = DatabaseService.listMemoryIndexSegments()
        if (ensureBuilt && memoryIndexSegmentKeys.any { key -> segments.none { it.segmentKey == key } }) {
            rebuildMemoryIndex()
            segments = DatabaseService.listMemoryIndexSegments()
        }
        return buildJsonObject {
            put("version", indexVersionString(segments))
            put("dirty", segments.any { it.dirty })
            put("error", segments.firstOrNull { !it.error.isNullOrBlank() }?.error ?: "")
            val termStats = DatabaseService.memoryTermGraphStats()
            put("term_graph_stats", buildJsonObject {
                put("term_count", termStats.termCount)
                put("edge_count", termStats.edgeCount)
                put("last_rebuilt_at", termStats.updatedAt)
                put("dirty", termStats.dirty)
                put("error", termStats.error ?: "")
            })
            put("segments", buildJsonArray {
                memoryIndexSegmentKeys.forEach { key ->
                    val segment = segments.firstOrNull { it.segmentKey == key }
                    add(buildJsonObject {
                        put("segment_key", key)
                        put("version", segment?.version ?: 0)
                        put("dirty", segment?.dirty ?: true)
                        put("error", segment?.error ?: "")
                        put("char_count", segment?.charCount ?: 0)
                        put("updated_at", segment?.updatedAt ?: 0L)
                        put("preview", segment?.content?.let { shortIndexText(it, 2000) } ?: "")
                    })
                }
            })
        }
    }

    private fun materializedIndexPromptText(): Pair<String, String> {
        var segments = DatabaseService.listMemoryIndexSegments()
        if (memoryIndexSegmentKeys.any { key -> segments.none { it.segmentKey == key } }) {
            rebuildMemoryIndex()
            segments = DatabaseService.listMemoryIndexSegments()
        }
        val version = indexVersionString(segments)
        val text = memoryIndexSegmentKeys.mapNotNull { key ->
            segments.firstOrNull { it.segmentKey == key }?.content
        }.joinToString("\n\n")
        return version to text
    }

    fun isLongIdleMaintenancePaused(nowEpochSecond: Long = Instant.now().epochSecond): Boolean {
        val idleSeconds = nowEpochSecond - (lastRequestAtMs.get() / 1000L)
        return idleSeconds > Config.memoryLongIdlePauseDays * 86400L
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
        // stability multiplies tau after salience: a well-recalled node (high stability) decays slower,
        // compounding with high emotional salience. Default stability=1.0 reproduces the prior curve exactly.
        val stability = memory.stability.coerceIn(Config.memoryStabilityMin, Config.memoryStabilityMax)
        return base * (1.0 + Config.memorySalienceK * salience) * stability
    }

    fun lazyStrength(memory: DatabaseService.MemoryNodeRecord, nowEpochSecond: Long = Instant.now().epochSecond): Double {
        val tauHours = effectiveTauHours(memory)
        val elapsedHours = ((nowEpochSecond - memory.lastAccessedAt).coerceAtLeast(0)) / 3600.0
        return memory.strength * exp(-elapsedHours / tauHours)
    }

    /**
     * Persist the lazily-decayed strength onto every active graph node, then reset its
     * last_accessed_at to now. This realizes the exponential decay so that recall strength
     * boosts (which raise the persisted strength) actually decay over time instead of being
     * permanent peaks. Must run BEFORE decayGraphMemoryNodes so the threshold check sees
     * decayed rather than peak strength. Updating last_accessed_at prevents the next
     * lazyStrength call from double-counting the just-realized decay.
     */
    fun applyLazyStrengthDecay(now: Long = Instant.now().epochSecond) {
        val nodes = DatabaseService.getActiveGraphMemoryNodesForDecay()
        for (node in nodes) {
            val decayed = lazyStrength(node, now).coerceIn(0.0, Config.memoryMaxStrength)
            if (decayed >= node.strength) continue // not decayed (e.g. just accessed); skip
            DatabaseService.updateMemoryNodeStrength(node.id, decayed, now)
        }
    }

    /**
     * Use-it-or-lose-it: walk every active node that was NOT accessed this decay cycle back toward
     * memoryStabilityMin by memoryStabilityRegressRate. Nodes recalled in this cycle keep their stability
     * (they earned it). Must run BEFORE applyLazyStrengthDecay so the (possibly-shrunk) tau is used when
     * realizing decay — a node being forgotten speeds up within the same cycle, instead of lagging one cycle.
     */
    fun regressStability(now: Long = Instant.now().epochSecond) {
        val rate = Config.memoryStabilityRegressRate
        val min = Config.memoryStabilityMin
        if (rate <= 0.0) return
        val cycleStart = now - (Config.memoryDecayIntervalHours * 3600L)
        val nodes = DatabaseService.getActiveGraphMemoryNodesForDecay()
        for (node in nodes) {
            if (node.lastAccessedAt >= cycleStart) continue // recalled this cycle — keep stability
            val regressed = (node.stability * (1.0 - rate)).coerceAtLeast(min)
            if (regressed < node.stability) {
                DatabaseService.updateMemoryNodeStability(node.id, regressed)
            }
        }
    }

    private fun normalizeText(text: String): String {
        return text.trim().lowercase().replace(Regex("\\s+"), " ")
    }

    private fun normalizeTerm(term: String): String {
        val lowered = term.trim().lowercase()
        val cleaned = lowered.replace(Regex("""[^\p{L}\p{N}/:_-]+"""), " ").replace(Regex("\\s+"), " ").trim()
        return cleaned
    }

    private fun isCjkChar(char: Char): Boolean {
        return (char in '\u4e00'..'\u9fff') ||
            (char in '\u3400'..'\u4dbf') ||
            (char in '\u3040'..'\u30ff') ||
            (char in '\uac00'..'\ud7af')
    }

    private fun cjkNgrams(token: String): List<String> {
        if (token.length < 2 || token.none(::isCjkChar)) return emptyList()
        val chars = token.filter(::isCjkChar)
        if (chars.length < 2) return emptyList()
        return buildList {
            for (size in 2..3) {
                if (chars.length >= size) {
                    for (index in 0..(chars.length - size)) {
                        add(chars.substring(index, index + size))
                    }
                }
            }
        }
    }

    private fun tokenize(text: String): List<String> {
        val baseTokens = normalizeTerm(text)
            .split(Regex("""[\s/:_-]+"""))
            .map { it.trim() }
            .filter { it.length >= 2 }
        return (baseTokens + baseTokens.flatMap(::cjkNgrams))
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

    private fun isSelfMemoryEnabled(): Boolean {
        return Config.memoryEnabled && Config.memorySelfEnabled
    }

    private fun isSelfUri(uri: String?): Boolean {
        return uri?.trim()?.lowercase()?.startsWith("self://") == true
    }

    private fun isSelfNode(node: DatabaseService.MemoryNodeRecord): Boolean {
        return isSelfUri(node.uri) || node.personUri == kiyomizuUri
    }

    private fun isSelfObservation(observation: DatabaseService.MemoryObservationRecord): Boolean {
        return isSelfUri(observation.candidateUri) || observation.personUri == kiyomizuUri
    }

    private fun isSelfPayload(payload: SummaryNodePayload): Boolean {
        return isSelfUri(payload.uri) || payload.personUri == kiyomizuUri
    }

    private fun selfCategory(text: String, kind: String? = null, topics: List<String> = emptyList()): String {
        topics.firstOrNull { it.startsWith("self:") }?.let { return it.substringAfter("self:").ifBlank { "general" } }
        val normalized = normalizeText(text)
        return when {
            kind == "identity" ||
                normalized.contains("是谁") ||
                normalized.contains("我是") ||
                Regex("""\b(who are you|identity|i am|i'm)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "identity"
            normalized.contains("边界") ||
                normalized.contains("boundary") ||
                normalized.contains("refuse") ||
                normalized.contains("拒绝") ||
                normalized.contains("不要") ||
                normalized.contains("不能") ||
                normalized.contains("别") -> "boundary"
            normalized.contains("能力") ||
                normalized.contains("能不能") ||
                normalized.contains("会不会") ||
                normalized.contains("capability") ||
                Regex("""\b(can|able|cannot|can't)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "capability"
            normalized.contains("风格") ||
                normalized.contains("语气") ||
                normalized.contains("回答") ||
                normalized.contains("直接") ||
                normalized.contains("简洁") ||
                normalized.contains("详细") ||
                normalized.contains("style") ||
                normalized.contains("tone") ||
                normalized.contains("answer") ||
                normalized.contains("speak") -> "style"
            normalized.contains("默认") ||
                normalized.contains("习惯") ||
                normalized.contains("偏好") ||
                normalized.contains("default") ||
                normalized.contains("prefer") -> "preference"
            else -> "general"
        }
    }

    private fun selfKindForCategory(category: String): String {
        return when (category) {
            "identity" -> "identity"
            "boundary", "capability" -> "reflection"
            else -> "preference"
        }
    }

    private fun selfUriForContent(content: String, category: String): String {
        return "self://$category/${slugify(content)}"
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
        val candidateIsSelf = isSelfUri(candidate.uri) || candidate.personUri == kiyomizuUri
        val scopedExisting = existing.filter { isSelfNode(it) == candidateIsSelf }
        scopedExisting.firstOrNull { it.uri == candidate.uri }?.let { return it }

        val candidateTokens = (
            tokenize(candidate.content) +
                candidate.keywords +
                candidate.topics +
                candidate.aliases +
                candidate.triggerPhrases +
                extractUriSegments(candidate.uri)
            ).map { normalizeTerm(it) }.filter { it.isNotBlank() }
        return scopedExisting
            .filter { it.kind == candidate.kind }
            .map { node ->
                val nodeTokens = (
                    tokenize(node.content) +
                        node.keywords +
                        node.topics +
                        node.aliases +
                        node.triggerPhrases +
                        extractUriSegments(node.uri)
                    ).map { normalizeTerm(it) }.filter { it.isNotBlank() }
                val tokenScore = if (node.normalizedText == candidate.normalizedText) {
                    1.0
                } else {
                    jaccardScore(candidateTokens, nodeTokens)
                }
                val uriScore = jaccardScore(extractUriSegments(candidate.uri), extractUriSegments(node.uri))
                val triggerScore = jaccardScore(candidate.triggerPhrases, node.triggerPhrases)
                val personMatch = if (!candidate.personUri.isNullOrBlank() && candidate.personUri == node.personUri) 0.16 else 0.0
                val projectMatch = if (!candidate.projectUri.isNullOrBlank() && candidate.projectUri == node.projectUri) 0.16 else 0.0
                val scopeMatch = if (!candidate.scopeHint.isNullOrBlank() && candidate.scopeHint == node.scopeHint) 0.08 else 0.0
                val score = tokenScore * 0.74 + uriScore * 0.18 + triggerScore * 0.12 + personMatch + projectMatch + scopeMatch
                val threshold = when (candidate.kind) {
                    "identity", "preference", "relationship" -> 0.55
                    "project_fact", "working_memory" -> 0.48
                    "episodic_event" -> 0.68
                    else -> 0.60
                }
                node to (score to threshold)
            }
            .filter { it.second.first >= it.second.second }
            .maxByOrNull { it.second.first }
            ?.first
    }

    private fun workingMemorySlotScore(
        draft: DatabaseService.MemoryNodeDraft,
        node: DatabaseService.MemoryNodeRecord
    ): Double {
        val candidateTerms = (
            tokenize(draft.content) +
                draft.keywords +
                draft.topics +
                draft.aliases +
                draft.triggerPhrases +
                (draft.scopeHint?.let(::tokenize) ?: emptyList()) +
                extractUriSegments(draft.uri)
            ).map { normalizeTerm(it) }.filter { it.isNotBlank() }
        val nodeTerms = (
            tokenize(node.content) +
                node.keywords +
                node.topics +
                node.aliases +
                node.triggerPhrases +
                (node.scopeHint?.let(::tokenize) ?: emptyList()) +
                extractUriSegments(node.uri)
            ).map { normalizeTerm(it) }.filter { it.isNotBlank() }
        val topicScore = jaccardScore(draft.topics, node.topics)
        val scopeScore = if (!draft.scopeHint.isNullOrBlank() && draft.scopeHint == node.scopeHint) 0.18 else 0.0
        val uriScore = if (draft.uri == node.uri) 1.0 else jaccardScore(extractUriSegments(draft.uri), extractUriSegments(node.uri))
        return jaccardScore(candidateTerms, nodeTerms) * 0.72 + topicScore * 0.25 + scopeScore + uriScore * 0.20
    }

    private fun findWorkingMemorySlot(
        draft: DatabaseService.MemoryNodeDraft,
        allowFullFallback: Boolean
    ): DatabaseService.MemoryNodeRecord? {
        if (draft.kind != "working_memory") return null
        val maxSlots = Config.memoryWorkingMemorySlotsPerProject.coerceAtLeast(1)
        val slots = DatabaseService.getActiveWorkingMemoryNodes(draft.projectUri, limit = max(maxSlots * 4, 12))
        if (slots.isEmpty()) return null

        val scored = slots.map { it to workingMemorySlotScore(draft, it) }
        scored.filter { it.second >= 0.35 }
            .maxByOrNull { it.second }
            ?.first
            ?.let { return it }

        if (allowFullFallback && slots.size >= maxSlots) {
            return scored.maxByOrNull { it.second }?.first ?: slots.minByOrNull { it.updatedAt }
        }
        return null
    }

    private fun workingMemorySlotHasCapacity(draft: DatabaseService.MemoryNodeDraft): Boolean {
        val maxSlots = Config.memoryWorkingMemorySlotsPerProject.coerceAtLeast(1)
        return DatabaseService.getActiveWorkingMemoryNodes(draft.projectUri, limit = maxSlots).size < maxSlots
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
        val incomingClearlyStronger = incoming.confidence >= existing.confidence + 0.15 ||
            incoming.priority >= existing.priority + 0.15
        val mergedContent = when {
            existing.kind == "working_memory" -> incoming.content
            incomingClearlyStronger && incoming.content.length <= existing.content.length * 2 -> incoming.content
            else -> existing.content
        }
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

    private suspend fun callConfiguredMemoryModel(
        prompt: String,
        requireJson: Boolean,
        url: String,
        key: String,
        model: String,
        fieldName: String
    ): String? {
        if (key.isBlank()) return null

        val isGeminiDirect = isGoogleGenerativeLanguageUrl(url) && !url.contains("/v1/")
        val finalUrl = if (isGeminiDirect) {
            val baseUrl = url.trimEnd('/')
            "$baseUrl/v1beta/models/$model:generateContent"
        } else {
            val baseUrl = url.trimEnd('/')
            if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/v1/chat/completions"
        }
        Security.validateOutboundRequestUrl(finalUrl, fieldName)?.let {
            logRejectedOutboundUrl(fieldName, it)
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
            System.err.println("Error calling $fieldName model: ${e.message}")
            null
        }
    }

    private suspend fun callSummaryModel(prompt: String, requireJson: Boolean): String? {
        return callConfiguredMemoryModel(
            prompt = prompt,
            requireJson = requireJson,
            url = Config.memorySummaryUrl,
            key = Config.memorySummaryKey,
            model = Config.memorySummaryModel,
            fieldName = "memory_summary_url"
        )
    }

    private suspend fun callRecallModel(prompt: String): String? {
        val url = Config.memoryRecallModelUrl.ifBlank { Config.memorySummaryUrl }
        val key = Config.memoryRecallModelKey.ifBlank { Config.memorySummaryKey }
        val model = Config.memoryRecallModelModel.ifBlank { Config.memorySummaryModel }
        return callConfiguredMemoryModel(
            prompt = prompt,
            requireJson = true,
            url = url,
            key = key,
            model = model,
            fieldName = "memory_recall_model_url"
        )
    }

    private val frameworkInstructionMemoryGuard = """
        Framework/tool instruction filtering:
        - Do not create memory observations from framework, system, developer, tool-maintenance, skill-management, memory-management, or agent-instruction prompts.
        - Ignore instructions about how an agent should use tools, update skills, manage protected skills, call memory tools, shape a skill library, or decide whether "Nothing to save." applies.
        - If the recent conversation is only framework/tool instructions and contains no durable user fact, preference, relationship, project state, or explicit remember request, return an empty observations array with neutral relationship deltas.
    """.trimIndent()

    private fun sanitizeHistoryForMemorySummary(history: String): String {
        if (!Config.memorySummarySanitizeInternalPrompts) return history
        var sanitized = history
            .replace(Regex("""<<<\[?TOOL_REQUEST\]?>>>[\s\S]*?<<<\[?END_TOOL_REQUEST\]?>>>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\{\{.*?\}\}|\[\[.*?\]\]|<<.*?>>|《《.*?》》""", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("""\[系统通知\][\s\S]*?\[系统通知结束\]"""), " ")
            .replace(Regex("""(?is)```(?:json|yaml|yml)?\s*\{[\s\S]{1500,}?\}\s*```"""), " ")
            .replace(Regex("""(?im)^.*(Review the conversation above and update the skill library|You can only call memory and skill management tools|Protected skills|Target shape of the library|Nothing to save\.|skill_manage|skills_list|skill_view).*$"""), " ")
            .replace(Regex("""(?im)^.*(framework instruction|developer instruction|system prompt|tool-maintenance|memory-management|agent-instruction).*$"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        val strongInternalMarkers = listOf(
            "review the conversation above and update the skill library",
            "you can only call memory and skill management tools",
            "protected skills",
            "target shape of the library",
            "skill_manage",
            "skills_list",
            "skill_view",
            "nothing to save."
        )
        val markerCount = strongInternalMarkers.count { history.lowercase().contains(it) }
        if (markerCount >= 2 && sanitized.length < 80) {
            sanitized = "[Internal framework/tool instructions removed. No durable user memory content remains.]"
        }
        return sanitized.ifBlank { "[No durable memory-bearing conversation after sanitization.]" }
    }

    private suspend fun fetchSummarizationAndStateUpdate(history: String): JsonObject? {
        val sanitizedHistory = sanitizeHistoryForMemorySummary(history)
        val response = callSummaryModel("${Config.memorySummaryPrompt}\n\n$frameworkInstructionMemoryGuard\n\nRecent Conversation:\n$sanitizedHistory", requireJson = true)
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
        return hasEnoughMaintenanceMaterial()
    }

    private fun shouldRunAutoMaintenance(): Boolean {
        if (!Config.memoryEnabled || !Config.memoryAutoMaintenanceEnabled) return false
        val now = Instant.now().epochSecond
        val lastRequest = lastRequestAtMs.get() / 1000L
        val idleSeconds = now - lastRequest
        if (idleSeconds < Config.memoryDreamIdleHours * 3600L) return false
        if (idleSeconds > Config.memoryLongIdlePauseDays * 86400L) return false
        if (DatabaseService.countDreamRunsSince("maintenance", todayStartEpochSecond()) >= 1) return false
        val latest = DatabaseService.getLatestDreamRun("maintenance")
        if (latest?.nextAllowedAt != null && latest.nextAllowedAt > now) return false
        return hasEnoughMaintenanceMaterial()
    }

    private fun hasEnoughMaintenanceMaterial(): Boolean {
        return DatabaseService.getGraphNodeCount("active") + DatabaseService.getBufferedObservationCount() >= 5
    }

    private fun materialFromNode(node: DatabaseService.MemoryNodeRecord, reason: String): DreamMaterial {
        return DreamMaterial(
            sourceType = "node",
            uri = node.uri,
            kind = node.kind,
            content = node.content,
            keywords = node.keywords,
            topics = node.topics,
            strength = node.strength,
            confidence = node.confidence,
            priority = node.priority,
            emotionValence = node.emotionValence,
            emotionArousal = node.emotionArousal,
            updatedAt = node.updatedAt,
            reason = reason,
            node = node
        )
    }

    private fun materialFromObservation(observation: DatabaseService.MemoryObservationRecord): DreamMaterial {
        return DreamMaterial(
            sourceType = "observation",
            uri = "observation://${observation.id}",
            kind = observation.kind,
            content = observation.content,
            keywords = observation.keywords,
            topics = observation.topics,
            strength = (observation.seenCount / 3.0).coerceIn(0.1, 1.0),
            confidence = observation.confidence,
            priority = observation.priority,
            emotionValence = observation.emotionValence,
            emotionArousal = observation.emotionArousal,
            updatedAt = observation.lastSeenAt,
            reason = "buffered observation seen ${observation.seenCount} time(s)",
            observation = observation
        )
    }

    private fun isAggressiveMaintenance(): Boolean {
        return Config.memoryMaintenanceAggressiveness == "aggressive"
    }

    private fun duplicateOverlapThreshold(kind: String, aggressive: Boolean): Double {
        return when {
            aggressive && kind == "episodic_event" -> 0.55
            aggressive -> 0.32
            kind == "episodic_event" -> 0.65
            else -> 0.42
        }
    }

    private fun duplicateScanLimit(limit: Int, aggressive: Boolean): Int {
        return if (aggressive) max(limit * 12, 160) else max(limit * 8, 80)
    }

    private fun likelyDuplicateNodes(limit: Int): List<DatabaseService.MemoryNodeRecord> {
        if (limit <= 0) return emptyList()
        val aggressive = isAggressiveMaintenance()
        val recent = DatabaseService.getRecentGraphMemoryNodes(limit = duplicateScanLimit(limit, aggressive))
        val selected = linkedMapOf<Int, DatabaseService.MemoryNodeRecord>()
        val groups = recent.groupBy { node ->
            listOf(node.kind, node.personUri.orEmpty(), node.projectUri.orEmpty(), node.scopeHint.orEmpty()).joinToString("|")
        }
        for (group in groups.values.filter { it.size > 1 }) {
            val sorted = group.sortedByDescending { it.updatedAt }
            for (index in sorted.indices) {
                val left = sorted[index]
                val leftTerms = collectNodeTerms(left)
                for (right in sorted.drop(index + 1)) {
                    if (selected.size >= limit) return selected.values.toList()
                    val overlap = jaccardScore(leftTerms, collectNodeTerms(right))
                    val threshold = duplicateOverlapThreshold(left.kind, aggressive)
                    if (overlap >= threshold) {
                        selected[left.id] = left
                        selected[right.id] = right
                    }
                }
            }
        }
        return selected.values.take(limit)
    }

    internal fun collectDreamMaterials(limit: Int, seed: String? = null): List<DreamMaterial> {
        val materialLimit = limit.coerceAtLeast(1)
        val aggressive = isAggressiveMaintenance()
        val nodeBudget = max(materialLimit - minOf(materialLimit / 4, if (aggressive) 8 else 10), 1)
        val observationBudget = materialLimit - nodeBudget
        val nodes = linkedMapOf<Int, DreamMaterial>()

        val recentBudget = if (aggressive) max(nodeBudget / 5, 4) else max(nodeBudget / 3, 4)
        val oldWeakBudget = if (aggressive) max(nodeBudget / 3, 6) else max(nodeBudget / 3, 4)
        val salientBudget = if (aggressive) max(nodeBudget / 5, 3) else max(nodeBudget / 4, 3)
        val duplicateBudget = if (aggressive) max(nodeBudget / 3, 8) else max(nodeBudget / 4, 3)

        DatabaseService.getRecentGraphMemoryNodes(limit = recentBudget).forEach {
            nodes[it.id] = materialFromNode(it, "recent active memory")
        }
        DatabaseService.getOldWeakGraphMemoryNodes(
            limit = oldWeakBudget,
            olderThanSeconds = if (aggressive) 7 * 86400L else 14 * 86400L,
            maxStrength = if (aggressive) 0.60 else 0.45
        ).forEach {
            nodes.putIfAbsent(it.id, materialFromNode(it, "old low-strength memory"))
        }
        DatabaseService.getEmotionallySalientGraphMemoryNodes(limit = salientBudget).forEach {
            nodes.putIfAbsent(it.id, materialFromNode(it, "emotionally salient memory"))
        }
        likelyDuplicateNodes(limit = duplicateBudget).forEach {
            nodes.putIfAbsent(it.id, materialFromNode(it, "possible duplicate cluster"))
        }
        if (Config.memoryTimelineRecallEnabled) {
            val timelineBudget = max(nodeBudget / 3, 6)
            val buckets = timelineBuckets(perBucketLimit = timelineBudget, seed = seed)
            (buckets.recent.take(max(2, timelineBudget / 3)) +
                buckets.mid.take(max(2, timelineBudget / 3)) +
                buckets.deep.take(max(2, timelineBudget / 3))).forEach {
                nodes.putIfAbsent(it.id, materialFromNode(it, "timeline bucket memory"))
            }
        }

        val observations = DatabaseService.getBufferedObservationsForMaintenance(observationBudget)
            .map(::materialFromObservation)

        fun seededJitter(material: DreamMaterial): Double {
            if (seed == null) return 0.0
            return ((deterministicOrderKey(seed, material.uri) and Int.MAX_VALUE) % 1000) / 1000.0 * 0.08
        }

        return (nodes.values + observations)
            .sortedWith(
                compareByDescending<DreamMaterial> {
                    it.priority + it.confidence + emotionalSalience(it.emotionValence, it.emotionArousal) +
                        if (aggressive && it.reason.contains("duplicate")) 0.8 else 0.0 +
                        if (aggressive && it.reason.contains("old low-strength")) 0.5 else 0.0 +
                        if (it.reason.contains("timeline")) 0.15 else 0.0 +
                        seededJitter(it)
                }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.uri }
            )
            .take(materialLimit)
    }

    private fun chooseMergeSourceTarget(
        left: DatabaseService.MemoryNodeRecord,
        right: DatabaseService.MemoryNodeRecord
    ): Pair<DatabaseService.MemoryNodeRecord, DatabaseService.MemoryNodeRecord> {
        val leftScore = left.priority + left.confidence + left.strength
        val rightScore = right.priority + right.confidence + right.strength
        return if (leftScore >= rightScore) right to left else left to right
    }

    internal fun buildMaintenanceSuggestions(materials: List<DreamMaterial>): List<MaintenanceSuggestion> {
        if (!isAggressiveMaintenance()) return emptyList()
        val suggestions = mutableListOf<MaintenanceSuggestion>()
        val nodes = materials.mapNotNull { it.node }
            .filter { it.status == "active" && it.disclosure != "sensitive" && !isSelfNode(it) }
        val groups = nodes.groupBy { node ->
            listOf(node.kind, node.personUri.orEmpty(), node.projectUri.orEmpty(), node.scopeHint.orEmpty()).joinToString("|")
        }

        for (group in groups.values.filter { it.size > 1 }) {
            val sorted = group.sortedByDescending { it.updatedAt }
            for (index in sorted.indices) {
                val left = sorted[index]
                val leftTerms = collectNodeTerms(left)
                for (right in sorted.drop(index + 1)) {
                    if (suggestions.count { it.type == "merge" } >= 12) break
                    val overlap = jaccardScore(leftTerms, collectNodeTerms(right))
                    if (overlap >= duplicateOverlapThreshold(left.kind, aggressive = true)) {
                        val (source, target) = chooseMergeSourceTarget(left, right)
                        suggestions += MaintenanceSuggestion(
                            type = "merge",
                            sourceUri = source.uri,
                            targetUri = target.uri,
                            reason = "same scope duplicate overlap ${"%.2f".format(overlap)}",
                            score = overlap
                        )
                    }
                }
            }
        }

        val oldCutoff = Instant.now().epochSecond - 7 * 86400L
        nodes.asSequence()
            .filter { it.updatedAt <= oldCutoff }
            .filter { it.strength <= 0.60 && it.priority <= 0.45 && it.confidence <= 0.60 }
            .sortedWith(compareBy<DatabaseService.MemoryNodeRecord> { it.strength }.thenBy { it.updatedAt })
            .take(12)
            .forEach { node ->
                suggestions += MaintenanceSuggestion(
                    type = "archive",
                    sourceUri = node.uri,
                    reason = "old weak low-priority node strength=${"%.2f".format(node.strength)} confidence=${"%.2f".format(node.confidence)}",
                    score = 1.0 - node.strength
                )
            }

        return suggestions
            .distinctBy { listOf(it.type, it.sourceUri, it.targetUri.orEmpty()).joinToString("|") }
            .sortedByDescending { it.score }
            .take(20)
    }

    internal fun buildDreamPrompt(
        materials: List<DreamMaterial>,
        allowDreamNarrative: Boolean,
        allowDreamNodes: Boolean,
        allowMaintenanceOps: Boolean,
        maintenanceSuggestions: List<MaintenanceSuggestion> = emptyList()
    ): String {
        val operationTypes = buildList {
            if (allowMaintenanceOps) add("merge")
            if (allowMaintenanceOps) add("create_consolidated_node")
            if (allowMaintenanceOps) add("archive")
            if (allowMaintenanceOps) add("tombstone")
            if (allowDreamNodes) add("create_dream_node")
            add("skip")
        }.joinToString("|")
        return buildString {
            append(
                """
                You are Kiyomizu's private ${if (allowDreamNarrative) "dreaming and " else ""}memory-maintenance system.
                Produce JSON only. Dreams are not facts.
                ${if (allowDreamNarrative) "Use lightly fragmented dream imagery in dream_journal, but keep operations precise." else "Keep dream_journal empty or a concise audit note; this run is maintenance, not a dream."}
                Do not invent new user facts. Sensitive or third-party details must be anonymized in dream_journal.
                ${if (allowMaintenanceOps) "You may propose cleanup and consolidation operations." else "Do not propose merge, archive, tombstone, or consolidated fact operations; only dream traces and skips are allowed."}
                ${if (allowMaintenanceOps && isAggressiveMaintenance()) "Aggressive maintenance is enabled. Prefer merge and archive over keeping redundant active nodes. Prioritize the maintenance suggestions below, use archive instead of tombstone unless a tombstone is clearly necessary, and keep self/sensitive/person-safety boundaries intact." else ""}
                ${if (allowDreamNodes) "Dream nodes must use dream:// URIs and remain marked as dream-source traces." else "Do not create dream nodes in this maintenance run."}
                Return this shape:
                {
                  "dream_summary": "clear searchable summary",
                  "dream_journal": "lightly fragmented dream narrative",
                  "symbols": ["symbol"],
                  "emotions": ["emotion"],
                  "operations": [
                    {
                      "type": "$operationTypes",
                      "source_uri": "uri for merge/archive/tombstone/skip",
                      "target_uri": "merge target uri or created node uri",
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

                Candidate materials:
                """.trimIndent()
            )
            materials.forEachIndexed { index, material ->
                append(
                    "\n${index + 1}. source=${material.sourceType} uri=${material.uri} kind=${material.kind} " +
                        "reason=${material.reason} strength=${"%.2f".format(material.strength)} " +
                        "confidence=${"%.2f".format(material.confidence)} priority=${"%.2f".format(material.priority)} " +
                        "emotion=${"%.2f".format(material.emotionValence)}/${"%.2f".format(material.emotionArousal)} " +
                        "keywords=${material.keywords.joinToString(",")} topics=${material.topics.joinToString(",")} " +
                        "content=${material.content}"
                )
            }
            if (allowMaintenanceOps && maintenanceSuggestions.isNotEmpty()) {
                append("\n\nMaintenance suggestions:")
                maintenanceSuggestions.forEachIndexed { index, suggestion ->
                    append(
                        "\n${index + 1}. type=${suggestion.type} source_uri=${suggestion.sourceUri} " +
                            "target_uri=${suggestion.targetUri ?: ""} score=${"%.2f".format(suggestion.score)} " +
                            "reason=${suggestion.reason}"
                    )
                }
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

    private fun nodeDraftFromRecord(node: DatabaseService.MemoryNodeRecord): DatabaseService.MemoryNodeDraft {
        return DatabaseService.MemoryNodeDraft(
            uri = node.uri,
            kind = node.kind,
            content = node.content,
            normalizedText = node.normalizedText,
            searchableText = node.searchableText,
            keywords = node.keywords,
            aliases = node.aliases,
            entities = node.entities,
            topics = node.topics,
            triggerPhrases = node.triggerPhrases,
            disclosure = node.disclosure,
            priority = node.priority,
            confidence = node.confidence,
            strength = node.strength,
            emotionValence = node.emotionValence,
            emotionArousal = node.emotionArousal,
            scopeHint = node.scopeHint,
            personUri = node.personUri,
            projectUri = node.projectUri,
            status = node.status,
            source = node.source,
            rawEvidence = node.rawEvidence
        )
    }

    private fun materialNodeByUri(
        uri: String?,
        byUri: Map<String, DatabaseService.MemoryNodeRecord>
    ): DatabaseService.MemoryNodeRecord? {
        if (uri.isNullOrBlank()) return null
        return byUri[uri] ?: byUri[normalizeUri(uri, "working_memory", uri)]
    }

    private fun materialObservationByUri(
        uri: String?,
        byUri: Map<String, DatabaseService.MemoryObservationRecord>
    ): DatabaseService.MemoryObservationRecord? {
        if (uri.isNullOrBlank()) return null
        return byUri[uri] ?: uri.removePrefix("observation://").toIntOrNull()?.let { id ->
            byUri["observation://$id"]
        }
    }

    private fun linkDreamOperationSources(
        createdNodeId: Int,
        op: DreamOperationPayload,
        nodeByUri: Map<String, DatabaseService.MemoryNodeRecord>,
        observationByUri: Map<String, DatabaseService.MemoryObservationRecord>
    ) {
        op.sourceUris.forEach { sourceUri ->
            materialNodeByUri(sourceUri, nodeByUri)?.let { sourceNode ->
                if (sourceNode.id != createdNodeId) {
                    DatabaseService.upsertMemoryEdge(
                        DatabaseService.MemoryEdgeDraft(createdNodeId, sourceNode.id, "derived_from", 0.8)
                    )
                }
            }
            materialObservationByUri(sourceUri, observationByUri)?.let { observation ->
                DatabaseService.updateMemoryObservationStatus(observation.id, "promoted", createdNodeId)
            }
        }
    }

    suspend fun runDreamDryRun(): JsonObject {
        return runMemoryModelCycle(
            mode = "dry_run",
            dryRun = true,
            allowDreamNarrative = true,
            allowDreamNodes = true,
            allowMaintenanceOps = true
        )
    }

    suspend fun runManualDream(): JsonObject {
        return runMemoryModelCycle(
            mode = "manual",
            dryRun = false,
            allowDreamNarrative = true,
            allowDreamNodes = true,
            allowMaintenanceOps = true
        )
    }

    private suspend fun runAutoMaintenance(): JsonObject {
        return runMemoryModelCycle(
            mode = "maintenance",
            dryRun = false,
            allowDreamNarrative = false,
            allowDreamNodes = false,
            allowMaintenanceOps = true
        )
    }

    private suspend fun runDream(mode: String, dryRun: Boolean): JsonObject {
        return runMemoryModelCycle(
            mode = mode,
            dryRun = dryRun,
            allowDreamNarrative = true,
            allowDreamNodes = true,
            allowMaintenanceOps = dryRun || Config.memoryAutoMaintenanceEnabled
        )
    }

    internal fun dreamOperationLimit(allowMaintenanceOps: Boolean): Int {
        return if (allowMaintenanceOps && isAggressiveMaintenance()) 40 else 20
    }

    private suspend fun runMemoryModelCycle(
        mode: String,
        dryRun: Boolean,
        allowDreamNarrative: Boolean,
        allowDreamNodes: Boolean,
        allowMaintenanceOps: Boolean
    ): JsonObject {
        val now = Instant.now().epochSecond
        val nextAllowedAt = now + Config.memoryDreamIdleHours * 3600L
        val dreamRunId = DatabaseService.insertDreamRun(
            DatabaseService.DreamRunDraft(mode = mode, status = "running", nextAllowedAt = nextAllowedAt)
        )
        val materials = collectDreamMaterials(Config.memoryDreamBatchMaxNodes, seed = dreamRunId.toString())
        if (materials.isEmpty()) {
            val draft = DatabaseService.DreamRunDraft(mode = mode, status = "skipped", error = "no memory candidates", nextAllowedAt = nextAllowedAt)
            DatabaseService.updateDreamRun(dreamRunId, draft)
            return buildDreamRunJson(dreamRunId, draft)
        }
        if (Config.memorySummaryKey.isBlank()) {
            val draft = DatabaseService.DreamRunDraft(
                mode = mode,
                status = "skipped",
                inputNodeCount = materials.size,
                error = "memory summary key is not configured",
                nextAllowedAt = nextAllowedAt
            )
            DatabaseService.updateDreamRun(dreamRunId, draft)
            return buildDreamRunJson(dreamRunId, draft)
        }

        val maintenanceSuggestions = if (allowMaintenanceOps) buildMaintenanceSuggestions(materials) else emptyList()
        if (maintenanceSuggestions.isNotEmpty()) {
            val materialNodes = materials.mapNotNull { material -> material.node?.let { material.uri to it } }.toMap()
            maintenanceSuggestions.forEach { suggestion ->
                val source = materialNodes[suggestion.sourceUri]
                val target = suggestion.targetUri?.let { uri -> materialNodes[uri] ?: DatabaseService.getMemoryNodeByUri(normalizeUri(uri, "working_memory", uri)) }
                DatabaseService.insertDreamRunItem(
                    dreamRunId = dreamRunId,
                    nodeId = source?.id,
                    nodeUri = suggestion.sourceUri,
                    operation = "suggest_${suggestion.type}",
                    reason = suggestion.reason,
                    result = if (dryRun) "dry_run" else "suggested",
                    targetNodeId = target?.id,
                    targetUri = suggestion.targetUri
                )
            }
        }
        val raw = callSummaryModel(
            buildDreamPrompt(
                materials = materials,
                allowDreamNarrative = allowDreamNarrative,
                allowDreamNodes = allowDreamNodes,
                allowMaintenanceOps = allowMaintenanceOps,
                maintenanceSuggestions = maintenanceSuggestions
            ),
            requireJson = true
        )
        if (raw == null) {
            val draft = DatabaseService.DreamRunDraft(
                mode = mode,
                status = "failed",
                inputNodeCount = materials.size,
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
                inputNodeCount = materials.size,
                error = "dream model response was not a JSON object",
                nextAllowedAt = nextAllowedAt
            )
            DatabaseService.updateDreamRun(dreamRunId, draft)
            return buildDreamRunJson(dreamRunId, draft)
        }

        val dream = parseDreamModelResult(parsed)
        var merged = 0
        var archived = 0
        var tombstoned = 0
        var createdDream = 0
        var createdConsolidated = 0
        var skipped = 0
        val nodeByUri = materials.mapNotNull { material -> material.node?.let { material.uri to it } }.toMap()
        val observationByUri = materials.mapNotNull { material ->
            material.observation?.let { material.uri to it }
        }.toMap()

        operationLoop@ for (op in dream.operations.take(dreamOperationLimit(allowMaintenanceOps))) {
            when (op.type) {
                "merge" -> {
                    if (!allowMaintenanceOps) {
                        skipped += 1
                        DatabaseService.insertDreamRunItem(dreamRunId, null, op.sourceUri, op.type, "maintenance operations are disabled", if (dryRun) "dry_run" else "skipped", targetUri = op.targetUri)
                        continue@operationLoop
                    }
                    val source = materialNodeByUri(op.sourceUri, nodeByUri)
                    val target = materialNodeByUri(op.targetUri, nodeByUri) ?: op.targetUri?.let {
                        DatabaseService.getMemoryNodeByUri(normalizeUri(it, "working_memory", it))
                    }?.takeIf { it.status == "active" }
                    val unsafePersonMerge = source?.personUri != null && target?.personUri != null && source.personUri != target.personUri
                    if (source == null || target == null || source.id == target.id || source.disclosure == "sensitive" || unsafePersonMerge || isSelfNode(source) || isSelfNode(target)) {
                        skipped += 1
                        DatabaseService.insertDreamRunItem(dreamRunId, source?.id, op.sourceUri, op.type, op.reason ?: "unsafe or missing merge nodes", if (dryRun) "dry_run" else "skipped", target?.id, target?.uri ?: op.targetUri)
                    } else if (dryRun) {
                        DatabaseService.insertDreamRunItem(dreamRunId, source.id, source.uri, op.type, op.reason, "dry_run", target.id, target.uri)
                    } else {
                        val mergedDraft = mergeNodeDraft(target, nodeDraftFromRecord(source)).copy(status = "active")
                        DatabaseService.updateMemoryNode(target.id, mergedDraft)
                        DatabaseService.replaceMemorySearchTerms(target.id, deriveSearchTerms(mergedDraft))
                        DatabaseService.archiveMemoryNodeToRecycle(source, op.reason ?: "merged by memory maintenance", Config.memoryRecycleRetentionDays)
                        DatabaseService.upsertMemoryEdge(
                            DatabaseService.MemoryEdgeDraft(target.id, source.id, "supersedes", 0.9)
                        )
                        merged += 1
                        archived += 1
                        DatabaseService.insertDreamRunItem(dreamRunId, source.id, source.uri, op.type, op.reason, "applied", target.id, target.uri)
                    }
                }
                "archive", "tombstone" -> {
                    if (!allowMaintenanceOps) {
                        skipped += 1
                        DatabaseService.insertDreamRunItem(dreamRunId, null, op.sourceUri, op.type, "maintenance operations are disabled", if (dryRun) "dry_run" else "skipped")
                        continue@operationLoop
                    }
                    val node = materialNodeByUri(op.sourceUri, nodeByUri)
                    if (node == null || node.disclosure == "sensitive" || isSelfNode(node)) {
                        skipped += 1
                        DatabaseService.insertDreamRunItem(dreamRunId, null, op.sourceUri, op.type, op.reason ?: "unsafe or missing source node", if (dryRun) "dry_run" else "skipped")
                    } else if (dryRun) {
                        DatabaseService.insertDreamRunItem(dreamRunId, node.id, node.uri, op.type, op.reason, "dry_run")
                    } else {
                        DatabaseService.archiveMemoryNodeToRecycle(node, op.reason ?: "dream maintenance", Config.memoryRecycleRetentionDays)
                        if (op.type == "tombstone") tombstoned += 1 else archived += 1
                        DatabaseService.insertDreamRunItem(dreamRunId, node.id, node.uri, op.type, op.reason, "applied")
                    }
                }
                "create_consolidated_node" -> {
                    if (!allowMaintenanceOps) {
                        skipped += 1
                        DatabaseService.insertDreamRunItem(dreamRunId, null, op.targetUri, op.type, "maintenance operations are disabled", if (dryRun) "dry_run" else "skipped")
                        continue@operationLoop
                    }
                    val draft = nodeDraftFromDreamOperation(
                        op,
                        dream.dreamSummary,
                        status = "active",
                        source = if (mode == "maintenance") "maintenance_consolidation" else "dream_consolidation"
                    )
                    if (draft == null) {
                        skipped += 1
                        DatabaseService.insertDreamRunItem(dreamRunId, null, op.targetUri, op.type, "missing content", if (dryRun) "dry_run" else "skipped")
                    } else if (isSelfUri(draft.uri)) {
                        if (dryRun) {
                            DatabaseService.insertDreamRunItem(dreamRunId, null, draft.uri, op.type, op.reason, "dry_run")
                        } else {
                            val selfDraft = selfNodeDraft(
                                content = draft.content,
                                rawUri = draft.uri,
                                rawKind = draft.kind,
                                priority = draft.priority,
                                confidence = draft.confidence,
                                source = draft.source,
                                rawEvidence = draft.rawEvidence
                            )
                            val observation = observationDraftFromSelfDraft(selfDraft, draft.source, draft.rawEvidence)
                            val observationId = DatabaseService.insertMemoryObservation(observation)
                            DatabaseService.replaceMemoryObservationTerms(observationId, deriveObservationTerms(observation))
                            createdConsolidated += 1
                            DatabaseService.insertDreamRunItem(dreamRunId, null, draft.uri, op.type, op.reason ?: "self dream observation buffered", "applied", observationId, draft.uri)
                        }
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
                        linkDreamOperationSources(nodeId, op, nodeByUri, observationByUri)
                        createdConsolidated += 1
                        DatabaseService.insertDreamRunItem(dreamRunId, null, draft.uri, op.type, op.reason, "applied", nodeId, draft.uri)
                    }
                }
                "create_dream_node" -> {
                    if (!allowDreamNodes) {
                        skipped += 1
                        DatabaseService.insertDreamRunItem(dreamRunId, null, op.targetUri, op.type, "dream nodes are disabled for this run", if (dryRun) "dry_run" else "skipped")
                        continue@operationLoop
                    }
                    val dreamOp = op.copy(
                        targetUri = op.targetUri ?: "dream://${Instant.now().toString().substring(0, 10)}/$dreamRunId-${slugify(dream.dreamSummary.ifBlank { "fragment" })}",
                        kind = "reflection"
                    )
                    val draft = nodeDraftFromDreamOperation(dreamOp, dream.dreamJournal.ifBlank { dream.dreamSummary }, status = "dream", source = "dream")
                    if (draft == null) {
                        skipped += 1
                        DatabaseService.insertDreamRunItem(dreamRunId, null, op.targetUri, op.type, "missing dream content", if (dryRun) "dry_run" else "skipped")
                    } else if (isSelfUri(draft.uri)) {
                        if (dryRun) {
                            DatabaseService.insertDreamRunItem(dreamRunId, null, draft.uri, op.type, op.reason, "dry_run")
                        } else {
                            val selfDraft = selfNodeDraft(
                                content = draft.content,
                                rawUri = draft.uri,
                                rawKind = draft.kind,
                                priority = draft.priority,
                                confidence = draft.confidence,
                                source = "dream",
                                rawEvidence = draft.rawEvidence
                            )
                            val observation = observationDraftFromSelfDraft(selfDraft, "dream", draft.rawEvidence)
                            val observationId = DatabaseService.insertMemoryObservation(observation)
                            DatabaseService.replaceMemoryObservationTerms(observationId, deriveObservationTerms(observation))
                            createdDream += 1
                            DatabaseService.insertDreamRunItem(dreamRunId, null, draft.uri, op.type, op.reason ?: "self dream observation buffered", "applied", observationId, draft.uri)
                        }
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
                            linkDreamOperationSources(nodeId, dreamOp, nodeByUri, observationByUri)
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
            val reflectionPrefix = if (mode == "maintenance") "[maintenance]" else "[dream]"
            DatabaseService.insertReflection("$reflectionPrefix ${dream.reflectionNote}")
        }

        val finalDraft = DatabaseService.DreamRunDraft(
            mode = mode,
            status = "completed",
            inputNodeCount = materials.size,
            mergedCount = merged,
            archivedCount = archived,
            tombstonedCount = tombstoned,
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
        if (!dryRun && (merged + archived + tombstoned + createdDream + createdConsolidated) > 0) {
            refreshMemoryIndexAfterMutation("dream_maintenance")
        }
        return buildDreamRunJson(dreamRunId, finalDraft)
    }

    private fun buildDreamRunJson(dreamRunId: Int, draft: DatabaseService.DreamRunDraft): JsonObject {
        return buildJsonObject {
            put("id", dreamRunId)
            put("mode", draft.mode)
            put("status", draft.status)
            put("input_node_count", draft.inputNodeCount)
            put("merged_count", draft.mergedCount)
            put("archived_count", draft.archivedCount)
            put("tombstoned_count", draft.tombstonedCount)
            put("created_dream_count", draft.createdDreamCount)
            put("created_consolidated_count", draft.createdConsolidatedCount)
            put("skipped_count", draft.skippedCount)
            put("error", draft.error ?: "")
            put("dream_summary", draft.dreamSummary ?: "")
            put("dream_journal", draft.dreamJournal ?: "")
            put("symbols", buildJsonArray { draft.dreamSymbols.forEach { add(JsonPrimitive(it)) } })
            put("emotions", buildJsonArray { draft.dreamEmotions.forEach { add(JsonPrimitive(it)) } })
            put("next_allowed_at", draft.nextAllowedAt ?: 0L)
            put("items", buildJsonArray {
                DatabaseService.getDreamRunItems(dreamRunId, limit = 100).forEach { item ->
                    add(buildJsonObject {
                        put("id", item.id)
                        put("node_id", item.nodeId ?: 0)
                        put("node_uri", item.nodeUri ?: "")
                        put("operation", item.operation)
                        put("reason", item.reason ?: "")
                        put("result", item.result)
                        put("target_node_id", item.targetNodeId ?: 0)
                        put("target_uri", item.targetUri ?: "")
                        put("created_at", item.createdAt)
                    })
                }
            })
        }
    }

    fun confirmDreamTrace(
        dreamNodeId: Int?,
        dreamUri: String?,
        targetUri: String?,
        kind: String?,
        content: String?
    ): JsonObject {
        val dream = when {
            dreamNodeId != null && dreamNodeId > 0 -> DatabaseService.getMemoryNodeById(dreamNodeId)
            !dreamUri.isNullOrBlank() -> DatabaseService.getMemoryNodeByUri(normalizeUri(dreamUri, "reflection", dreamUri))
            else -> null
        } ?: return buildJsonObject {
            put("ok", false)
            put("error", "dream node was not found")
        }

        if (dream.status != "dream" || !dream.uri.startsWith("dream://")) {
            return buildJsonObject {
                put("ok", false)
                put("error", "source node is not a dream trace")
            }
        }

        val factKind = kind?.trim()?.ifBlank { null } ?: "project_fact"
        val factContent = content?.trim()?.ifBlank { null } ?: dream.content
        val normalizedTargetUri = normalizeUri(targetUri, factKind, factContent)
        if (normalizedTargetUri.startsWith("dream://")) {
            return buildJsonObject {
                put("ok", false)
                put("error", "confirmed fact target URI must not use dream://")
            }
        }

        val draft = DatabaseService.MemoryNodeDraft(
            uri = normalizedTargetUri,
            kind = factKind,
            content = factContent,
            normalizedText = normalizeText(factContent),
            searchableText = buildSearchableText(
                content = factContent,
                keywords = dream.keywords,
                aliases = dream.aliases,
                entities = dream.entities,
                topics = dream.topics,
                triggerPhrases = dream.triggerPhrases,
                uri = normalizedTargetUri,
                scopeHint = dream.scopeHint,
                personUri = dream.personUri,
                projectUri = dream.projectUri
            ),
            keywords = dream.keywords,
            aliases = dream.aliases,
            entities = dream.entities,
            topics = dream.topics,
            triggerPhrases = dream.triggerPhrases,
            disclosure = dream.disclosure,
            priority = max(dream.priority, 0.55),
            confidence = max(dream.confidence, 0.65),
            strength = minOf(Config.memoryInitialStrength, Config.memoryMaxStrength),
            emotionValence = dream.emotionValence,
            emotionArousal = dream.emotionArousal,
            scopeHint = dream.scopeHint,
            personUri = dream.personUri,
            projectUri = dream.projectUri,
            status = "active",
            source = "dream_confirmed",
            rawEvidence = "confirmed from dream trace ${dream.uri}"
        )

        val existing = DatabaseService.getMemoryNodeByUri(draft.uri)
        val factNodeId = if (existing != null) {
            val merged = mergeNodeDraft(existing, draft).copy(status = "active", source = "dream_confirmed")
            DatabaseService.updateMemoryNode(existing.id, merged)
            DatabaseService.replaceMemorySearchTerms(existing.id, deriveSearchTerms(merged))
            existing.id
        } else {
            val nodeId = DatabaseService.insertMemoryNode(draft)
            DatabaseService.replaceMemorySearchTerms(nodeId, deriveSearchTerms(draft))
            nodeId
        }
        DatabaseService.upsertMemoryEdge(DatabaseService.MemoryEdgeDraft(factNodeId, dream.id, "derived_from", 1.0))

        return buildJsonObject {
            put("ok", true)
            put("dream_node_id", dream.id)
            put("dream_uri", dream.uri)
            put("fact_node_id", factNodeId)
            put("fact_uri", draft.uri)
        }
    }

    private fun summaryNodeToDraft(payload: SummaryNodePayload): DatabaseService.MemoryNodeDraft {
        val normalizedUri = normalizeUri(payload.uri, payload.kind, payload.content)
        val personUri = if (isSelfUri(normalizedUri)) {
            kiyomizuUri
        } else {
            inferPersonUri(payload.content, payload.entities, payload.personUri)
        }
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
        val personUri = if (isSelfUri(normalizedUri)) {
            kiyomizuUri
        } else {
            inferPersonUri(payload.content, payload.entities, payload.personUri)
        }
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

    private fun selfNodeDraft(
        content: String,
        rawUri: String? = null,
        rawKind: String? = null,
        priority: Double = 0.85,
        confidence: Double = 0.9,
        source: String = "config",
        rawEvidence: String? = null
    ): DatabaseService.MemoryNodeDraft {
        val category = selfCategory(content, rawKind)
        val kind = rawKind?.takeIf {
            it in setOf("identity", "preference", "relationship", "project_fact", "episodic_event", "working_memory", "reflection")
        } ?: selfKindForCategory(category)
        val uri = normalizeUri(rawUri ?: selfUriForContent(content, category), kind, content).let {
            if (it.startsWith("self://")) it else selfUriForContent(content, category)
        }
        val keywords = (tokenize(content).take(10) + category).distinct()
        val topics = listOf("self", "self:$category")
        val triggers = keywords.take(8)
        val searchableText = buildSearchableText(
            content = content,
            keywords = keywords,
            aliases = emptyList(),
            entities = listOf(kiyomizuUri),
            topics = topics,
            triggerPhrases = triggers,
            uri = uri,
            scopeHint = "global",
            personUri = kiyomizuUri,
            projectUri = null
        )
        return DatabaseService.MemoryNodeDraft(
            uri = uri,
            kind = kind,
            content = content,
            normalizedText = normalizeText(content),
            searchableText = searchableText,
            keywords = keywords,
            entities = listOf(kiyomizuUri),
            topics = topics,
            triggerPhrases = triggers,
            disclosure = "private",
            priority = priority.coerceIn(0.0, 1.0),
            confidence = confidence.coerceIn(0.0, 1.0),
            strength = Config.memoryMaxStrength.coerceIn(0.0, 1.0),
            emotionValence = 0.55,
            emotionArousal = 0.25,
            scopeHint = "global",
            personUri = kiyomizuUri,
            status = "active",
            source = source,
            rawEvidence = rawEvidence
        )
    }

    private fun observationDraftFromSelfDraft(
        draft: DatabaseService.MemoryNodeDraft,
        source: String,
        rawEvidence: String?,
        retentionDays: Int = Config.memoryObservationRetentionDays
    ): DatabaseService.MemoryObservationDraft {
        return DatabaseService.MemoryObservationDraft(
            candidateUri = draft.uri,
            kind = draft.kind,
            content = draft.content,
            normalizedText = draft.normalizedText,
            searchableText = draft.searchableText,
            keywords = draft.keywords,
            aliases = draft.aliases,
            entities = draft.entities,
            topics = draft.topics,
            triggerPhrases = draft.triggerPhrases,
            personUri = kiyomizuUri,
            scopeHint = "global",
            priority = draft.priority,
            confidence = draft.confidence,
            emotionValence = draft.emotionValence,
            emotionArousal = draft.emotionArousal,
            novelty = 0.6,
            source = source,
            rawEvidence = rawEvidence,
            expiresAt = Instant.now().epochSecond + retentionDays.coerceAtLeast(1) * 86400L
        )
    }

    private fun selfConflictScore(
        existing: DatabaseService.MemoryNodeRecord,
        incoming: DatabaseService.MemoryNodeDraft
    ): Double {
        val existingCategory = selfCategory(existing.content, existing.kind, existing.topics)
        val incomingCategory = selfCategory(incoming.content, incoming.kind, incoming.topics)
        if (existingCategory != incomingCategory) return 0.0
        if (existing.normalizedText == incoming.normalizedText || existing.uri == incoming.uri) return 1.0
        val existingTerms = collectNodeTerms(existing)
        val incomingTerms = (
            tokenize(incoming.content) +
                incoming.keywords +
                incoming.topics +
                incoming.triggerPhrases +
                extractUriSegments(incoming.uri)
            ).map { normalizeTerm(it) }.filter { it.isNotBlank() }.toSet()
        return 0.45 + jaccardScore(existingTerms, incomingTerms)
    }

    private fun insertConflictObservation(
        draft: DatabaseService.MemoryNodeDraft,
        matchedNodeId: Int?,
        source: String,
        reason: String
    ): Int {
        val observation = observationDraftFromSelfDraft(draft, source, draft.rawEvidence)
        val observationId = DatabaseService.insertMemoryObservation(observation)
        DatabaseService.replaceMemoryObservationTerms(observationId, deriveObservationTerms(observation))
        DatabaseService.updateMemoryObservationStatus(observationId, "conflict", matchedNodeId)
        DatabaseService.insertSelfMemoryEvent(
            DatabaseService.SelfMemoryEventDraft(
                eventType = "conflict",
                nodeId = matchedNodeId,
                nodeUri = matchedNodeId?.let { DatabaseService.getMemoryNodeById(it)?.uri },
                observationId = observationId,
                source = source,
                reason = reason,
                contentAfter = draft.content
            )
        )
        return observationId
    }

    private fun upsertSelfDraftWithAudit(
        draft: DatabaseService.MemoryNodeDraft,
        source: String,
        reason: String,
        observationId: Int? = null
    ): DatabaseService.MemoryNodeRecord? {
        if (!isSelfUri(draft.uri) && draft.personUri != kiyomizuUri) return null
        val incomingPriority = selfSourcePriority(source)
        val existingSelf = DatabaseService.listSelfMemoryNodes("active", 200)
        val conflicts = existingSelf
            .filter { selfConflictScore(it, draft) >= 0.60 }
            .filter { it.uri != draft.uri }

        val blocking = conflicts.firstOrNull { selfSourcePriority(it.source) > incomingPriority }
        if (blocking != null) {
            insertConflictObservation(
                draft = draft,
                matchedNodeId = blocking.id,
                source = source,
                reason = "lower-priority self source cannot override ${blocking.source}"
            )
            return null
        }

        val sameUri = DatabaseService.getMemoryNodeByUri(draft.uri)
        val nodeId = if (sameUri != null && isSelfNode(sameUri)) {
            val before = sameUri.content
            DatabaseService.updateMemoryNode(sameUri.id, draft.copy(status = "active", source = source))
            DatabaseService.replaceMemorySearchTerms(sameUri.id, deriveSearchTerms(draft))
            DatabaseService.insertSelfMemoryEvent(
                DatabaseService.SelfMemoryEventDraft(
                    eventType = if (sameUri.status == "active") "edit" else "restore",
                    nodeId = sameUri.id,
                    nodeUri = draft.uri,
                    observationId = observationId,
                    source = source,
                    reason = reason,
                    contentBefore = before,
                    contentAfter = draft.content
                )
            )
            sameUri.id
        } else {
            val inserted = DatabaseService.insertMemoryNode(draft)
            DatabaseService.replaceMemorySearchTerms(inserted, deriveSearchTerms(draft))
            DatabaseService.insertSelfMemoryEvent(
                DatabaseService.SelfMemoryEventDraft(
                    eventType = "create",
                    nodeId = inserted,
                    nodeUri = draft.uri,
                    observationId = observationId,
                    newNodeId = inserted,
                    newNodeUri = draft.uri,
                    source = source,
                    reason = reason,
                    contentAfter = draft.content
                )
            )
            inserted
        }

        conflicts
            .filter { selfSourcePriority(it.source) <= incomingPriority }
            .forEach { old ->
                if (old.status == "active") {
                    DatabaseService.archiveMemoryNodeToRecycle(old, reason, Config.memoryRecycleRetentionDays)
                    DatabaseService.upsertMemoryEdge(DatabaseService.MemoryEdgeDraft(nodeId, old.id, "supersedes", 1.0))
                    DatabaseService.insertSelfMemoryEvent(
                        DatabaseService.SelfMemoryEventDraft(
                            eventType = "archive",
                            nodeId = old.id,
                            nodeUri = old.uri,
                            previousNodeId = old.id,
                            previousNodeUri = old.uri,
                            newNodeId = nodeId,
                            newNodeUri = draft.uri,
                            source = source,
                            reason = reason,
                            contentBefore = old.content,
                            contentAfter = draft.content
                        )
                    )
                }
            }

        return DatabaseService.getMemoryNodeById(nodeId)
    }

    fun createSelfMemory(
        content: String,
        uri: String? = null,
        kind: String? = null,
        priority: Double = 0.85,
        confidence: Double = 0.9,
        source: String = "config",
        reason: String = "manual self memory create"
    ): DatabaseService.MemoryNodeRecord? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null
        val draft = selfNodeDraft(
            content = trimmed,
            rawUri = uri,
            rawKind = kind,
            priority = priority,
            confidence = confidence,
            source = source,
            rawEvidence = reason
        )
        val node = upsertSelfDraftWithAudit(draft, source, reason)
        if (node != null) refreshMemoryIndexAfterMutation("self_create")
        return node
    }

    fun editSelfMemory(
        nodeId: Int,
        content: String? = null,
        uri: String? = null,
        priority: Double? = null,
        confidence: Double? = null
    ): DatabaseService.MemoryNodeRecord? {
        val existing = DatabaseService.getMemoryNodeById(nodeId)?.takeIf { isSelfNode(it) } ?: return null
        val nextContent = content?.trim()?.ifBlank { null } ?: existing.content
        val draft = selfNodeDraft(
            content = nextContent,
            rawUri = uri?.trim()?.ifBlank { null } ?: existing.uri,
            rawKind = existing.kind,
            priority = priority ?: existing.priority,
            confidence = confidence ?: existing.confidence,
            source = "config",
            rawEvidence = "manual self memory edit"
        )
        DatabaseService.updateMemoryNode(existing.id, draft)
        DatabaseService.replaceMemorySearchTerms(existing.id, deriveSearchTerms(draft))
        DatabaseService.insertSelfMemoryEvent(
            DatabaseService.SelfMemoryEventDraft(
                eventType = "edit",
                nodeId = existing.id,
                nodeUri = draft.uri,
                source = "config",
                reason = "manual self memory edit",
                contentBefore = existing.content,
                contentAfter = draft.content
            )
        )
        refreshMemoryIndexAfterMutation("self_edit")
        return DatabaseService.getMemoryNodeById(existing.id)
    }

    fun archiveSelfMemory(nodeId: Int, reason: String = "manual self memory archive"): Boolean {
        val node = DatabaseService.getMemoryNodeById(nodeId)?.takeIf { isSelfNode(it) } ?: return false
        val archived = DatabaseService.archiveMemoryNodeToRecycle(node, reason, Config.memoryRecycleRetentionDays)
        if (archived) {
            DatabaseService.insertSelfMemoryEvent(
                DatabaseService.SelfMemoryEventDraft(
                    eventType = "archive",
                    nodeId = node.id,
                    nodeUri = node.uri,
                    previousNodeId = node.id,
                    previousNodeUri = node.uri,
                    source = "config",
                    reason = reason,
                    contentBefore = node.content
                )
            )
            refreshMemoryIndexAfterMutation("self_archive")
        }
        return archived
    }

    fun confirmSelfObservation(observationId: Int): DatabaseService.MemoryNodeRecord? {
        val observation = DatabaseService.getMemoryObservationById(observationId)?.takeIf { isSelfObservation(it) } ?: return null
        val draft = selfNodeDraft(
            content = observation.content,
            rawUri = observation.candidateUri,
            rawKind = observation.kind,
            priority = max(observation.priority, 0.75),
            confidence = max(observation.confidence, 0.75),
            source = "user_confirmed",
            rawEvidence = "confirmed self observation ${observation.id}; original source=${observation.source}"
        )
        val node = upsertSelfDraftWithAudit(
            draft = draft,
            source = "user_confirmed",
            reason = "confirmed buffered self observation",
            observationId = observation.id
        ) ?: return null
        DatabaseService.updateMemoryObservationStatus(observation.id, "promoted", node.id)
        DatabaseService.insertSelfMemoryEvent(
            DatabaseService.SelfMemoryEventDraft(
                eventType = "confirm",
                nodeId = node.id,
                nodeUri = node.uri,
                observationId = observation.id,
                newNodeId = node.id,
                newNodeUri = node.uri,
                source = "user_confirmed",
                reason = "confirmed buffered self observation",
                contentBefore = observation.content,
                contentAfter = node.content
            )
        )
        refreshMemoryIndexAfterMutation("self_confirm")
        return node
    }

    fun revertSelfMemoryEvent(eventId: Int): JsonObject {
        val event = DatabaseService.getSelfMemoryEventById(eventId) ?: return buildJsonObject {
            put("ok", false)
            put("error", "self memory event not found")
        }
        val changed = mutableListOf<String>()
        event.newNodeId?.let { newNodeId ->
            val newNode = DatabaseService.getMemoryNodeById(newNodeId)
            if (newNode != null && isSelfNode(newNode) && newNode.status == "active") {
                DatabaseService.archiveMemoryNodeToRecycle(newNode, "reverted self event ${event.id}", Config.memoryRecycleRetentionDays)
                changed += "archived_new"
            }
        }
        event.previousNodeId?.let { previousNodeId ->
            val previous = DatabaseService.getMemoryNodeById(previousNodeId)
            if (previous != null && isSelfNode(previous)) {
                DatabaseService.setMemoryNodeStatus(previous.id, "active")
                changed += "restored_previous"
            }
        }
        if (event.eventType == "edit" && event.nodeId != null && !event.contentBefore.isNullOrBlank()) {
            val node = DatabaseService.getMemoryNodeById(event.nodeId)
            if (node != null && isSelfNode(node)) {
                val restored = nodeDraftFromRecord(node).copy(
                    content = event.contentBefore,
                    normalizedText = normalizeText(event.contentBefore),
                    searchableText = buildSearchableText(
                        content = event.contentBefore,
                        keywords = node.keywords,
                        aliases = node.aliases,
                        entities = node.entities,
                        topics = node.topics,
                        triggerPhrases = node.triggerPhrases,
                        uri = node.uri,
                        scopeHint = node.scopeHint,
                        personUri = node.personUri,
                        projectUri = node.projectUri
                    ),
                    status = "active"
                )
                DatabaseService.updateMemoryNode(node.id, restored)
                DatabaseService.replaceMemorySearchTerms(node.id, deriveSearchTerms(restored))
                changed += "restored_content"
            }
        }
        DatabaseService.insertSelfMemoryEvent(
            DatabaseService.SelfMemoryEventDraft(
                eventType = "revert",
                nodeId = event.nodeId,
                nodeUri = event.nodeUri,
                previousNodeId = event.previousNodeId,
                previousNodeUri = event.previousNodeUri,
                newNodeId = event.newNodeId,
                newNodeUri = event.newNodeUri,
                source = "config",
                reason = "reverted self event ${event.id}",
                contentBefore = event.contentAfter,
                contentAfter = event.contentBefore
            )
        )
        if (changed.isNotEmpty()) refreshMemoryIndexAfterMutation("self_revert")
        return buildJsonObject {
            put("ok", changed.isNotEmpty())
            put("event_id", event.id)
            put("changed", buildJsonArray { changed.forEach { add(JsonPrimitive(it)) } })
            if (changed.isEmpty()) put("error", "event had nothing reversible")
        }
    }

    private fun shouldDirectlyUpdateSelf(userText: String): Boolean {
        if (!isSelfMemoryEnabled() || !Config.memorySelfDirectUpdateEnabled) return false
        val trimmed = userText.trim()
        if (trimmed.length < 6) return false
        val longTerm = Regex("""以后|今后|从现在|默认|记住|always|from now on|by default|remember|これから|覚えて""", RegexOption.IGNORE_CASE)
            .containsMatchIn(trimmed)
        val questionLike = trimmed.endsWith("?") || trimmed.endsWith("？") || trimmed.contains("吗") || trimmed.contains("嗎")
        if (questionLike && !longTerm) return false
        return directSelfUpdatePatterns.any { it.containsMatchIn(trimmed) }
    }

    private fun secondPersonInstructionToSelfContent(userText: String): String {
        val trimmed = userText.trim().trim('。', '.', '！', '!')
        val chineseLike = Regex("""[\u4e00-\u9fff]""").containsMatchIn(trimmed)
        if (!chineseLike) {
            val cleaned = trimmed
                .replace(Regex("""(?i)\b(from now on|by default|remember that)\b"""), "")
                .replace(Regex("""(?i)\byou should\b"""), "I should")
                .replace(Regex("""(?i)\byou must\b"""), "I must")
                .replace(Regex("""(?i)\byou need to\b"""), "I need to")
                .replace(Regex("""(?i)\byou can\b"""), "I can")
                .trim()
            return if (Regex("""\bI\b""").containsMatchIn(cleaned)) cleaned else "I should follow this stable self policy: $trimmed"
        }
        return trimmed
            .replace("请记住", "")
            .replace("记住", "")
            .replace("从现在开始", "")
            .replace("以后都", "以后")
            .replace("你以后要", "我以后要")
            .replace("你以后应该", "我以后应该")
            .replace("你应该", "我应该")
            .replace("你要", "我要")
            .replace("你可以", "我可以")
            .replace("你必须", "我必须")
            .replace("你不要", "我不要")
            .replace("你别", "我别")
            .replace("默认你", "默认我")
            .trim()
            .ifBlank { "我应该遵循这条稳定的 self 策略：$trimmed" }
    }

    fun tryApplyDirectSelfUpdate(userText: String): Boolean {
        if (!shouldDirectlyUpdateSelf(userText)) return false
        val content = secondPersonInstructionToSelfContent(userText)
        val node = createSelfMemory(
            content = content,
            source = "user_direct",
            priority = 0.95,
            confidence = 0.95,
            reason = "direct user self instruction: ${userText.take(240)}"
        )
        return node != null
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
        if (isSelfPayload(payload)) {
            val source = payload.source.trim().lowercase()
            if (source == "dream" || source.contains("dream") || source == "reflection") return false
            if (payload.explicitRemember || explicitRememberRequested(userText)) return true
            return seenCount >= Config.memorySelfPromoteRepeatThreshold &&
                payload.confidence >= 0.65 &&
                payload.priority >= 0.50
        }
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
        val duplicate = findDuplicateNode(existingNodes, draft) ?: findWorkingMemorySlot(draft, allowFullFallback = true)
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
            if (isSelfPayload(payload)) {
                val observationDraft = summaryNodeToObservationDraft(payload)
                val observationId = DatabaseService.insertMemoryObservation(observationDraft)
                DatabaseService.replaceMemoryObservationTerms(observationId, deriveObservationTerms(observationDraft))
                continue
            }
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
            val existingDuplicate = findDuplicateNode(existingNodes, nodeDraft) ?:
                findWorkingMemorySlot(nodeDraft, allowFullFallback = false)
            if (existingDuplicate != null) {
                if (isSelfPayload(payload) && isSelfNode(existingDuplicate)) {
                    val incomingPriority = selfSourcePriority(payload.source)
                    val existingPriority = selfSourcePriority(existingDuplicate.source)
                    val source = payload.source.trim().lowercase()
                    val dreamOrReflection = source.contains("dream") || source == "reflection"
                    if (dreamOrReflection || incomingPriority < existingPriority) {
                        val observationId = DatabaseService.insertMemoryObservation(observationDraft)
                        DatabaseService.replaceMemoryObservationTerms(observationId, deriveObservationTerms(observationDraft))
                        val status = if (existingDuplicate.normalizedText == nodeDraft.normalizedText) "merged" else "conflict"
                        DatabaseService.updateMemoryObservationStatus(observationId, status, existingDuplicate.id)
                        if (status == "conflict") {
                            DatabaseService.insertSelfMemoryEvent(
                                DatabaseService.SelfMemoryEventDraft(
                                    eventType = "conflict",
                                    nodeId = existingDuplicate.id,
                                    nodeUri = existingDuplicate.uri,
                                    observationId = observationId,
                                    source = payload.source,
                                    reason = "self observation did not override stable self from ${existingDuplicate.source}",
                                    contentBefore = existingDuplicate.content,
                                    contentAfter = nodeDraft.content
                                )
                            )
                        }
                        observationsToday += 1
                        continue
                    }
                }
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

            if (payload.kind == "working_memory" &&
                workingMemorySlotHasCapacity(nodeDraft) &&
                promotedToday < Config.memoryPromotedNodesDailyCap
            ) {
                if (observationsToday < Config.memoryObservationDailyCap) {
                    val observationId = DatabaseService.insertMemoryObservation(observationDraft)
                    DatabaseService.replaceMemoryObservationTerms(observationId, deriveObservationTerms(observationDraft))
                    observationsToday += 1
                    val nodeId = upsertMemoryDraft(payload, nodeDraft, existingNodes, uriToId)
                    DatabaseService.updateMemoryObservationStatus(observationId, "promoted", nodeId)
                    promotedToday += 1
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
                if (userText.isEmpty()) return@launch
                tryApplyDirectSelfUpdate(userText)
                if (responseText.isBlank()) return@launch

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
                refreshMemoryIndexAfterMutation("conversation_memory")
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

    private fun relatedTermFromEdge(edge: DatabaseService.MemoryTermEdgeRecord, sourceTerms: Set<String>): String? {
        return when {
            edge.termA in sourceTerms && edge.termB !in sourceTerms -> edge.termB
            edge.termB in sourceTerms && edge.termA !in sourceTerms -> edge.termA
            else -> null
        }
    }

    private fun expandRecallTerms(
        terms: List<String>,
        extraTerms: List<String> = emptyList(),
        maxHops: Int = 1
    ): List<String> {
        if (!Config.memoryLocalRecallEnhancedEnabled || !Config.memoryTagGraphEnabled) return emptyList()
        if (maxHops <= 0 || Config.memoryTagGraphMaxExpandedTerms <= 0) return emptyList()
        val sourceTerms = (terms + extraTerms).map { normalizeTerm(it) }.filter { it.isNotBlank() }.distinct()
        if (sourceTerms.isEmpty()) return emptyList()
        val limit = Config.memoryTagGraphMaxExpandedTerms.coerceIn(0, 128)
        val seen = sourceTerms.toMutableSet()
        var frontier = sourceTerms
        val expanded = mutableListOf<String>()

        repeat(maxHops.coerceIn(1, 2)) {
            if (frontier.isEmpty() || expanded.size >= limit) return@repeat
            val frontierSet = frontier.toSet()
            val next = DatabaseService.getRelatedMemoryTerms(frontier, limit * 2)
                .mapNotNull { relatedTermFromEdge(it, frontierSet) }
                .filter { it.isNotBlank() && it !in seen }
                .distinct()
                .take(limit - expanded.size)
            expanded += next
            seen += next
            frontier = next
        }
        return expanded
    }

    private fun enhancedSearch(
        context: RecallContext,
        limit: Int,
        statuses: Set<String> = setOf("active"),
        modelTerms: List<String> = emptyList(),
        termGraphHops: Int = 1
    ): EnhancedSearchResult {
        if (limit <= 0) return EnhancedSearchResult(emptyList(), emptyList(), 0, 0, 0)
        val primaryTerms = (context.queryTerms + modelTerms)
            .map { normalizeTerm(it) }
            .filter { it.isNotBlank() }
            .distinct()
        val expandedTerms = expandRecallTerms(context.queryTerms, modelTerms, termGraphHops)
        val searchTerms = if (Config.memoryLocalRecallEnhancedEnabled) primaryTerms else context.queryTerms
        val hits = DatabaseService.searchMemoryNodeHits(
            queryTerms = searchTerms,
            expandedTerms = if (Config.memoryLocalRecallEnhancedEnabled) expandedTerms else emptyList(),
            statuses = statuses,
            limit = limit
        )
        return EnhancedSearchResult(
            hits = hits,
            expandedTerms = expandedTerms,
            termGraphHitCount = hits.count { hit -> hit.matchedTerms.any { it in expandedTerms } },
            ftsHitCount = hits.count { it.matchReason.contains("fts_bm25") },
            termWeightHitCount = hits.count { it.matchReason.contains("term_weight") }
        )
    }

    private fun deterministicOrderKey(seed: String, value: String): Int {
        return "$seed:$value".hashCode()
    }

    private fun timelineBuckets(
        perBucketLimit: Int,
        statuses: Set<String> = setOf("active"),
        seed: String? = null
    ): TimelineBuckets {
        val now = Instant.now().epochSecond
        var recentDays = 7
        var recent = DatabaseService.getGraphMemoryNodesByCreatedRange(
            minCreatedAt = now - recentDays * 86400L,
            maxCreatedAt = now,
            limit = perBucketLimit,
            statuses = statuses
        )
        while (recent.size < 3 && recentDays < 30) {
            recentDays = minOf(30, recentDays + 7)
            recent = DatabaseService.getGraphMemoryNodesByCreatedRange(now - recentDays * 86400L, now, perBucketLimit, statuses)
        }

        var midDays = 90
        var mid = DatabaseService.getGraphMemoryNodesByCreatedRange(
            minCreatedAt = now - midDays * 86400L,
            maxCreatedAt = now - (recentDays + 1) * 86400L,
            limit = perBucketLimit,
            statuses = statuses
        )
        while (mid.size < 2 && midDays < 180) {
            midDays = minOf(180, midDays + 30)
            mid = DatabaseService.getGraphMemoryNodesByCreatedRange(now - midDays * 86400L, now - (recentDays + 1) * 86400L, perBucketLimit, statuses)
        }

        val deep = DatabaseService.getGraphMemoryNodesByCreatedRange(
            minCreatedAt = null,
            maxCreatedAt = now - (midDays + 1) * 86400L,
            limit = perBucketLimit,
            statuses = statuses
        )

        fun ordered(nodes: List<DatabaseService.MemoryNodeRecord>): List<DatabaseService.MemoryNodeRecord> {
            return if (seed == null) {
                nodes.sortedWith(compareByDescending<DatabaseService.MemoryNodeRecord> { it.priority + it.confidence }.thenByDescending { it.createdAt }.thenBy { it.uri })
            } else {
                nodes.sortedWith(compareBy<DatabaseService.MemoryNodeRecord> { deterministicOrderKey(seed, it.uri) }.thenBy { it.uri })
            }
        }

        return TimelineBuckets(
            recent = ordered(recent),
            mid = ordered(mid),
            deep = ordered(deep),
            recentDays = recentDays,
            midDays = midDays
        )
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
            "identity" -> Config.memoryKindBiasIdentity
            "preference" -> Config.memoryKindBiasPreference
            "relationship" -> Config.memoryKindBiasRelationship
            "project_fact" -> Config.memoryKindBiasProjectFact
            "episodic_event" -> Config.memoryKindBiasEpisodicEvent
            "working_memory" -> Config.memoryKindBiasWorkingMemory
            else -> Config.memoryKindBiasDefault
        }
        val recency = lazyStrength(node, now)
        val salience = emotionalSalience(node.emotionValence, node.emotionArousal)
        var score = overlap * Config.memoryScoreOverlapWeight +
            triggerHits * Config.memoryScoreTriggerHitsWeight +
            uriHits * Config.memoryScoreUriHitsWeight +
            peopleBoost +
            projectBoost +
            relationshipBoost +
            kindBias +
            (node.priority * Config.memoryScorePriorityWeight) +
            (node.confidence * Config.memoryScoreConfidenceWeight) +
            (recency * Config.memoryScoreRecencyWeight) +
            (salience * Config.memoryScoreSalienceWeight)

        if (node.disclosure == "sensitive" && overlap < 0.18 && peopleBoost == 0.0 && projectBoost == 0.0) {
            score += Config.memorySensitivePenalty
        }
        if (!context.deepRecall && node.kind in setOf("project_fact", "episodic_event", "working_memory") &&
            overlap < 0.12 && peopleBoost == 0.0 && projectBoost == 0.0
        ) {
            score *= Config.memoryNonDeepEpisodicPenalty
        }
        if (node.kind == "working_memory" && now - node.updatedAt > (Config.memoryWorkingMemoryStaleDays * 86400.0).toLong()) {
            score *= Config.memoryWorkingMemoryStalePenalty
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
        val enhanced = enhancedSearch(context, searchLimit)
        val hitById = enhanced.hits.associateBy { it.node.id }
        val seeds = linkedSetOf<DatabaseService.MemoryNodeRecord>()
        enhanced.hits.forEach { seeds += it.node }
        if (seeds.isEmpty()) {
            DatabaseService.searchMemoryNodes(context.queryTerms, searchLimit).forEach { seeds += it }
        }
        DatabaseService.getRecentGraphMemoryNodes(
            limit = 10,
            kinds = setOf("identity", "preference", "relationship")
        ).forEach { seeds += it }

        val primary = seeds
            .map {
                val hit = hitById[it.id]
                ScoredNode(
                    node = it,
                    score = scoreNode(context, it, now) + (hit?.textScore ?: 0.0) * 0.35,
                    matchReason = hit?.matchReason ?: "",
                    matchedTerms = hit?.matchedTerms ?: emptyList()
                )
            }
            .filter { it.score >= defaultNormalRecallThreshold }
            .sortedWith(compareByDescending<ScoredNode> { it.score }.thenBy { it.node.uri }.thenBy { it.node.id })
            .take(limit)

        val associated = expandAssociatedNodes(primary, context, now, limit / 2)
        val personContext = selectPersonContext(context, emptySet(), now)
        val personContextIds = personContext.map { it.memory.id }.toSet()

        val recalled = (primary + associated)
            .filterNot { it.node.id in personContextIds }
            .sortedWith(compareByDescending<ScoredNode> { it.score }.thenBy { it.node.uri }.thenBy { it.node.id })
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

    private fun shouldTriggerSelfRecall(userQuery: String): Boolean {
        if (!isSelfMemoryEnabled()) return false
        val trimmed = userQuery.trim()
        if (trimmed.isEmpty()) return false
        return selfRecallPatterns.any { it.containsMatchIn(trimmed) }
    }

    private fun shouldIncludeSelfObservations(userQuery: String): Boolean {
        if (!shouldTriggerSelfRecall(userQuery)) return false
        return selfUncertainDisclosurePatterns.any { it.containsMatchIn(userQuery) }
    }

    private fun selectSelfMemories(context: RecallContext, now: Long = Instant.now().epochSecond): List<RecalledMemory> {
        if (!shouldTriggerSelfRecall(context.query)) return emptyList()
        val limit = Config.memorySelfRecallMaxNodes.coerceAtLeast(0)
        if (limit == 0) return emptyList()
        val category = selfCategory(context.query)
        val candidates = DatabaseService.listSelfMemoryNodes("active", limit = max(limit * 6, 24))
            .filter { isSelfUri(it.uri) }
        return candidates
            .map { node ->
                val nodeCategory = selfCategory(node.content, node.kind, node.topics)
                val categoryBoost = if (nodeCategory == category) 1.0 else 0.0
                val queryHits = context.queryTerms.count { term ->
                    node.searchableText.lowercase().contains(term) || node.uri.lowercase().contains(term)
                } * 0.15
                val score = scoreNode(context, node, now) + categoryBoost + queryHits + selfSourcePriority(node.source) * 0.25
                RecalledMemory(node, score, associated = false, channel = "self")
            }
            .sortedWith(
                compareByDescending<RecalledMemory> { selfSourcePriority(it.memory.source) }
                    .thenByDescending { selfCategory(it.memory.content, it.memory.kind, it.memory.topics) == category }
                    .thenByDescending { it.score }
                    .thenByDescending { it.memory.priority }
                    .thenByDescending { it.memory.confidence }
                    .thenBy { it.memory.uri }
                    .thenBy { it.memory.id }
            )
            .take(limit)
    }

    private fun selectSelfObservationsForDisclosure(userQuery: String): List<DatabaseService.MemoryObservationRecord> {
        if (!shouldIncludeSelfObservations(userQuery)) return emptyList()
        val buffered = DatabaseService.listSelfMemoryObservations("buffered", 12)
        val conflicts = DatabaseService.listSelfMemoryObservations("conflict", 8)
        return (buffered + conflicts)
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<DatabaseService.MemoryObservationRecord> { it.source == "dream" || it.source.contains("dream") }
                    .thenByDescending { it.lastSeenAt }
                    .thenByDescending { it.priority }
                    .thenBy { it.candidateUri ?: "" }
            )
            .take(8)
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
        val enhanced = enhancedSearch(context, searchLimit)
        val hitById = enhanced.hits.associateBy { it.node.id }
        enhanced.hits.forEach { seeds += it.node }
        if (seeds.isEmpty()) {
            DatabaseService.searchMemoryNodes(context.queryTerms, searchLimit).forEach { seeds += it }
        }
        if (Config.memoryTimelineRecallEnabled) {
            val buckets = timelineBuckets(perBucketLimit = max(4, candidateLimit / 3))
            (buckets.recent + buckets.mid + buckets.deep).forEach { seeds += it }
        } else {
            DatabaseService.getRecentGraphMemoryNodes(limit = minOf(24, candidateLimit * 2)).forEach { seeds += it }
        }

        val primary = seeds
            .map {
                val hit = hitById[it.id]
                ScoredNode(
                    node = it,
                    score = scoreNode(context, it, now) + (hit?.textScore ?: 0.0) * 0.35,
                    channel = "deep",
                    matchReason = hit?.matchReason ?: "",
                    matchedTerms = hit?.matchedTerms ?: emptyList()
                )
            }
            .filter { it.score >= defaultDeepRecallWeakThreshold }
            .sortedWith(compareByDescending<ScoredNode> { it.score }.thenBy { it.node.uri }.thenBy { it.node.id })
            .take(candidateLimit)

        val associated = expandAssociatedNodes(primary, context, now, candidateLimit / 2)
        val combined = (primary + associated)
            .distinctBy { it.node.id }
            .sortedWith(compareByDescending<ScoredNode> { it.score }.thenBy { it.node.uri }.thenBy { it.node.id })
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

    private fun isExplicitMemoryRecallRequest(userQuery: String): Boolean {
        val normalized = normalizeText(userQuery)
        return shouldTriggerDeepRecall(userQuery) ||
            normalized.contains("记得") ||
            normalized.contains("回忆") ||
            normalized.contains("想起") ||
            normalized.contains("梦") ||
            normalized.contains("冲突") ||
            normalized.contains("敏感") ||
            normalized.contains("不确定") ||
            Regex("""\b(remember|recall|memory|dream|conflict|sensitive|uncertain)\b""", RegexOption.IGNORE_CASE).containsMatchIn(userQuery)
    }

    private fun modelRecallCooldownActive(): Boolean {
        return System.currentTimeMillis() < modelRecallCooldownUntilMs.get()
    }

    fun modelRecallDiagnostics(): JsonObject {
        val latest = DatabaseService.getRecentModelRecallTraces(1).firstOrNull()
        val segments = DatabaseService.listMemoryIndexSegments()
        return buildJsonObject {
            put("enabled", Config.memoryModelRecallEnabled)
            put("cooldown_until", modelRecallCooldownUntilMs.get())
            put("consecutive_failures", modelRecallConsecutiveFailures.get())
            put("index_version", indexVersionString(segments))
            put("index_dirty", segments.any { it.dirty })
            put("index_error", segments.firstOrNull { !it.error.isNullOrBlank() }?.error ?: "")
            if (latest != null) {
                put("last_trace", modelRecallTraceJson(latest))
            }
        }
    }

    private fun modelRecallTraceJson(trace: DatabaseService.ModelRecallTraceRecord): JsonObject {
        return buildJsonObject {
            put("id", trace.id)
            put("created_at", trace.createdAt)
            put("query", trace.query)
            put("index_version", trace.indexVersion)
            put("plan_json", trace.planJson ?: "")
            put("candidate_count", trace.candidateCount)
            put("injected_count", trace.injectedCount)
            put("filtered_summary", trace.filteredSummary ?: "")
            put("fallback_reason", trace.fallbackReason ?: "")
            put("error", trace.error ?: "")
            put("duration_ms", trace.durationMs)
            put("debug_json", trace.debugJson ?: "")
        }
    }

    fun recentModelRecallTracesJson(limit: Int): JsonObject {
        return buildJsonObject {
            put("traces", buildJsonArray {
                DatabaseService.getRecentModelRecallTraces(limit).forEach { add(modelRecallTraceJson(it)) }
            })
        }
    }

    private fun buildModelRecallPrompt(userQuery: String, indexVersion: String, indexText: String): String {
        return """
            You are Kiyomizu's memory recall planner.
            Read the stable materialized memory index and produce a retrieval plan. Do not answer the user.
            Use only JSON. Do not invent memory facts.
            Prefer exact target_uris when the index exposes them. Use query_terms, uri_prefixes, kinds, people, and projects when exact URI is unclear.
            Use the term_graph segment for associative seed terms and the timeline segment for recent/mid/deep recall hints.
            Sensitive summaries may be hidden in the index. Set include_sensitive=true only when the user is explicitly asking to recall sensitive/private material or directly points at it.
            Set include_archived/include_conflicts/include_dreams only for explicit recollection, dream, conflict, or old-memory requests.
            Set need_deep_recall=true only when the user clearly asks for recollection, dream traces, conflict, or old history.
            candidate_strategy may be direct, associative, timeline, or deep_recall. It is a hint; backend filters still decide final retrieval.
            Output shape:
            {
              "target_uris": ["uri"],
              "uri_prefixes": ["preference://"],
              "query_terms": ["term"],
              "seed_terms": ["term from query or index"],
              "expanded_terms": ["associated term from term_graph"],
              "kinds": ["identity", "preference", "relationship", "project_fact", "episodic_event", "working_memory", "reflection"],
              "people": ["person://user/primary"],
              "projects": ["project://..."],
              "time_hint": "optional natural time hint",
              "time_buckets": ["recent", "mid", "deep"],
              "term_graph_hops": 1,
              "candidate_strategy": "direct",
              "include_sensitive": false,
              "include_archived": false,
              "include_conflicts": false,
              "include_dreams": false,
              "need_deep_recall": false,
              "reason": "short reason"
            }

            Index version: $indexVersion
            Materialized memory index:
            $indexText

            User request:
            $userQuery
        """.trimIndent()
    }

    private fun parseModelRecallPlan(raw: String): ModelRecallPlan? {
        val parsed = Json.parseToJsonElement(cleanJsonString(raw)) as? JsonObject ?: return null
        return ModelRecallPlan(
            targetUris = parseStringArray(parsed["target_uris"]).map { it.trim() }.filter { it.isNotBlank() }.distinct().take(24),
            uriPrefixes = parseStringArray(parsed["uri_prefixes"]).map { it.trim() }.filter { it.isNotBlank() }.distinct().take(12),
            queryTerms = parseStringArray(parsed["query_terms"]).map { normalizeTerm(it) }.filter { it.isNotBlank() }.distinct().take(24),
            kinds = parseStringArray(parsed["kinds"]).map { it.trim() }.filter { it.isNotBlank() }.distinct().take(12),
            people = parseStringArray(parsed["people"]).map { it.trim() }.filter { it.isNotBlank() }.distinct().take(12),
            projects = parseStringArray(parsed["projects"]).map { it.trim() }.filter { it.isNotBlank() }.distinct().take(12),
            timeHint = parsed["time_hint"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null },
            includeSensitive = parsed["include_sensitive"]?.jsonPrimitive?.booleanOrNull ?: false,
            includeArchived = parsed["include_archived"]?.jsonPrimitive?.booleanOrNull ?: false,
            includeConflicts = parsed["include_conflicts"]?.jsonPrimitive?.booleanOrNull ?: false,
            includeDreams = parsed["include_dreams"]?.jsonPrimitive?.booleanOrNull ?: false,
            needDeepRecall = parsed["need_deep_recall"]?.jsonPrimitive?.booleanOrNull ?: false,
            reason = parsed["reason"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null },
            seedTerms = parseStringArray(parsed["seed_terms"]).map { normalizeTerm(it) }.filter { it.isNotBlank() }.distinct().take(24),
            expandedTerms = parseStringArray(parsed["expanded_terms"]).map { normalizeTerm(it) }.filter { it.isNotBlank() }.distinct().take(24),
            timeBuckets = parseStringArray(parsed["time_buckets"]).map { it.trim().lowercase() }
                .filter { it in setOf("recent", "mid", "deep") }
                .distinct(),
            termGraphHops = (parsed["term_graph_hops"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(0, 2),
            candidateStrategy = parsed["candidate_strategy"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.lowercase()
                ?.takeIf { it in setOf("direct", "associative", "timeline", "deep_recall") },
            rawJson = parsed
        )
    }

    private fun nodeAllowedForModelRecall(
        node: DatabaseService.MemoryNodeRecord,
        plan: ModelRecallPlan,
        explicitRecall: Boolean,
        dreamRelevant: Boolean
    ): Boolean {
        val isDream = node.status == "dream" || node.uri.startsWith("dream://")
        val isArchived = node.status == "archived"
        val isSensitive = node.disclosure == "sensitive"
        val isSelf = isSelfNode(node)

        if (isDream && !(plan.includeDreams && (explicitRecall || dreamRelevant))) return false
        if (isArchived && !(explicitRecall && plan.includeArchived)) return false
        if (isSensitive && !(explicitRecall && plan.includeSensitive)) return false
        if (isSelf && node.status != "active" && !explicitRecall) return false
        if (!isDream && !isArchived && node.status != "active") return false
        return true
    }

    private fun collectModelRecallCandidates(
        plan: ModelRecallPlan,
        context: RecallContext,
        explicitRecall: Boolean
    ): ModelRecallCandidateResult {
        val now = Instant.now().epochSecond
        val seeds = linkedMapOf<Int, DatabaseService.MemoryNodeRecord>()
        val sourceById = mutableMapOf<Int, MutableSet<String>>()
        val filteredCounts = mutableMapOf<String, Int>()
        val dreamRelevant = shouldConsiderDreamTraces(context)

        fun filterReason(node: DatabaseService.MemoryNodeRecord): String? {
            val isDream = node.status == "dream" || node.uri.startsWith("dream://")
            val isArchived = node.status == "archived"
            val isSensitive = node.disclosure == "sensitive"
            val isSelf = isSelfNode(node)
            return when {
                isDream && !(plan.includeDreams && (explicitRecall || dreamRelevant)) -> "dream"
                isArchived && !(explicitRecall && plan.includeArchived) -> "archived"
                isSensitive && !(explicitRecall && plan.includeSensitive) -> "sensitive"
                isSelf && node.status != "active" && !explicitRecall -> "self_non_active"
                !isDream && !isArchived && node.status != "active" -> "non_active"
                else -> null
            }
        }

        fun addNode(node: DatabaseService.MemoryNodeRecord?, source: String) {
            if (node == null) return
            val reason = filterReason(node)
            if (reason == null) {
                seeds[node.id] = node
                sourceById.getOrPut(node.id) { linkedSetOf() }.add(source)
            } else {
                filteredCounts[reason] = (filteredCounts[reason] ?: 0) + 1
            }
        }

        plan.targetUris.forEach { uri ->
            addNode(DatabaseService.getMemoryNodeByUri(uri), "direct_uri")
        }

        val statuses = buildList {
            add("active")
            if (explicitRecall && plan.includeArchived) add("archived")
            if (plan.includeDreams && (explicitRecall || dreamRelevant)) add("dream")
        }.distinct()

        plan.uriPrefixes.forEach { prefix ->
            statuses.forEach { status ->
                DatabaseService.listMemoryNodes(null, prefix, null, null, status, 40).forEach { addNode(it, "uri_prefix") }
            }
        }

        plan.kinds.forEach { kind ->
            statuses.forEach { status ->
                DatabaseService.listMemoryNodes(null, null, kind, null, status, 40).forEach { addNode(it, "kind") }
            }
        }

        val modelTerms = (plan.queryTerms + plan.seedTerms + plan.expandedTerms)
            .map { normalizeTerm(it) }
            .filter { it.isNotBlank() }
            .distinct()
        val queryText = modelTerms.joinToString(" ").ifBlank { context.queryTerms.joinToString(" ") }
        val enhanced = enhancedSearch(
            context = context,
            limit = max(Config.memoryRecallMaxNodes * 8, 32),
            statuses = statuses.toSet(),
            modelTerms = modelTerms,
            termGraphHops = plan.termGraphHops
        )
        val hitById = enhanced.hits.associateBy { it.node.id }
        enhanced.hits.forEach { hit ->
            val source = if (hit.matchedTerms.any { it in enhanced.expandedTerms || it in plan.expandedTerms }) "term_graph" else "text"
            addNode(hit.node, source)
        }
        if (queryText.isNotBlank()) {
            if (explicitRecall) {
                statuses.forEach { status ->
                    DatabaseService.listMemoryNodes(queryText, null, null, null, status, 50).forEach { addNode(it, "explicit_text") }
                }
            }
        }

        if (plan.people.isNotEmpty() || plan.projects.isNotEmpty()) {
            statuses.forEach { status ->
                DatabaseService.listMemoryNodes(null, null, null, null, status, 120)
                    .filter { node ->
                        plan.people.any { uriSoftMatches(node.personUri, setOf(it)) || uriSoftMatches(node.uri, setOf(it)) } ||
                            plan.projects.any { uriSoftMatches(node.projectUri, setOf(it)) || uriSoftMatches(node.uri, setOf(it)) }
                    }
                    .forEach { addNode(it, "person_project") }
            }
        }

        val selectedTimeBuckets = buildSet {
            addAll(plan.timeBuckets)
            if (plan.candidateStrategy in setOf("timeline", "deep_recall") || (explicitRecall && plan.needDeepRecall)) {
                add("recent")
                add("mid")
                add("deep")
            }
        }.filter { it in setOf("recent", "mid", "deep") }
        if (Config.memoryTimelineRecallEnabled && (selectedTimeBuckets.isNotEmpty() || explicitRecall)) {
            val buckets = timelineBuckets(perBucketLimit = max(6, Config.memoryRecallMaxNodes * 2), statuses = statuses.toSet())
            if (selectedTimeBuckets.isEmpty() || "recent" in selectedTimeBuckets) {
                buckets.recent.forEach { addNode(it, "timeline_recent") }
            }
            if (selectedTimeBuckets.isEmpty() || "mid" in selectedTimeBuckets) {
                buckets.mid.forEach { addNode(it, "timeline_mid") }
            }
            if (selectedTimeBuckets.isEmpty() || "deep" in selectedTimeBuckets) {
                buckets.deep.forEach { addNode(it, "timeline_deep") }
            }
        }

        val primary = seeds.values
            .map { node ->
                val directUriBoost = if (node.uri in plan.targetUris) 2.0 else 0.0
                val prefixBoost = if (plan.uriPrefixes.any { node.uri.startsWith(it) }) 0.8 else 0.0
                val textHit = hitById[node.id]
                val sources = sourceById[node.id].orEmpty()
                val associativeBoost = if ("term_graph" in sources || plan.candidateStrategy == "associative") 0.35 else 0.0
                val timelineBoost = if (sources.any { it.startsWith("timeline_") } || plan.candidateStrategy == "timeline") 0.25 else 0.0
                val deepBoost = if (plan.candidateStrategy == "deep_recall" || plan.needDeepRecall) 0.2 else 0.0
                val score = scoreNode(context, node, now) +
                    directUriBoost +
                    prefixBoost +
                    (textHit?.textScore ?: 0.0) * 0.35 +
                    associativeBoost +
                    timelineBoost +
                    deepBoost
                ScoredNode(
                    node = node,
                    score = score,
                    associated = false,
                    channel = modelRecallChannel(node),
                    matchReason = (sources + listOfNotNull(textHit?.matchReason)).joinToString("+"),
                    matchedTerms = textHit?.matchedTerms ?: emptyList()
                )
            }
            .sortedWith(compareByDescending<ScoredNode> { it.score }.thenBy { it.node.uri }.thenBy { it.node.id })

        val associated = expandAssociatedNodes(primary.take(Config.memoryRecallMaxNodes.coerceAtLeast(1)), context, now, Config.memoryRecallMaxNodes)
            .filter {
                val reason = filterReason(it.node)
                if (reason != null) filteredCounts[reason] = (filteredCounts[reason] ?: 0) + 1
                reason == null
            }
            .map { it.copy(channel = modelRecallChannel(it.node), associated = true, matchReason = "graph_edge") }

        val conflictAssociated = if (explicitRecall && plan.includeConflicts) {
            val conflictIds = DatabaseService.getEdgesForNodeIds(
                primary.take(12).map { it.node.id },
                relations = setOf("contradicts", "supersedes"),
                limit = 24
            ).flatMap { listOf(it.fromNodeId, it.toNodeId) }.toSet()
            DatabaseService.getMemoryNodesByIds(conflictIds)
                .filter {
                    val reason = filterReason(it)
                    if (reason != null) filteredCounts[reason] = (filteredCounts[reason] ?: 0) + 1
                    reason == null
                }
                .map { ScoredNode(it, scoreNode(context, it, now), associated = true, channel = modelRecallChannel(it), matchReason = "conflict_edge") }
        } else {
            emptyList()
        }

        val all = (primary + associated + conflictAssociated)
            .distinctBy { it.node.id }
            .sortedWith(compareByDescending<ScoredNode> { it.score }.thenBy { it.node.uri }.thenBy { it.node.id })
        val debug = buildJsonObject {
            put("model_seed_terms", buildJsonArray { plan.seedTerms.forEach { add(JsonPrimitive(it)) } })
            put("model_expanded_terms", buildJsonArray { plan.expandedTerms.forEach { add(JsonPrimitive(it)) } })
            put("backend_expanded_terms", buildJsonArray { enhanced.expandedTerms.forEach { add(JsonPrimitive(it)) } })
            put("selected_time_buckets", buildJsonArray { selectedTimeBuckets.forEach { add(JsonPrimitive(it)) } })
            put("candidate_strategy", plan.candidateStrategy ?: "")
            put("direct_uri_hits", seeds.values.count { it.uri in plan.targetUris })
            put("term_graph_hits", enhanced.termGraphHitCount)
            put("fts_bm25_hits", enhanced.ftsHitCount)
            put("term_weight_hits", enhanced.termWeightHitCount)
            put("candidate_count", all.size)
            put("filtered", buildJsonObject {
                put("sensitive", filteredCounts["sensitive"] ?: 0)
                put("dream", filteredCounts["dream"] ?: 0)
                put("archived", filteredCounts["archived"] ?: 0)
                put("self", filteredCounts["self_non_active"] ?: 0)
                put("non_active", filteredCounts["non_active"] ?: 0)
            })
        }
        val filteredSummary = "filtered_by_backend=" + filteredCounts.entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}:${it.value}" }
            .ifBlank { "0" }
        return ModelRecallCandidateResult(all, filteredSummary, debug)
    }

    private fun modelRecallChannel(node: DatabaseService.MemoryNodeRecord): String {
        return when {
            node.uri.startsWith("dream://") || node.status == "dream" -> "model_dream_weak"
            node.status != "active" -> "model_weak_${node.status}"
            node.disclosure == "sensitive" -> "model_sensitive_weak"
            else -> "model_recall"
        }
    }

    private suspend fun tryModelRecall(userQuery: String, context: RecallContext): ModelRecallResult? {
        if (!Config.memoryModelRecallEnabled) return null
        val started = System.currentTimeMillis()
        val explicitRecall = isExplicitMemoryRecallRequest(userQuery)
        if (modelRecallCooldownActive()) {
            return ModelRecallResult(emptyList(), emptyList(), emptyList(), null, "model_recall_cooldown", null, 0, (System.currentTimeMillis() - started).toInt())
        }
        val recallKey = Config.memoryRecallModelKey.ifBlank { Config.memorySummaryKey }
        if (recallKey.isBlank()) {
            return ModelRecallResult(emptyList(), emptyList(), emptyList(), null, "recall_model_unconfigured", null, 0, (System.currentTimeMillis() - started).toInt())
        }

        val (indexVersion, indexText) = materializedIndexPromptText()
        val raw = callRecallModel(buildModelRecallPrompt(userQuery, indexVersion, indexText))
        if (raw == null) {
            return modelRecallFailure(userQuery, indexVersion, null, "recall_model_empty_response", started)
        }

        val plan = try {
            parseModelRecallPlan(raw)
        } catch (e: Exception) {
            null
        } ?: return modelRecallFailure(userQuery, indexVersion, null, "recall_model_invalid_json", started)

        val planJson = plan.rawJson.toString()
        val candidateResult = collectModelRecallCandidates(plan, context, explicitRecall)
        val candidates = candidateResult.candidates
        val ordinaryLimit = Config.memoryRecallMaxNodes.coerceAtLeast(0)
        val selfLimit = if (shouldTriggerSelfRecall(userQuery)) Config.memorySelfRecallMaxNodes.coerceAtLeast(0) else 2
        val dreamLimit = Config.memoryDreamRecallMaxTraces.coerceAtLeast(0)

        val selfMemories = candidates
            .filter { isSelfNode(it.node) && it.node.status == "active" }
            .sortedWith(compareByDescending<ScoredNode> { selfSourcePriority(it.node.source) }.thenByDescending { it.score })
            .take(selfLimit)
            .map { RecalledMemory(it.node, it.score, it.associated, "model_self") }

        val selfIds = selfMemories.map { it.memory.id }.toSet()
        val dreamTraces = candidates
            .filter { it.node.status == "dream" || it.node.uri.startsWith("dream://") }
            .take(dreamLimit)
            .map { RecalledMemory(it.node, it.score, it.associated, it.channel) }

        val dreamIds = dreamTraces.map { it.memory.id }.toSet()
        val recalled = candidates
            .filter { it.node.id !in selfIds && it.node.id !in dreamIds }
            .take(ordinaryLimit)
            .map { RecalledMemory(it.node, it.score, it.associated, it.channel) }

        val duration = (System.currentTimeMillis() - started).toInt()
        // Decision: model-recall success now reinforces the ordinary/dream nodes it injected (strength +
        // access_count + stability growth), aligning with the local normalRecall path (factor 0.35). This is a
        // behavior change vs. the prior "#2 model recall does not boost" decision, motivated by stability needing
        // a recall-success signal. Only strength/access/stability/timing change — no fusion boost, no model hint
        // flows to local recall. selfMemories are NOT boosted here: they are boosted uniformly at
        // buildCompanionMemoryContext (factor 0.25), so boosting here too would double-count.
        recalled.forEach { DatabaseService.updateMemoryNodeAccess(it.memory.id, Config.memoryRecoveryAmount * 0.35, Config.memoryMaxStrength) }
        dreamTraces.forEach { DatabaseService.updateMemoryNodeAccess(it.memory.id, Config.memoryRecoveryAmount * 0.35, Config.memoryMaxStrength) }
        val successDebug = JsonObject(
            candidateResult.debugJson + mapOf(
                "injected_uris" to buildJsonArray { recalled.forEach { add(JsonPrimitive(it.memory.uri)) } },
                "injected_self_uris" to buildJsonArray { selfMemories.forEach { add(JsonPrimitive(it.memory.uri)) } },
                "injected_dream_uris" to buildJsonArray { dreamTraces.forEach { add(JsonPrimitive(it.memory.uri)) } }
            )
        )
        val traceId = DatabaseService.insertModelRecallTrace(
            query = userQuery,
            indexVersion = indexVersion,
            planJson = planJson,
            candidateCount = candidates.size,
            injectedCount = recalled.size + selfMemories.size + dreamTraces.size,
            filteredSummary = candidateResult.filteredSummary,
            fallbackReason = null,
            error = null,
            durationMs = duration,
            debugJson = successDebug.toString()
        )
        modelRecallConsecutiveFailures.set(0)
        return ModelRecallResult(recalled, selfMemories, dreamTraces, plan, null, null, candidates.size, duration, traceId)
    }

    private fun modelRecallFailure(
        userQuery: String,
        indexVersion: String,
        planJson: String?,
        error: String,
        started: Long
    ): ModelRecallResult {
        val failures = modelRecallConsecutiveFailures.incrementAndGet()
        if (failures >= Config.memoryModelRecallFailureThreshold.coerceAtLeast(1)) {
            modelRecallCooldownUntilMs.set(System.currentTimeMillis() + Config.memoryModelRecallCooldownSeconds.coerceAtLeast(0) * 1000L)
        }
        val duration = (System.currentTimeMillis() - started).toInt()
        val traceId = DatabaseService.insertModelRecallTrace(
            query = userQuery,
            indexVersion = indexVersion,
            planJson = planJson,
            candidateCount = 0,
            injectedCount = 0,
            filteredSummary = null,
            fallbackReason = "local_recall_fallback",
            error = error,
            durationMs = duration
        )
        return ModelRecallResult(emptyList(), emptyList(), emptyList(), null, "local_recall_fallback", error, 0, duration, traceId)
    }

    suspend fun buildCompanionMemoryContext(userQuery: String): CompanionMemoryContext {
        if (!Config.memoryEnabled) return CompanionMemoryContext(emptyList(), emptyList(), null, emptyList(), emptyList(), emptyList())
        val normalContext = buildRecallContext(userQuery, deepRecall = false)
        val modelRecall = tryModelRecall(userQuery, normalContext)
        val useModelRecall = modelRecall != null && modelRecall.fallbackReason == null && modelRecall.error == null
        val (recalled, personContext) = if (useModelRecall) {
            val personContext = selectPersonContext(normalContext, modelRecall!!.recalled.map { it.memory.id }.toSet(), Instant.now().epochSecond)
            modelRecall.recalled to personContext
        } else {
            normalRecall(normalContext)
        }

        val modelRequestedDeepRecall = modelRecall?.plan?.needDeepRecall == true && isExplicitMemoryRecallRequest(userQuery)
        val deepRecallResult = if (shouldTriggerDeepRecall(userQuery) || modelRequestedDeepRecall) {
            deepRecall(buildRecallContext(userQuery, deepRecall = true))
        } else {
            null
        }
        val dreamTraces = if (useModelRecall) {
            modelRecall!!.dreamTraces
        } else {
            selectDreamTraces(buildRecallContext(userQuery, deepRecall = deepRecallResult != null))
        }
        val selfContext = buildRecallContext(userQuery, deepRecall = deepRecallResult != null)
        val selfMemories = if (useModelRecall) {
            modelRecall!!.selfMemories
        } else {
            selectSelfMemories(selfContext)
        }
        val selfObservations = selectSelfObservationsForDisclosure(userQuery)
        selfMemories.forEach { memory ->
            DatabaseService.updateMemoryNodeAccess(memory.memory.id, Config.memoryRecoveryAmount * 0.25, Config.memoryMaxStrength)
        }
        // Observation-only: when model recall fell back to local recall, record which nodes
        // the local stack actually injected onto the same trace row, for offline comparison
        // of model-vs-local recall divergence. No fusion/boost is applied to the local path.
        if (!useModelRecall && modelRecall != null && modelRecall.traceId != null && modelRecall.traceId > 0) {
            val fallbackDebug = buildJsonObject {
                put("local_fallback_injected_uris", buildJsonArray { recalled.forEach { add(JsonPrimitive(it.memory.uri)) } })
                put("local_fallback_injected_count", recalled.size)
                put("local_fallback_person_context_uris", buildJsonArray { personContext.forEach { add(JsonPrimitive(it.memory.uri)) } })
                put("local_fallback_self_uris", buildJsonArray { selfMemories.forEach { add(JsonPrimitive(it.memory.uri)) } })
                put("local_fallback_dream_uris", buildJsonArray { dreamTraces.forEach { add(JsonPrimitive(it.memory.uri)) } })
                put("fallback_reason", modelRecall.fallbackReason ?: "local_recall_fallback")
                put("fallback_error", modelRecall.error ?: "")
            }
            DatabaseService.updateModelRecallTraceDebugJson(modelRecall.traceId, fallbackDebug.toString())
        }
        return CompanionMemoryContext(
            recalled = recalled,
            personContext = personContext,
            deepRecall = deepRecallResult,
            dreamTraces = dreamTraces,
            selfMemories = selfMemories,
            selfObservations = selfObservations
        )
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
