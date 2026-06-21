package hifumi.kiyomizu

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        Config.memoryDecayIntervalHours = 24
        Config.memoryDecayRate = 0.1
        Config.memoryThreshold = 0.1
        Config.memoryRecoveryAmount = 0.3
        Config.memoryMaxStrength = 1.0
        Config.memoryInitialStrength = 0.8
        Config.intimacyDecayRate = 0.5
        Config.memoryDecayTauHours = 360.0
        Config.memorySalienceK = 1.0
        Config.memoryRecallMaxNodes = 6
        Config.memoryDeepRecallEnabled = true
        Config.memoryDeepRecallMaxCandidates = 40
        Config.memoryDeepRecallMaxClues = 10
        Config.memoryPersonContextMaxClues = 2
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
    fun publicConfigOmitsSecretsAndLegacyEmbeddingSurface() {
        withIsolatedDb {
            resetConfig()
            Config.memorySummaryKey = "summary-secret"

            val publicJson = ConfigApi.publicConfigJson()
            assertTrue("memory_summary_key" !in publicJson)
            assertEquals("true", publicJson["memory_summary_key_configured"]?.jsonPrimitive?.content)
            assertTrue("memory_embedding_url" !in publicJson)
            assertTrue("memory_embedding_model" !in publicJson)
            assertTrue("memory_embedding_key_configured" !in publicJson)
            assertTrue("config_password_changeable" in publicJson)

            val keepResult = ConfigApi.applyUpdate(buildJsonObject {
                put("memory_summary_key", "")
            })
            assertTrue(keepResult.errors.isEmpty())
            assertEquals("summary-secret", Config.memorySummaryKey)

            val clearResult = ConfigApi.applyUpdate(buildJsonObject {
                put("clear_memory_summary_key", true)
            })
            assertTrue(clearResult.errors.isEmpty())
            assertEquals("", Config.memorySummaryKey)
            assertTrue("memory_summary_key" !in clearResult.responseBody)
        }
    }

    @Test
    fun applyUpdateRejectsInvalidGraphMemoryNumbers() {
        withIsolatedDb {
            resetConfig()

            val result = ConfigApi.applyUpdate(buildJsonObject {
                put("memory_decay_rate", 1.5)
                put("memory_recall_max_nodes", -1)
                put("memory_deep_recall_max_candidates", 2)
                put("memory_deep_recall_max_clues", 5)
            })

            assertTrue(result.errors.contains("memory_decay_rate must be between 0.0 and 1.0"))
            assertTrue(result.errors.contains("memory_recall_max_nodes must be an integer 0-20"))
            assertTrue(result.errors.contains("memory_deep_recall_max_clues must be less than or equal to memory_deep_recall_max_candidates"))
            assertEquals(0.1, Config.memoryDecayRate)
            assertEquals(6, Config.memoryRecallMaxNodes)
            assertEquals(40, Config.memoryDeepRecallMaxCandidates)
            assertEquals(10, Config.memoryDeepRecallMaxClues)
        }
    }

    @Test
    fun applyUpdateRejectsUnsafeOutboundUrls() {
        withIsolatedDb {
            resetConfig()

            val result = ConfigApi.applyUpdate(buildJsonObject {
                put("upstream", "http://169.254.169.254")
                put("memory_summary_url", "https://localhost:11434")
            })

            assertTrue(result.errors.any { it.contains("upstream must use https") })
            assertTrue(result.errors.any { it.contains("memory_summary_url must not target localhost") })
            assertEquals("https://example.com", Config.upstream)
            assertEquals("https://generativelanguage.googleapis.com", Config.memorySummaryUrl)
        }
    }

    @Test
    fun loadPersistedDiscardsUnsafeUrlsAndIgnoresLegacyEmbeddingFields() {
        withIsolatedDb {
            resetConfig()

            val poisoned = buildJsonObject {
                put("preset", "custom")
                put("upstream", "http://169.254.169.254")
                put("memory_summary_url", "http://localhost:11434")
                put("memory_embedding_url", "https://generativelanguage.googleapis.com")
                put("max_recalled_memories", 9)
            }.toString()

            Config.loadPersisted(poisoned)

            assertEquals("", Config.upstream)
            assertEquals("", Config.memorySummaryUrl)
            assertEquals(9, Config.memoryRecallMaxNodes, "legacy max_recalled_memories should map forward")
            assertFalse("memory_embedding_url" in ConfigApi.publicConfigJson())
        }
    }

    @Test
    fun applyUpdatePersistsGraphMemoryConfigAcrossReload() {
        withIsolatedDb {
            resetConfig()

            val result = ConfigApi.applyUpdate(buildJsonObject {
                put("preset", "anthropic")
                put("upstream", "")
                put("cache_ttl", "5m")
                put("memory_enabled", true)
                put("memory_summary_key", "summary-secret")
                put("memory_recall_max_nodes", 8)
                put("memory_deep_recall_enabled", false)
                put("memory_deep_recall_max_candidates", 24)
                put("memory_deep_recall_max_clues", 6)
                put("memory_person_context_max_clues", 3)
            })

            assertTrue(result.errors.isEmpty())

            resetConfig()
            assertEquals("custom", Config.preset)

            Config.loadPersisted(DatabaseService.loadConfig())

            assertEquals("anthropic", Config.preset)
            assertEquals("https://api.anthropic.com", Config.upstream)
            assertEquals("5m", Config.cacheTtl)
            assertTrue(Config.memoryEnabled)
            assertEquals("summary-secret", Config.memorySummaryKey)
            assertEquals(8, Config.memoryRecallMaxNodes)
            assertFalse(Config.memoryDeepRecallEnabled)
            assertEquals(24, Config.memoryDeepRecallMaxCandidates)
            assertEquals(6, Config.memoryDeepRecallMaxClues)
            assertEquals(3, Config.memoryPersonContextMaxClues)
        }
    }
}
