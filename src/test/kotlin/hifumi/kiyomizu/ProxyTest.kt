package hifumi.kiyomizu

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProxyTest {

    @Test
    fun testSelfTest() {
        val claudeRequest = buildJsonObject {
            put("model", "anthropic/claude-sonnet-4.6")
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

        val patchedClaude = MessagePatcher.patchJsonBody(claudeRequest)

        val providerOnly = patchedClaude["provider"]?.jsonObject?.get("only")?.jsonArray
        assertNotNull(providerOnly)
        assertEquals("anthropic", providerOnly[0].jsonPrimitive.content, "provider forced")

        assertTrue("cache_control" !in patchedClaude, "top-level cache_control omitted by default in explicit mode")

        val messages = patchedClaude["messages"]?.jsonArray?.mapNotNull { it as? JsonObject }
        assertNotNull(messages)

        val explicitCacheBlocks = MessagePatcher.countExplicitCacheBlocks(messages)
        assertEquals(3, explicitCacheBlocks, "all stable cacheable messages get breakpoints when <= max")

        val breakpointIndexes = MessagePatcher.findLoggedCacheBreakpointIndexes(messages)
        assertEquals(listOf(0, 1, 2), breakpointIndexes, "breakpoints placed across stable prefix before dynamic tail")

        val strippedThinkingBlocks = MessagePatcher.countStrippedThinkingBlocks(messages)
        assertEquals(0, strippedThinkingBlocks, "thinking blocks stripped")

        assertEquals("Newest user message.", messages.last()["content"]?.jsonPrimitive?.content, "dynamic tail remains unpatched")

        Config.forceProvider = "vertex"
        val patchedVertexClaude = MessagePatcher.patchJsonBody(claudeRequest)
        val vertexProviderOnly = patchedVertexClaude["provider"]?.jsonObject?.get("only")?.jsonArray
        assertNotNull(vertexProviderOnly)
        assertEquals("google-vertex", vertexProviderOnly[0].jsonPrimitive.content, "Claude Vertex provider uses OpenRouter slug")

        Config.forceProvider = "anthropic"

        val nonClaudeRequest = buildJsonObject {
            put("model", "openai/gpt-4.1")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Hello.")
                })
            })
        }
        val patchedNonClaude = MessagePatcher.patchJsonBody(nonClaudeRequest)
        assertEquals(nonClaudeRequest, patchedNonClaude, "non-Claude request forwarded unchanged")

        val geminiRequest = buildJsonObject {
            put("model", "google/gemini-2.5-flash")
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", "You are helpful.") })
                add(buildJsonObject { put("role", "user"); put("content", "Message one.") })
                add(buildJsonObject { put("role", "assistant"); put("content", "Answer one.") })
                add(buildJsonObject { put("role", "user"); put("content", "Message two.") })
            })
        }
        val patchedGemini = MessagePatcher.patchJsonBody(geminiRequest)
        val geminiProviderOnly = patchedGemini["provider"]?.jsonObject?.get("only")?.jsonArray
        assertNotNull(geminiProviderOnly)
        assertEquals("google-ai-studio", geminiProviderOnly[0].jsonPrimitive.content, "Gemini uses aistudio provider by default")

        val geminiMessages = patchedGemini["messages"]?.jsonArray?.mapNotNull { it as? JsonObject }
        assertNotNull(geminiMessages)
        val geminiBreakpoints = MessagePatcher.findLoggedCacheBreakpointIndexes(geminiMessages)
        assertNotNull(geminiBreakpoints)
        assertEquals(1, geminiBreakpoints.size, "Gemini gets exactly one cache breakpoint")
        assertEquals(geminiMessages.size - 1, geminiBreakpoints[0], "Gemini breakpoint is on the last message")

        Config.geminiProvider = "vertex"
        val patchedVertexGemini = MessagePatcher.patchJsonBody(geminiRequest)
        val vertexGeminiProviderOnly = patchedVertexGemini["provider"]?.jsonObject?.get("only")?.jsonArray
        assertNotNull(vertexGeminiProviderOnly)
        assertEquals("google-vertex", vertexGeminiProviderOnly[0].jsonPrimitive.content, "Gemini Vertex provider uses OpenRouter slug")

        Config.geminiProvider = "aistudio"

        val modelListPayload = buildJsonObject {
            put("data", buildJsonArray {
                add(buildJsonObject {
                    put("id", "anthropic/claude-sonnet-4.6")
                    put("name", "Claude Sonnet 4.6")
                    put("context_length", 200000)
                    put("pricing", buildJsonObject {
                        put("prompt", "0.000003")
                    })
                })
                add(buildJsonObject {
                    put("id", JsonNull)
                })
            })
        }
        val modelList = MessagePatcher.normalizeModelList(modelListPayload)
        assertEquals("list", modelList["object"]?.jsonPrimitive?.content, "model list object is OpenAI-shaped")

        val modelData = modelList["data"]?.jsonArray
        assertNotNull(modelData)
        assertEquals(1, modelData.size, "invalid models are filtered")

        val firstModel = modelData[0].jsonObject
        assertEquals("anthropic/claude-sonnet-4.6", firstModel["id"]?.jsonPrimitive?.content, "model id preserved")
        assertEquals("model", firstModel["object"]?.jsonPrimitive?.content, "model object set")
        assertEquals("anthropic", firstModel["owned_by"]?.jsonPrimitive?.content, "owned_by derived")

        assertEquals("/api/v1/chat/completions", ProxyService.normalizeUpstreamPath("/v1/chat/completions"), "OpenAI chat path maps to OpenRouter API path")
        assertEquals("/api/v1/chat/completions", ProxyService.normalizeUpstreamPath("/api/v1/chat/completions"), "OpenRouter API path is preserved")
    }
}
