package hifumi.kiyomizu.http

import hifumi.kiyomizu.ConfigAuth
import hifumi.kiyomizu.DatabaseService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

fun Route.installLogsRoutes() {
    get("/api/logs") {
        if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@get }
        if (!requireConfigAuth(call)) return@get
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
        val logs = DatabaseService.getRecentRequestLogs(limit)
        call.respondText(buildJsonObject {
            put("logs", buildJsonArray {
                logs.forEach { l ->
                    add(buildJsonObject {
                        put("id", l.id)
                        put("at", l.at)
                        put("method", l.method)
                        put("pathname", l.pathname)
                        put("patched", l.patched)
                        put("removed_thinking_blocks", l.removedThinkingBlocks)
                        if (l.model != null) put("model", l.model)
                        if (l.messageCount != null) put("message_count", l.messageCount)
                        if (l.explicitCacheBlocks != null) put("explicit_cache_blocks", l.explicitCacheBlocks)
                        if (l.cacheMode != null) put("cache_mode", l.cacheMode)
                        if (l.cacheStrategy != null) put("cache_strategy", l.cacheStrategy)
                        if (l.cacheBreakpoints != null) put("cache_breakpoints", l.cacheBreakpoints)
                        put("cache_breakpoint_indexes", buildJsonArray { l.cacheBreakpointIndexes.forEach { add(it) } })
                        if (l.patchEligible != null) put("patch_eligible", l.patchEligible)
                        if (l.inputTokens != null) put("input_tokens", l.inputTokens)
                        if (l.outputTokens != null) put("output_tokens", l.outputTokens)
                        if (l.cacheReadInputTokens != null) put("cache_read_input_tokens", l.cacheReadInputTokens)
                        if (l.cacheCreationInputTokens != null) put("cache_creation_input_tokens", l.cacheCreationInputTokens)
                        if (l.cachedPromptTokens != null) put("cached_prompt_tokens", l.cachedPromptTokens)
                        if (l.usageJson != null) put("usage_json", l.usageJson)
                    })
                }
            })
        }.toString(), ContentType.Application.Json)
    }
}