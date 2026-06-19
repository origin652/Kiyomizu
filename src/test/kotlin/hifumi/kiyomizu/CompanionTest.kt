package hifumi.kiyomizu

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionTest {

    private fun resetDbFiles() {
        listOf(
            File("kiyomizu_companion.db"),
            File("kiyomizu_companion.db-wal"),
            File("kiyomizu_companion.db-shm")
        ).forEach {
            if (it.exists()) it.delete()
        }
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
            initialStrength = 1.0
        )

        val memories = DatabaseService.getAllMemoriesForSearch()
        assertEquals(1, memories.size)
        assertEquals("User loves Kotlin", memories[0].content)
        assertEquals(1.0, memories[0].strength)

        // 4. Memory reinforcement
        DatabaseService.updateMemoryAccessAndStrength(memories[0].id, 0.2, 1.0)
        val reinforcedMemories = DatabaseService.getAllMemoriesForSearch()
        assertEquals(1.0, reinforcedMemories[0].strength, "Should not exceed max strength")

        // 5. Memory decay
        DatabaseService.decayAllMemories(decayRate = 0.2, threshold = 0.1)
        val decayedMemories = DatabaseService.getAllMemoriesForSearch()
        assertEquals(1, decayedMemories.size)
        assertEquals(0.8, decayedMemories[0].strength, 1e-5, "Strength reduced by decayRate")

        // 6. Memory forget (below threshold)
        DatabaseService.decayAllMemories(decayRate = 0.75, threshold = 0.1)
        val forgottenMemories = DatabaseService.getAllMemoriesForSearch()
        assertEquals(0, forgottenMemories.size, "Memory below threshold should be deleted")

        // Cleanup
        resetDbFiles()
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
        assertTrue(lowText.contains("丁寧な言葉遣い"), "Low intimacy → polite-tone directive")

        DatabaseService.updateRelationshipState(85.0, 85.0, "happy")
        val patchedHigh = MessagePatcher.injectCompanionPrompt(messages)
        val highText = MessagePatcher.extractTextContent(patchedHigh.last()["content"])
        assertTrue(highText.contains("非常に親密で特別な存在"), "High intimacy → intimate-tone directive")

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
        DriverManager.getConnection("jdbc:sqlite:kiyomizu_companion.db").use { conn ->
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
}
