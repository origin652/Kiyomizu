package hifumi.kiyomizu

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigApiTest {

    private fun resetConfig() {
        System.clearProperty("kiyomizu.config.password")
        Config.preset = "custom"
        Config.upstream = "https://example.com"
        Config.cacheTtl = "1h"
        Config.cacheMode = "explicit"
        Config.cacheStrategy = "stable-prefix"
        Config.cacheBreakpoints = 4
        Config.memoryEnabled = false
        Config.memorySummaryUrl = "https://generativelanguage.googleapis.com"
        Config.memorySummaryKey = ""
        Config.memorySummaryModel = "gemini-2.5-flash"
        Config.memorySummaryPrompt = "prompt"
        Config.memoryEmbeddingUrl = "https://generativelanguage.googleapis.com"
        Config.memoryEmbeddingKey = ""
        Config.memoryEmbeddingModel = "text-embedding-004"
        Config.memoryDecayIntervalHours = 24
        Config.memoryDecayRate = 0.1
        Config.memoryThreshold = 0.1
        Config.memoryRecoveryAmount = 0.3
        Config.memoryMaxStrength = 1.0
        Config.memoryInitialStrength = 1.0
        Config.intimacyDecayRate = 0.5
        Config.spontaneousRecallProbability = 0.15
        Config.maxRecalledMemories = 5
    }

    private fun withIsolatedDb(block: () -> Unit) {
        val tempDir = Files.createTempDirectory("kiyomizu-config-api-test")
        val dbPath = tempDir.resolve("kiyomizu-config-api.db").toString()
        System.setProperty("kiyomizu.db.file", dbPath)
        try {
            DatabaseService.initDatabase()
            block()
        } finally {
            System.clearProperty("kiyomizu.db.file")
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun publicConfigOmitsSecretsAndAllowsExplicitClearing() {
        withIsolatedDb {
            resetConfig()
            Config.memorySummaryKey = "summary-secret"
            Config.memoryEmbeddingKey = "embedding-secret"

            val publicJson = ConfigApi.publicConfigJson()
            assertTrue("memory_summary_key" !in publicJson, "summary key must never be exposed")
            assertTrue("memory_embedding_key" !in publicJson, "embedding key must never be exposed")
            assertEquals("true", publicJson["memory_summary_key_configured"]?.jsonPrimitive?.content)
            assertEquals("true", publicJson["memory_embedding_key_configured"]?.jsonPrimitive?.content)
            assertTrue("config_password_changeable" in publicJson)

        val keepResult = ConfigApi.applyUpdate(buildJsonObject {
            put("memory_summary_key", "")
            put("memory_embedding_key", "")
        })
        assertTrue(keepResult.errors.isEmpty(), "blank secret fields should mean keep the stored keys, but got: ${keepResult.errors}")
            assertEquals("summary-secret", Config.memorySummaryKey)
            assertEquals("embedding-secret", Config.memoryEmbeddingKey)

            val clearResult = ConfigApi.applyUpdate(buildJsonObject {
                put("clear_memory_summary_key", true)
                put("clear_memory_embedding_key", true)
            })
            assertTrue(clearResult.errors.isEmpty(), "explicit clear flags should be accepted")
            assertEquals("", Config.memorySummaryKey)
            assertEquals("", Config.memoryEmbeddingKey)
            assertTrue("memory_summary_key" !in clearResult.responseBody)
            assertTrue("memory_embedding_key" !in clearResult.responseBody)
        }
    }

    @Test
    fun applyUpdateRejectsInvalidCompanionNumbers() {
        withIsolatedDb {
            resetConfig()

            val result = ConfigApi.applyUpdate(buildJsonObject {
                put("memory_decay_rate", 1.5)
                put("spontaneous_recall_probability", -0.1)
                put("max_recalled_memories", -1)
            })

            assertTrue(result.errors.contains("memory_decay_rate must be between 0.0 and 1.0"))
            assertTrue(result.errors.contains("spontaneous_recall_probability must be between 0.0 and 1.0"))
            assertTrue(result.errors.contains("max_recalled_memories must be an integer 0-50"))
            assertEquals(0.1, Config.memoryDecayRate, "invalid updates must not mutate config")
            assertEquals(0.15, Config.spontaneousRecallProbability, "invalid updates must not mutate config")
            assertEquals(5, Config.maxRecalledMemories, "invalid updates must not mutate config")
        }
    }

    @Test
    fun applyUpdatePersistsConfigAcrossReload() {
        withIsolatedDb {
            resetConfig()

            val result = ConfigApi.applyUpdate(buildJsonObject {
                put("preset", "anthropic")
                put("upstream", "")
                put("cache_ttl", "5m")
                put("memory_enabled", true)
                put("memory_summary_key", "summary-secret")
                put("memory_embedding_key", "embedding-secret")
                put("memory_decay_interval_hours", 72)
            })

            assertTrue(result.errors.isEmpty(), "valid updates should persist")

            resetConfig()
            assertEquals("custom", Config.preset, "test sanity: local reset should clear live config")

            Config.loadPersisted(DatabaseService.loadConfig())

            assertEquals("anthropic", Config.preset)
            assertEquals("https://api.anthropic.com", Config.upstream)
            assertEquals("5m", Config.cacheTtl)
            assertTrue(Config.memoryEnabled)
            assertEquals("summary-secret", Config.memorySummaryKey)
            assertEquals("embedding-secret", Config.memoryEmbeddingKey)
            assertEquals(72, Config.memoryDecayIntervalHours)
        }
    }

}
