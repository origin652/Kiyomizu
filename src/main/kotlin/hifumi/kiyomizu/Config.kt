package hifumi.kiyomizu

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
            You are the inner mind of Kiyomizu, an AI companion. Analyze the recent conversation between the User and the Assistant.
            Extract key new facts, preferences, emotional milestones, or shared experiences about the user or your relationship as a list of distinct atomic statements.
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
}
