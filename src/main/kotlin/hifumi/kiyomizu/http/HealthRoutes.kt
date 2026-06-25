package hifumi.kiyomizu.http

import hifumi.kiyomizu.Config
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.installHealthRoutes() {
    options("{...}") {
        call.respond(HttpStatusCode.NoContent)
    }

    get("/health") {
        val healthText = if (System.getenv("KIYOMIZU_VERBOSE_HEALTH") == "1") {
            listOf(
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
        } else {
            "ok\n"
        }
        call.respondText(healthText, ContentType.Text.Plain)
    }
}

fun Route.installStaticRoutes() {
    get("/") {
        serveUi(call)
    }
    get("/ui") {
        serveUi(call)
    }
    get("/favicon.ico") {
        serveResource(call, "favicon.ico", ContentType.Image.XIcon)
    }

    get("/ui/static/{path...}") {
        val pathSegments = call.parameters.getAll("path").orEmpty()
        if (pathSegments.isEmpty() || pathSegments.any { it.isBlank() || it == "." || it == ".." || it.contains('/') || it.contains('\\') }) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val raw = pathSegments.joinToString("/")
        serveResource(call, "static/$raw", contentTypeForExtension(raw.substringAfterLast('.', "").lowercase()), cacheable = true)
    }
}