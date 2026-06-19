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
    println("Starting OpenRouter cache proxy listening on http://${Config.host}:${Config.port}")
    println("Cache mode: ${Config.cacheMode}")
    println("Send top-level cache_control: ${Config.sendTopLevelCacheControl}")
    println("Cache strategy: ${Config.cacheStrategy}")
    println("Cache breakpoints: ${Config.cacheBreakpoints}")
    println("Dynamic tail messages: ${Config.dynamicTailMessages}")
    println("Strip thinking: ${Config.stripThinking}")
    println("Model filter: ${Config.modelFilter}")

    embeddedServer(Netty, port = Config.port, host = Config.host) {
        install(CORS) {
            anyHost()
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
                if (call.response.headers["Access-Control-Allow-Origin"] == null) {
                    call.response.headers.append("Access-Control-Allow-Origin", "*", safeOnly = true)
                }
            }
        })

        routing {
            options("{...}") {
                call.respond(HttpStatusCode.NoContent)
            }

            get("/health") {
                val healthText = listOf(
                    "OpenRouter cache proxy is running.",
                    "Upstream: ${Config.upstream}",
                    "Forced provider (Claude): ${Config.forceProvider}",
                    "Forced provider (Gemini): ${Config.geminiProvider}",
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
                    val configJson = buildJsonObject {
                        put("force_provider", Config.forceProvider)
                        put("gemini_provider", Config.geminiProvider)
                        put("cache_ttl", Config.cacheTtl)
                        put("cache_mode", Config.cacheMode)
                        put("cache_strategy", Config.cacheStrategy)
                        put("cache_breakpoints", Config.cacheBreakpoints)
                    }
                    call.respondText(configJson.toString(), ContentType.Application.Json)
                }
                post {
                    val bodyText = call.receiveText()
                    val body = try { Json.parseToJsonElement(bodyText) as? JsonObject } catch(e: Exception) { null }
                    val errors = mutableListOf<String>()
                    if (body != null) {
                        body["force_provider"]?.jsonPrimitive?.contentOrNull?.let {
                            if (it in listOf("anthropic", "bedrock", "vertex")) {
                                Config.forceProvider = it
                            } else {
                                errors.add("force_provider must be one of: anthropic, bedrock, vertex")
                            }
                        }
                        body["gemini_provider"]?.jsonPrimitive?.contentOrNull?.let {
                            if (it in listOf("aistudio", "vertex")) {
                                Config.geminiProvider = it
                            } else {
                                errors.add("gemini_provider must be one of: aistudio, vertex")
                            }
                        }
                        body["cache_ttl"]?.jsonPrimitive?.contentOrNull?.let {
                            if (it in listOf("5m", "1h", "none")) {
                                Config.cacheTtl = it
                            } else {
                                errors.add("cache_ttl must be one of: 5m, 1h, none")
                            }
                        }
                        body["cache_mode"]?.jsonPrimitive?.contentOrNull?.let {
                            if (it in listOf("explicit", "automatic")) {
                                Config.cacheMode = it
                            } else {
                                errors.add("cache_mode must be one of: explicit, automatic")
                            }
                        }
                        body["cache_strategy"]?.jsonPrimitive?.contentOrNull?.let {
                            if (it in listOf("stable-prefix", "last")) {
                                Config.cacheStrategy = it
                            } else {
                                errors.add("cache_strategy must be one of: stable-prefix, last")
                            }
                        }
                        body["cache_breakpoints"]?.jsonPrimitive?.intOrNull?.let {
                            if (it in 0..4) {
                                Config.cacheBreakpoints = it
                            } else {
                                errors.add("cache_breakpoints must be an integer 0-4")
                            }
                        }
                    }
                    if (errors.isNotEmpty()) {
                        call.respondText(
                            buildJsonObject { put("errors", buildJsonArray { errors.forEach { add(it) } }) }.toString(),
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest
                        )
                    } else {
                        println("Config updated: forceProvider=${Config.forceProvider}, geminiProvider=${Config.geminiProvider}, cacheTtl=${Config.cacheTtl}, cacheMode=${Config.cacheMode}, cacheStrategy=${Config.cacheStrategy}, cacheBreakpoints=${Config.cacheBreakpoints}")
                        val responseJson = buildJsonObject {
                            put("force_provider", Config.forceProvider)
                            put("gemini_provider", Config.geminiProvider)
                            put("cache_ttl", Config.cacheTtl)
                            put("cache_mode", Config.cacheMode)
                            put("cache_strategy", Config.cacheStrategy)
                            put("cache_breakpoints", Config.cacheBreakpoints)
                        }
                        call.respondText(responseJson.toString(), ContentType.Application.Json)
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
    val upstreamUrl = "${Config.upstream}/api/v1/models"
    val cleanedHeaders = ProxyService.cleanHeaders(call.request.headers)
    try {
        val response = ProxyService.client.get(upstreamUrl) {
            cleanedHeaders.forEach { (k, v) ->
                if (k.lowercase() != "host") {
                    header(k, v)
                }
            }
            header("host", URI(Config.upstream).host)
        }
        val text = response.bodyAsText()
        val payload = try { Json.parseToJsonElement(text) } catch(e: Exception) { null }
        val statusVal = response.status.value
        if (payload == null) {
            call.respondText(text, response.contentType() ?: ContentType.Application.Json, HttpStatusCode.fromValue(statusVal))
        } else {
            val normalized = MessagePatcher.normalizeModelList(payload)
            call.respondText(normalized.toString(), ContentType.Application.Json, HttpStatusCode.fromValue(statusVal))
        }
    } catch (e: Exception) {
        e.printStackTrace()
        call.respondText("Proxy error: ${e.message}", ContentType.Text.Plain, HttpStatusCode.BadGateway)
    }
}

private fun Route.fallbackProxyRoute() {
    route("{...}") {
        handle {
            val path = call.request.path()
            val query = call.request.queryString()
            val upstreamPath = ProxyService.normalizeUpstreamPath(path)
            val upstreamUrl = "${Config.upstream}$upstreamPath${if (query.isNotEmpty()) "?$query" else ""}"
            
            val cleanedHeaders = ProxyService.cleanHeaders(call.request.headers)
            val requestBodyText = call.receiveText()
            var finalBodyBytes = requestBodyText.toByteArray(Charsets.UTF_8)
            var hasBetaHeader = false

            val isJson = call.request.contentType().match(ContentType.Application.Json)
            if (requestBodyText.isNotEmpty() && isJson) {
                try {
                    val originalJson = Json.parseToJsonElement(requestBodyText) as? JsonObject
                    if (originalJson != null) {
                        val patchedJson = MessagePatcher.patchJsonBody(originalJson)
                        finalBodyBytes = patchedJson.toString().toByteArray(Charsets.UTF_8)
                        
                        val model = originalJson["model"]?.jsonPrimitive?.contentOrNull
                        if (MessagePatcher.isCacheProvider() && MessagePatcher.shouldPatchModel(model)) {
                            hasBetaHeader = true
                        }
                        ProxyService.logRequest(call.request.httpMethod.value, path, originalJson, patchedJson)
                    }
                } catch (e: Exception) {
                    System.err.println("Failed to parse request JSON: ${e.message}")
                }
            }

            try {
                val methodStr = call.request.httpMethod.value
                val upstreamResponse = ProxyService.client.request(upstreamUrl) {
                    this.method = HttpMethod.parse(methodStr)
                    cleanedHeaders.forEach { (k, v) ->
                        if (k.lowercase() != "host" && k.lowercase() != "content-length") {
                            header(k, v)
                        }
                    }
                    header("host", URI(Config.upstream).host)
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

                call.respondBytesWriter(
                    status = HttpStatusCode.fromValue(statusVal),
                    contentType = upstreamResponse.contentType() ?: ContentType.Application.OctetStream
                ) {
                    channel.copyTo(this)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                call.respondText("Proxy error: ${e.message}", ContentType.Text.Plain, HttpStatusCode.BadGateway)
            }
        }
    }
}
