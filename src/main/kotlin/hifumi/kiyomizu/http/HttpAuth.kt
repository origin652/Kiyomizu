package hifumi.kiyomizu.http

import hifumi.kiyomizu.ConfigAuth
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream

suspend fun requireConfigAuth(call: ApplicationCall): Boolean {
    return when (ConfigAuth.authorizeConfigCall(call)) {
        ConfigAuth.AuthDecision.AUTHORIZED -> true
        ConfigAuth.AuthDecision.RATE_LIMITED -> {
            ConfigAuth.rejectRateLimited(call)
            false
        }
        ConfigAuth.AuthDecision.UNAUTHORIZED -> {
            ConfigAuth.reject(call)
            false
        }
    }
}

suspend fun receiveTextLimited(call: ApplicationCall, maxBytes: Long): String? {
    val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (contentLength != null && contentLength > maxBytes) {
        respondPayloadTooLarge(call, maxBytes)
        return null
    }

    val channel = call.receiveChannel()
    val buffer = ByteArray(8192)
    val out = ByteArrayOutputStream()
    var total = 0L

    while (!channel.isClosedForRead) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) {
            respondPayloadTooLarge(call, maxBytes)
            return null
        }
        out.write(buffer, 0, read)
    }

    return out.toString(Charsets.UTF_8.name())
}

suspend fun respondPayloadTooLarge(call: ApplicationCall, maxBytes: Long) {
    call.respondText(
        buildJsonObject {
            put("error", "request body is too large")
            put("max_bytes", maxBytes)
        }.toString(),
        ContentType.Application.Json,
        HttpStatusCode.PayloadTooLarge
    )
}