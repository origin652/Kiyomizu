package hifumi.kiyomizu.http

import hifumi.kiyomizu.ConfigAuth
import hifumi.kiyomizu.Security
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.path

fun Application.installKiyomizuPlugins() {
    if (Security.shouldAllowBrowserCors()) {
        install(CORS) {
            allowHost("localhost")
            allowHost("127.0.0.1")
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowHeader("http-referer")
            allowHeader("x-title")
            allowHeader("anthropic-beta")
            allowHeader(ConfigAuth.headerName)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Patch)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Options)
            allowCredentials = true
            allowNonSimpleContentTypes = true
        }
    }

    install(createApplicationPlugin("SecurityHeaders") {
        onCallRespond { call ->
            call.response.headers.append("X-Content-Type-Options", "nosniff", safeOnly = true)
            call.response.headers.append("X-Frame-Options", "DENY", safeOnly = true)
            call.response.headers.append("Referrer-Policy", "no-referrer", safeOnly = true)
            call.response.headers.append("Permissions-Policy", "geolocation=(), microphone=(), camera=()", safeOnly = true)
            call.response.headers.append(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
                safeOnly = true
            )
            if (call.request.path().startsWith("/api/")) {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store", safeOnly = true)
            }
        }
    })

    if (Security.shouldAllowBrowserCors()) {
        install(createApplicationPlugin("PrivateNetworkCORS") {
            onCallRespond { call ->
                call.response.headers.append("Access-Control-Allow-Private-Network", "true", safeOnly = true)
            }
        })
    }
}