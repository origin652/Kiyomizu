package hifumi.kiyomizu

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object Config {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8787
    val host = System.getenv("HOST") ?: "127.0.0.1"
    val upstream = System.getenv("OPENROUTER_BASE_URL") ?: "https://openrouter.ai"
    val sendTopLevelCacheControl = System.getenv("SEND_TOP_LEVEL_CACHE_CONTROL") == "1"
    val dynamicTailMessages = System.getenv("DYNAMIC_TAIL_MESSAGES")?.toIntOrNull() ?: 1
    val stripThinking = System.getenv("STRIP_THINKING") != "0"
    val modelFilter = System.getenv("MODEL_FILTER") ?: "anthropic,claude"
    val betaHeader = System.getenv("ANTHROPIC_BETA") ?: "extended-cache-ttl-2025-04-11"
    val geminiModelFilter = System.getenv("GEMINI_MODEL_FILTER") ?: "google,gemini"

    private val forceProviderRef = AtomicReference(System.getenv("FORCE_PROVIDER") ?: "anthropic")
    private val geminiProviderRef = AtomicReference(System.getenv("GEMINI_PROVIDER") ?: "aistudio")
    private val cacheTtlRef = AtomicReference(System.getenv("CACHE_TTL") ?: "1h")
    private val cacheModeRef = AtomicReference(System.getenv("CACHE_MODE") ?: "explicit")
    private val cacheStrategyRef = AtomicReference(System.getenv("CACHE_STRATEGY") ?: "stable-prefix")
    private val cacheBreakpointsRef = AtomicInteger(System.getenv("CACHE_BREAKPOINTS")?.toIntOrNull() ?: 4)

    var forceProvider: String
        get() = forceProviderRef.get()
        set(value) { forceProviderRef.set(value) }

    var geminiProvider: String
        get() = geminiProviderRef.get()
        set(value) { geminiProviderRef.set(value) }

    var cacheTtl: String
        get() = cacheTtlRef.get()
        set(value) { cacheTtlRef.set(value) }

    var cacheMode: String
        get() = cacheModeRef.get()
        set(value) { cacheModeRef.set(value) }

    var cacheStrategy: String
        get() = cacheStrategyRef.get()
        set(value) { cacheStrategyRef.set(value) }

    var cacheBreakpoints: Int
        get() = cacheBreakpointsRef.get()
        set(value) { cacheBreakpointsRef.set(value) }
}
