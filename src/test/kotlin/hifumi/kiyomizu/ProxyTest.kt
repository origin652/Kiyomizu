package hifumi.kiyomizu

import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyTest {
    private val tempDir = Files.createTempDirectory("kiyomizu-proxy-test")
    private val testDbPath = tempDir.resolve("kiyomizu_companion.db").toString()

    init {
        System.setProperty("kiyomizu.db.file", testDbPath)
    }

    private fun patch(path: String, body: JsonObject): JsonObject = runBlocking { MessagePatcher.patchJsonBody(path, body) }

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
        Config.preset = "custom"
        Config.upstream = "https://example.com"
        Config.cacheTtl = "1h"
        Config.cacheMode = "explicit"
        Config.cacheStrategy = "stable-prefix"
        Config.cacheBreakpoints = 4
        Config.memoryEnabled = false
        Config.memoryRecallMaxNodes = 5
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
    }

    @Test
    fun anthropicPresetPatchesMessagesAndHeaders() {
        resetConfig()
        Config.preset = "anthropic"
        Config.upstream = "https://api.anthropic.com"

        val request = buildJsonObject {
            put("model", "claude-sonnet-4-5")
            put("stream", true)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", "You are concise.")
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Stable user context.")
                })
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "thinking")
                            put("thinking", "private scratchpad")
                        })
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "Visible answer.")
                        })
                    })
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Newest user message.")
                })
            })
        }

        val patched = patch("/v1/messages", request)
        assertTrue("provider" !in patched, "anthropic preset does not inject provider")
        assertTrue("cache_control" !in patched, "explicit mode keeps top-level cache_control absent")

        val messages = patched["messages"]?.jsonArray?.mapNotNull { it as? JsonObject }
        assertNotNull(messages)
        assertEquals(3, MessagePatcher.countExplicitCacheBlocks(messages), "stable prefix gets explicit breakpoints")
        assertEquals(listOf(0, 1, 2), MessagePatcher.findLoggedCacheBreakpointIndexes(messages))
        assertEquals(0, MessagePatcher.countStrippedThinkingBlocks(messages), "thinking blocks stripped")
        assertEquals("Newest user message.", messages.last()["content"]?.jsonPrimitive?.content, "dynamic tail remains untouched")

        assertEquals("/v1/messages", ProxyService.normalizeUpstreamPath("/v1/messages"))

        val adjustedHeaders = ProxyService.adjustHeadersForUpstream(
            ProxyService.cleanHeaders(headersOf("Authorization", "Bearer sk-anthropic-test"))
        )
        assertTrue(adjustedHeaders.none { it.first.equals("Authorization", ignoreCase = true) })
        assertEquals("sk-anthropic-test", adjustedHeaders.first { it.first == "x-api-key" }.second)
        assertEquals("2023-06-01", adjustedHeaders.first { it.first == "anthropic-version" }.second)
    }

    @Test
    fun anthropicAutomaticAddsTopLevelCacheControl() {
        resetConfig()
        Config.preset = "anthropic"
        Config.upstream = "https://api.anthropic.com"
        Config.cacheMode = "automatic"

        val request = buildJsonObject {
            put("model", "claude-sonnet-4-5")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Stable prompt.")
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "New prompt.")
                })
            })
        }

        val patched = patch("/v1/messages", request)
        val cacheControl = patched["cache_control"]?.jsonObject
        assertNotNull(cacheControl)
        assertEquals("ephemeral", cacheControl["type"]?.jsonPrimitive?.content)
        assertEquals("1h", cacheControl["ttl"]?.jsonPrimitive?.content)

        val messages = patched["messages"]?.jsonArray?.mapNotNull { it as? JsonObject }
        assertNotNull(messages)
        assertEquals(0, MessagePatcher.countExplicitCacheBlocks(messages), "automatic mode leaves block-level breakpoints alone")
    }

    @Test
    fun customPresetIsDumbPipe() {
        resetConfig()
        Config.preset = "custom"
        Config.upstream = "https://llm.example.test"

        val request = buildJsonObject {
            put("model", "gpt-4.1")
            put("provider", buildJsonObject {
                put("only", buildJsonArray { add("openai") })
            })
            put("cache_control", buildJsonObject {
                put("type", "ephemeral")
            })
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Pass this through unchanged.")
                })
            })
        }

        val patched = patch("/v1/chat/completions", request)
        assertEquals(request, patched, "custom preset leaves the request body untouched")
        assertEquals("/v1/chat/completions", ProxyService.normalizeUpstreamPath("/v1/chat/completions"))

        val adjustedHeaders = ProxyService.adjustHeadersForUpstream(
            ProxyService.cleanHeaders(headersOf("Authorization", "Token raw-custom-header"))
        )
        assertEquals(listOf("Authorization" to "Token raw-custom-header"), adjustedHeaders)
    }

    @Test
    fun anthropicPresetForwardsNonBearerAuthorizationVerbatim() {
        resetConfig()
        Config.preset = "anthropic"

        // A non-Bearer Authorization (e.g. Basic) must not be stuffed into
        // x-api-key as a literal string; pass it through unchanged instead.
        val adjusted = ProxyService.adjustHeadersForUpstream(
            ProxyService.cleanHeaders(headersOf("Authorization", "Basic dXNlcjpwYXNz"))
        )
        assertTrue(adjusted.any { it.first.equals("Authorization", ignoreCase = true) && it.second == "Basic dXNlcjpwYXNz" })
        assertTrue(adjusted.none { it.first.equals("x-api-key", ignoreCase = true) })
        assertTrue(adjusted.any { it.first.equals("anthropic-version", ignoreCase = true) })
    }

    @Test
    fun anthropicPresetPreservesExistingXApiKey() {
        resetConfig()
        Config.preset = "anthropic"

        val adjusted = ProxyService.adjustHeadersForUpstream(
            ProxyService.cleanHeaders(
                headersOf(
                    "Authorization" to listOf("Bearer should-be-overridden-by-existing"),
                    "x-api-key" to listOf("explicit-key")
                )
            )
        )
        val apiKeys = adjusted.filter { it.first.equals("x-api-key", ignoreCase = true) }
        assertEquals(1, apiKeys.size, "explicit x-api-key must not be duplicated by the Authorization rewrite")
        assertEquals("explicit-key", apiKeys.single().second)
    }

    @Test
    fun cleanHeadersStripsInternalAuthHeaders() {
        val cleaned = ProxyService.cleanHeaders(
            headersOf(
                "Authorization" to listOf("Bearer upstream-key"),
                ConfigAuth.headerName to listOf("config-secret-pass"),
                "Content-Type" to listOf("application/json")
            )
        )

        assertTrue(cleaned.any { it.first == "Authorization" && it.second == "Bearer upstream-key" })
        assertTrue(cleaned.any { it.first == "Content-Type" && it.second == "application/json" })
        assertTrue(cleaned.none { it.first.equals(ConfigAuth.headerName, ignoreCase = true) })
    }

    @Test
    fun explicitCacheOmitsTtlWhenConfiguredNone() {
        resetConfig()
        Config.preset = "anthropic"
        Config.upstream = "https://api.anthropic.com"
        Config.cacheTtl = "none"

        val request = buildJsonObject {
            put("model", "claude-sonnet-4-5")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Stable prompt.")
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "New message.")
                })
            })
        }

        val patched = patch("/v1/messages", request)
        val messages = patched["messages"]?.jsonArray?.mapNotNull { it as? JsonObject }
        assertNotNull(messages)
        val cacheControlObj = messages[0]["content"]?.jsonArray?.get(0)?.jsonObject?.get("cache_control")?.jsonObject
        assertNotNull(cacheControlObj)
        assertEquals("ephemeral", cacheControlObj["type"]?.jsonPrimitive?.content)
        assertTrue("ttl" !in cacheControlObj, "ttl key omitted when cacheTtl is none")
    }

    @Test
    fun parsesAnthropicCacheUsageDiagnostics() {
        val usage = ProxyService.extractUsageDiagnostics(
            """{"usage":{"input_tokens":120,"output_tokens":30,"cache_read_input_tokens":80,"cache_creation_input_tokens":20}}""",
            isSse = false
        )

        assertNotNull(usage)
        assertEquals(120, usage.inputTokens)
        assertEquals(30, usage.outputTokens)
        assertEquals(80, usage.cacheReadInputTokens)
        assertEquals(20, usage.cacheCreationInputTokens)
        assertNull(usage.cachedPromptTokens)
    }

    @Test
    fun parsesOpenAiCompatibleCachedPromptTokens() {
        val usage = ProxyService.extractUsageDiagnostics(
            """{"usage":{"prompt_tokens":100,"completion_tokens":12,"prompt_tokens_details":{"cached_tokens":64}}}""",
            isSse = false
        )

        assertNotNull(usage)
        assertEquals(100, usage.inputTokens)
        assertEquals(12, usage.outputTokens)
        assertEquals(64, usage.cachedPromptTokens)
        assertNull(usage.cacheReadInputTokens)
        assertNull(usage.cacheCreationInputTokens)
    }

    @Test
    fun missingUsageDiagnosticsStayUnknown() {
        assertNull(ProxyService.extractUsageDiagnostics("""{"choices":[]}""", isSse = false))
    }

    @Test
    fun nullUsageDiagnosticsAreIgnored() {
        assertNull(ProxyService.extractUsageDiagnostics("""{"usage":null}""", isSse = false))
    }

    @Test
    fun nullUsageDiagnosticsInSseAreIgnored() {
        val usage = ProxyService.extractUsageDiagnostics(
            """
            data: {"type":"message_delta","usage":null}
            data: {"usage":{"input_tokens":10,"output_tokens":4}}
            data: [DONE]
            """.trimIndent(),
            isSse = true
        )

        assertNotNull(usage)
        assertEquals(10, usage.inputTokens)
        assertEquals(4, usage.outputTokens)
    }

    @Test
    fun customChatCompletionsStillGetsCompanionInjection() {
        resetConfig()
        resetDbFiles()
        DatabaseService.initDatabase()
        DatabaseService.updateRelationshipState(50.0, 50.0, "neutral")
        Config.memoryEnabled = true

        val request = buildJsonObject {
            put("model", "openai/gpt-4.1")
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", "You are helpful.") })
                add(buildJsonObject { put("role", "user"); put("content", "Stable user context.") })
                add(buildJsonObject { put("role", "assistant"); put("content", "Stable answer.") })
                add(buildJsonObject { put("role", "user"); put("content", "Newest question.") })
            })
        }

        val patched = patch("/v1/chat/completions", request)
        val messages = patched["messages"]?.jsonArray?.mapNotNull { it as? JsonObject }
        assertNotNull(messages)
        // 3c: companion context is appended as a standalone user message after the tail,
        // so the user's real message is unchanged and the injected block is the last item.
        assertEquals(5, messages.size)
        assertTrue(MessagePatcher.extractTextContent(messages[3]["content"]).endsWith("Newest question."))
        val tailText = MessagePatcher.extractTextContent(messages.last()["content"])
        assertTrue(tailText.contains("Kiyomizu Companion Core"))

        Config.memoryEnabled = false
        resetDbFiles()
    }

    @Test
    fun responsesApiGetsCompanionInjectionIntoInput() {
        resetConfig()
        resetDbFiles()
        DatabaseService.initDatabase()
        DatabaseService.updateRelationshipState(50.0, 50.0, "neutral")
        Config.memoryEnabled = true

        val request = buildJsonObject {
            put("model", "gpt-5")
            put("input", buildJsonArray {
                add(buildJsonObject {
                    put("type", "message")
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_text")
                            put("text", "Tell me something.")
                        })
                    })
                })
            })
        }

        val patched = patch("/v1/responses", request)
        val input = patched["input"]?.jsonArray
        assertNotNull(input)
        assertEquals(2, input.size, "single-turn input gets a fresh user turn appended to preserve the stable prefix")
        val appendedContent = input[1].jsonObject["content"]?.jsonArray
        assertNotNull(appendedContent)
        assertTrue(appendedContent[0].jsonObject["text"]?.jsonPrimitive?.content?.contains("Kiyomizu Companion Core") == true)

        Config.memoryEnabled = false
        resetDbFiles()
    }

    @Test
    fun geminiGenerateContentGetsCompanionInjectionIntoParts() {
        resetConfig()
        resetDbFiles()
        DatabaseService.initDatabase()
        DatabaseService.updateRelationshipState(50.0, 50.0, "neutral")
        Config.memoryEnabled = true

        val request = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", "Hello Gemini direct.")
                        })
                    })
                })
            })
        }

        val patched = patch("/v1beta/models/gemini-2.5-flash:generateContent", request)
        val contents = patched["contents"]?.jsonArray
        assertNotNull(contents)
        assertEquals(2, contents.size, "single-turn contents gets a fresh user turn appended to preserve the stable prefix")
        val appendedParts = contents[1].jsonObject["parts"]?.jsonArray
        assertNotNull(appendedParts)
        assertTrue(appendedParts[0].jsonObject["text"]?.jsonPrimitive?.content?.contains("Kiyomizu Companion Core") == true)

        Config.memoryEnabled = false
        resetDbFiles()
    }
}
