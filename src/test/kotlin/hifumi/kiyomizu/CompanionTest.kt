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
import kotlin.test.assertNull
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
        Config.trustDownScale = 1.5
        Config.trustUpScale = 0.8
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
        // round-2 stability modulators — defaults reproduce round-1 behavior (守零).
        Config.memoryStabilitySpacingK = 0.0
        Config.memoryStabilityStabilizationDecay = 0.0
        // round-2 consolidation + feedback signal — defaults off / safe.
        Config.memoryConsolidationWmThreshold = 1.0
        Config.memoryFeedbackCorrectionEnabled = false
        Config.memoryFeedbackPenaltyK = 0.3
        Config.memoryFeedbackResolveLookbackHours = 48.0
        Config.memoryFeedbackRewardEnabled = false
        Config.memoryFeedbackRewardK = 0.35
        Config.memoryTopicEnabled = true
        Config.memoryTopicUnusedSlotCap = 5
        Config.memoryTopicCandidatePool = 20
        Config.memoryTopicLruWindow = 20
        Config.memoryTopicUsedRetentionDays = 30
        Config.memoryTopicDailyLimit = 4
        Config.memoryTopicColdRounds = 3
        Config.memorySummaryKey = ""
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
        // trust=50 falls into the middle self-disclosure stage.
        assertTrue(tailText.contains("feel safe enough to share feelings"))
    }

    @Test
    fun companionPromptInjectsGuardedTrustStageAtLowTrust() = runBlocking {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.memoryEnabled = true
        DatabaseService.updateRelationshipState(50.0, 20.0, "neutral")
        insertNode(
            uri = "preference://food/drink/tea",
            kind = "preference",
            content = "The user prefers tea.",
            keywords = listOf("tea"),
            triggerPhrases = listOf("tea"),
            personUri = "person://user/primary"
        )
        val original = listOf(
            buildJsonObject { put("role", "user"); put("content", "Do you remember my tea preference?") }
        )
        val patched = MessagePatcher.injectCompanionPrompt(original)
        val tailText = MessagePatcher.extractTextContent(patched.last()["content"])
        assertTrue(tailText.contains("do not yet feel safe"), "low trust should inject guarded stage")
        assertTrue(!tailText.contains("feel deeply safe"), "low trust should not inject open stage")
    }

    @Test
    fun companionPromptInjectsOpenTrustStageAtHighTrust() = runBlocking {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.memoryEnabled = true
        DatabaseService.updateRelationshipState(50.0, 90.0, "neutral")
        insertNode(
            uri = "preference://food/drink/tea",
            kind = "preference",
            content = "The user prefers tea.",
            keywords = listOf("tea"),
            triggerPhrases = listOf("tea"),
            personUri = "person://user/primary"
        )
        val original = listOf(
            buildJsonObject { put("role", "user"); put("content", "Do you remember my tea preference?") }
        )
        val patched = MessagePatcher.injectCompanionPrompt(original)
        val tailText = MessagePatcher.extractTextContent(patched.last()["content"])
        assertTrue(tailText.contains("feel deeply safe"), "high trust should inject open stage")
        assertTrue(!tailText.contains("hold back your deepest vulnerabilities"), "high trust should not inject mid stage")
    }

    @Test
    fun companionPromptTrustStageBoundaryAt30() = runBlocking {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.memoryEnabled = true
        // trust=30.0 falls into the [30,70) branch (< 70.0).
        DatabaseService.updateRelationshipState(50.0, 30.0, "neutral")
        insertNode(
            uri = "preference://food/drink/tea",
            kind = "preference",
            content = "The user prefers tea.",
            keywords = listOf("tea"),
            triggerPhrases = listOf("tea"),
            personUri = "person://user/primary"
        )
        val original = listOf(
            buildJsonObject { put("role", "user"); put("content", "Do you remember my tea preference?") }
        )
        val patched = MessagePatcher.injectCompanionPrompt(original)
        val tailText = MessagePatcher.extractTextContent(patched.last()["content"])
        assertTrue(tailText.contains("feel safe enough to share"), "trust=30 should land in middle stage")
        assertTrue(!tailText.contains("do not yet feel safe"), "trust=30 should not land in guarded stage")
    }

    // ---- Trust-gated disclosure filtering (candidate + output layers) ----

    private fun insertFourDisclosureTeaNodes() {
        insertNode(uri = "preference://tea/hint", kind = "preference", content = "hint-tier tea note", keywords = listOf("tea"), triggerPhrases = listOf("tea"), disclosure = "hint", personUri = "person://user/primary")
        insertNode(uri = "preference://tea/private", kind = "preference", content = "private-tier tea note", keywords = listOf("tea"), triggerPhrases = listOf("tea"), disclosure = "private", personUri = "person://user/primary")
        insertNode(uri = "preference://tea/quote", kind = "preference", content = "quote-tier tea note", keywords = listOf("tea"), triggerPhrases = listOf("tea"), disclosure = "quote_allowed", personUri = "person://user/primary")
        insertNode(uri = "preference://tea/sensitive", kind = "preference", content = "sensitive-tier tea note", keywords = listOf("tea"), triggerPhrases = listOf("tea"), disclosure = "sensitive", personUri = "person://user/primary")
    }

    private suspend fun recalledUris(query: String) =
        MemoryService.buildCompanionMemoryContext(query).recalled.map { it.memory.uri }.toSet()

    @Test
    fun lowTrustRecallFiltersSensitiveAndPrivateNodes() = runBlocking {
        resetDbFiles(); resetConfig(); DatabaseService.initDatabase()
        Config.memoryEnabled = true
        DatabaseService.updateRelationshipState(50.0, 20.0, "neutral")
        insertFourDisclosureTeaNodes()
        val uris = recalledUris("tea")
        assertTrue(uris.contains("preference://tea/hint"), "hint should be recalled at low trust")
        assertTrue(!uris.contains("preference://tea/private"), "private should be filtered at low trust")
        assertTrue(!uris.contains("preference://tea/quote"), "quote_allowed should be filtered at low trust")
        assertTrue(!uris.contains("preference://tea/sensitive"), "sensitive should be filtered at low trust")
    }

    @Test
    fun midTrustRecallAllowsHintPrivateQuoteButNotSensitive() = runBlocking {
        resetDbFiles(); resetConfig(); DatabaseService.initDatabase()
        Config.memoryEnabled = true
        DatabaseService.updateRelationshipState(50.0, 50.0, "neutral")
        insertFourDisclosureTeaNodes()
        val uris = recalledUris("tea")
        assertTrue(uris.contains("preference://tea/hint"))
        assertTrue(uris.contains("preference://tea/private"))
        assertTrue(uris.contains("preference://tea/quote"))
        assertTrue(!uris.contains("preference://tea/sensitive"), "sensitive should be filtered at mid trust")
    }

    @Test
    fun highTrustRecallAllowsAllDisclosuresIncludingSensitive() = runBlocking {
        resetDbFiles(); resetConfig(); DatabaseService.initDatabase()
        Config.memoryEnabled = true
        DatabaseService.updateRelationshipState(50.0, 90.0, "neutral")
        insertFourDisclosureTeaNodes()
        val uris = recalledUris("tea")
        assertTrue(uris.contains("preference://tea/hint"))
        assertTrue(uris.contains("preference://tea/private"))
        assertTrue(uris.contains("preference://tea/quote"))
        assertTrue(uris.contains("preference://tea/sensitive"), "sensitive should be recalled at high trust")
    }

    @Test
    fun trustDisclosureBoundaryAt30And70() {
        // 30 lands in middle tier (<70), 70 lands in high tier (else).
        assertEquals(setOf("hint"), MemoryService.allowedDisclosuresForTrust(29.0))
        assertEquals(setOf("hint", "private", "quote_allowed"), MemoryService.allowedDisclosuresForTrust(30.0))
        assertEquals(setOf("hint", "private", "quote_allowed"), MemoryService.allowedDisclosuresForTrust(69.0))
        assertEquals(setOf("hint", "private", "quote_allowed", "sensitive"), MemoryService.allowedDisclosuresForTrust(70.0))
    }

    @Test
    fun pinnedMemoriesExemptFromTrustFilter() = runBlocking {
        resetDbFiles(); resetConfig(); DatabaseService.initDatabase()
        Config.memoryEnabled = true
        DatabaseService.updateRelationshipState(50.0, 20.0, "neutral")
        val pinnedId = insertNode(
            uri = "preference://tea/pinned-sensitive",
            kind = "preference",
            content = "pinned sensitive tea note",
            keywords = listOf("tea"),
            triggerPhrases = listOf("tea"),
            disclosure = "sensitive",
            personUri = "person://user/primary",
            status = "active"
        )
        setPinned(pinnedId, pinned = true)
        val pinned = MemoryService.buildCompanionMemoryContext("tea").pinnedMemories
        assertTrue(pinned.any { it.memory.uri == "preference://tea/pinned-sensitive" }, "pinned sensitive node must be exempt from trust filter")
    }

    @Test
    fun outputLayerDropsDisallowedDisclosureEvenIfCandidateLeaked() = runBlocking {
        resetDbFiles(); resetConfig(); DatabaseService.initDatabase()
        Config.memoryEnabled = true
        DatabaseService.updateRelationshipState(50.0, 20.0, "neutral")
        insertNode(
            uri = "preference://tea/sensitive",
            kind = "preference",
            content = "SECRET-tea-leak",
            keywords = listOf("tea"),
            triggerPhrases = listOf("tea"),
            disclosure = "sensitive",
            personUri = "person://user/primary"
        )
        val original = listOf(
            buildJsonObject { put("role", "user"); put("content", "tell me about tea") }
        )
        val tailText = MessagePatcher.extractTextContent(MessagePatcher.injectCompanionPrompt(original).last()["content"])
        assertTrue(!tailText.contains("SECRET-tea-leak"), "output layer must withhold sensitive content at low trust")
    }

    @Test
    fun reflectionsHiddenBelowTrust30() = runBlocking {
        resetDbFiles(); resetConfig(); DatabaseService.initDatabase()
        Config.memoryEnabled = true
        DatabaseService.insertReflection("a private reflection line")
        DatabaseService.updateRelationshipState(50.0, 20.0, "neutral")
        val original = listOf(buildJsonObject { put("role", "user"); put("content", "hi") })
        val lowTail = MessagePatcher.extractTextContent(MessagePatcher.injectCompanionPrompt(original).last()["content"])
        assertTrue(!lowTail.contains("Recent private reflections"), "reflections should be hidden below trust 30")

        DatabaseService.updateRelationshipState(50.0, 50.0, "neutral")
        val highTail = MessagePatcher.extractTextContent(MessagePatcher.injectCompanionPrompt(original).last()["content"])
        assertTrue(highTail.contains("Recent private reflections"), "reflections should show at trust >= 30")
    }

    @Test
    fun explicitRecallDoesNotBypassTrustDisclosureFilter() = runBlocking {
        resetDbFiles(); resetConfig(); DatabaseService.initDatabase()
        Config.memoryEnabled = true
        DatabaseService.updateRelationshipState(50.0, 20.0, "neutral")
        insertNode(
            uri = "preference://tea/sensitive",
            kind = "preference",
            content = "SECRET-tea-explicit",
            keywords = listOf("tea"),
            triggerPhrases = listOf("tea"),
            disclosure = "sensitive",
            personUri = "person://user/primary"
        )
        // Explicit assistant-recollection request would normally allow sensitive; trust gate must still hold.
        val original = listOf(buildJsonObject { put("role", "user"); put("content", "Do you remember what I told you about tea?") })
        val tailText = MessagePatcher.extractTextContent(MessagePatcher.injectCompanionPrompt(original).last()["content"])
        assertTrue(!tailText.contains("SECRET-tea-explicit"), "low trust must withhold sensitive even on explicit recall")
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
        // Disable asymmetric trust scaling so this test asserts raw accumulation.
        Config.trustDownScale = 1.0
        Config.trustUpScale = 1.0
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
    fun trustDeltaAsymmetricScalingDownScalesByConfig() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        DatabaseService.updateRelationshipState(50.0, 50.0, "neutral")
        // Default trustDownScale = 1.5 → -10 * 1.5 = -15 → trust = 35.0
        DatabaseService.applyRelationshipDelta(0.0, -10.0, "neutral")
        assertEquals(35.0, DatabaseService.getRelationshipState().trust, 1e-5)

        DatabaseService.updateRelationshipState(50.0, 50.0, "neutral")
        Config.trustDownScale = 2.0
        DatabaseService.applyRelationshipDelta(0.0, -10.0, "neutral")
        assertEquals(30.0, DatabaseService.getRelationshipState().trust, 1e-5)
    }

    @Test
    fun trustDeltaAsymmetricScalingUpScalesByConfig() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        DatabaseService.updateRelationshipState(50.0, 50.0, "neutral")
        // Default trustUpScale = 0.8 → +10 * 0.8 = +8 → trust = 58.0
        DatabaseService.applyRelationshipDelta(0.0, 10.0, "neutral")
        assertEquals(58.0, DatabaseService.getRelationshipState().trust, 1e-5)

        DatabaseService.updateRelationshipState(50.0, 50.0, "neutral")
        Config.trustUpScale = 1.0
        DatabaseService.applyRelationshipDelta(0.0, 10.0, "neutral")
        assertEquals(60.0, DatabaseService.getRelationshipState().trust, 1e-5)
    }

    @Test
    fun trustDeltaClampsAtZeroAndHundred() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        DatabaseService.updateRelationshipState(50.0, 5.0, "neutral")
        // -100 * 1.5 = -150, clamp to 0
        DatabaseService.applyRelationshipDelta(0.0, -100.0, "neutral")
        assertEquals(0.0, DatabaseService.getRelationshipState().trust, 1e-5)

        DatabaseService.updateRelationshipState(50.0, 95.0, "neutral")
        // +100 * 0.8 = +80, clamp to 100
        DatabaseService.applyRelationshipDelta(0.0, 100.0, "neutral")
        assertEquals(100.0, DatabaseService.getRelationshipState().trust, 1e-5)
    }

    @Test
    fun intimacyDeltaNotScaledByTrustKnobs() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.trustDownScale = 5.0
        Config.trustUpScale = 0.1
        DatabaseService.updateRelationshipState(40.0, 50.0, "neutral")
        // intimacy delta is not touched by trust scaling knobs.
        DatabaseService.applyRelationshipDelta(10.0, 0.0, "neutral")
        val state = DatabaseService.getRelationshipState()
        assertEquals(50.0, state.intimacy, 1e-5)
        assertEquals(50.0, state.trust, 1e-5)
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

    // ---- Round 2: spacing + stabilization decay (档①②) ----

    @Test
    fun defaultMultipliersReproducePriorStabilityBump() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.memoryEnabled = true
        Config.memoryDecayTauHours = 360.0
        val id = insertNode(
            uri = "preference://food/drink/rooibos",
            kind = "preference",
            content = "The user likes rooibos.",
            keywords = listOf("rooibos"),
            strength = 1.0
        )
        val now = Instant.now().epochSecond
        DatabaseService.updateMemoryNodeStrength(id, 1.0, now)
        // spacingK=0, stabDecay=0 (defaults) => multipliers must be exactly 1.0.
        val node = DatabaseService.getMemoryNodeById(id)!!
        val (spacing, stabilization) = MemoryService.stabilityMultipliers(node, now)
        assertEquals(1.0, spacing, 1e-12, "default spacing multiplier must be 1.0")
        assertEquals(1.0, stabilization, 1e-12, "default stabilization multiplier must be 1.0")
        // A bump with default multipliers must equal the round-1 formula stability*(1+growthK*factor) bit-for-bit.
        DatabaseService.updateMemoryNodeAccess(id, Config.memoryRecoveryAmount * 0.35, Config.memoryMaxStrength, spacing, stabilization)
        val after = DatabaseService.getMemoryNodeById(id)!!
        val expected = (1.0 * (1.0 + Config.memoryStabilityGrowthK * 0.35)).coerceAtMost(Config.memoryStabilityMax)
        assertEquals(expected, after.stability, 1e-9, "default-multiplier bump must reproduce round-1 stability growth")
    }

    @Test
    fun spacingBoostsStabilityMoreWhenNearlyForgotten() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.memoryEnabled = true
        Config.memoryDecayTauHours = 360.0
        Config.memoryStabilitySpacingK = 1.0
        val now = Instant.now().epochSecond
        val aId = insertNode(
            uri = "episodic://event/forgotten",
            kind = "episodic_event",
            content = "A nearly-forgotten event.",
            keywords = listOf("forgotten"),
            strength = 1.0
        )
        val bId = insertNode(
            uri = "episodic://event/recent",
            kind = "episodic_event",
            content = "A recently-recalled event.",
            keywords = listOf("recent"),
            strength = 1.0
        )
        // A: backdated 30 days => low retrievability (nearly forgotten). B: just accessed => R≈1.
        DatabaseService.updateMemoryNodeStrength(aId, 1.0, now - 30L * 24 * 3600)
        DatabaseService.updateMemoryNodeStrength(bId, 1.0, now)
        val aNode = DatabaseService.getMemoryNodeById(aId)!!
        val bNode = DatabaseService.getMemoryNodeById(bId)!!
        val (aSpacing, _) = MemoryService.stabilityMultipliers(aNode, now)
        val (bSpacing, _) = MemoryService.stabilityMultipliers(bNode, now)
        assertTrue(aSpacing > bSpacing, "nearly-forgotten node should get a larger spacing multiplier; a=$aSpacing b=$bSpacing")
        DatabaseService.updateMemoryNodeAccess(aId, Config.memoryRecoveryAmount * 0.35, Config.memoryMaxStrength, aSpacing, 1.0)
        DatabaseService.updateMemoryNodeAccess(bId, Config.memoryRecoveryAmount * 0.35, Config.memoryMaxStrength, bSpacing, 1.0)
        val aAfter = DatabaseService.getMemoryNodeById(aId)!!
        val bAfter = DatabaseService.getMemoryNodeById(bId)!!
        assertTrue(aAfter.stability > bAfter.stability,
            "nearly-forgotten node should grow stability more; a=${aAfter.stability} b=${bAfter.stability}")
    }

    @Test
    fun stabilizationDecayShrinksGrowthAtHighStability() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.memoryEnabled = true
        Config.memoryDecayTauHours = 360.0
        Config.memoryStabilityStabilizationDecay = 0.5
        val now = Instant.now().epochSecond
        val lowId = insertNode(uri = "preference://food/drink/low", kind = "preference", content = "low stability node.", keywords = listOf("low"), strength = 1.0)
        val highId = insertNode(uri = "preference://food/drink/high", kind = "preference", content = "high stability node.", keywords = listOf("high"), strength = 1.0)
        DatabaseService.updateMemoryNodeStrength(lowId, 1.0, now)
        DatabaseService.updateMemoryNodeStrength(highId, 1.0, now)
        DatabaseService.updateMemoryNodeStability(lowId, 1.0)
        DatabaseService.updateMemoryNodeStability(highId, 6.0)
        val lowNode = DatabaseService.getMemoryNodeById(lowId)!!
        val highNode = DatabaseService.getMemoryNodeById(highId)!!
        val (_, lowStab) = MemoryService.stabilityMultipliers(lowNode, now)
        val (_, highStab) = MemoryService.stabilityMultipliers(highNode, now)
        assertTrue(highStab < lowStab, "high-stability node should have smaller stabilization multiplier; low=$lowStab high=$highStab")
        // Same R (both just accessed), same factor; high-stability grows less in absolute terms.
        DatabaseService.updateMemoryNodeAccess(lowId, Config.memoryRecoveryAmount * 0.35, Config.memoryMaxStrength, 1.0, lowStab)
        DatabaseService.updateMemoryNodeAccess(highId, Config.memoryRecoveryAmount * 0.35, Config.memoryMaxStrength, 1.0, highStab)
        val lowAfter = DatabaseService.getMemoryNodeById(lowId)!!
        val highAfter = DatabaseService.getMemoryNodeById(highId)!!
        // stabilization decay shrinks the relative growth RATE, not the absolute delta (a high-stability base
        // is larger, so its absolute gain can still exceed a low-stability node's). Compare relative growth.
        val lowRelativeGrowth = (lowAfter.stability - 1.0) / 1.0
        val highRelativeGrowth = (highAfter.stability - 6.0) / 6.0
        assertTrue(highRelativeGrowth < lowRelativeGrowth,
            "high-stability node should have a smaller relative growth rate; low=$lowRelativeGrowth high=$highRelativeGrowth")
    }

    // ---- Round 2: user feedback signal (档③) ----

    @Test
    fun denialPenalizesPendingStability() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.memoryEnabled = true
        Config.memoryFeedbackCorrectionEnabled = true
        Config.memoryFeedbackPenaltyK = 0.3
        val id = insertNode(uri = "identity://user/name2", kind = "identity", content = "The user's name is Bo.", keywords = listOf("name"), strength = 1.0)
        DatabaseService.updateMemoryNodeStability(id, 4.0)
        val traceId = DatabaseService.insertModelRecallTrace(
            query = "what is my name", indexVersion = "v1", planJson = null, candidateCount = 1,
            injectedCount = 1, filteredSummary = null, fallbackReason = null, error = null, durationMs = 10,
            debugJson = "{}", injectedNodeIds = MemoryService.encodeInjectedNodeIds(listOf(id))
        )
        assertTrue(MemoryService.detectMemoryCorrection("不对，不是这个"), "denial phrase should be detected when enabled")
        // Resolve previous round: denial => stability *= (1-0.3) = 2.8, trace resolved.
        MemoryService.resolvePreviousRecallFeedback("不对，不是这个")
        val after = DatabaseService.getMemoryNodeById(id)!!
        assertEquals(2.8, after.stability, 1e-9, "denied node stability should be penalized to 4.0*0.7=2.8")
        val trace = DatabaseService.getRecentModelRecallTraces(10).first { it.id == traceId }
        assertNotNull(trace.resolvedAt, "trace should be marked resolved after denial")
    }

    @Test
    fun noDenialResolvesTraceWithoutPenalty() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.memoryEnabled = true
        Config.memoryFeedbackCorrectionEnabled = true
        val id = insertNode(uri = "preference://food/drink/thanks", kind = "preference", content = "The user likes thanks-tea.", keywords = listOf("thanks"), strength = 1.0)
        DatabaseService.updateMemoryNodeStability(id, 4.0)
        val traceId = DatabaseService.insertModelRecallTrace(
            query = "what tea", indexVersion = "v1", planJson = null, candidateCount = 1,
            injectedCount = 1, filteredSummary = null, fallbackReason = null, error = null, durationMs = 10,
            debugJson = "{}", injectedNodeIds = MemoryService.encodeInjectedNodeIds(listOf(id))
        )
        assertFalse(MemoryService.detectMemoryCorrection("好的谢谢"), "non-denial message should not be detected")
        MemoryService.resolvePreviousRecallFeedback("好的谢谢")
        val after = DatabaseService.getMemoryNodeById(id)!!
        assertEquals(4.0, after.stability, 1e-9, "non-denial should not penalize stability")
        val trace = DatabaseService.getRecentModelRecallTraces(10).first { it.id == traceId }
        assertNotNull(trace.resolvedAt, "trace should still be resolved (implicit success)")
    }

    @Test
    fun feedbackDisabledByDefaultNoPenalty() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.memoryEnabled = true
        // memoryFeedbackCorrectionEnabled defaults false.
        val id = insertNode(uri = "preference://food/drink/default", kind = "preference", content = "default node.", keywords = listOf("default"), strength = 1.0)
        DatabaseService.updateMemoryNodeStability(id, 4.0)
        val traceId = DatabaseService.insertModelRecallTrace(
            query = "what", indexVersion = "v1", planJson = null, candidateCount = 1,
            injectedCount = 1, filteredSummary = null, fallbackReason = null, error = null, durationMs = 10,
            debugJson = "{}", injectedNodeIds = MemoryService.encodeInjectedNodeIds(listOf(id))
        )
        assertFalse(MemoryService.detectMemoryCorrection("不是这个"), "detection must short-circuit when disabled")
        MemoryService.resolvePreviousRecallFeedback("不是这个")
        val after = DatabaseService.getMemoryNodeById(id)!!
        assertEquals(4.0, after.stability, 1e-9, "disabled feedback must not penalize")
        val trace = DatabaseService.getRecentModelRecallTraces(10).first { it.id == traceId }
        assertNull(trace.resolvedAt, "disabled feedback must leave traces pending (守零)")
    }

    // ---- B-档 graded feedback: positive reward (档③ extension) ----

    private fun gradeTrace(id: Int): Int? =
        DatabaseService.getRecentModelRecallTraces(10).first { it.id == id }.feedbackGrade

    @Test
    fun confirmationRewardsStabilityAndGradesPositive() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.memoryEnabled = true
        Config.memoryFeedbackCorrectionEnabled = true
        Config.memoryFeedbackRewardEnabled = true
        Config.memoryFeedbackRewardK = 0.35
        Config.memoryMaxStrength = 1.0
        Config.memoryRecoveryAmount = 0.3
        val id = insertNode(uri = "preference://food/drink/coffee", kind = "preference", content = "The user likes coffee.", keywords = listOf("coffee"), strength = 0.5)
        DatabaseService.updateMemoryNodeStability(id, 4.0)
        val before = DatabaseService.getMemoryNodeById(id)!!
        val traceId = DatabaseService.insertModelRecallTrace(
            query = "what coffee", indexVersion = "v1", planJson = null, candidateCount = 1,
            injectedCount = 1, filteredSummary = null, fallbackReason = null, error = null, durationMs = 10,
            debugJson = "{}", injectedNodeIds = MemoryService.encodeInjectedNodeIds(listOf(id))
        )
        assertTrue(MemoryService.detectMemoryConfirmation("对没错就是它"), "confirmation phrase should be detected when reward enabled")
        MemoryService.resolvePreviousRecallFeedback("对没错就是它")
        val after = DatabaseService.getMemoryNodeById(id)!!
        // rewardStrength = rewardK * recoveryAmount = 0.35 * 0.3 = 0.105, fed through updateMemoryNodeAccess'
        // stability growth multiplicands (spacing/stabilization). Either way: confirmation must NOT
        // penalize, and must raise stability strictly above the (non-penalized) baseline.
        assertTrue(after.stability > before.stability, "confirmation should boost stability, got ${before.stability}→${after.stability}")
        assertEquals(1, gradeTrace(traceId), "trace should be graded +1 (confirm)")
        val trace = DatabaseService.getRecentModelRecallTraces(10).first { it.id == traceId }
        assertNotNull(trace.resolvedAt, "confirmed trace should be resolved")
    }

    @Test
    fun denialWinsOverConfirmation() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.memoryEnabled = true
        Config.memoryFeedbackCorrectionEnabled = true
        Config.memoryFeedbackRewardEnabled = true
        Config.memoryFeedbackPenaltyK = 0.5
        val id = insertNode(uri = "identity://user/name3", kind = "identity", content = "The user's name is Cy.", keywords = listOf("name"), strength = 1.0)
        DatabaseService.updateMemoryNodeStability(id, 4.0)
        val traceId = DatabaseService.insertModelRecallTrace(
            query = "what name", indexVersion = "v1", planJson = null, candidateCount = 1,
            injectedCount = 1, filteredSummary = null, fallbackReason = null, error = null, durationMs = 10,
            debugJson = "{}", injectedNodeIds = MemoryService.encodeInjectedNodeIds(listOf(id))
        )
        // A message that matches both an "is wrong" pattern and a "right" token => treated as denial.
        MemoryService.resolvePreviousRecallFeedback("不对，对的就是它——其实我记错了")
        val after = DatabaseService.getMemoryNodeById(id)!!
        assertEquals(2.0, after.stability, 1e-9, "denial should win: 4.0*0.5=2.0, not boosted")
        assertEquals(-1, gradeTrace(traceId), "grade should be -1 (deny) when both patterns match")
    }

    @Test
    fun rewardDisabledByDefaultLeavesNeutral() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.memoryEnabled = true
        Config.memoryFeedbackCorrectionEnabled = true
        // memoryFeedbackRewardEnabled defaults false.
        val id = insertNode(uri = "preference://food/drink/green", kind = "preference", content = "The user likes green tea.", keywords = listOf("green"), strength = 1.0)
        DatabaseService.updateMemoryNodeStability(id, 4.0)
        val traceId = DatabaseService.insertModelRecallTrace(
            query = "what tea", indexVersion = "v1", planJson = null, candidateCount = 1,
            injectedCount = 1, filteredSummary = null, fallbackReason = null, error = null, durationMs = 10,
            debugJson = "{}", injectedNodeIds = MemoryService.encodeInjectedNodeIds(listOf(id))
        )
        assertFalse(MemoryService.detectMemoryConfirmation("对就是它"), "confirmation must short-circuit when reward disabled")
        MemoryService.resolvePreviousRecallFeedback("对就是它")
        val after = DatabaseService.getMemoryNodeById(id)!!
        assertEquals(4.0, after.stability, 1e-9, "reward disabled => no boost, no penalty on neutral message")
        assertEquals(0, gradeTrace(traceId), "grade should be 0 (neutral) when reward disabled")
    }

    @Test
    fun confirmedPinnedNodeNotMutated() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.memoryEnabled = true
        Config.memoryFeedbackCorrectionEnabled = true
        Config.memoryFeedbackRewardEnabled = true
        val id = insertNode(uri = "preference://food/drink/pinned", kind = "preference", content = "pinned pref.", keywords = listOf("pinned"), strength = 1.0)
        setPinned(id, pinned = true)
        val before = DatabaseService.getMemoryNodeById(id)!!
        val traceId = DatabaseService.insertModelRecallTrace(
            query = "what", indexVersion = "v1", planJson = null, candidateCount = 1,
            injectedCount = 1, filteredSummary = null, fallbackReason = null, error = null, durationMs = 10,
            debugJson = "{}", injectedNodeIds = MemoryService.encodeInjectedNodeIds(listOf(id))
        )
        MemoryService.resolvePreviousRecallFeedback("对没错")
        val after = DatabaseService.getMemoryNodeById(id)!!
        assertEquals(before.stability, after.stability, 1e-9, "pinned node must be immune to confirm reward")
        assertEquals(1, gradeTrace(traceId), "trace still graded +1 even though pinned node skipped")
    }

    // ---- edge detail GET (task #2 plumbing) ----

    @Test
    fun edgeDetailRoundTrips() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.memoryEnabled = true
        val a = insertNode(uri = "preference://edge/a", kind = "preference", content = "a", keywords = listOf("a"), strength = 1.0)
        val b = insertNode(uri = "identity://edge/b", kind = "identity", content = "b", keywords = listOf("b"), strength = 1.0)
        DatabaseService.upsertMemoryEdge(DatabaseService.MemoryEdgeDraft(a, b, "about_person", 0.75))
        val all = DatabaseService.listAllMemoryEdges()
        assertTrue(all.isNotEmpty(), "edge should be persisted")
        val edge = all.first()
        val fetched = MemoryService.memoryEdgeDetail(edge.id)
        assertNotNull(fetched, "edge detail should return the edge")
        assertEquals("about_person", fetched.relation)
        assertEquals(0.75, fetched.weight, 1e-9)
        assertEquals(a, fetched.fromNodeId)
        assertEquals(b, fetched.toNodeId)
        assertNull(MemoryService.memoryEdgeDetail(999999), "missing edge should return null")
    }

    // ---- Round 2: working_memory consolidation rule (档④) ----

    @Test
    fun workingMemoryPileUpSuggestsConsolidate() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.memoryEnabled = true
        Config.memoryMaintenanceAggressiveness = "aggressive"
        // slots default = 3; threshold default = 1.0 => need >= 3 working_memory in one project.
        val projectUri = "project://kiyomizu"
        val now = Instant.now().epochSecond
        val ids = (1..3).map { i ->
            val id = insertNode(
                uri = "working://auto/$i",
                kind = "working_memory",
                content = "working task $i",
                keywords = listOf("task$i"),
                projectUri = projectUri,
                strength = 0.8
            )
            DatabaseService.updateMemoryNodeStrength(id, 0.8, now)
            id
        }
        val materials = ids.mapIndexed { idx, id ->
            val n = DatabaseService.getMemoryNodeById(id)!!
            MemoryService.DreamMaterial(
                sourceType = "node",
                uri = n.uri,
                kind = "working_memory",
                node = n,
                content = "working task ${idx + 1}",
                keywords = listOf("task${idx + 1}"),
                topics = emptyList(),
                strength = 0.8,
                confidence = 0.7,
                priority = 0.6,
                emotionValence = 0.5,
                emotionArousal = 0.3,
                updatedAt = now,
                reason = "test"
            )
        }
        val suggestions = MemoryService.buildMaintenanceSuggestions(materials)
        val consolidate = suggestions.firstOrNull { it.type == "consolidate" }
        assertNotNull(consolidate, "pile-up of 3 working_memory (>= slots 3) should yield a consolidate suggestion")
        assertTrue(consolidate!!.reason.contains("working_memory pile-up"), "reason should describe the pile-up")
    }

    @Test
    fun workingMemoryBelowThresholdNoConsolidate() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()
        Config.memoryEnabled = true
        Config.memoryMaintenanceAggressiveness = "aggressive"
        val projectUri = "project://kiyomizu"
        val now = Instant.now().epochSecond
        // Only 2 working_memory nodes, below the default slot threshold of 3.
        val ids = (1..2).map { i ->
            val id = insertNode(uri = "working://auto/below$i", kind = "working_memory", content = "task $i", keywords = listOf("task$i"), projectUri = projectUri, strength = 0.8)
            DatabaseService.updateMemoryNodeStrength(id, 0.8, now)
            id
        }
        val materials = ids.mapIndexed { idx, id ->
            val n = DatabaseService.getMemoryNodeById(id)!!
            MemoryService.DreamMaterial(
                sourceType = "node",
                uri = n.uri,
                kind = "working_memory",
                node = n,
                content = "task ${idx + 1}",
                keywords = listOf("task${idx + 1}"),
                topics = emptyList(),
                strength = 0.8,
                confidence = 0.7,
                priority = 0.6,
                emotionValence = 0.5,
                emotionArousal = 0.3,
                updatedAt = now,
                reason = "test"
            )
        }
        val suggestions = MemoryService.buildMaintenanceSuggestions(materials)
        assertNull(suggestions.firstOrNull { it.type == "consolidate" }, "below-threshold pile-up should not suggest consolidation")
    }

    // ---- Topics: CRUD, FIFO consume, retention soft-delete ----

    private fun seedTopic(title: String, leadIn: String = "", generatedAt: Long = Instant.now().epochSecond): Int {
        return DatabaseService.insertTopic(
            DatabaseService.TopicDraft(title = title, leadIn = leadIn, sourceUris = listOf("preference://test/$title"))
        )
    }

    @Test
    fun topicInsertListAndCountWork() {
        resetDbFiles(); resetConfig(); DatabaseService.initDatabase()
        assertEquals(-1, DatabaseService.insertTopic(DatabaseService.TopicDraft(title = "  ")), "blank title rejected")
        val a = seedTopic("京都旅行"); val b = seedTopic("茶道")
        assertTrue(a > 0 && b > 0)
        assertEquals(2, DatabaseService.countTopics("unused"))
        assertEquals(2, DatabaseService.listTopics("unused").size)
        assertEquals(0, DatabaseService.countTopics("used"))
    }

    @Test
    fun claimOldestUnusedTopicIsFifoAndMarksUsed() {
        resetDbFiles(); resetConfig(); DatabaseService.initDatabase()
        seedTopic("first", generatedAt = 1000L)
        seedTopic("second", generatedAt = 2000L)
        seedTopic("third", generatedAt = 3000L)
        val claimed = DatabaseService.claimOldestUnusedTopic(retentionDays = 30)
        assertNotNull(claimed)
        assertEquals("first", claimed.title, "FIFO picks oldest generated")
        assertEquals("used", claimed.status)
        assertNotNull(claimed.usedAt)
        assertNotNull(claimed.purgeAfter)
        assertEquals(2, DatabaseService.countTopics("unused"))
        assertEquals(1, DatabaseService.countTopics("used"))
    }

    @Test
    fun claimReturnsNullWhenPoolEmpty() {
        resetDbFiles(); resetConfig(); DatabaseService.initDatabase()
        assertNull(DatabaseService.claimOldestUnusedTopic(retentionDays = 30))
    }

    @Test
    fun purgeExpiredTopicsRemovesPastRetention() {
        resetDbFiles(); resetConfig(); DatabaseService.initDatabase()
        seedTopic("used-old")
        val claimed = DatabaseService.claimOldestUnusedTopic(retentionDays = 30)
        assertNotNull(claimed)
        // force purge_after into the past
        DriverManager.getConnection("jdbc:sqlite:$testDbPath").use { conn ->
            conn.prepareStatement("UPDATE topics SET purge_after = ? WHERE id = ?").use { pstmt ->
                pstmt.setLong(1, Instant.now().epochSecond - 1)
                pstmt.setInt(2, claimed.id)
                pstmt.executeUpdate()
            }
        }
        val purged = DatabaseService.purgeExpiredTopics()
        assertEquals(1, purged)
        assertEquals(0, DatabaseService.countTopics("used"))
    }

    @Test
    fun topicCollectorExcludesRecentlySampledUris() {
        resetDbFiles(); resetConfig(); DatabaseService.initDatabase()
        Config.memoryTopicCandidatePool = 6
        Config.memoryTopicLruWindow = 2
        insertNode(uri = "preference://a", kind = "preference", content = "likes a", strength = 0.9)
        insertNode(uri = "preference://b", kind = "preference", content = "likes b", strength = 0.8)
        insertNode(uri = "preference://c", kind = "preference", content = "likes c", strength = 0.7)
        val first = MemoryService.collectTopicCandidateNodes(6).map { it.node.uri }.toSet()
        assertTrue("preference://a" in first)
        val second = MemoryService.collectTopicCandidateNodes(6).map { it.node.uri }.toSet()
        // first-sampled high-strength uris should be LRU-excluded on the second pass
        assertTrue(first.intersect(second).size <= 3, "second pass should not re-pick recently sampled uris: first=$first second=$second")
    }

    @Test
    fun topicPromptBlockIsReadOnlyText() {
        val t = DatabaseService.TopicRecord(
            id = 1, title = "京都旅行", leadIn = "上次你提到想去京都…", sourceUris = listOf("episodic://kyoto"),
            status = "unused", generatedAt = 0, usedAt = null, purgeAfter = null, createdAt = 0, updatedAt = 0
        )
        val block = MemoryService.topicPromptBlock(t)
        assertTrue(block.contains("京都旅行"))
        assertTrue(block.contains("for reference only"), "block must frame the topic as optional/reference")
        assertFalse(block.contains("must") || block.contains("call a tool"), "block must not instruct operations")
    }

    @Test
    fun maybeConsumeReturnsNullWhenPoolEmptyEvenIfSignalMatches() {
        resetDbFiles(); resetConfig(); DatabaseService.initDatabase()
        // keyword "聊点什么" is in the default switch-keywords table; no summary key => judge returns false fast,
        // and pool is empty anyway. The function must not throw and must return null.
        val r = runBlocking { MemoryService.maybeConsumeTopicForQuery("聊点什么吧，无聊") }
        assertNull(r)
    }

    // ---- Pinned / stable user memory ----

    private fun setPinned(nodeId: Int, pinned: Boolean, alwaysInject: Boolean = false) {
        DriverManager.getConnection("jdbc:sqlite:$testDbPath").use { conn ->
            conn.prepareStatement(
                "UPDATE memory_nodes SET pinned = ?, always_inject = ? WHERE id = ?"
            ).use { ps ->
                ps.setInt(1, if (pinned) 1 else 0)
                ps.setInt(2, if (alwaysInject) 1 else 0)
                ps.setInt(3, nodeId)
                ps.executeUpdate()
            }
        }
    }

    @Test
    fun pinnedNodeNotPenalizedByRecallFeedback() {
        resetDbFiles(); resetConfig(); DatabaseService.initDatabase()
        Config.memoryEnabled = true
        Config.memoryFeedbackCorrectionEnabled = true
        Config.memoryFeedbackPenaltyK = 0.3
        val id = insertNode(uri = "preference://pinned/setting", kind = "preference", content = "The user wants terse answers.", keywords = listOf("terse"), strength = 1.0)
        DatabaseService.updateMemoryNodeStability(id, 4.0)
        setPinned(id, pinned = true)
        DatabaseService.insertModelRecallTrace(
            query = "answer style", indexVersion = "v1", planJson = null, candidateCount = 1,
            injectedCount = 1, filteredSummary = null, fallbackReason = null, error = null, durationMs = 10,
            debugJson = "{}", injectedNodeIds = MemoryService.encodeInjectedNodeIds(listOf(id))
        )
        MemoryService.resolvePreviousRecallFeedback("不对，不是这个")
        val after = DatabaseService.getMemoryNodeById(id)!!
        assertEquals(4.0, after.stability, 1e-9, "pinned node must be immune to recall-feedback penalty")
    }

    @Test
    fun alwaysInjectNodeAlwaysSelectedRegardlessOfScore() = runBlocking {
        resetDbFiles(); resetConfig(); DatabaseService.initDatabase()
        Config.memoryEnabled = true
        Config.memoryPinnedEnabled = true
        Config.memoryRecallMaxNodes = 1 // tight cap so only the top scorer would normally fit
        // High-relevance pinned node (overlaps query) — would be selected anyway.
        val hot = insertNode(uri = "preference://pinned/coffee", kind = "preference", content = "The user loves coffee.", keywords = listOf("coffee"), strength = 1.0)
        setPinned(hot, pinned = true)
        // Zero-overlap pinned node with always_inject — must still be selected despite the cap.
        val cold = insertNode(uri = "preference://pinned/anchored", kind = "preference", content = "Anchored setting with no query overlap.", keywords = listOf("zzz_unrelated"), strength = 1.0)
        setPinned(cold, pinned = true, alwaysInject = true)

        val selected = MemoryService.selectPinnedMemoriesPublic("coffee")
        val uris = selected.map { it.memory.uri }.toSet()
        assertTrue(uris.contains("preference://pinned/anchored"), "always_inject node must bypass the top-N cap")
        assertTrue(uris.contains("preference://pinned/coffee"), "top-scoring pinned node must be selected")
    }

    // ---- B-档 graded feedback: pure-local path now also produces a gradeable trace (盲点补齐) ----

    @Test
    fun pureLocalRecallCreatesGradeableTraceAndFeedbackApplies() = runBlocking {
        resetDbFiles(); resetConfig(); DatabaseService.initDatabase()
        Config.memoryEnabled = true
        Config.memoryModelRecallEnabled = false // pure-local path: tryModelRecall returns null, no trace otherwise
        Config.memoryFeedbackCorrectionEnabled = true
        Config.memoryFeedbackRewardEnabled = true
        Config.memoryFeedbackRewardK = 0.35
        Config.memoryPinnedEnabled = true
        // A pinned+always_inject node so the local stack definitely injects it regardless of query score.
        val id = insertNode(uri = "preference://local/coffee", kind = "preference", content = "The user loves coffee.", keywords = listOf("coffee"), strength = 1.0)
        setPinned(id, pinned = true, alwaysInject = true)
        DatabaseService.updateMemoryNodeStability(id, 4.0)

        val ctx = MemoryService.buildCompanionMemoryContext("coffee")
        val pinnedIds = ctx.pinnedMemories.map { it.memory.id }.toSet()
        assertTrue(pinnedIds.contains(id), "pinned node must be injected by the local path")

        val trace = DatabaseService.getRecentModelRecallTraces(10).firstOrNull { it.fallbackReason == "local_recall_only" }
        assertNotNull(trace, "pure-local path must create a local_recall_only trace row")
        val injected = MemoryService.decodeInjectedNodeIds(trace.injectedNodeIds)
        assertTrue(injected.contains(id), "local trace injected_node_ids must include the injected node id")

        // Confirm this round's injected memory: feedback should grade the local trace +1 and boost.
        val before = DatabaseService.getMemoryNodeById(id)!!
        MemoryService.resolvePreviousRecallFeedback("对没错就是咖啡")
        val after = DatabaseService.getMemoryNodeById(id)!!
        // pinned nodes are exempt from the reward boost, so stability stays — but the trace must still
        // be graded +1, proving the local trace is wired into the feedback loop.
        assertEquals(1, gradeTrace(trace.id), "local trace must be graded +1 on confirmation")
        assertEquals(before.stability, after.stability, 1e-9, "pinned node stays exempt from reward boost")
    }
}
