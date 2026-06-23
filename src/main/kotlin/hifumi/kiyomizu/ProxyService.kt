package hifumi.kiyomizu

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.*
import java.time.Instant

object ProxyService {
    val client = HttpClient(CIO) {
        engine {
            requestTimeout = 300000 // 5 minutes
        }
        // Redirects are resolved by the validated proxy layer, never auto-followed,
        // so an upstream cannot redirect us into a private/metadata address that
        // bypassed Security.validateOutboundRequestUrl.
        followRedirects = false
        expectSuccess = false
    }

    private val hopByHopHeaders = setOf(
        "connection",
        "content-length",
        "host",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade"
    )

    private val internalHeaders = setOf(
        ConfigAuth.headerName
    )

    fun normalizeUpstreamPath(pathname: String): String {
        return pathname
    }

    fun corsHeaders(requestedHeaders: String?): List<Pair<String, String>> {
        val reqHeaders = requestedHeaders ?: "authorization,content-type,http-referer,x-title,anthropic-beta"
        return listOf(
            "access-control-allow-origin" to "*",
            "access-control-allow-methods" to "GET,POST,PUT,PATCH,DELETE,OPTIONS",
            "access-control-allow-headers" to reqHeaders,
            "access-control-allow-private-network" to "true"
        )
    }

