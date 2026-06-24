package hifumi.kiyomizu

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.math.exp
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompanionTest {
    private val tempDir = Files.createTempDirectory("kiyomizu-companion-test")
    private val testDbPath = tempDir.resolve("kiyomizu_companion.db").toString()

    init {
        System.setProperty("kiyomizu.db.file", testDbPath)
    }

    @AfterTest
    fun cleanupIsolatedDb() {
        System.clearProperty("kiyomizu.db.file")
        tempDir.toFile().deleteRecursively()
    }

    private fun resetDbFiles() {
        listOf(
            File(testDbPath),
            File("${testDbPath}-wal"),
            File("${testDbPath}-shm")
        ).forEach {
            if (it.exists()) it.delete()
        }
    }

    private fun resetConfig() {
        Config.memoryEnabled = false
        Config.memoryInitialStrength = 0.8
        Config.memoryMaxStrength = 1.0
        Config.memoryRecoveryAmount = 0.3
        Config.memoryDecayRate = 0.1
        Config.memoryThreshold = 0.1
        Config.intimacyDecayRate = 0.5
        Config.memoryDecayTauHours = 360.0
        Config.memorySalienceK = 1.0
        Config.memoryRecallMaxNodes = 6
        Config.memoryDeepRecallEnabled = true
        Config.memoryDeepRecallMaxCandidates = 40
        Config.memoryDeepRecallMaxClues = 10
        Config.memoryPersonContextMaxClues = 2
        Config.memoryBufferedIngestionEnabled = true
        Config.memoryObservationRetentionDays = 14
        Config.memoryLowConfidenceObservationRetentionDays = 3
        Config.memoryObservationMinConfidence = 0.35
        Config.memoryPromoteRepeatThreshold = 2
        Config.memoryProjectFactPromoteRepeatThreshold = 2
        Config.memoryWorkingMemorySlotsPerProject = 3
        Config.memoryObservationDailyCap = 200
        Config.memoryPromotedNodesDailyCap = 20
        Config.memoryDreamEnabled = false
        Config.memoryAutoMaintenanceEnabled = false
        Config.memoryDreamDailyLimit = 1
        Config.memoryDreamIdleHours = 12
        Config.memoryDreamBatchMaxNodes = 40
        Config.memoryDreamDryRunDailyLimit = 3
        Config.memoryLongIdlePauseDays = 7
        Config.memoryRecycleRetentionDays = 30
        Config.memoryDreamRecallMaxTraces = 2
        Config.memoryMaintenanceAggressiveness = "aggressive"
        Config.memorySelfEnabled = true
        Config.memorySelfDirectUpdateEnabled = true
        Config.memorySelfRecallMaxNodes = 8
        Config.memorySelfPromoteRepeatThreshold = 3
        Config.memoryModelRecallEnabled = false
        Config.memoryRecallModelUrl = ""
        Config.memoryRecallModelKey = ""
        Config.memoryRecallModelModel = ""
        Config.memoryModelRecallFailureThreshold = 3
        Config.memoryModelRecallCooldownSeconds = 300
        Config.memoryModelRecallTraceRetention = 200
        Config.memoryLocalRecallEnhancedEnabled = true
        Config.memoryTagGraphEnabled = true
        Config.memoryTagGraphMaxExpandedTerms = 16
        Config.memoryTimelineRecallEnabled = true
        Config.memorySummarySanitizeInternalPrompts = true
        Config.memorySummaryKey = ""
        // scoreNode weights (env-only tunables) — reset to defaults to avoid cross-test leakage.
        Config.memoryKindBiasIdentity = 0.35
        Config.memoryKindBiasPreference = 0.30
        Config.memoryKindBiasRelationship = 0.28
        Config.memoryKindBiasProjectFact = 0.12
        Config.memoryKindBiasEpisodicEvent = 0.08
        Config.memoryKindBiasWorkingMemory = 0.06
        Config.memoryKindBiasDefault = 0.10
        Config.memoryScoreOverlapWeight = 2.6
        Config.memoryScoreTriggerHitsWeight = 0.35
        Config.memoryScoreUriHitsWeight = 0.18
        Config.memoryScorePriorityWeight = 0.55
        Config.memoryScoreConfidenceWeight = 0.55
        Config.memoryScoreRecencyWeight = 0.9
        Config.memoryScoreSalienceWeight = 0.2
        Config.memorySensitivePenalty = -1.2
        Config.memoryNonDeepEpisodicPenalty = 0.45
        Config.memoryWorkingMemoryStaleDays = 14.0
        Config.memoryWorkingMemoryStalePenalty = 0.7
        // per-node stability (FSRS-inspired, env-only) — reset to defaults.
        Config.memoryStabilityGrowthK = 0.6
        Config.memoryStabilityMin = 1.0
        Config.memoryStabilityMax = 8.0
        Config.memoryStabilityRegressRate = 0.05
    }

    private fun insertNode(
        uri: String,
        kind: String,
        content: String,
        keywords: List<String> = emptyList(),
        topics: List<String> = emptyList(),
        triggerPhrases: List<String> = emptyList(),
        disclosure: String = "hint",
        priority: Double = 0.6,
        confidence: Double = 0.7,
        strength: Double = 0.8,
        personUri: String? = null,
        projectUri: String? = null,
        emotionValence: Double = 0.5,
        emotionArousal: Double = 0.3,
        status: String = "active"
    ): Int {
        val draft = DatabaseService.MemoryNodeDraft(
            uri = uri,
            kind = kind,
            content = content,
            normalizedText = content.trim().lowercase(),
            searchableText = listOf(content, keywords.joinToString(" "), topics.joinToString(" "), triggerPhrases.joinToString(" "), uri).joinToString(" "),
            keywords = keywords,
            topics = topics,
            triggerPhrases = triggerPhrases,
            disclosure = disclosure,
            priority = priority,
            confidence = confidence,
            strength = strength,
            personUri = personUri,
            projectUri = projectUri,
            emotionValence = emotionValence,
            emotionArousal = emotionArousal,
            status = status
        )
        val id = DatabaseService.insertMemoryNode(draft)
        DatabaseService.replaceMemorySearchTerms(id, listOf(
            *keywords.map { DatabaseService.MemorySearchTermDraft(it.lowercase(), "keyword") }.toTypedArray(),
            *triggerPhrases.map { DatabaseService.MemorySearchTermDraft(it.lowercase(), "trigger") }.toTypedArray()
        ))
        return id
    }

    private fun setNodeUpdatedAt(nodeId: Int, updatedAt: Long) {
        DriverManager.getConnection("jdbc:sqlite:$testDbPath").use { conn ->
            conn.prepareStatement("UPDATE memory_nodes SET updated_at = ? WHERE id = ?").use { pstmt ->
                pstmt.setLong(1, updatedAt)
                pstmt.setInt(2, nodeId)
                pstmt.executeUpdate()
            }
        }
    }

    @Test
    fun graphMemoryCrudAndSearchWork() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        val anchorId = insertNode(
            uri = "person://user/primary",
            kind = "identity",
            content = "The primary user.",
            keywords = listOf("user"),
            disclosure = "private",
            priority = 0.2,
            confidence = 0.9,
            strength = 0.6
        )
        val teaId = insertNode(
            uri = "preference://food/drink/tea",
            kind = "preference",
            content = "The user prefers tea.",
            keywords = listOf("tea", "drink"),
            triggerPhrases = listOf("tea"),
            personUri = "person://user/primary",
            emotionValence = 0.7
        )
        DatabaseService.upsertMemoryEdge(
            DatabaseService.MemoryEdgeDraft(teaId, anchorId, "about_person", 1.0)
        )

        val searched = DatabaseService.searchMemoryNodes(listOf("tea"), 10)
        assertEquals(1, searched.size)
        assertEquals("preference://food/drink/tea", searched.first().uri)

        DatabaseService.updateMemoryNodeAccess(teaId, 0.2, 1.0)
        val updated = DatabaseService.getMemoryNodeById(teaId)
        assertNotNull(updated)
        assertEquals(1, updated.accessCount)
        assertEquals(2, DatabaseService.getGraphNodeCount())
        assertEquals(1, DatabaseService.getGraphEdgeCount())
        assertTrue(DatabaseService.getSearchTermCount() >= 2)

        insertNode(
            uri = "working://auto/throwaway",
            kind = "working_memory",
            content = "Temporary note.",
            keywords = listOf("temporary"),
            strength = 0.05
        )
        // Mirror production ordering: realize lazy decay first (no-op for a freshly
        // inserted node whose last_accessed_at == now), then archive below-threshold nodes.
        MemoryService.applyLazyStrengthDecay()
        DatabaseService.decayGraphMemoryNodes(0.1)
        assertEquals(2, DatabaseService.getGraphNodeCount("active"), "below-threshold working memory should be archived, not deleted")
        assertEquals(1, DatabaseService.getGraphNodeCount("archived"), "archived working memory should sit in archived status")
        assertTrue(
            DatabaseService.listRecycleBin(20).any { it.uri == "working://auto/throwaway" },
            "archived node should leave a recycle-bin row"
        )
    }

    @Test
    fun dreamNodesStayOutOfNormalSearchButCanBeSelectedAsDreamTraces() = runBlocking {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        Config.memoryEnabled = true
        insertNode(
            uri = "dream://2026-06/archive-hall",
            kind = "reflection",
            content = "A dream trace about a quiet archive hall.",
            keywords = listOf("archive", "dream"),
            triggerPhrases = listOf("dream"),
            status = "dream",
            confidence = 0.4
        )

        assertTrue(DatabaseService.searchMemoryNodes(listOf("archive"), 10).isEmpty(), "dream nodes should not appear in normal memory search")
        val context = MemoryService.buildCompanionMemoryContext("你梦到了什么？")
        assertEquals(1, context.dreamTraces.size)
        assertEquals("dream", context.dreamTraces.first().memory.status)
    }

    @Test
    fun memoryIndexBuildsFixedSegmentsAndHidesSensitiveText() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        insertNode(
            uri = "preference://drink/tea",
            kind = "preference",
            content = "The user prefers oolong tea in the afternoon.",
            keywords = listOf("tea", "oolong"),
            topics = listOf("drink"),
            personUri = "person://user/primary",
            disclosure = "private",
            priority = 0.8,
            confidence = 0.9
        )
        insertNode(
            uri = "secret://vault/launch-code",
            kind = "private_fact",
            content = "The secret launch code is alpha seven.",
            keywords = listOf("launch", "code"),
            topics = listOf("secret"),
            disclosure = "sensitive",
            priority = 0.9,
            confidence = 0.9
        )

        val index = MemoryService.rebuildMemoryIndex()
        val segments = index["segments"]?.jsonArray?.map { it.jsonObject } ?: emptyList()

        assertEquals(
            listOf("global", "self", "people", "projects", "topics", "recent", "dreams", "term_graph", "timeline"),
            segments.map { it["segment_key"]?.jsonPrimitive?.content }
        )
        val stats = index["term_graph_stats"]?.jsonObject
        assertNotNull(stats)
        assertTrue((stats["edge_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0) >= 1)
        val previewText = segments.joinToString("\n") { it["preview"]?.jsonPrimitive?.content.orEmpty() }
        assertTrue(previewText.contains("The user prefers oolong tea"))
        assertTrue(previewText.contains("sensitive active count=1"))
        assertFalse(previewText.contains("alpha seven"), "sensitive node text should not appear in normal materialized index previews")
    }

    @Test
    fun localRecallUsesTermGraphAndCjkNgramsWithoutEmbeddings() = runBlocking {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        Config.memoryEnabled = true
        Config.memoryRecallMaxNodes = 4
        insertNode(
            uri = "preference://tea/oolong",
            kind = "preference",
            content = "The user prefers oolong tea in the afternoon.",
            keywords = listOf("oolong", "tea"),
            personUri = "person://user/primary"
        )
        insertNode(
            uri = "episode://tea/gongfu-session",
            kind = "episodic_event",
            content = "The user mentioned a gongfu tea session.",
            keywords = listOf("gongfu", "oolong"),
            personUri = "person://user/primary"
        )
        insertNode(
            uri = "preference://weather/summer",
            kind = "preference",
            content = "用户觉得夏天不算太热时适合散步。",
            keywords = emptyList(),
            personUri = "person://user/primary"
        )
        MemoryService.rebuildMemoryIndex()

        val associative = MemoryService.buildCompanionMemoryContext("gongfu")
        assertTrue(
            associative.recalled.any { it.memory.uri == "preference://tea/oolong" },
            associative.recalled.joinToString("\n") { it.memory.uri }
        )

        val cjk = MemoryService.buildCompanionMemoryContext("太热")
        assertTrue(
            cjk.recalled.any { it.memory.uri == "preference://weather/summer" },
            cjk.recalled.joinToString("\n") { it.memory.uri }
        )
    }

    @Test
    fun observationBufferCrudAndCountsWork() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        val now = Instant.now().epochSecond
        val draft = DatabaseService.MemoryObservationDraft(
            candidateUri = "project://kiyomizu/memory/buffer",
            kind = "project_fact",
            content = "Memory observations should be buffered before promotion.",
            normalizedText = "memory observations should be buffered before promotion.",
            searchableText = "Memory observations should be buffered before promotion. kiyomizu memory buffer",
            keywords = listOf("memory", "buffer"),
            topics = listOf("kiyomizu"),
            projectUri = "project://kiyomizu",
            confidence = 0.7,
            priority = 0.6,
            expiresAt = now + 86400
        )
        val id = DatabaseService.insertMemoryObservation(draft)
        DatabaseService.replaceMemoryObservationTerms(id, listOf(DatabaseService.MemorySearchTermDraft("memory", "keyword")))

        assertEquals(1, DatabaseService.getBufferedObservationCount())
        val seen = DatabaseService.updateMemoryObservationSeen(id, draft)
        assertEquals(2, seen)
        DatabaseService.updateMemoryObservationStatus(id, "promoted", matchedNodeId = 42)
        assertEquals(0, DatabaseService.getBufferedObservationCount())
        assertEquals(1, DatabaseService.getObservationCountSince("promoted", now - 10))
    }

    @Test
    fun directSelfInstructionCreatesStableSelfMemory() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.memoryEnabled = true

        val applied = MemoryService.tryApplyDirectSelfUpdate("你以后要更直接地回答技术问题")

        assertTrue(applied)
        val self = DatabaseService.listSelfMemoryNodes("active", 20).filter { it.uri.startsWith("self://") }
        assertEquals(1, self.size)
        assertTrue(self.first().content.contains("我以后要更直接地回答技术问题"))
        assertEquals("person://self/kiyomizu", self.first().personUri)
        assertEquals("user_direct", self.first().source)
    }

    @Test
    fun ordinaryRequestDoesNotCreateSelfMemory() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.memoryEnabled = true

        val applied = MemoryService.tryApplyDirectSelfUpdate("帮我写一个 Kotlin 测试")

        assertFalse(applied)
        val self = DatabaseService.listSelfMemoryNodes("active", 20).filter { it.uri.startsWith("self://") }
        assertTrue(self.isEmpty())
    }

    @Test
    fun activeSelfMemorySurvivesGraphDecay() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        insertNode(
            uri = "self://style/direct-technical-answers",
            kind = "preference",
            content = "I should answer technical questions directly.",
            keywords = listOf("direct", "technical"),
            topics = listOf("self", "self:style"),
            strength = 0.01,
            personUri = "person://self/kiyomizu"
        )

        DatabaseService.decayGraphMemoryNodes(0.1)

        val self = DatabaseService.listSelfMemoryNodes("active", 20).filter { it.uri.startsWith("self://") }
        assertEquals(1, self.size)
    }

    @Test
    fun selfRecallOnlyInjectsOnSelfRelatedQueries() = runBlocking {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.memoryEnabled = true
        Config.memoryRecallMaxNodes = 0
        insertNode(
            uri = "self://style/direct-technical-answers",
            kind = "preference",
            content = "I should answer technical questions directly.",
            keywords = listOf("direct", "technical"),
            topics = listOf("self", "self:style"),
            triggerPhrases = listOf("style"),
            priority = 0.9,
            confidence = 0.9,
            personUri = "person://self/kiyomizu"
        )

        val normal = MemoryService.buildCompanionMemoryContext("请解释一下 Kotlin data class")
        assertTrue(normal.selfMemories.isEmpty())

        val selfQuery = MemoryService.buildCompanionMemoryContext("你的回答风格是什么？")
        assertEquals(1, selfQuery.selfMemories.size)
        assertEquals("self://style/direct-technical-answers", selfQuery.selfMemories.first().memory.uri)
    }

    @Test
    fun activeWorkingMemorySlotsAreScopedByProject() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        insertNode(
            uri = "working://project-a/current-parser",
            kind = "working_memory",
            content = "Project A parser task is active.",
            keywords = listOf("parser"),
            projectUri = "project://a"
        )
        insertNode(
            uri = "working://project-b/current-ui",
            kind = "working_memory",
            content = "Project B UI task is active.",
            keywords = listOf("ui"),
            projectUri = "project://b"
        )

        val projectA = DatabaseService.getActiveWorkingMemoryNodes("project://a", limit = 10)
        val projectB = DatabaseService.getActiveWorkingMemoryNodes("project://b", limit = 10)

        assertEquals(1, projectA.size)
        assertEquals("working://project-a/current-parser", projectA.first().uri)
        assertEquals(1, projectB.size)
        assertEquals("working://project-b/current-ui", projectB.first().uri)
    }

    @Test
    fun expiredRecycleBinCompressionTombstonesAndCleansSearchTerms() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        val nodeId = insertNode(
            uri = "working://auto/noisy-temp-note",
            kind = "working_memory",
            content = "Temporary noisy note that should be compressed.",
            keywords = listOf("noisy", "temporary"),
            strength = 0.2
        )
        val node = assertNotNull(DatabaseService.getMemoryNodeById(nodeId))
        assertTrue(DatabaseService.getSearchTermCount() > 0)

        assertTrue(DatabaseService.archiveMemoryNodeToRecycle(node, "test cleanup", retentionDays = 1))
        val expiredAt = Instant.now().epochSecond - 10
        DriverManager.getConnection("jdbc:sqlite:$testDbPath").use { conn ->
            conn.prepareStatement("UPDATE memory_recycle_bin SET purge_after = ? WHERE node_id = ?").use { pstmt ->
                pstmt.setLong(1, expiredAt)
                pstmt.setInt(2, nodeId)
                pstmt.executeUpdate()
            }
        }

        assertEquals(1, DatabaseService.compressExpiredRecycleBin())
        val tombstone = DatabaseService.getMemoryNodeById(nodeId)
        assertNotNull(tombstone)
        assertEquals("tombstone", tombstone.status)
        assertEquals(emptyList(), tombstone.keywords)
        assertEquals(null, tombstone.rawEvidence)
        assertEquals(0, DatabaseService.getSearchTermCount())
        assertTrue(DatabaseService.searchMemoryNodes(listOf("noisy"), 10).isEmpty())
    }

    @Test
    fun confirmDreamTraceCopiesDreamIntoOrdinaryFactNode() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        val dreamId = insertNode(
            uri = "dream://2026-06/archive-hall",
            kind = "reflection",
            content = "A dream trace about pruning an archive hall.",
            keywords = listOf("archive", "pruning"),
            topics = listOf("memory"),
            status = "dream",
            confidence = 0.4
        )

        val result = MemoryService.confirmDreamTrace(
            dreamNodeId = dreamId,
            dreamUri = null,
            targetUri = "project://kiyomizu/memory/archive-pruning",
            kind = "project_fact",
            content = "Kiyomizu should prune noisy memory archive entries only after confirmation."
        )

        assertTrue(result["ok"]!!.jsonPrimitive.boolean)
        val factUri = result["fact_uri"]!!.jsonPrimitive.content
        val fact = DatabaseService.getMemoryNodeByUri(factUri)
        assertNotNull(fact)
        assertEquals("active", fact.status)
        assertEquals("dream_confirmed", fact.source)
        assertEquals(1, DatabaseService.getGraphEdgeCount())
    }

    @Test
    fun dreamRunItemsCanBeReadBackForDryRunDisplay() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        val runId = DatabaseService.insertDreamRun(
            DatabaseService.DreamRunDraft(mode = "dry_run", status = "running")
        )
        DatabaseService.insertDreamRunItem(
            dreamRunId = runId,
            nodeId = null,
            nodeUri = "working://auto/noisy-temp-note",
            operation = "archive",
            reason = "low strength temporary note",
            result = "dry_run",
            targetNodeId = null,
            targetUri = null
        )

        val items = DatabaseService.getDreamRunItems(runId)
        assertEquals(1, items.size)
        assertEquals("archive", items.first().operation)
        assertEquals("dry_run", items.first().result)
    }

    @Test
    fun aggressiveMaintenanceExpandsCandidatesAndSuggestions() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        val duplicateLeft = insertNode(
            uri = "memory://left",
            kind = "preference",
            content = "alpha beta gamma delta copper nickel",
            strength = 0.8
        )
        val duplicateRight = insertNode(
            uri = "memory://right",
            kind = "preference",
            content = "alpha beta gamma delta silver bronze",
            strength = 0.7
        )
        val oldWeak = insertNode(
            uri = "working://old/low-value-note",
            kind = "working_memory",
            content = "A stale low value working note.",
            priority = 0.3,
            confidence = 0.4,
            strength = 0.55
        )
        val oldAt = Instant.now().epochSecond - 8 * 86400L
        setNodeUpdatedAt(duplicateLeft, oldAt)
        setNodeUpdatedAt(duplicateRight, oldAt)
        setNodeUpdatedAt(oldWeak, oldAt)

        repeat(8) { index ->
            insertNode(
                uri = "working://recent/filler-$index",
                kind = "recent_filler_$index",
                content = "Recent filler memory $index",
                strength = 0.9
            )
        }

        Config.memoryMaintenanceAggressiveness = "standard"
        val standard = MemoryService.collectDreamMaterials(20)
        assertFalse(standard.any { it.reason == "possible duplicate cluster" && it.uri.startsWith("memory://") })
        assertFalse(standard.any { it.reason == "old low-strength memory" && it.uri == "working://old/low-value-note" })

        Config.memoryMaintenanceAggressiveness = "aggressive"
        val aggressive = MemoryService.collectDreamMaterials(20)
        assertTrue(
            aggressive.any { it.reason == "possible duplicate cluster" && it.uri.startsWith("memory://") },
            aggressive.joinToString("\n") { "${it.uri} :: ${it.reason}" }
        )
        assertTrue(aggressive.any { it.reason == "old low-strength memory" && it.uri == "working://old/low-value-note" })

        val suggestions = MemoryService.buildMaintenanceSuggestions(aggressive)
        assertTrue(suggestions.any { it.type == "merge" && it.sourceUri.startsWith("memory://") && it.targetUri?.startsWith("memory://") == true })
        assertTrue(suggestions.any { it.type == "archive" && it.sourceUri == "working://old/low-value-note" })

        val prompt = MemoryService.buildDreamPrompt(
            materials = aggressive,
            allowDreamNarrative = true,
            allowDreamNodes = true,
            allowMaintenanceOps = true,
            maintenanceSuggestions = suggestions
        )
        assertTrue(prompt.contains("Aggressive maintenance is enabled"))
        assertTrue(prompt.contains("Maintenance suggestions"))
    }

    @Test
    fun aggressiveMaintenanceRaisesOperationLimitOnlyWhenMaintenanceIsAllowed() {
        resetConfig()

        Config.memoryMaintenanceAggressiveness = "standard"
        assertEquals(20, MemoryService.dreamOperationLimit(allowMaintenanceOps = true))

        Config.memoryMaintenanceAggressiveness = "aggressive"
        assertEquals(40, MemoryService.dreamOperationLimit(allowMaintenanceOps = true))
        assertEquals(20, MemoryService.dreamOperationLimit(allowMaintenanceOps = false))
    }

    @Test
    fun companionPromptInjectionPreservesStablePrefixAndUsesDynamicTail() = runBlocking {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        Config.memoryEnabled = true
        DatabaseService.updateRelationshipState(50.0, 50.0, "neutral")
        insertNode(
            uri = "preference://food/drink/tea",
            kind = "preference",
            content = "The user prefers tea.",
            keywords = listOf("tea"),
            triggerPhrases = listOf("tea"),
            personUri = "person://user/primary"
        )

        val original = listOf(
            buildJsonObject { put("role", "system"); put("content", "You are helpful.") },
            buildJsonObject { put("role", "user"); put("content", "Stable user context.") },
            buildJsonObject { put("role", "assistant"); put("content", "Stable answer.") },
            buildJsonObject { put("role", "user"); put("content", "Do you remember my tea preference?") }
        )

        val patched = MessagePatcher.injectCompanionPrompt(original)
        assertEquals(4, patched.size)
        assertEquals("You are helpful.", MessagePatcher.extractTextContent(patched[0]["content"]))
        assertEquals("Stable user context.", MessagePatcher.extractTextContent(patched[1]["content"]))
        assertEquals("Stable answer.", MessagePatcher.extractTextContent(patched[2]["content"]))
        val tailText = MessagePatcher.extractTextContent(patched[3]["content"])
        assertTrue(tailText.contains("Kiyomizu Companion Core"))
        assertTrue(tailText.contains("The user prefers tea."))
        assertTrue(tailText.endsWith("Do you remember my tea preference?"))
    }

    @Test
    fun deepRecallTriggersOnlyOnExplicitRecallRequests() = runBlocking {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        Config.memoryEnabled = true
        insertNode(
            uri = "episode://2026-06/coffee-switch",
            kind = "episodic_event",
            content = "The user said they switched from coffee to tea.",
            keywords = listOf("coffee", "tea"),
            triggerPhrases = listOf("remember tea", "coffee"),
            personUri = "person://user/primary",
            emotionValence = 0.6,
            emotionArousal = 0.5
        )

        val ordinary = MemoryService.buildCompanionMemoryContext("I remember tea.")
        assertEquals(null, ordinary.deepRecall, "user self-reference should not trigger deep recall")

        val explicit = MemoryService.buildCompanionMemoryContext("Do you remember what I told you about tea?")
        assertNotNull(explicit.deepRecall, "explicit assistant recollection request should trigger deep recall")
        val clueCount = explicit.deepRecall!!.direct.size + explicit.deepRecall!!.weak.size
        assertTrue(clueCount > 0, "deep recall should surface at least one clue")
    }

    @Test
    fun personContextIsScopedWithoutHardSessions() = runBlocking {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        Config.memoryEnabled = true
        Config.memoryPersonContextMaxClues = 1
        insertNode(
            uri = "relationship://user/mother",
            kind = "relationship",
            content = "The user's mother is important in their recent conversations.",
            keywords = listOf("mother"),
            triggerPhrases = listOf("mother"),
            personUri = "person://user/primary/mother"
        )

        val context = MemoryService.buildCompanionMemoryContext("My mother called again today.")
        assertEquals(1, context.personContext.size)
        assertEquals("person://user/primary/mother", context.personContext.first().memory.personUri)
    }

    @Test
    fun relationshipDeltaUpdatesAccumulate() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        DatabaseService.updateRelationshipState(40.0, 50.0, "neutral")

        DatabaseService.applyRelationshipDelta(5.0, -10.0, "happy")
        DatabaseService.applyRelationshipDelta(2.5, 3.0, "caring")

        val state = DatabaseService.getRelationshipState()
        assertEquals(47.5, state.intimacy, 1e-5)
        assertEquals(43.0, state.trust, 1e-5)
        assertEquals("caring", state.mood)
    }

    @Test
    fun intimacyDecayDoesNotDoubleCountElapsedTime() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        DatabaseService.updateRelationshipState(50.0, 50.0, "neutral")

        val staleTimestamp = Instant.now().epochSecond - (2 * 86400)
        DriverManager.getConnection("jdbc:sqlite:$testDbPath").use { conn ->
            conn.prepareStatement("""
                UPDATE relationship_state
                SET last_interaction_at = ?, last_decay_at = ?
                WHERE id = 1
            """.trimIndent()).use { pstmt ->
                pstmt.setLong(1, staleTimestamp)
                pstmt.setLong(2, staleTimestamp)
                pstmt.executeUpdate()
            }
        }

        assertTrue(DatabaseService.decayIntimacy(1.0))
        val afterFirstDecay = DatabaseService.getRelationshipState()
        assertEquals(48.0, afterFirstDecay.intimacy, 1e-3)

        assertFalse(DatabaseService.decayIntimacy(1.0))
        val afterSecondDecay = DatabaseService.getRelationshipState()
        assertEquals(48.0, afterSecondDecay.intimacy, 1e-3)
        assertEquals(afterFirstDecay.lastDecayAt, afterSecondDecay.lastDecayAt)
    }

    @Test
    fun emotionalSalienceAndLazyDecayUseGraphNodes() {
        val neutral = MemoryService.emotionalSalience(0.5, 0.3)
        assertEquals(0.15, neutral, 1e-5)

        val mem = DatabaseService.MemoryNodeRecord(
            id = 1,
            uri = "working://auto/test",
            kind = "working_memory",
            content = "x",
            normalizedText = "x",
            searchableText = "x",
            keywords = emptyList(),
            aliases = emptyList(),
            entities = emptyList(),
            topics = emptyList(),
            triggerPhrases = emptyList(),
            disclosure = "private",
            priority = 0.5,
            confidence = 0.5,
            strength = 1.0,
            emotionValence = 0.5,
            emotionArousal = 0.3,
            scopeHint = null,
            personUri = null,
            projectUri = null,
            createdAt = 0,
            updatedAt = 0,
            lastAccessedAt = Instant.now().epochSecond - 3600L,
            accessCount = 0,
            status = "active",
            source = "test",
            rawEvidence = null
        )
        Config.memoryDecayTauHours = 360.0
        Config.memorySalienceK = 1.0
        val now = Instant.now().epochSecond
        val decayed = MemoryService.lazyStrength(mem, now)
        assertTrue(decayed < 1.0)
        val fresh = mem.copy(lastAccessedAt = now)
        assertEquals(1.0, MemoryService.lazyStrength(fresh, now), 1e-9)
    }

    @Test
    fun companionPromptHandlesArrayContent() = runBlocking {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        Config.memoryEnabled = true
        val original = listOf(
            buildJsonObject { put("role", "system"); put("content", "System.") },
            buildJsonObject { put("role", "user"); put("content", "Stable.") },
            buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "Do you remember tea?")
                    })
                })
            }
        )

        val patched = MessagePatcher.injectCompanionPrompt(original)
        assertEquals(3, patched.size)
        val tailContent = patched.last()["content"] as JsonArray
        assertEquals(2, tailContent.size)
        assertEquals("text", tailContent[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertTrue(
            tailContent[0].jsonObject["text"]?.jsonPrimitive?.content?.contains("Kiyomizu Companion Core") == true
        )
    }

    @Test
    fun scoreNodeWeightDefaultsReproducePriorHardcodedValues() {
        resetConfig()
        // Guards against drift: these Config defaults must equal the constants
        // previously hardcoded inside MemoryService.scoreNode.
        assertEquals(0.35, Config.memoryKindBiasIdentity, 1e-9)
        assertEquals(0.30, Config.memoryKindBiasPreference, 1e-9)
        assertEquals(0.28, Config.memoryKindBiasRelationship, 1e-9)
        assertEquals(0.12, Config.memoryKindBiasProjectFact, 1e-9)
        assertEquals(0.08, Config.memoryKindBiasEpisodicEvent, 1e-9)
        assertEquals(0.06, Config.memoryKindBiasWorkingMemory, 1e-9)
        assertEquals(0.10, Config.memoryKindBiasDefault, 1e-9)
        assertEquals(2.6, Config.memoryScoreOverlapWeight, 1e-9)
        assertEquals(0.35, Config.memoryScoreTriggerHitsWeight, 1e-9)
        assertEquals(0.18, Config.memoryScoreUriHitsWeight, 1e-9)
        assertEquals(0.55, Config.memoryScorePriorityWeight, 1e-9)
        assertEquals(0.55, Config.memoryScoreConfidenceWeight, 1e-9)
        assertEquals(0.9, Config.memoryScoreRecencyWeight, 1e-9)
        assertEquals(0.2, Config.memoryScoreSalienceWeight, 1e-9)
        assertEquals(-1.2, Config.memorySensitivePenalty, 1e-9)
        assertEquals(0.45, Config.memoryNonDeepEpisodicPenalty, 1e-9)
        assertEquals(14.0, Config.memoryWorkingMemoryStaleDays, 1e-9)
        assertEquals(0.7, Config.memoryWorkingMemoryStalePenalty, 1e-9)
    }

    @Test
    fun scoreNodeOverlapWeightZeroSuppressesOverlapMatches() = runBlocking {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        Config.memoryEnabled = true
        Config.memoryRecallMaxNodes = 6
        insertNode(
            uri = "preference://drink/tea",
            kind = "preference",
            content = "The user prefers tea.",
            keywords = listOf("tea"),
            personUri = "person://user/primary"
        )
        MemoryService.rebuildMemoryIndex()

        // The overlap weight contributes positively to a node's recall score when its terms
        // overlap the query. Compare the recalled score for the same node under a high vs a
        // near-zero overlap weight (all other weights at default): the high-weight case must
        // score strictly higher, proving Config.memoryScoreOverlapWeight actually flows into
        // the score rather than being dead config.
        Config.memoryScoreOverlapWeight = 3.0
        val high = MemoryService.buildCompanionMemoryContext("tea").recalled
            .firstOrNull { it.memory.uri == "preference://drink/tea" }
        Config.memoryScoreOverlapWeight = 0.05
        val low = MemoryService.buildCompanionMemoryContext("tea").recalled
            .firstOrNull { it.memory.uri == "preference://drink/tea" }
        Config.memoryScoreOverlapWeight = 2.6

        assertNotNull(high, "high overlap weight should surface the tea node")
        assertNotNull(low, "low overlap weight should still surface the tea node (other paths contribute)")
        // The overlap contribution is `overlap * weight`, so raising the weight from 0.05 to
        // 3.0 must strictly raise the node's recall score for the same query.
        assertTrue(
            high!!.score > low!!.score,
            "overlap weight must raise the score: high=${high.score} low=${low.score}"
        )
    }

    @Test
    fun applyLazyStrengthDecayPersistsDecayedStrength() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        Config.memoryEnabled = true
        Config.memoryDecayTauHours = 360.0
        Config.memorySalienceK = 1.0
        val nodeId = insertNode(
            uri = "preference://food/drink/coffee",
            kind = "preference",
            content = "The user likes coffee.",
            keywords = listOf("coffee"),
            strength = 1.0,
            emotionValence = 0.5,
            emotionArousal = 0.3
        )
        // Backdate the node 30 days so lazyStrength has room to decay. Use the same
        // strength-persisting helper to set both the peak strength and last_accessed_at.
        val now = Instant.now().epochSecond
        val thirtyDaysAgo = now - 30L * 24 * 3600
        DatabaseService.updateMemoryNodeStrength(nodeId, 1.0, thirtyDaysAgo)

        val before = DatabaseService.getMemoryNodeById(nodeId)
        assertNotNull(before)
        assertEquals(1.0, before.strength, 1e-9)
        val lazyBefore = MemoryService.lazyStrength(before, now)
        assertTrue(lazyBefore < 1.0, "sanity: lazyStrength should be < 1 after 30d, got $lazyBefore")

        MemoryService.applyLazyStrengthDecay(now)

        val after = DatabaseService.getMemoryNodeById(nodeId)
        assertNotNull(after)
        assertTrue(after.strength < 1.0, "persisted strength should decay below 1.0, got ${after.strength}")
        assertTrue(after.lastAccessedAt >= now - 5, "last_accessed_at should be realized to now, got ${after.lastAccessedAt}")
        // The persisted strength should now match what lazyStrength predicted (tau unchanged).
        assertEquals(lazyBefore, after.strength, 1e-9)

        // Calling again immediately must be a near-no-op (no double-counting): elapsed ~0.
        val after2 = DatabaseService.getMemoryNodeById(nodeId)
        MemoryService.applyLazyStrengthDecay(now)
        val after3 = DatabaseService.getMemoryNodeById(nodeId)
        assertNotNull(after3)
        assertEquals(after2!!.strength, after3!!.strength, 1e-9, "immediate re-decay must not double-count")
    }

    @Test
    fun localRecallFallbackRecordsInjectedUris() = runBlocking {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        Config.memoryEnabled = true
        Config.memoryRecallMaxNodes = 6
        // Force model recall down the failure path (which writes a trace): enable it with a
        // non-blank key but point the URL at a loopback address that Security rejects, so
        // callRecallModel returns null -> recall_model_empty_response -> modelRecallFailure ->
        // a trace row with fallback_reason=local_recall_fallback, and the local stack runs.
        Config.memoryModelRecallEnabled = true
        Config.memoryRecallModelUrl = "https://127.0.0.1"
        Config.memoryRecallModelKey = "test-key"
        Config.memoryRecallModelModel = "test-model"

        insertNode(
            uri = "preference://food/drink/tea",
            kind = "preference",
            content = "The user prefers tea.",
            keywords = listOf("tea"),
            personUri = "person://user/primary"
        )
        MemoryService.rebuildMemoryIndex()

        val query = "tea"
        val context = MemoryService.buildCompanionMemoryContext(query)
        assertTrue(
            context.recalled.any { it.memory.uri == "preference://food/drink/tea" },
            context.recalled.joinToString("\n") { it.memory.uri }
        )

        val traces = DatabaseService.getRecentModelRecallTraces(10)
        // Exactly one trace row for this query — guards against the double-INSERT pattern.
        assertEquals(1, traces.count { it.query == query }, "expected a single trace row per fallback event")
        val trace = traces.first { it.query == query }
        assertEquals("local_recall_fallback", trace.fallbackReason)
        assertNotNull(trace.debugJson, "fallback trace should carry debug_json")
        val debug = Json.parseToJsonElement(trace.debugJson).jsonObject
        val injected = debug["local_fallback_injected_uris"]?.jsonArray
        assertNotNull(injected, "debug_json should include local_fallback_injected_uris")
        assertTrue(
            injected!!.any { it.jsonPrimitive.content == "preference://food/drink/tea" },
            "injected URIs should include the recalled tea node: $injected"
        )
    }

    @Test
    fun defaultStabilityReproducesPriorLazyStrength() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        Config.memoryDecayTauHours = 360.0
        Config.memorySalienceK = 1.0
        // Neutral emotion so the salience term is exactly computable: arousal=0.3, valence=0.5.
        val nodeId = insertNode(
            uri = "preference://food/drink/oolong",
            kind = "preference",
            content = "The user likes oolong tea.",
            keywords = listOf("oolong"),
            strength = 1.0,
            emotionValence = 0.5,
            emotionArousal = 0.3
        )
        val now = Instant.now().epochSecond
        val thirtyDaysAgo = now - 30L * 24 * 3600
        DatabaseService.updateMemoryNodeStrength(nodeId, 1.0, thirtyDaysAgo)

        val node = DatabaseService.getMemoryNodeById(nodeId)
        assertNotNull(node)
        assertEquals(1.0, node.stability, 1e-9, "fresh node default stability must be 1.0")
        // Prior formula: strength * exp(-elapsed / (base * (1 + salienceK * salience))). With stability=1.0
        // the new formula multiplies by 1.0 and must reproduce it bit-for-bit.
        val salience = MemoryService.emotionalSalience(0.5, 0.3)
        val tauHours = 360.0 * (1.0 + 1.0 * salience)
        val elapsedHours = (now - thirtyDaysAgo) / 3600.0
        val expected = 1.0 * exp(-elapsedHours / tauHours)
        assertEquals(expected, MemoryService.lazyStrength(node, now), 1e-9,
            "default stability=1.0 must reproduce the pre-stability lazyStrength curve")
    }

    @Test
    fun stabilityGrowsOnRecallAndSlowsDecay() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        Config.memoryEnabled = true
        Config.memoryDecayTauHours = 360.0
        Config.memorySalienceK = 1.0
        val aId = insertNode(
            uri = "identity://user/name",
            kind = "identity",
            content = "The user's name is Aya.",
            keywords = listOf("name"),
            strength = 1.0
        )
        val bId = insertNode(
            uri = "preference://food/drink/water",
            kind = "preference",
            content = "The user drinks water.",
            keywords = listOf("water"),
            strength = 1.0
        )
        // Backdate both so lazyStrength has room to decay, and so they are "untouched by now".
        val now = Instant.now().epochSecond
        val thirtyDaysAgo = now - 30L * 24 * 3600
        DatabaseService.updateMemoryNodeStrength(aId, 1.0, thirtyDaysAgo)
        DatabaseService.updateMemoryNodeStrength(bId, 1.0, thirtyDaysAgo)

        // Reinforce A several times via the recall-boost hook (factor 0.35 -> normalizedFactor 0.35).
        // growthK=0.6 => each call: stability *= (1 + 0.6*0.35) ≈ 1.21; clamp to max 8.
        repeat(5) {
            DatabaseService.updateMemoryNodeAccess(aId, Config.memoryRecoveryAmount * 0.35, Config.memoryMaxStrength)
        }

        val a = DatabaseService.getMemoryNodeById(aId)!!
        val b = DatabaseService.getMemoryNodeById(bId)!!
        assertTrue(a.stability > 1.0, "recalled node stability should grow above 1.0, got ${a.stability}")
        assertEquals(1.0, b.stability, 1e-9, "untouched node stability should stay at 1.0")
        // A decays slower: at a fixed future horizon, lazyStrength(A) > lazyStrength(B) even though both
        // started at strength 1.0 — A's larger tau keeps it higher. (A's lastAccessedAt is now, so use a
        // horizon measured from now for a fair comparison via a synthetic future.)
        val future = now + 30L * 24 * 3600
        val aFuture = a.copy(lastAccessedAt = now)
        val bFuture = b.copy(lastAccessedAt = now)
        assertTrue(
            MemoryService.lazyStrength(aFuture, future) > MemoryService.lazyStrength(bFuture, future),
            "higher stability should yield slower decay (higher lazyStrength at a future horizon); " +
                "A=${MemoryService.lazyStrength(aFuture, future)}, B=${MemoryService.lazyStrength(bFuture, future)}"
        )
    }

    @Test
    fun regressStabilityPullsUntouchedTowardMin() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        Config.memoryEnabled = true
        Config.memoryDecayTauHours = 360.0
        Config.memoryStabilityRegressRate = 0.05
        val staleId = insertNode(
            uri = "episodic://event/old",
            kind = "episodic_event",
            content = "An old event.",
            keywords = listOf("old"),
            strength = 0.8
        )
        val freshId = insertNode(
            uri = "preference://food/drink/matcha",
            kind = "preference",
            content = "The user likes matcha.",
            keywords = listOf("matcha"),
            strength = 0.8
        )
        val now = Instant.now().epochSecond
        // Stale node: last accessed well before the decay cycle window.
        val cycleStart = now - (Config.memoryDecayIntervalHours * 3600L)
        val longAgo = cycleStart - 24 * 3600L
        DatabaseService.updateMemoryNodeStrength(staleId, 0.8, longAgo)
        DatabaseService.updateMemoryNodeStrength(freshId, 0.8, now)
        // Pump both stabilities high, then regress.
        DatabaseService.updateMemoryNodeStability(staleId, 4.0)
        DatabaseService.updateMemoryNodeStability(freshId, 4.0)

        MemoryService.regressStability(now)

        val staleAfter = DatabaseService.getMemoryNodeById(staleId)!!
        val freshAfter = DatabaseService.getMemoryNodeById(freshId)!!
        assertTrue(staleAfter.stability < 4.0, "untouched node stability should regress, got ${staleAfter.stability}")
        assertTrue(
            staleAfter.stability >= Config.memoryStabilityMin - 1e-9,
            "regressed stability should not drop below min, got ${staleAfter.stability}"
        )
        assertEquals(4.0, freshAfter.stability, 1e-9,
            "node accessed this cycle should keep its stability, got ${freshAfter.stability}")
    }
}
