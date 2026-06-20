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
        ConfigAuth.headerName,
        Security.proxyAuthHeaderName
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
                if (v.startsWith("Bearer ", ignoreCase = true)) {
                    apiKey = v.substring(7).trim()
                } else {
                    apiKey = v.trim()
                }
            } else {
                result.add(k to v)
            }
        }

        if (apiKey.isNotEmpty()) {
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

    fun logRequest(method: String, pathname: String, originalBody: JsonObject, patchedBody: JsonObject) {
        val originalSummary = summarizeBody(originalBody)
        val patchedSummary = summarizeBody(patchedBody)
        val changed = originalSummary != patchedSummary

        val originalThinking = MessagePatcher.countStrippedThinkingBlocks(
            originalBody["messages"]?.jsonArray?.mapNotNull { it as? JsonObject }
        )
        val patchedThinking = MessagePatcher.countStrippedThinkingBlocks(
            patchedBody["messages"]?.jsonArray?.mapNotNull { it as? JsonObject }
        )

        val logObj = buildJsonObject {
            put("at", Instant.now().toString())
            put("method", method)
            put("pathname", pathname)
            put("patched", changed)
            if (originalThinking != null && patchedThinking != null) {
                put("removed_thinking_blocks", originalThinking - patchedThinking)
            }
            put("sent", patchedSummary)
        }

        println(json.encodeToString(JsonObject.serializer(), logObj))
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
}
