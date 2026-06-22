package hifumi.kiyomizu

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
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
        Config.memorySummaryKey = ""
    }

    private fun insertNode(
        uri: String,
        kind: String,
        content: String,
        keywords: List<String> = emptyList(),
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
            searchableText = listOf(content, keywords.joinToString(" "), triggerPhrases.joinToString(" "), uri).joinToString(" "),
            keywords = keywords,
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
        DatabaseService.decayGraphMemoryNodes(0.1)
        assertEquals(2, DatabaseService.getGraphNodeCount(), "below-threshold working memory should be removed")
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
}
