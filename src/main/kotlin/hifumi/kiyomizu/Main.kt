package hifumi.kiyomizu

import hifumi.kiyomizu.http.installKiyomizuPlugins
import hifumi.kiyomizu.http.installKiyomizuRoutes
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*

fun main() {
    DatabaseService.initDatabase()
    ConfigAuth.loadPersisted(DatabaseService.loadConfigPassword())
    Config.loadPersisted(DatabaseService.loadConfig())

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
    if (Security.isPubliclyBound()) {
        if (!Security.isRemotePasswordSetupAllowed() && !ConfigAuth.isConfigured()) {
            println("Security: remote first-run password setup is disabled. Set KIYOMIZU_CONFIG_PASSWORD before exposing this server.")
        }
    }

    embeddedServer(Netty, port = Config.port, host = Config.host) {
        MemoryService.startDecayJob(this)
        MemoryService.startConsolidationJob(this)
        installKiyomizuPlugins()
        routing {
            installKiyomizuRoutes()
        }
    }.start(wait = true)
}