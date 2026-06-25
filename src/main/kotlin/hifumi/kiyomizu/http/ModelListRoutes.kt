package hifumi.kiyomizu.http

import hifumi.kiyomizu.Config
import hifumi.kiyomizu.ProxyService
import hifumi.kiyomizu.Security
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.net.URI

fun Route.installModelListRoutes() {
    val modelPaths = listOf("/models", "/model", "/v1/models", "/v1/model", "/api/v1/models", "/api/v1/model")
    modelPaths.forEach { path ->
        get(path) {
            handleModelListProxy(call)
        }
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
    val validationError = Security.validateOutboundRequestUrl(upstreamUrl, "upstream")
    if (validationError != null) {
        call.respondText(validationError, ContentType.Text.Plain, HttpStatusCode.BadRequest)
        return
    }
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
        System.err.println("Model list proxy failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
        call.respondText("Proxy error", ContentType.Text.Plain, HttpStatusCode.BadGateway)
    }
}