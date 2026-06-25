package hifumi.kiyomizu.http

import hifumi.kiyomizu.Config
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

suspend fun serveUi(call: ApplicationCall) {
    serveResource(call, "ui.html", ContentType.Text.Html)
}

suspend fun serveResource(
    call: ApplicationCall,
    resourceName: String,
    contentType: ContentType,
    cacheable: Boolean = false,
) {
    val stream = Config::class.java.classLoader.getResourceAsStream(resourceName)
    if (stream != null) {
        if (cacheable) {
            call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=86400", safeOnly = true)
        }
        when {
            contentType.match(ContentType.Text.Html) -> {
                val text = stream.bufferedReader().use { it.readText() }
                call.respondText(text, contentType)
            }
            else -> {
                val bytes = stream.use { it.readBytes() }
                call.respondBytes(bytes, contentType)
            }
        }
    } else {
        call.respondText("$resourceName not found in resources", ContentType.Text.Plain, HttpStatusCode.NotFound)
    }
}

fun contentTypeForExtension(ext: String): ContentType = when (ext) {
    "html" -> ContentType.Text.Html
    "css" -> ContentType.Text.CSS
    "js" -> ContentType.Text.JavaScript
    "json" -> ContentType.Application.Json
    "svg" -> ContentType.Image.SVG
    "png" -> ContentType.Image.PNG
    "jpg", "jpeg" -> ContentType.Image.JPEG
    "gif" -> ContentType.Image.GIF
    "ico" -> ContentType.Image.XIcon
    "woff2" -> ContentType("font", "woff2")
    "woff" -> ContentType("font", "woff")
    "ttf" -> ContentType("font", "ttf")
    else -> ContentType.Application.OctetStream
}