    fun cleanHeaders(headers: Headers): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        headers.forEach { key, values ->
            val lowerKey = key.lowercase()
            if (!hopByHopHeaders.contains(lowerKey) &&
                !internalHeaders.contains(lowerKey) &&
                !lowerKey.startsWith("access-control-")
            ) {
                list.add(key to values.joinToString(", "))
            }
        }
        return list
    }

    fun adjustHeadersForUpstream(cleanedHeaders: List<Pair<String, String>>): List<Pair<String, String>> {
        if (Config.preset != "anthropic") {
            return cleanedHeaders
        }

        val result = mutableListOf<Pair<String, String>>()
        var apiKey = ""
        cleanedHeaders.forEach { (k, v) ->
            val lk = k.lowercase()
            if (lk == "authorization") {
                val trimmed = v.trim()
                if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
                    apiKey = trimmed.substring(7).trim()
                } else {
                    // Non-Bearer Authorization is not an Anthropic API key; forward
                    // it verbatim instead of corrupting x-api-key with the full value.
                    result.add(k to v)
                }
            } else {
                result.add(k to v)
            }
        }

        if (apiKey.isNotEmpty() && result.none { it.first.lowercase() == "x-api-key" }) {
            result.add("x-api-key" to apiKey)
        }
        if (result.none { it.first.lowercase() == "anthropic-version" }) {
            result.add("anthropic-version" to "2023-06-01")
        }
        return result
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun logRequest(method: String, pathname: String, originalBody: JsonObject, patchedBody: JsonObject): Int? {
        val originalSummary = summarizeBody(originalBody)
        val patchedSummary = summarizeBody(patchedBody)
        val changed = originalSummary != patchedSummary

        val originalMessages = originalBody["messages"]?.jsonArray?.mapNotNull { it as? JsonObject }
        val patchedMessages = patchedBody["messages"]?.jsonArray?.mapNotNull { it as? JsonObject }
        val originalThinking = MessagePatcher.countStrippedThinkingBlocks(originalMessages)
        val patchedThinking = MessagePatcher.countStrippedThinkingBlocks(patchedMessages)
        val removedThinking = if (originalThinking != null && patchedThinking != null) originalThinking - patchedThinking else 0
        val model = originalBody["model"]?.jsonPrimitive?.contentOrNull
        val patchEligible = Config.preset == "anthropic" && MessagePatcher.shouldPatchModel(model)
        val breakpointIndexes = MessagePatcher.findLoggedCacheBreakpointIndexes(patchedMessages) ?: emptyList()

        val atStr = Instant.now().toString()
        val logObj = buildJsonObject {
            put("at", atStr)
            put("method", method)
            put("pathname", pathname)
            put("patched", changed)
            if (originalThinking != null && patchedThinking != null) {
                put("removed_thinking_blocks", removedThinking)
            }
            put("sent", patchedSummary)
        }

        println(json.encodeToString(JsonObject.serializer(), logObj))

        return try {
            DatabaseService.insertRequestLog(
                at = atStr,
                method = method,
                pathname = pathname,
                patched = changed,
                removedThinkingBlocks = removedThinking,
                model = model,
                messageCount = patchedMessages?.size,
                explicitCacheBlocks = MessagePatcher.countExplicitCacheBlocks(patchedMessages),
                cacheMode = Config.cacheMode,
                cacheStrategy = Config.cacheStrategy,
                cacheBreakpoints = Config.cacheBreakpoints,
                cacheBreakpointIndexes = breakpointIndexes,
                patchEligible = patchEligible
            )
        } catch (e: Exception) {
            System.err.println("Failed to persist request log: ${e.message}")
            null
        }
    }

    private fun summarizeBody(body: JsonObject): JsonObject {
        val model = body["model"]?.jsonPrimitive?.contentOrNull
        val messages = body["messages"]?.jsonArray?.mapNotNull { it as? JsonObject }
        val eligibleForPatch = Config.preset == "anthropic" && MessagePatcher.shouldPatchModel(model)

        return buildJsonObject {
            if (model != null) put("model", model)
            body["stream"]?.let { put("stream", it) }
            put("preset", Config.preset)
            put("eligible_for_patch", eligibleForPatch)
            put("cache_mode", Config.cacheMode)
            put("send_top_level_cache_control", Config.sendTopLevelCacheControl)
            put("cache_strategy", Config.cacheStrategy)
            put("cache_breakpoints", Config.cacheBreakpoints)
            put("dynamic_tail_messages", Config.dynamicTailMessages)
            put("strip_thinking", Config.stripThinking)
            body["cache_control"]?.let { put("cache_control", it) }
            body["provider"]?.let { put("provider", it) }
            if (messages != null) {
                put("messages", messages.size)
                MessagePatcher.countExplicitCacheBlocks(messages)?.let { put("explicit_cache_blocks", it) }
                MessagePatcher.countStrippedThinkingBlocks(messages)?.let { put("stripped_thinking_blocks", it) }
                MessagePatcher.findLoggedCacheBreakpointIndexes(messages)?.let { indexes ->
                    put("cache_breakpoint_indexes", buildJsonArray { indexes.forEach { add(it) } })
                }
            }
        }
    }

    fun extractUsageDiagnostics(responseText: String, isSse: Boolean): DatabaseService.RequestUsageDraft? {
        if (responseText.isBlank()) return null
        return if (isSse) {
            responseText.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("data:") }
                .map { it.substring(5).trim() }
                .filter { it.isNotBlank() && it != "[DONE]" }
                .mapNotNull { data ->
                    runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull()
                        ?.let { usageFromJson(it) }
                }
                .fold(null as DatabaseService.RequestUsageDraft?) { acc, usage -> mergeUsage(acc, usage) }
        } else {
            runCatching { Json.parseToJsonElement(responseText) as? JsonObject }.getOrNull()
                ?.let { usageFromJson(it) }
        }
    }

    private fun usageFromJson(json: JsonObject): DatabaseService.RequestUsageDraft? {
        val usage = json["usage"]?.jsonObject
        val usageMetadata = json["usageMetadata"]?.jsonObject
        val source = usage ?: usageMetadata ?: return null

        fun JsonObject.intField(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
        val promptDetails = usage?.get("prompt_tokens_details")?.jsonObject
        val inputTokens = usage?.intField("input_tokens")
            ?: usage?.intField("prompt_tokens")
            ?: usageMetadata?.intField("promptTokenCount")
        val outputTokens = usage?.intField("output_tokens")
            ?: usage?.intField("completion_tokens")
            ?: usageMetadata?.intField("candidatesTokenCount")
        val cacheRead = usage?.intField("cache_read_input_tokens")
        val cacheCreation = usage?.intField("cache_creation_input_tokens")
        val cachedPrompt = promptDetails?.intField("cached_tokens")
            ?: usageMetadata?.intField("cachedContentTokenCount")

        if (inputTokens == null && outputTokens == null && cacheRead == null && cacheCreation == null && cachedPrompt == null) {
            return null
        }
        return DatabaseService.RequestUsageDraft(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheReadInputTokens = cacheRead,
            cacheCreationInputTokens = cacheCreation,
            cachedPromptTokens = cachedPrompt,
            usageJson = source.toString()
        )
    }

    private fun mergeUsage(
        current: DatabaseService.RequestUsageDraft?,
        next: DatabaseService.RequestUsageDraft
    ): DatabaseService.RequestUsageDraft {
        if (current == null) return next
        return DatabaseService.RequestUsageDraft(
            inputTokens = next.inputTokens ?: current.inputTokens,
            outputTokens = next.outputTokens ?: current.outputTokens,
            cacheReadInputTokens = next.cacheReadInputTokens ?: current.cacheReadInputTokens,
            cacheCreationInputTokens = next.cacheCreationInputTokens ?: current.cacheCreationInputTokens,
            cachedPromptTokens = next.cachedPromptTokens ?: current.cachedPromptTokens,
            usageJson = next.usageJson ?: current.usageJson
        )
    }
}
