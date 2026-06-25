package hifumi.kiyomizu.http

import hifumi.kiyomizu.http.companion.installCompanionRoutes
import io.ktor.server.routing.*

fun Route.installKiyomizuRoutes() {
    installHealthRoutes()
    installStaticRoutes()
    installConfigRoutes()
    installModelListRoutes()
    installCompanionRoutes()
    installLogsRoutes()
    installFallbackProxyRoute()
}