package hifumi.kiyomizu.http

import hifumi.kiyomizu.Config
import hifumi.kiyomizu.DatabaseService
import hifumi.kiyomizu.MemoryService
import hifumi.kiyomizu.MessagePatcher
import hifumi.kiyomizu.ProxyService
import hifumi.kiyomizu.Security
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.*
import java.net.URI

fun Route.installFallbackProxyRoute() {
    route("{...}") {
        handle {
            MemoryService.touchLastRequest()
            val upstreamBase = Config.upstream
            if (upstreamBase.isBlank()) {
                call.respondText("Upstream URL is not configured", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                return@handle
            }
            val methodStr = call.request.httpMethod.value
            val path = call.request.path()
            val query = call.request.queryString()
            val upstreamPath = ProxyService.normalizeUpstreamPath(path)
            val upstreamUrl = "$upstreamBase$upstreamPath${if (query.isNotEmpty()) "?$query" else ""}"
            val validationError = Security.validateOutboundRequestUrl(upstreamUrl, "upstream")
            if (validationError != null) {
                call.respondText(validationError, ContentType.Text.Plain, HttpStatusCode.BadRequest)
                return@handle
            }

            val cleanedHeaders = ProxyService.cleanHeaders(call.request.headers)
            val mayHaveRequestBody = methodStr != "GET" && methodStr != "HEAD"
            val requestBodyText = if (mayHaveRequestBody) {
                receiveTextLimited(call, Config.maxProxyRequestBytes) ?: return@handle
            } else {
                ""
            }
            var finalBodyBytes = requestBodyText.toByteArray(Charsets.UTF_8)
            var hasBetaHeader = false
            var originalJson: JsonObject? = null
            var requestLogId: Int? = null

            val isJson = call.request.contentType().match(ContentType.Application.Json)
            if (requestBodyText.isNotEmpty() && isJson) {
                try {
                    val parsed = Json.parseToJsonElement(requestBodyText) as? JsonObject
                    if (parsed != null) {
                        originalJson = parsed
                        val patchedJson = MessagePatcher.patchJsonBody(path, parsed)
                        finalBodyBytes = patchedJson.toString().toByteArray(Charsets.UTF_8)

                        if (Config.preset == "anthropic" && MessagePatcher.isAnthropicMessagesRequest(path, parsed)) {
                            hasBetaHeader = true
                        }
                        requestLogId = ProxyService.logRequest(call.request.httpMethod.value, path, parsed, patchedJson)
                    }
                } catch (e: Exception) {
                    System.err.println("Failed to parse request JSON: ${e.message}")
                }
            }

            try {
                val finalHeaders = ProxyService.adjustHeadersForUpstream(cleanedHeaders)
                val upstreamResponse = ProxyService.client.request(upstreamUrl) {
                    this.method = HttpMethod.parse(methodStr)
                    finalHeaders.forEach { (k, v) ->
                        if (k.lowercase() != "host" && k.lowercase() != "content-length") {
                            header(k, v)
                        }
                    }
                    header("host", URI(upstreamBase).host)
                    if (hasBetaHeader) {
                        header("anthropic-beta", Config.betaHeader)
                    }
                    if (mayHaveRequestBody && finalBodyBytes.isNotEmpty()) {
                        setBody(finalBodyBytes)
                    }
                }

                val responseHeaders = ProxyService.cleanHeaders(upstreamResponse.headers)
                responseHeaders.forEach { (k, v) ->
                    call.response.headers.append(k, v, safeOnly = true)
                }

                val statusVal = upstreamResponse.status.value
                val channel = upstreamResponse.bodyAsChannel()
                val responseContentType = upstreamResponse.contentType()
                val isTextResponse = responseContentType != null && (
                    responseContentType.match(ContentType.Application.Json) ||
                    responseContentType.match(ContentType.Text.EventStream) ||
                    responseContentType.match(ContentType.Text.Plain)
                )

                call.respondBytesWriter(
                    status = HttpStatusCode.fromValue(statusVal),
                    contentType = responseContentType ?: ContentType.Application.OctetStream
                ) {
                    val captured = channel.copyToAndCapture(this, isTextResponse)
                    val isSse = responseContentType?.match(ContentType.Text.EventStream) == true
                    if (requestLogId != null && isTextResponse) {
                        runCatching {
                            ProxyService.extractUsageDiagnostics(captured, isSse)?.let { usage ->
                                DatabaseService.updateRequestLogUsage(requestLogId!!, usage)
                            }
                        }.onFailure { e ->
                            System.err.println("Failed to record usage diagnostics: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
                        }
                    }
                    if (originalJson != null && isTextResponse && Config.memoryEnabled) {
                        runCatching {
                            val compiledText = compileResponseText(captured, isSse)
                            if (compiledText.isNotEmpty()) {
                                MemoryService.extractAndSaveMemoriesAsync(path, originalJson, compiledText)
                                MemoryService.recordAssistantTurn(compiledText)
                            }
                        }.onFailure { e ->
                            System.err.println("Failed to schedule memory extraction: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
                        }
                    }
                }
            } catch (e: Exception) {
                System.err.println("Proxy request failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
                call.respondText("Proxy error", ContentType.Text.Plain, HttpStatusCode.BadGateway)
            }
        }
    }
}

private suspend fun ByteReadChannel.copyToAndCapture(target: ByteWriteChannel, isText: Boolean): String {
    val buffer = ByteArray(4096)
    val out = java.io.ByteArrayOutputStream()
    var totalCaptured = 0
    while (!isClosedForRead) {
        val read = readAvailable(buffer, 0, buffer.size)
        if (read < 0) break
        if (read > 0) {
            target.writeFully(buffer, 0, read)
            if (isText && totalCaptured < 100000) {
                out.write(buffer, 0, read)
                totalCaptured += read
            }
        }
    }
    return if (isText) out.toString("UTF-8") else ""
}

private fun compileSseResponse(sseText: String): String {
    val lines = sseText.split("\n")
    val sb = java.lang.StringBuilder()
    for (line in lines) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) continue
        val dataStr = trimmed.substring(5).trim()
        if (dataStr == "[DONE]") continue
        try {
            val json = Json.parseToJsonElement(dataStr) as? JsonObject ?: continue
            val openAiContent = json["choices"]?.jsonArray?.getOrNull(0)?.jsonObject
                ?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            if (openAiContent != null) {
                sb.append(openAiContent)
                continue
            }
            if (json["type"]?.jsonPrimitive?.contentOrNull == "content_block_delta") {
                val delta = json["delta"]?.jsonObject ?: continue
                if (delta["type"]?.jsonPrimitive?.contentOrNull == "text_delta") {
                    delta["text"]?.jsonPrimitive?.contentOrNull?.let { sb.append(it) }
                    continue
                }
            }
            val responsesDelta = json["delta"]?.jsonPrimitive?.contentOrNull
            if (responsesDelta != null) {
                sb.append(responsesDelta)
            }
        } catch (e: Exception) {
            // Ignore parse errors for keep-alives
        }
    }
    return sb.toString()
}

private fun compileResponseText(text: String, isSse: Boolean): String {
    if (isSse) {
        return compileSseResponse(text)
    }
    try {
        val json = Json.parseToJsonElement(text) as? JsonObject ?: return text
        val openAiContent = json["choices"]?.jsonArray?.getOrNull(0)?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        if (openAiContent != null) return openAiContent
        val responsesContent = json["output"]?.jsonArray?.mapNotNull { outputItem ->
            (outputItem as? JsonObject)?.get("content")?.jsonArray?.mapNotNull { contentItem ->
                (contentItem as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
            }?.joinToString("\n")
        }?.joinToString("\n")
        if (!responsesContent.isNullOrBlank()) return responsesContent
        val topLevelOutputText = json["output_text"]?.jsonPrimitive?.contentOrNull
        if (!topLevelOutputText.isNullOrBlank()) return topLevelOutputText
        val anthropicContent = json["content"]?.jsonArray
        if (anthropicContent != null) {
            return anthropicContent.mapNotNull { block ->
                (block as? JsonObject)?.let { o ->
                    if (o["type"]?.jsonPrimitive?.contentOrNull == "text") {
                        o["text"]?.jsonPrimitive?.contentOrNull
                    } else null
                }
            }.joinToString("\n")
        }
        val geminiContent = json["candidates"]?.jsonArray?.getOrNull(0)?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.mapNotNull { part ->
                (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
            }?.joinToString("\n")
        if (!geminiContent.isNullOrBlank()) return geminiContent
    } catch (e: Exception) {
        // Ignore
    }
    return text
}