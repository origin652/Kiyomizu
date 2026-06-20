package hifumi.kiyomizu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object Config {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8787
    val host = System.getenv("HOST") ?: "127.0.0.1"
    val sendTopLevelCacheControl = System.getenv("SEND_TOP_LEVEL_CACHE_CONTROL") == "1"
    val dynamicTailMessages = System.getenv("DYNAMIC_TAIL_MESSAGES")?.toIntOrNull() ?: 1
    val stripThinking = System.getenv("STRIP_THINKING") != "0"
    val modelFilter = System.getenv("MODEL_FILTER") ?: "anthropic,claude"
    val betaHeader = System.getenv("ANTHROPIC_BETA") ?: "extended-cache-ttl-2025-04-11"
    val maxProxyRequestBytes = System.getenv("KIYOMIZU_MAX_PROXY_REQUEST_BYTES")?.toLongOrNull()
        ?.coerceIn(1024L, 100L * 1024L * 1024L)
        ?: (25L * 1024L * 1024L)
    val maxConfigRequestBytes = System.getenv("KIYOMIZU_MAX_CONFIG_REQUEST_BYTES")?.toLongOrNull()
        ?.coerceIn(1024L, 1024L * 1024L)
        ?: (128L * 1024L)

    private val presetRef = AtomicReference(System.getenv("PRESET") ?: "custom")
    private val upstreamRef = AtomicReference(
        System.getenv("UPSTREAM_URL")
            ?: System.getenv("OPENROUTER_BASE_URL")
            ?: ""
    )
    private val cacheTtlRef = AtomicReference(System.getenv("CACHE_TTL") ?: "1h")
    private val cacheModeRef = AtomicReference(System.getenv("CACHE_MODE") ?: "explicit")
    private val cacheStrategyRef = AtomicReference(System.getenv("CACHE_STRATEGY") ?: "stable-prefix")
    private val cacheBreakpointsRef = AtomicInteger(System.getenv("CACHE_BREAKPOINTS")?.toIntOrNull() ?: 4)

    // Companion Memory Settings
    private val memoryEnabledRef = AtomicReference(System.getenv("MEMORY_ENABLED") == "1")
    
    private val memorySummaryUrlRef = AtomicReference(System.getenv("MEMORY_SUMMARY_URL") ?: "https://generativelanguage.googleapis.com")
    private val memorySummaryKeyRef = AtomicReference(System.getenv("MEMORY_SUMMARY_KEY") ?: "")
    private val memorySummaryModelRef = AtomicReference(System.getenv("MEMORY_SUMMARY_MODEL") ?: "gemini-2.5-flash")
    private val memorySummaryPromptRef = AtomicReference(
        System.getenv("MEMORY_SUMMARY_PROMPT") ?: """
            You are the inner mind of an AI companion. Analyze the recent conversation between the User and the Assistant.
            Extract key new facts, preferences, emotional milestones, or shared experiences about the user or your relationship as a list of distinct atomic statements.
            Write each memory in the same language the user used in the conversation. Do not translate memories unless the user clearly switched languages on purpose.
            Preserve names, places, wording nuance, and culturally specific expressions as faithfully as possible while keeping each memory short and atomic.
            Also, evaluate how this interaction affects your intimacy (intimacy_delta between -5.0 and +5.0) and trust (trust_delta between -5.0 and +5.0), and assess your current mood (one of: happy, caring, lonely, worried, neutral).
            You MUST respond with a single JSON object in the following format:
            {
              "memories": [
                { "content": "Atomic memory statement", "type": "semantic"|"episodic", "emotion_tag": "joy"|"sadness"|"warmth"|"anxiety"|"neutral", "importance": 0.5 }
              ],
              "intimacy_delta": 1.5,
              "trust_delta": 0.5,
              "mood": "happy"
            }
            Do not include markdown formatting or backticks around the JSON.
        """.trimIndent()
    )
    
    private val memoryEmbeddingUrlRef = AtomicReference(System.getenv("MEMORY_EMBEDDING_URL") ?: "https://generativelanguage.googleapis.com")
    private val memoryEmbeddingKeyRef = AtomicReference(System.getenv("MEMORY_EMBEDDING_KEY") ?: "")
    private val memoryEmbeddingModelRef = AtomicReference(System.getenv("MEMORY_EMBEDDING_MODEL") ?: "text-embedding-004")

    private val memoryDecayIntervalHoursRef = AtomicInteger(System.getenv("MEMORY_DECAY_INTERVAL_HOURS")?.toIntOrNull() ?: 24)
    private val memoryDecayRateRef = AtomicReference(System.getenv("MEMORY_DECAY_RATE")?.toDoubleOrNull() ?: 0.1)
    private val memoryThresholdRef = AtomicReference(System.getenv("MEMORY_THRESHOLD")?.toDoubleOrNull() ?: 0.1)
    private val memoryRecoveryAmountRef = AtomicReference(System.getenv("MEMORY_RECOVERY_AMOUNT")?.toDoubleOrNull() ?: 0.3)
    private val memoryMaxStrengthRef = AtomicReference(System.getenv("MEMORY_MAX_STRENGTH")?.toDoubleOrNull() ?: 1.0)
    private val memoryInitialStrengthRef = AtomicReference(System.getenv("MEMORY_INITIAL_STRENGTH")?.toDoubleOrNull() ?: 1.0)
    
    private val intimacyDecayRateRef = AtomicReference(System.getenv("INTIMACY_DECAY_RATE")?.toDoubleOrNull() ?: 0.5)
    private val spontaneousRecallProbabilityRef = AtomicReference(System.getenv("SPONTANEOUS_RECALL_PROBABILITY")?.toDoubleOrNull() ?: 0.15)
    private val maxRecalledMemoriesRef = AtomicInteger(System.getenv("MAX_RECALLED_MEMORIES")?.toIntOrNull() ?: 5)

    var preset: String
        get() = presetRef.get()
        set(value) { presetRef.set(value) }

    var upstream: String
        get() {
            val u = upstreamRef.get().trim()
            if (u.isNotEmpty()) return u
            return when (preset) {
                "anthropic" -> "https://api.anthropic.com"
                else -> ""
            }
        }
        set(value) { upstreamRef.set(value.trim()) }

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

    // Companion Memory Getters/Setters
    var memoryEnabled: Boolean
        get() = memoryEnabledRef.get()
        set(value) { memoryEnabledRef.set(value) }

    var memorySummaryUrl: String
        get() = memorySummaryUrlRef.get()
        set(value) { memorySummaryUrlRef.set(value) }

    var memorySummaryKey: String
        get() = memorySummaryKeyRef.get()
        set(value) { memorySummaryKeyRef.set(value) }

    var memorySummaryModel: String
        get() = memorySummaryModelRef.get()
        set(value) { memorySummaryModelRef.set(value) }

    var memorySummaryPrompt: String
        get() = memorySummaryPromptRef.get()
        set(value) { memorySummaryPromptRef.set(value) }

    var memoryEmbeddingUrl: String
        get() = memoryEmbeddingUrlRef.get()
        set(value) { memoryEmbeddingUrlRef.set(value) }

    var memoryEmbeddingKey: String
        get() = memoryEmbeddingKeyRef.get()
        set(value) { memoryEmbeddingKeyRef.set(value) }

    var memoryEmbeddingModel: String
        get() = memoryEmbeddingModelRef.get()
        set(value) { memoryEmbeddingModelRef.set(value) }

    var memoryDecayIntervalHours: Int
        get() = memoryDecayIntervalHoursRef.get()
        set(value) { memoryDecayIntervalHoursRef.set(value) }

    var memoryDecayRate: Double
        get() = memoryDecayRateRef.get()
        set(value) { memoryDecayRateRef.set(value) }

    var memoryThreshold: Double
        get() = memoryThresholdRef.get()
        set(value) { memoryThresholdRef.set(value) }

    var memoryRecoveryAmount: Double
        get() = memoryRecoveryAmountRef.get()
        set(value) { memoryRecoveryAmountRef.set(value) }

    var memoryMaxStrength: Double
        get() = memoryMaxStrengthRef.get()
        set(value) { memoryMaxStrengthRef.set(value) }

    var memoryInitialStrength: Double
        get() = memoryInitialStrengthRef.get()
        set(value) { memoryInitialStrengthRef.set(value) }

    var intimacyDecayRate: Double
        get() = intimacyDecayRateRef.get()
        set(value) { intimacyDecayRateRef.set(value) }

    var spontaneousRecallProbability: Double
        get() = spontaneousRecallProbabilityRef.get()
        set(value) { spontaneousRecallProbabilityRef.set(value) }

    var maxRecalledMemories: Int
        get() = maxRecalledMemoriesRef.get()
        set(value) { maxRecalledMemoriesRef.set(value) }

    data class Snapshot(
        val preset: String,
        val upstream: String,
        val cacheTtl: String,
        val cacheMode: String,
        val cacheStrategy: String,
        val cacheBreakpoints: Int,
        val memoryEnabled: Boolean,
        val memorySummaryUrl: String,
        val memorySummaryKey: String,
        val memorySummaryModel: String,
        val memorySummaryPrompt: String,
        val memoryEmbeddingUrl: String,
        val memoryEmbeddingKey: String,
        val memoryEmbeddingModel: String,
        val memoryDecayIntervalHours: Int,
        val memoryDecayRate: Double,
        val memoryThreshold: Double,
        val memoryRecoveryAmount: Double,
        val memoryMaxStrength: Double,
        val memoryInitialStrength: Double,
        val intimacyDecayRate: Double,
        val spontaneousRecallProbability: Double,
        val maxRecalledMemories: Int
    ) {
        fun toJson(): JsonObject {
            return buildJsonObject {
                put("preset", preset)
                put("upstream", upstream)
                put("cache_ttl", cacheTtl)
                put("cache_mode", cacheMode)
                put("cache_strategy", cacheStrategy)
                put("cache_breakpoints", cacheBreakpoints)
                put("memory_enabled", memoryEnabled)
                put("memory_summary_url", memorySummaryUrl)
                put("memory_summary_key", memorySummaryKey)
                put("memory_summary_model", memorySummaryModel)
                put("memory_summary_prompt", memorySummaryPrompt)
                put("memory_embedding_url", memoryEmbeddingUrl)
                put("memory_embedding_key", memoryEmbeddingKey)
                put("memory_embedding_model", memoryEmbeddingModel)
                put("memory_decay_interval_hours", memoryDecayIntervalHours)
                put("memory_decay_rate", memoryDecayRate)
                put("memory_threshold", memoryThreshold)
                put("memory_recovery_amount", memoryRecoveryAmount)
                put("memory_max_strength", memoryMaxStrength)
                put("memory_initial_strength", memoryInitialStrength)
                put("intimacy_decay_rate", intimacyDecayRate)
                put("spontaneous_recall_probability", spontaneousRecallProbability)
                put("max_recalled_memories", maxRecalledMemories)
            }
        }
    }

    fun snapshot(): Snapshot {
        return Snapshot(
            preset = preset,
            upstream = upstreamRef.get().trim(),
            cacheTtl = cacheTtl,
            cacheMode = cacheMode,
            cacheStrategy = cacheStrategy,
            cacheBreakpoints = cacheBreakpoints,
            memoryEnabled = memoryEnabled,
            memorySummaryUrl = memorySummaryUrl,
            memorySummaryKey = memorySummaryKey,
            memorySummaryModel = memorySummaryModel,
            memorySummaryPrompt = memorySummaryPrompt,
            memoryEmbeddingUrl = memoryEmbeddingUrl,
            memoryEmbeddingKey = memoryEmbeddingKey,
            memoryEmbeddingModel = memoryEmbeddingModel,
            memoryDecayIntervalHours = memoryDecayIntervalHours,
            memoryDecayRate = memoryDecayRate,
            memoryThreshold = memoryThreshold,
            memoryRecoveryAmount = memoryRecoveryAmount,
            memoryMaxStrength = memoryMaxStrength,
            memoryInitialStrength = memoryInitialStrength,
            intimacyDecayRate = intimacyDecayRate,
            spontaneousRecallProbability = spontaneousRecallProbability,
            maxRecalledMemories = maxRecalledMemories
        )
    }

    fun applySnapshot(snapshot: Snapshot) {
        preset = snapshot.preset
        upstream = snapshot.upstream
        cacheTtl = snapshot.cacheTtl
        cacheMode = snapshot.cacheMode
        cacheStrategy = snapshot.cacheStrategy
        cacheBreakpoints = snapshot.cacheBreakpoints
        memoryEnabled = snapshot.memoryEnabled
        memorySummaryUrl = snapshot.memorySummaryUrl
        memorySummaryKey = snapshot.memorySummaryKey
        memorySummaryModel = snapshot.memorySummaryModel
        memorySummaryPrompt = snapshot.memorySummaryPrompt
        memoryEmbeddingUrl = snapshot.memoryEmbeddingUrl
        memoryEmbeddingKey = snapshot.memoryEmbeddingKey
        memoryEmbeddingModel = snapshot.memoryEmbeddingModel
        memoryDecayIntervalHours = snapshot.memoryDecayIntervalHours
        memoryDecayRate = snapshot.memoryDecayRate
        memoryThreshold = snapshot.memoryThreshold
        memoryRecoveryAmount = snapshot.memoryRecoveryAmount
        memoryMaxStrength = snapshot.memoryMaxStrength
        memoryInitialStrength = snapshot.memoryInitialStrength
        intimacyDecayRate = snapshot.intimacyDecayRate
        spontaneousRecallProbability = snapshot.spontaneousRecallProbability
        maxRecalledMemories = snapshot.maxRecalledMemories
    }

    fun loadPersisted(jsonText: String?) {
        if (jsonText.isNullOrBlank()) return
        try {
            val body = Json.parseToJsonElement(jsonText) as? JsonObject ?: return
            applySnapshot(
                Snapshot(
                    preset = body.stringValue("preset") ?: preset,
                    upstream = body.stringValue("upstream") ?: upstreamRef.get().trim(),
                    cacheTtl = body.stringValue("cache_ttl") ?: cacheTtl,
                    cacheMode = body.stringValue("cache_mode") ?: cacheMode,
                    cacheStrategy = body.stringValue("cache_strategy") ?: cacheStrategy,
                    cacheBreakpoints = body.intValue("cache_breakpoints") ?: cacheBreakpoints,
                    memoryEnabled = body.booleanValue("memory_enabled") ?: memoryEnabled,
                    memorySummaryUrl = body.stringValue("memory_summary_url") ?: memorySummaryUrl,
                    memorySummaryKey = body.stringValue("memory_summary_key") ?: memorySummaryKey,
                    memorySummaryModel = body.stringValue("memory_summary_model") ?: memorySummaryModel,
                    memorySummaryPrompt = body.stringValue("memory_summary_prompt") ?: memorySummaryPrompt,
                    memoryEmbeddingUrl = body.stringValue("memory_embedding_url") ?: memoryEmbeddingUrl,
                    memoryEmbeddingKey = body.stringValue("memory_embedding_key") ?: memoryEmbeddingKey,
                    memoryEmbeddingModel = body.stringValue("memory_embedding_model") ?: memoryEmbeddingModel,
                    memoryDecayIntervalHours = body.intValue("memory_decay_interval_hours") ?: memoryDecayIntervalHours,
                    memoryDecayRate = body.doubleValue("memory_decay_rate") ?: memoryDecayRate,
                    memoryThreshold = body.doubleValue("memory_threshold") ?: memoryThreshold,
                    memoryRecoveryAmount = body.doubleValue("memory_recovery_amount") ?: memoryRecoveryAmount,
                    memoryMaxStrength = body.doubleValue("memory_max_strength") ?: memoryMaxStrength,
                    memoryInitialStrength = body.doubleValue("memory_initial_strength") ?: memoryInitialStrength,
                    intimacyDecayRate = body.doubleValue("intimacy_decay_rate") ?: intimacyDecayRate,
                    spontaneousRecallProbability = body.doubleValue("spontaneous_recall_probability") ?: spontaneousRecallProbability,
                    maxRecalledMemories = body.intValue("max_recalled_memories") ?: maxRecalledMemories
                )
            )
        } catch (e: Exception) {
            System.err.println("Failed to load persisted config: ${e.message}")
        }
    }

    private fun JsonObject.stringValue(field: String): String? {
        return (this[field] as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.booleanValue(field: String): Boolean? {
        return (this[field] as? JsonPrimitive)?.booleanOrNull
    }

    private fun JsonObject.intValue(field: String): Int? {
        return (this[field] as? JsonPrimitive)?.intOrNull
    }

    private fun JsonObject.doubleValue(field: String): Double? {
        return (this[field] as? JsonPrimitive)?.doubleOrNull
    }
}
