package hifumi.kiyomizu

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionTest {
    private val tempDir = Files.createTempDirectory("kiyomizu-companion-test")
    private val testDbPath = tempDir.resolve("kiyomizu_companion.db").toString()

    init {
        System.setProperty("kiyomizu.db.file", testDbPath)
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

    @AfterTest
    fun cleanupIsolatedDb() {
        System.clearProperty("kiyomizu.db.file")
        tempDir.toFile().deleteRecursively()
    }

    private fun resetConfig() {
        Config.memoryEnabled = false
        Config.memoryInitialStrength = 1.0
        Config.memoryMaxStrength = 1.0
        Config.memoryRecoveryAmount = 0.3
        Config.memoryDecayRate = 0.1
        Config.memoryThreshold = 0.1
        Config.intimacyDecayRate = 0.5
        Config.spontaneousRecallProbability = 0.0
        Config.maxRecalledMemories = 5
        Config.memoryDecayTauHours = 360.0
        Config.memorySalienceK = 1.0
        Config.memoryConsolidationIdleMinutes = 30
        Config.memoryAssociationSpread = 3
        Config.memorySemanticDedupThreshold = 0.80
    }

    @Test
    fun testCosineSimilarity() {
        val vec1 = floatArrayOf(1.0f, 0.0f, 0.0f)
        val vec2 = floatArrayOf(0.0f, 1.0f, 0.0f)
        val vec3 = floatArrayOf(1.0f, 1.0f, 0.0f)

        val sim12 = MemoryService.cosineSimilarity(vec1, vec2)
        val sim13 = MemoryService.cosineSimilarity(vec1, vec3)

        assertEquals(0.0, sim12, 1e-5, "Orthogonal vectors have 0 similarity")
        assertEquals(0.70710678, sim13, 1e-5, "Vector similarity check")
    }

    @Test
    fun testDatabaseOperations() {
        resetDbFiles()
        resetConfig()

        // Initialize Database
        DatabaseService.initDatabase()

        // 1. Check default relationship state
        val state = DatabaseService.getRelationshipState()
        assertEquals(10.0, state.intimacy, "Default intimacy is 10")
        assertEquals(10.0, state.trust, "Default trust is 10")
        assertEquals("neutral", state.mood, "Default mood is neutral")

        // 2. Update relationship state
        DatabaseService.updateRelationshipState(55.5, 60.0, "happy")
        val updatedState = DatabaseService.getRelationshipState()
        assertEquals(55.5, updatedState.intimacy)
        assertEquals(60.0, updatedState.trust)
        assertEquals("happy", updatedState.mood)

        // 3. Insert and decay memory
        val dummyVector = FloatArray(1536) { 0.1f }
        DatabaseService.insertMemory(
            content = "User loves Kotlin",
            vector = dummyVector,
            type = "semantic",
            emotionTag = "joy",
            initialStrength = 1.0,
            emotionValence = 0.85,
            emotionArousal = 0.6
        )

        val memories = DatabaseService.getAllMemoriesForSearch()
        assertEquals(1, memories.size)
        assertEquals("User loves Kotlin", memories[0].content)
        assertEquals(1.0, memories[0].strength)
        assertEquals(0.85, memories[0].emotionValence, 1e-5, "valence persisted")
        assertEquals(0.6, memories[0].emotionArousal, 1e-5, "arousal persisted")

        // 4. Memory reinforcement (also bumps access_count)
        DatabaseService.updateMemoryAccessAndStrength(memories[0].id, 0.2, 1.0)
        val reinforcedMemories = DatabaseService.getAllMemoriesForSearch()
        assertEquals(1.0, reinforcedMemories[0].strength, "Should not exceed max strength")
        assertEquals(1, reinforcedMemories[0].accessCount, "access_count incremented on recall")

        // 5. decayAllMemories now only garbage-collects below-threshold memories
        // (Ebbinghaus decay is lazy at read time). A strength-1.0 memory is not deleted.
        DatabaseService.decayAllMemories(decayRate = 0.2, threshold = 0.1)
        val decayedMemories = DatabaseService.getAllMemoriesForSearch()
        assertEquals(1, decayedMemories.size)
        assertEquals(1.0, decayedMemories[0].strength, 1e-5, "strength unchanged by lazy decay")

        // 6. Memory forget (a low-strength memory below threshold is deleted)
        DatabaseService.insertMemory(
            content = "Trivial ephemeral note",
            vector = dummyVector,
            type = "semantic",
            emotionTag = "neutral",
            initialStrength = 0.05
        )
        DatabaseService.decayAllMemories(decayRate = 0.75, threshold = 0.1)
        val forgottenMemories = DatabaseService.getAllMemoriesForSearch()
        assertEquals(1, forgottenMemories.size, "Only the below-threshold memory is deleted")
        assertEquals("User loves Kotlin", forgottenMemories[0].content)

        // Cleanup
        resetDbFiles()
    }

    @Test
    fun testDuplicateMemoryDetectionPrefersExistingSemanticEntry() {
        val existing = listOf(
            DatabaseService.MemoryRecord(
                id = 7,
                content = "User prefers light roast coffee.",
                vector = floatArrayOf(1.0f, 0.0f, 0.0f),
                type = "semantic",
                emotionTag = "neutral",
                strength = 0.8,
                createdAt = 0,
                lastAccessedAt = 0
            ),
            DatabaseService.MemoryRecord(
                id = 8,
                content = "User once asked if I remembered their coffee preference.",
                vector = floatArrayOf(0.0f, 1.0f, 0.0f),
                type = "episodic",
                emotionTag = "warmth",
                strength = 0.6,
                createdAt = 0,
                lastAccessedAt = 0
            )
        )

        val duplicate = MemoryService.findDuplicateMemory(
            existing = existing,
            candidateContent = "  user prefers light roast coffee.  ",
            candidateVector = floatArrayOf(1.0f, 0.0f, 0.0f),
            candidateType = "semantic"
        )
        assertTrue(duplicate != null)
        assertEquals(7, duplicate?.first?.id)
        assertEquals(1.0, duplicate!!.second, 1e-5)

        val differentType = MemoryService.findDuplicateMemory(
            existing = existing,
            candidateContent = "User once asked if I remembered their coffee preference.",
            candidateVector = floatArrayOf(0.0f, 1.0f, 0.0f),
            candidateType = "semantic"
        )
        assertEquals(null, differentType, "Same wording in a different memory type should not merge")
    }

    @Test
    fun testCompanionPromptInjection() = runBlocking {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        Config.memoryEnabled = true
        DatabaseService.updateRelationshipState(15.0, 15.0, "neutral")

        val messages = listOf(
            buildJsonObject {
                put("role", "user")
                put("content", "Hello companion")
            }
        )

        val patched = MessagePatcher.injectCompanionPrompt(messages)
        // Single-message conversation has no dynamic tail past the stable prefix, so the
        // companion context is appended as a fresh user turn instead of mutating a cached one.
        assertEquals(2, patched.size, "Companion appended to preserve the cache prefix")
        assertEquals(
            "Hello companion",
            MessagePatcher.extractTextContent(patched[0]["content"]),
            "Original message left untouched"
        )
        val lowText = MessagePatcher.extractTextContent(patched.last()["content"])
        assertTrue(
            lowText.contains("Reply in the same language as the user's latest message."),
            "Companion prompt preserves the user's latest language"
        )
        assertTrue(lowText.contains("polite, respectful"), "Low intimacy -> polite-tone directive")

        DatabaseService.updateRelationshipState(85.0, 85.0, "happy")
        val patchedHigh = MessagePatcher.injectCompanionPrompt(messages)
        val highText = MessagePatcher.extractTextContent(patchedHigh.last()["content"])
        assertTrue(highText.contains("deeply close and trusted"), "High intimacy -> intimate-tone directive")

        Config.memoryEnabled = false
        val untouched = MessagePatcher.injectCompanionPrompt(messages)
        assertEquals(messages.size, untouched.size, "No injection when disabled")

        resetDbFiles()
    }

    @Test
    fun testCompanionPreservesStablePrefix() = runBlocking {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        Config.memoryEnabled = true
        DatabaseService.updateRelationshipState(50.0, 50.0, "neutral")

        val original = listOf(
            buildJsonObject { put("role", "system"); put("content", "You are helpful.") },
            buildJsonObject { put("role", "user"); put("content", "Stable user context.") },
            buildJsonObject { put("role", "assistant"); put("content", "Stable answer.") },
            buildJsonObject { put("role", "user"); put("content", "Newest question.") }
        )

        val patched = MessagePatcher.injectCompanionPrompt(original)
        assertEquals(4, patched.size, "Companion prepends to existing tail message without resizing")
        assertEquals(
            "You are helpful.",
            MessagePatcher.extractTextContent(patched[0]["content"]),
            "System prefix is byte-identical (cache-safe)"
        )
        assertEquals(
            "Stable user context.",
            MessagePatcher.extractTextContent(patched[1]["content"]),
            "Stable user prefix is byte-identical (cache-safe)"
        )
        assertEquals(
            "Stable answer.",
            MessagePatcher.extractTextContent(patched[2]["content"]),
            "Stable assistant prefix is byte-identical (cache-safe)"
        )
        val tailText = MessagePatcher.extractTextContent(patched[3]["content"])
        assertTrue(tailText.contains("Kiyomizu Companion Core"), "Companion block injected into tail user message")
        assertTrue(tailText.endsWith("Newest question."), "Original tail content preserved at end")

        Config.memoryEnabled = false
        resetDbFiles()
    }

    @Test
    fun testCompanionHandlesArrayContent() = runBlocking {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        Config.memoryEnabled = true
        DatabaseService.updateRelationshipState(50.0, 50.0, "neutral")

        // Anthropic-style content block array
        val original = listOf(
            buildJsonObject { put("role", "system"); put("content", "System.") },
            buildJsonObject { put("role", "user"); put("content", "Stable.") },
            buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "Newest in blocks.")
                    })
                })
            }
        )

        val patched = MessagePatcher.injectCompanionPrompt(original)
        assertEquals(3, patched.size)
        val tailContent = patched.last()["content"] as JsonArray
        assertEquals(2, tailContent.size, "Companion prepended as a new text block before original blocks")
        val prefixBlock = tailContent[0].jsonObject
        assertEquals("text", prefixBlock["type"]?.jsonPrimitive?.content)
        assertTrue(
            prefixBlock["text"]?.jsonPrimitive?.content?.contains("Kiyomizu Companion Core") == true,
            "First block is the companion text"
        )
        assertEquals(
            "Newest in blocks.",
            tailContent[1].jsonObject["text"]?.jsonPrimitive?.content,
            "Original block preserved"
        )

        Config.memoryEnabled = false
        resetDbFiles()
    }

    @Test
    fun testRelationshipDeltaUpdatesAccumulate() {
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

        resetDbFiles()
    }

    @Test
    fun testIntimacyDecayDoesNotDoubleCountElapsedTime() {
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

        assertTrue(DatabaseService.decayIntimacy(1.0), "first pass should decay two days of inactivity")
        val afterFirstDecay = DatabaseService.getRelationshipState()
        assertEquals(48.0, afterFirstDecay.intimacy, 1e-5)

        assertFalse(DatabaseService.decayIntimacy(1.0), "second pass should not decay the same idle window twice")
        val afterSecondDecay = DatabaseService.getRelationshipState()
        assertEquals(48.0, afterSecondDecay.intimacy, 1e-5)
        assertEquals(afterFirstDecay.lastDecayAt, afterSecondDecay.lastDecayAt)

        resetDbFiles()
    }

    @Test
    fun testEmotionalSalience() {
        // Neutral valence (0.5) contributes only the base 0.5 regardless of arousal baseline weighting.
        val neutral = MemoryService.emotionalSalience(0.5, 0.3)
        assertEquals(0.3 * 0.5, neutral, 1e-5, "neutral valence -> arousal * 0.5")
        // High arousal + extreme valence => maximal salience.
        val intense = MemoryService.emotionalSalience(0.0, 1.0)
        assertEquals(1.0 * (0.5 + 0.5), intense, 1e-5, "extreme valence & max arousal => salience 1.0")
        // Low arousal => low salience even with extreme valence.
        val calm = MemoryService.emotionalSalience(0.0, 0.1)
        assertEquals(0.1 * 1.0, calm, 1e-5, "low arousal dampens salience")
    }

    @Test
    fun testLazyEbbinghausDecayFallsOverTime() {
        val mem = DatabaseService.MemoryRecord(
            id = 1,
            content = "x",
            vector = FloatArray(4),
            type = "semantic",
            emotionTag = "neutral",
            strength = 1.0,
            createdAt = 0,
            lastAccessedAt = Instant.now().epochSecond - 3600L, // 1 hour ago
            relatedIds = emptyList(),
            accessCount = 0,
            emotionValence = 0.5,
            emotionArousal = 0.3
        )
        Config.memoryDecayTauHours = 360.0
        Config.memorySalienceK = 1.0
        val now = Instant.now().epochSecond
        val decayed = MemoryService.lazyStrength(mem, now)
        // tau_eff = 360 * (1 + 1*0.15) = 414h; 1h elapsed => exp(-1/414) ~ 0.9976
        assertEquals(Math.exp(-1.0 / 414.0), decayed, 1e-3, "lazy decay over 1h at tau=414h")
        assertTrue(decayed < 1.0, "decayed strength is below stored strength")
        // Just-accessed memory decays negligibly.
        val fresh = mem.copy(lastAccessedAt = now)
        assertEquals(1.0, MemoryService.lazyStrength(fresh, now), 1e-9, "freshly accessed memory is full strength")
    }

    @Test
    fun testConsolidationCandidatesFilterReactivatedAndAffective() {
        resetDbFiles()
        resetConfig()
        DatabaseService.initDatabase()

        val vec = FloatArray(8) { 0.1f }
        // Reactivated + high arousal (candidate)
        val reactiveId = DatabaseService.insertMemory("late-night vent about work", vec, "episodic", "anxiety", 1.0, 0.2, 0.9)
        DatabaseService.updateMemoryAccessAndStrength(reactiveId, 0.1, 1.0) // bump access_count
        // Episodic but neutral affect + never reactivated (excluded)
        DatabaseService.insertMemory("a passing neutral comment", vec, "episodic", "neutral", 1.0, 0.5, 0.3)
        // Semantic (excluded by type)
        val semId = DatabaseService.insertMemory("user likes tea", vec, "semantic", "joy", 1.0, 0.7, 0.3)
        DatabaseService.updateMemoryAccessAndStrength(semId, 0.1, 1.0)

        val candidates = DatabaseService.getConsolidationCandidates(windowSeconds = 3600L, limit = 10)
        assertEquals(1, candidates.size, "only reactivated affective episodic memory qualifies")
        assertEquals("late-night vent about work", candidates[0].content)

        resetDbFiles()
    }

    @Test
    fun testSemanticDedupUsesLooseThreshold() {
        val existing = listOf(
            DatabaseService.MemoryRecord(
                id = 1,
                content = "User is under a lot of work pressure and vents late at night.",
                vector = floatArrayOf(1.0f, 0.0f, 0.0f),
                type = "semantic",
                emotionTag = "neutral",
                strength = 0.8,
                createdAt = 0,
                lastAccessedAt = 0,
                relatedIds = emptyList(),
                accessCount = 0,
                emotionValence = 0.5,
                emotionArousal = 0.3
            )
        )
        // Candidate rephrases; cosine ~0.7 between (1,0,0) and (0.7,0.7,0).
        val candidateVec = floatArrayOf(0.7f, 0.7f, 0.0f)
        val sim = MemoryService.cosineSimilarity(candidateVec, existing[0].vector)
        assertTrue(sim < 0.92, "below the strict 0.92 threshold")
        assertTrue(sim >= 0.7, "above the loose 0.80-ish band")

        val strict = MemoryService.findDuplicateMemory(existing, "User's job stresses them out", candidateVec, "semantic", 0.92)
        assertEquals(null, strict, "strict threshold does not merge a rephrase")
        val loose = MemoryService.findDuplicateMemory(existing, "User's job stresses them out", candidateVec, "semantic", 0.80)
        assertTrue(loose != null, "loose semantic threshold merges a near-duplicate rephrase")
    }
}
