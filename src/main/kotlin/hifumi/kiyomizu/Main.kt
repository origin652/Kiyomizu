package hifumi.kiyomizu

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.*
import java.net.URI

fun main() {
    DatabaseService.initDatabase()

    if (Config.cacheMode == "automatic" && Config.preset != "anthropic") {
        println("Warning: CACHE_MODE was automatic but PRESET is not anthropic. Forcing cacheMode to explicit.")
        Config.cacheMode = "explicit"
    }

    println("Starting Kiyomizu Cache Proxy listening on http://${Config.host}:${Config.port}")
    println("Preset: ${Config.preset}")
    println("Upstream: ${Config.upstream.ifEmpty { "<unset>" }}")
    println("Cache mode: ${Config.cacheMode}")
    println("Send top-level cache_control: ${Config.sendTopLevelCacheControl}")
    println("Cache strategy: ${Config.cacheStrategy}")
    println("Cache breakpoints: ${Config.cacheBreakpoints}")
    println("Dynamic tail messages: ${Config.dynamicTailMessages}")
    println("Strip thinking: ${Config.stripThinking}")
    println("Model filter: ${Config.modelFilter}")

    embeddedServer(Netty, port = Config.port, host = Config.host) {
        MemoryService.startDecayJob(this)

        install(CORS) {
            allowHost("localhost")
            allowHost("127.0.0.1")
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowHeader("http-referer")
            allowHeader("x-title")
            allowHeader("anthropic-beta")
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Patch)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Options)
            allowCredentials = true
            allowNonSimpleContentTypes = true
        }

        install(createApplicationPlugin("PrivateNetworkCORS") {
            onCallRespond { call ->
                call.response.headers.append("Access-Control-Allow-Private-Network", "true", safeOnly = true)
            }
        })

        routing {
            options("{...}") {
                call.respond(HttpStatusCode.NoContent)
            }

            get("/health") {
                val healthText = listOf(
                    "Kiyomizu Cache Proxy is running.",
                    "Preset: ${Config.preset}",
                    "Upstream: ${Config.upstream}",
                    "Prompt cache TTL: ${Config.cacheTtl}",
                    "Cache mode: ${Config.cacheMode}",
                    "Send top-level cache_control: ${Config.sendTopLevelCacheControl}",
                    "Cache strategy: ${Config.cacheStrategy}",
                    "Cache breakpoints: ${Config.cacheBreakpoints}",
                    "Dynamic tail messages: ${Config.dynamicTailMessages}",
                    "Strip thinking: ${Config.stripThinking}",
                    "Model filter: ${Config.modelFilter}"
                ).joinToString("\n")
                call.respondText(healthText, ContentType.Text.Plain)
            }

            get("/") {
                serveUi(call)
            }
            get("/ui") {
                serveUi(call)
            }

            route("/api/config") {
                get {
                    call.respondText(ConfigApi.publicConfigJson().toString(), ContentType.Application.Json)
                }
                post {
                    val bodyText = call.receiveText()
                    val body = try { Json.parseToJsonElement(bodyText) as? JsonObject } catch(e: Exception) { null }
                    val updateResult = ConfigApi.applyUpdate(body)
                    if (updateResult.errors.isNotEmpty()) {
                        call.respondText(
                            buildJsonObject {
                                put("errors", buildJsonArray { updateResult.errors.forEach { add(it) } })
                            }.toString(),
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest
                        )
                    } else {
                        println("Config updated: preset=${Config.preset}, upstream=${Config.upstream.ifEmpty { "<unset>" }}, cacheTtl=${Config.cacheTtl}, cacheMode=${Config.cacheMode}, cacheStrategy=${Config.cacheStrategy}, cacheBreakpoints=${Config.cacheBreakpoints}, memoryEnabled=${Config.memoryEnabled}")
                        call.respondText(updateResult.responseBody.toString(), ContentType.Application.Json)
                    }
                }
            }

            val modelPaths = listOf("/models", "/model", "/v1/models", "/v1/model", "/api/v1/models", "/api/v1/model")
            modelPaths.forEach { path ->
                get(path) {
                    handleModelListProxy(call)
                }
            }

            fallbackProxyRoute()
        }
    }.start(wait = true)
}

private suspend fun serveUi(call: ApplicationCall) {
    val stream = Config::class.java.classLoader.getResourceAsStream("ui.html")
    if (stream != null) {
        val html = stream.bufferedReader().use { it.readText() }
        call.respondText(html, ContentType.Text.Html)
    } else {
        call.respondText("UI HTML not found in resources", ContentType.Text.Plain, HttpStatusCode.NotFound)
    }
}

private suspend fun handleModelListProxy(call: ApplicationCall) {
    val upstream = Config.upstream
    if (upstream.isBlank()) {
        call.respondText("Upstream URL is not configured", ContentType.Text.Plain, HttpStatusCode.BadRequest)
        return
    }
    val query = call.request.queryString()
    val upstreamUrl = "$upstream${ProxyService.normalizeUpstreamPath(call.request.path())}${if (query.isNotEmpty()) "?$query" else ""}"
    val cleanedHeaders = ProxyService.cleanHeaders(call.request.headers)
    val finalHeaders = ProxyService.adjustHeadersForUpstream(cleanedHeaders)
    try {
        val response = ProxyService.client.get(upstreamUrl) {
            finalHeaders.forEach { (k, v) ->
                if (k.lowercase() != "host") {
                    header(k, v)
                }
            }
            header("host", URI(Config.upstream).host)
        }
        val text = response.bodyAsText()
        val statusVal = response.status.value
        call.respondText(text, response.contentType() ?: ContentType.Application.Json, HttpStatusCode.fromValue(statusVal))
    } catch (e: Exception) {
        e.printStackTrace()
        call.respondText("Proxy error: ${e.message}", ContentType.Text.Plain, HttpStatusCode.BadGateway)
    }
}

private fun Route.fallbackProxyRoute() {
    route("{...}") {
        handle {
            val upstreamBase = Config.upstream
            if (upstreamBase.isBlank()) {
                call.respondText("Upstream URL is not configured", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                return@handle
            }
            val path = call.request.path()
            val query = call.request.queryString()
            val upstreamPath = ProxyService.normalizeUpstreamPath(path)
            val upstreamUrl = "$upstreamBase$upstreamPath${if (query.isNotEmpty()) "?$query" else ""}"
            
            val cleanedHeaders = ProxyService.cleanHeaders(call.request.headers)
            val requestBodyText = call.receiveText()
            var finalBodyBytes = requestBodyText.toByteArray(Charsets.UTF_8)
            var hasBetaHeader = false
            var originalJson: JsonObject? = null

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
                        ProxyService.logRequest(call.request.httpMethod.value, path, parsed, patchedJson)
                    }
                } catch (e: Exception) {
                    System.err.println("Failed to parse request JSON: ${e.message}")
                }
            }

            try {
                val methodStr = call.request.httpMethod.value
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
                    if (methodStr != "GET" && methodStr != "HEAD" && finalBodyBytes.isNotEmpty()) {
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
                    if (originalJson != null && isTextResponse && Config.memoryEnabled) {
                        val compiledText = compileResponseText(captured, responseContentType!!.match(ContentType.Text.EventStream))
                        if (compiledText.isNotEmpty()) {
                            MemoryService.extractAndSaveMemoriesAsync(path, originalJson, compiledText)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                call.respondText("Proxy error: ${e.message}", ContentType.Text.Plain, HttpStatusCode.BadGateway)
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
            if (isText && totalCaptured < 100000) { // Limit capture to 100KB
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
