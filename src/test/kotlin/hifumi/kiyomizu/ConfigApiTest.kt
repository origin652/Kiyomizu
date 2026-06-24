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
            Config.memoryRecallModelKey = "recall-secret"

            val publicJson = ConfigApi.publicConfigJson()
            assertTrue("memory_summary_key" !in publicJson)
            assertEquals("true", publicJson["memory_summary_key_configured"]?.jsonPrimitive?.content)
            assertTrue("memory_recall_model_key" !in publicJson)
            assertEquals("true", publicJson["memory_recall_model_key_configured"]?.jsonPrimitive?.content)
            assertEquals("false", publicJson["memory_model_recall_enabled"]?.jsonPrimitive?.content)
            assertTrue("memory_embedding_url" !in publicJson)
            assertTrue("memory_embedding_model" !in publicJson)
            assertTrue("memory_embedding_key_configured" !in publicJson)
            assertEquals("aggressive", publicJson["memory_maintenance_aggressiveness"]?.jsonPrimitive?.content)
            assertEquals("true", publicJson["memory_local_recall_enhanced_enabled"]?.jsonPrimitive?.content)
            assertEquals("true", publicJson["memory_tag_graph_enabled"]?.jsonPrimitive?.content)
            assertEquals("16", publicJson["memory_tag_graph_max_expanded_terms"]?.jsonPrimitive?.content)
            assertEquals("true", publicJson["memory_timeline_recall_enabled"]?.jsonPrimitive?.content)
            assertEquals("true", publicJson["memory_summary_sanitize_internal_prompts"]?.jsonPrimitive?.content)
            assertTrue("config_password_changeable" in publicJson)

            val keepResult = ConfigApi.applyUpdate(buildJsonObject {
                put("memory_summary_key", "")
            })
            assertTrue(keepResult.errors.isEmpty())
            assertEquals("summary-secret", Config.memorySummaryKey)

            val clearResult = ConfigApi.applyUpdate(buildJsonObject {
                put("clear_memory_summary_key", true)
                put("clear_memory_recall_model_key", true)
            })
            assertTrue(clearResult.errors.isEmpty())
            assertEquals("", Config.memorySummaryKey)
            assertEquals("", Config.memoryRecallModelKey)
            assertTrue("memory_summary_key" !in clearResult.responseBody)
            assertTrue("memory_recall_model_key" !in clearResult.responseBody)
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
                put("memory_maintenance_aggressiveness", "reckless")
                put("memory_model_recall_failure_threshold", 0)
                put("memory_model_recall_cooldown_seconds", -1)
                put("memory_model_recall_trace_retention", 0)
                put("memory_tag_graph_max_expanded_terms", 129)
            })

            assertTrue(result.errors.contains("memory_decay_rate must be between 0.0 and 1.0"))
            assertTrue(result.errors.contains("memory_recall_max_nodes must be an integer 0-20"))
            assertTrue(result.errors.contains("memory_deep_recall_max_clues must be less than or equal to memory_deep_recall_max_candidates"))
            assertTrue(result.errors.contains("memory_maintenance_aggressiveness must be one of: standard, aggressive"))
            assertTrue(result.errors.contains("memory_model_recall_failure_threshold must be an integer 1-20"))
            assertTrue(result.errors.contains("memory_model_recall_cooldown_seconds must be an integer 0-86400"))
            assertTrue(result.errors.contains("memory_model_recall_trace_retention must be an integer 1-5000"))
            assertTrue(result.errors.contains("memory_tag_graph_max_expanded_terms must be an integer 0-128"))
            assertEquals(0.1, Config.memoryDecayRate)
            assertEquals(6, Config.memoryRecallMaxNodes)
            assertEquals(40, Config.memoryDeepRecallMaxCandidates)
            assertEquals(10, Config.memoryDeepRecallMaxClues)
            assertEquals("aggressive", Config.memoryMaintenanceAggressiveness)
            assertEquals(3, Config.memoryModelRecallFailureThreshold)
            assertEquals(300, Config.memoryModelRecallCooldownSeconds)
            assertEquals(200, Config.memoryModelRecallTraceRetention)
            assertEquals(16, Config.memoryTagGraphMaxExpandedTerms)
        }
    }

    @Test
    fun applyUpdateRejectsUnsafeOutboundUrls() {
        withIsolatedDb {
            resetConfig()

            val result = ConfigApi.applyUpdate(buildJsonObject {
                put("upstream", "http://169.254.169.254")
                put("memory_summary_url", "https://localhost:11434")
                put("memory_recall_model_url", "http://127.0.0.1:11434")
            })

            assertTrue(result.errors.any { it.contains("upstream must use https") })
            assertTrue(result.errors.any { it.contains("memory_summary_url must not target localhost") })
            assertTrue(result.errors.any { it.contains("memory_recall_model_url must use https") })
            assertEquals("https://example.com", Config.upstream)
            assertEquals("https://generativelanguage.googleapis.com", Config.memorySummaryUrl)
            assertEquals("", Config.memoryRecallModelUrl)
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
                put("memory_model_recall_enabled", true)
                put("memory_recall_model_url", "https://recall.example.com")
                put("memory_recall_model_key", "recall-secret")
                put("memory_recall_model_model", "recall-mini")
                put("memory_model_recall_failure_threshold", 5)
                put("memory_model_recall_cooldown_seconds", 120)
                put("memory_model_recall_trace_retention", 321)
                put("memory_local_recall_enhanced_enabled", false)
                put("memory_tag_graph_enabled", false)
                put("memory_tag_graph_max_expanded_terms", 7)
                put("memory_timeline_recall_enabled", false)
                put("memory_summary_sanitize_internal_prompts", false)
                put("memory_recall_max_nodes", 8)
                put("memory_deep_recall_enabled", false)
                put("memory_deep_recall_max_candidates", 24)
                put("memory_deep_recall_max_clues", 6)
                put("memory_person_context_max_clues", 3)
                put("memory_buffered_ingestion_enabled", false)
                put("memory_promote_repeat_threshold", 4)
                put("memory_dream_enabled", true)
                put("memory_auto_maintenance_enabled", true)
                put("memory_dream_daily_limit", 2)
                put("memory_dream_idle_hours", 18)
                put("memory_long_idle_pause_days", 14)
                put("memory_dream_recall_max_traces", 1)
                put("memory_maintenance_aggressiveness", "standard")
                put("memory_self_enabled", false)
                put("memory_self_direct_update_enabled", false)
                put("memory_self_recall_max_nodes", 4)
                put("memory_self_promote_repeat_threshold", 5)
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
            assertTrue(Config.memoryModelRecallEnabled)
            assertEquals("https://recall.example.com", Config.memoryRecallModelUrl)
            assertEquals("recall-secret", Config.memoryRecallModelKey)
            assertEquals("recall-mini", Config.memoryRecallModelModel)
            assertEquals(5, Config.memoryModelRecallFailureThreshold)
            assertEquals(120, Config.memoryModelRecallCooldownSeconds)
            assertEquals(321, Config.memoryModelRecallTraceRetention)
            assertFalse(Config.memoryLocalRecallEnhancedEnabled)
            assertFalse(Config.memoryTagGraphEnabled)
            assertEquals(7, Config.memoryTagGraphMaxExpandedTerms)
            assertFalse(Config.memoryTimelineRecallEnabled)
            assertFalse(Config.memorySummarySanitizeInternalPrompts)
            assertEquals(8, Config.memoryRecallMaxNodes)
            assertFalse(Config.memoryDeepRecallEnabled)
            assertEquals(24, Config.memoryDeepRecallMaxCandidates)
            assertEquals(6, Config.memoryDeepRecallMaxClues)
            assertEquals(3, Config.memoryPersonContextMaxClues)
            assertFalse(Config.memoryBufferedIngestionEnabled)
            assertEquals(4, Config.memoryPromoteRepeatThreshold)
            assertTrue(Config.memoryDreamEnabled)
            assertTrue(Config.memoryAutoMaintenanceEnabled)
            assertEquals(2, Config.memoryDreamDailyLimit)
            assertEquals(18, Config.memoryDreamIdleHours)
            assertEquals(14, Config.memoryLongIdlePauseDays)
            assertEquals(1, Config.memoryDreamRecallMaxTraces)
            assertEquals("standard", Config.memoryMaintenanceAggressiveness)
            assertFalse(Config.memorySelfEnabled)
            assertFalse(Config.memorySelfDirectUpdateEnabled)
            assertEquals(4, Config.memorySelfRecallMaxNodes)
            assertEquals(5, Config.memorySelfPromoteRepeatThreshold)
        }
    }
}
