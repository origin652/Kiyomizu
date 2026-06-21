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

    private val memoryEnabledRef = AtomicReference(System.getenv("MEMORY_ENABLED") == "1")
    private val memorySummaryUrlRef = AtomicReference(System.getenv("MEMORY_SUMMARY_URL") ?: "https://generativelanguage.googleapis.com")
    private val memorySummaryKeyRef = AtomicReference(System.getenv("MEMORY_SUMMARY_KEY") ?: "")
    private val memorySummaryModelRef = AtomicReference(System.getenv("MEMORY_SUMMARY_MODEL") ?: "gemini-2.5-flash")
    private val memorySummaryPromptRef = AtomicReference(
        System.getenv("MEMORY_SUMMARY_PROMPT") ?: """
            You are the inner memory system of Kiyomizu. Analyze the latest exchange between the user and the assistant.
            Build a compact graph-memory update instead of a chat log.
            Identity rules:
            - User first-person references like "I", "me", "my", "我", "我的" refer to person://user/primary.
            - Assistant first-person references refer to person://self/kiyomizu.
            - Only create other person nodes when the text explicitly mentions them.
            Memory principles:
            - Prefer durable identity, preference, relationship, project_fact, episodic_event, and working_memory nodes.
            - Keep each node atomic, specific, and in the same language the user used.
            - Do not invent facts that were not stated or strongly implied.
            - Use disclosure from: private, hint, quote_allowed, sensitive.
            - Use source = conversation unless there is a better direct reason.
            - person_uri should point to the most relevant person for that node when clear.
            - project_uri should be set only when the current exchange clearly belongs to a named project or task.
            Return a single JSON object with this exact shape:
            {
              "nodes": [
                {
                  "uri": "preference://food/drink/tea",
                  "kind": "preference",
                  "content": "The user prefers tea.",
                  "keywords": ["tea", "drink"],
                  "aliases": [],
                  "entities": ["person://user/primary"],
                  "topics": ["preference"],
                  "trigger_phrases": ["tea"],
                  "disclosure": "hint",
                  "priority": 0.7,
                  "confidence": 0.8,
                  "strength": 0.8,
                  "emotion_valence": 0.6,
                  "emotion_arousal": 0.2,
                  "scope_hint": "global",
                  "person_uri": "person://user/primary",
                  "project_uri": null,
                  "source": "conversation",
                  "raw_evidence": "short evidence quote"
                }
              ],
              "edges": [
                {
                  "from_uri": "preference://food/drink/tea",
                  "to_uri": "person://user/primary",
                  "relation": "about_person",
                  "weight": 1.0
                }
              ],
              "intimacy_delta": 0.0,
              "trust_delta": 0.0,
              "mood": "neutral"
            }
            Valid node kinds: identity, preference, relationship, project_fact, episodic_event, working_memory, reflection.
            Valid edge relations: related_to, derived_from, reinforces, contradicts, supersedes, mentions, about_person, about_project, belongs_to_scope, relationship_to, triggered_by.
            Output only JSON. No markdown.
        """.trimIndent()
    )

    private val memoryDecayIntervalHoursRef = AtomicInteger(System.getenv("MEMORY_DECAY_INTERVAL_HOURS")?.toIntOrNull() ?: 24)
    private val memoryDecayRateRef = AtomicReference(System.getenv("MEMORY_DECAY_RATE")?.toDoubleOrNull() ?: 0.1)
    private val memoryThresholdRef = AtomicReference(System.getenv("MEMORY_THRESHOLD")?.toDoubleOrNull() ?: 0.1)
    private val memoryRecoveryAmountRef = AtomicReference(System.getenv("MEMORY_RECOVERY_AMOUNT")?.toDoubleOrNull() ?: 0.3)
    private val memoryMaxStrengthRef = AtomicReference(System.getenv("MEMORY_MAX_STRENGTH")?.toDoubleOrNull() ?: 1.0)
    private val memoryInitialStrengthRef = AtomicReference(System.getenv("MEMORY_INITIAL_STRENGTH")?.toDoubleOrNull() ?: 0.8)
    private val intimacyDecayRateRef = AtomicReference(System.getenv("INTIMACY_DECAY_RATE")?.toDoubleOrNull() ?: 0.5)
    private val memoryDecayTauHoursRef = AtomicReference(System.getenv("MEMORY_DECAY_TAU_HOURS")?.toDoubleOrNull() ?: 360.0)
    private val memorySalienceKRef = AtomicReference(System.getenv("MEMORY_SALIENCE_K")?.toDoubleOrNull() ?: 1.0)

    private val memoryRecallMaxNodesRef = AtomicInteger(System.getenv("MEMORY_RECALL_MAX_NODES")?.toIntOrNull() ?: 6)
    private val memoryDeepRecallEnabledRef = AtomicReference(System.getenv("MEMORY_DEEP_RECALL_ENABLED") != "0")
    private val memoryDeepRecallMaxCandidatesRef = AtomicInteger(System.getenv("MEMORY_DEEP_RECALL_MAX_CANDIDATES")?.toIntOrNull() ?: 40)
    private val memoryDeepRecallMaxCluesRef = AtomicInteger(System.getenv("MEMORY_DEEP_RECALL_MAX_CLUES")?.toIntOrNull() ?: 10)
    private val memoryPersonContextMaxCluesRef = AtomicInteger(System.getenv("MEMORY_PERSON_CONTEXT_MAX_CLUES")?.toIntOrNull() ?: 2)

    var preset: String
        get() = presetRef.get()
        set(value) { presetRef.set(value) }

    var upstream: String
        get() {
            val configured = upstreamRef.get().trim()
            if (configured.isNotEmpty()) return configured
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

    var memoryDecayTauHours: Double
        get() = memoryDecayTauHoursRef.get()
        set(value) { memoryDecayTauHoursRef.set(value) }

    var memorySalienceK: Double
        get() = memorySalienceKRef.get()
        set(value) { memorySalienceKRef.set(value) }

    var memoryRecallMaxNodes: Int
        get() = memoryRecallMaxNodesRef.get()
        set(value) { memoryRecallMaxNodesRef.set(value) }

    var memoryDeepRecallEnabled: Boolean
        get() = memoryDeepRecallEnabledRef.get()
        set(value) { memoryDeepRecallEnabledRef.set(value) }

    var memoryDeepRecallMaxCandidates: Int
        get() = memoryDeepRecallMaxCandidatesRef.get()
        set(value) { memoryDeepRecallMaxCandidatesRef.set(value) }

    var memoryDeepRecallMaxClues: Int
        get() = memoryDeepRecallMaxCluesRef.get()
        set(value) { memoryDeepRecallMaxCluesRef.set(value) }

    var memoryPersonContextMaxClues: Int
        get() = memoryPersonContextMaxCluesRef.get()
        set(value) { memoryPersonContextMaxCluesRef.set(value) }

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
        val memoryDecayIntervalHours: Int,
        val memoryDecayRate: Double,
        val memoryThreshold: Double,
        val memoryRecoveryAmount: Double,
        val memoryMaxStrength: Double,
        val memoryInitialStrength: Double,
        val intimacyDecayRate: Double,
        val memoryDecayTauHours: Double,
        val memorySalienceK: Double,
        val memoryRecallMaxNodes: Int,
        val memoryDeepRecallEnabled: Boolean,
        val memoryDeepRecallMaxCandidates: Int,
        val memoryDeepRecallMaxClues: Int,
        val memoryPersonContextMaxClues: Int
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
                put("memory_decay_interval_hours", memoryDecayIntervalHours)
                put("memory_decay_rate", memoryDecayRate)
                put("memory_threshold", memoryThreshold)
                put("memory_recovery_amount", memoryRecoveryAmount)
                put("memory_max_strength", memoryMaxStrength)
                put("memory_initial_strength", memoryInitialStrength)
                put("intimacy_decay_rate", intimacyDecayRate)
                put("memory_decay_tau_hours", memoryDecayTauHours)
                put("memory_salience_k", memorySalienceK)
                put("memory_recall_max_nodes", memoryRecallMaxNodes)
                put("memory_deep_recall_enabled", memoryDeepRecallEnabled)
                put("memory_deep_recall_max_candidates", memoryDeepRecallMaxCandidates)
                put("memory_deep_recall_max_clues", memoryDeepRecallMaxClues)
                put("memory_person_context_max_clues", memoryPersonContextMaxClues)
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
            memoryDecayIntervalHours = memoryDecayIntervalHours,
            memoryDecayRate = memoryDecayRate,
            memoryThreshold = memoryThreshold,
            memoryRecoveryAmount = memoryRecoveryAmount,
            memoryMaxStrength = memoryMaxStrength,
            memoryInitialStrength = memoryInitialStrength,
            intimacyDecayRate = intimacyDecayRate,
            memoryDecayTauHours = memoryDecayTauHours,
            memorySalienceK = memorySalienceK,
            memoryRecallMaxNodes = memoryRecallMaxNodes,
            memoryDeepRecallEnabled = memoryDeepRecallEnabled,
            memoryDeepRecallMaxCandidates = memoryDeepRecallMaxCandidates,
            memoryDeepRecallMaxClues = memoryDeepRecallMaxClues,
            memoryPersonContextMaxClues = memoryPersonContextMaxClues
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
        memoryDecayIntervalHours = snapshot.memoryDecayIntervalHours
        memoryDecayRate = snapshot.memoryDecayRate
        memoryThreshold = snapshot.memoryThreshold
        memoryRecoveryAmount = snapshot.memoryRecoveryAmount
        memoryMaxStrength = snapshot.memoryMaxStrength
        memoryInitialStrength = snapshot.memoryInitialStrength
        intimacyDecayRate = snapshot.intimacyDecayRate
        memoryDecayTauHours = snapshot.memoryDecayTauHours
        memorySalienceK = snapshot.memorySalienceK
        memoryRecallMaxNodes = snapshot.memoryRecallMaxNodes
        memoryDeepRecallEnabled = snapshot.memoryDeepRecallEnabled
        memoryDeepRecallMaxCandidates = snapshot.memoryDeepRecallMaxCandidates
        memoryDeepRecallMaxClues = snapshot.memoryDeepRecallMaxClues
        memoryPersonContextMaxClues = snapshot.memoryPersonContextMaxClues
    }

    fun loadPersisted(jsonText: String?) {
        if (jsonText.isNullOrBlank()) return
        try {
            val body = Json.parseToJsonElement(jsonText) as? JsonObject ?: return

            body.stringValue("memory_embedding_url")?.let {
                sanitizePersistedUrl(it, "memory_embedding_url")
            }
            body.stringValue("memory_embedding_key")
            body.stringValue("memory_embedding_model")

            applySnapshot(
                Snapshot(
                    preset = body.stringValue("preset") ?: preset,
                    upstream = body.stringValue("upstream")?.let { sanitizePersistedUrl(it, "upstream") } ?: upstreamRef.get().trim(),
                    cacheTtl = body.stringValue("cache_ttl") ?: cacheTtl,
                    cacheMode = body.stringValue("cache_mode") ?: cacheMode,
                    cacheStrategy = body.stringValue("cache_strategy") ?: cacheStrategy,
                    cacheBreakpoints = body.intValue("cache_breakpoints") ?: cacheBreakpoints,
                    memoryEnabled = body.booleanValue("memory_enabled") ?: memoryEnabled,
                    memorySummaryUrl = body.stringValue("memory_summary_url")?.let { sanitizePersistedUrl(it, "memory_summary_url") } ?: memorySummaryUrl,
                    memorySummaryKey = body.stringValue("memory_summary_key") ?: memorySummaryKey,
                    memorySummaryModel = body.stringValue("memory_summary_model") ?: memorySummaryModel,
                    memorySummaryPrompt = body.stringValue("memory_summary_prompt") ?: memorySummaryPrompt,
                    memoryDecayIntervalHours = body.intValue("memory_decay_interval_hours") ?: memoryDecayIntervalHours,
                    memoryDecayRate = body.doubleValue("memory_decay_rate") ?: memoryDecayRate,
                    memoryThreshold = body.doubleValue("memory_threshold") ?: memoryThreshold,
                    memoryRecoveryAmount = body.doubleValue("memory_recovery_amount") ?: memoryRecoveryAmount,
                    memoryMaxStrength = body.doubleValue("memory_max_strength") ?: memoryMaxStrength,
                    memoryInitialStrength = body.doubleValue("memory_initial_strength") ?: memoryInitialStrength,
                    intimacyDecayRate = body.doubleValue("intimacy_decay_rate") ?: intimacyDecayRate,
                    memoryDecayTauHours = body.doubleValue("memory_decay_tau_hours") ?: memoryDecayTauHours,
                    memorySalienceK = body.doubleValue("memory_salience_k") ?: memorySalienceK,
                    memoryRecallMaxNodes = body.intValue("memory_recall_max_nodes")
                        ?: body.intValue("max_recalled_memories")
                        ?: memoryRecallMaxNodes,
                    memoryDeepRecallEnabled = body.booleanValue("memory_deep_recall_enabled") ?: memoryDeepRecallEnabled,
                    memoryDeepRecallMaxCandidates = body.intValue("memory_deep_recall_max_candidates") ?: memoryDeepRecallMaxCandidates,
                    memoryDeepRecallMaxClues = body.intValue("memory_deep_recall_max_clues") ?: memoryDeepRecallMaxClues,
                    memoryPersonContextMaxClues = body.intValue("memory_person_context_max_clues") ?: memoryPersonContextMaxClues
                )
            )
        } catch (e: Exception) {
            System.err.println("Failed to load persisted config: ${e.message}")
        }
    }

    private fun sanitizePersistedUrl(value: String, fieldName: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        val error = Security.validateOutboundBaseUrl(trimmed, fieldName)
        if (error != null) {
            System.err.println("Discarding persisted $fieldName: $error")
            return ""
        }
        return trimmed
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
