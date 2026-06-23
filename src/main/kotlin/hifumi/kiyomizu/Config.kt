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
            Build compact memory observations instead of a chat log or direct long-term memory writes.
            Identity rules:
            - User first-person references like "I", "me", "my", "我", "我的" refer to person://user/primary.
            - Assistant first-person references refer to person://self/kiyomizu.
            - Durable first-person assistant self-knowledge, behavior strategy, style, boundary, or capability observations may use self://... candidate_uri with person_uri = person://self/kiyomizu.
            - Only create other person nodes when the text explicitly mentions them.
            Memory principles:
            - Prefer durable identity, preference, relationship, project_fact, episodic_event, and working_memory observations.
            - Assistant self observations should be first-person self-descriptions, not second-person commands.
            - Do not promote dream-source self claims as facts; mark source=dream when they come from dreams.
            - Keep each observation atomic, specific, and in the same language the user used.
            - It is valid to return an empty observations array when the exchange has no durable memory value.
            - Do not invent facts that were not stated or strongly implied.
            - Use disclosure from: private, hint, quote_allowed, sensitive.
            - Use source = conversation unless there is a better direct reason.
            - person_uri should point to the most relevant person for that node when clear.
            - project_uri should be set only when the current exchange clearly belongs to a named project or task.
            - Set explicit_remember=true only when the user clearly asks Kiyomizu to remember or not forget something.
            Return a single JSON object with this exact shape:
            {
              "observations": [
                {
                  "candidate_uri": "preference://food/drink/tea",
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
                  "raw_evidence": "short evidence quote",
                  "novelty": 0.7,
                  "explicit_remember": false
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
    private val memoryBufferedIngestionEnabledRef = AtomicReference(System.getenv("MEMORY_BUFFERED_INGESTION_ENABLED") != "0")
    private val memoryObservationRetentionDaysRef = AtomicInteger(System.getenv("MEMORY_OBSERVATION_RETENTION_DAYS")?.toIntOrNull() ?: 14)
    private val memoryLowConfidenceObservationRetentionDaysRef = AtomicInteger(System.getenv("MEMORY_LOW_CONFIDENCE_OBSERVATION_RETENTION_DAYS")?.toIntOrNull() ?: 3)
    private val memoryObservationMinConfidenceRef = AtomicReference(System.getenv("MEMORY_OBSERVATION_MIN_CONFIDENCE")?.toDoubleOrNull() ?: 0.35)
    private val memoryPromoteRepeatThresholdRef = AtomicInteger(System.getenv("MEMORY_PROMOTE_REPEAT_THRESHOLD")?.toIntOrNull() ?: 2)
    private val memoryProjectFactPromoteRepeatThresholdRef = AtomicInteger(System.getenv("MEMORY_PROJECT_FACT_PROMOTE_REPEAT_THRESHOLD")?.toIntOrNull() ?: 2)
    private val memoryWorkingMemorySlotsPerProjectRef = AtomicInteger(System.getenv("MEMORY_WORKING_MEMORY_SLOTS_PER_PROJECT")?.toIntOrNull() ?: 3)
    private val memoryObservationDailyCapRef = AtomicInteger(System.getenv("MEMORY_OBSERVATION_DAILY_CAP")?.toIntOrNull() ?: 200)
    private val memoryPromotedNodesDailyCapRef = AtomicInteger(System.getenv("MEMORY_PROMOTED_NODES_DAILY_CAP")?.toIntOrNull() ?: 20)
    private val memoryDreamEnabledRef = AtomicReference(System.getenv("MEMORY_DREAM_ENABLED") == "1")
    private val memoryAutoMaintenanceEnabledRef = AtomicReference(System.getenv("MEMORY_AUTO_MAINTENANCE_ENABLED") == "1")
    private val memoryDreamDailyLimitRef = AtomicInteger(System.getenv("MEMORY_DREAM_DAILY_LIMIT")?.toIntOrNull() ?: 1)
    private val memoryDreamIdleHoursRef = AtomicInteger(System.getenv("MEMORY_DREAM_IDLE_HOURS")?.toIntOrNull() ?: 12)
    private val memoryDreamBatchMaxNodesRef = AtomicInteger(System.getenv("MEMORY_DREAM_BATCH_MAX_NODES")?.toIntOrNull() ?: 40)
    private val memoryDreamDryRunDailyLimitRef = AtomicInteger(System.getenv("MEMORY_DREAM_DRY_RUN_DAILY_LIMIT")?.toIntOrNull() ?: 3)
    private val memoryLongIdlePauseDaysRef = AtomicInteger(System.getenv("MEMORY_LONG_IDLE_PAUSE_DAYS")?.toIntOrNull() ?: 7)
    private val memoryRecycleRetentionDaysRef = AtomicInteger(System.getenv("MEMORY_RECYCLE_RETENTION_DAYS")?.toIntOrNull() ?: 30)
    private val memoryDreamRecallMaxTracesRef = AtomicInteger(System.getenv("MEMORY_DREAM_RECALL_MAX_TRACES")?.toIntOrNull() ?: 2)
    private val memoryMaintenanceAggressivenessRef = AtomicReference(
        System.getenv("MEMORY_MAINTENANCE_AGGRESSIVENESS")
            ?.trim()
            ?.lowercase()
            ?.takeIf { it in setOf("standard", "aggressive") }
            ?: "aggressive"
    )
    private val memorySelfEnabledRef = AtomicReference(System.getenv("MEMORY_SELF_ENABLED") != "0")
    private val memorySelfDirectUpdateEnabledRef = AtomicReference(System.getenv("MEMORY_SELF_DIRECT_UPDATE_ENABLED") != "0")
    private val memorySelfRecallMaxNodesRef = AtomicInteger(System.getenv("MEMORY_SELF_RECALL_MAX_NODES")?.toIntOrNull() ?: 8)
    private val memorySelfPromoteRepeatThresholdRef = AtomicInteger(System.getenv("MEMORY_SELF_PROMOTE_REPEAT_THRESHOLD")?.toIntOrNull() ?: 3)
    private val memoryModelRecallEnabledRef = AtomicReference(System.getenv("MEMORY_MODEL_RECALL_ENABLED") == "1")
    private val memoryRecallModelUrlRef = AtomicReference(System.getenv("MEMORY_RECALL_MODEL_URL") ?: "")
    private val memoryRecallModelKeyRef = AtomicReference(System.getenv("MEMORY_RECALL_MODEL_KEY") ?: "")
    private val memoryRecallModelModelRef = AtomicReference(System.getenv("MEMORY_RECALL_MODEL_MODEL") ?: "")
    private val memoryModelRecallFailureThresholdRef = AtomicInteger(System.getenv("MEMORY_MODEL_RECALL_FAILURE_THRESHOLD")?.toIntOrNull() ?: 3)
    private val memoryModelRecallCooldownSecondsRef = AtomicInteger(System.getenv("MEMORY_MODEL_RECALL_COOLDOWN_SECONDS")?.toIntOrNull() ?: 300)
    private val memoryModelRecallTraceRetentionRef = AtomicInteger(System.getenv("MEMORY_MODEL_RECALL_TRACE_RETENTION")?.toIntOrNull() ?: 200)
    private val memoryTrafficClassifierEnabledRef = AtomicReference(System.getenv("MEMORY_TRAFFIC_CLASSIFIER_ENABLED") != "0")
    private val memoryToolInternalBypassEnabledRef = AtomicReference(System.getenv("MEMORY_TOOL_INTERNAL_BYPASS_ENABLED") != "0")
    private val memoryUnknownDisableWriteRef = AtomicReference(System.getenv("MEMORY_UNKNOWN_DISABLE_WRITE") != "0")
    private val memoryTaskDisableAffectRef = AtomicReference(System.getenv("MEMORY_TASK_DISABLE_AFFECT") != "0")

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

    var memoryBufferedIngestionEnabled: Boolean
        get() = memoryBufferedIngestionEnabledRef.get()
        set(value) { memoryBufferedIngestionEnabledRef.set(value) }

    var memoryObservationRetentionDays: Int
        get() = memoryObservationRetentionDaysRef.get()
        set(value) { memoryObservationRetentionDaysRef.set(value) }

    var memoryLowConfidenceObservationRetentionDays: Int
        get() = memoryLowConfidenceObservationRetentionDaysRef.get()
        set(value) { memoryLowConfidenceObservationRetentionDaysRef.set(value) }

    var memoryObservationMinConfidence: Double
        get() = memoryObservationMinConfidenceRef.get()
        set(value) { memoryObservationMinConfidenceRef.set(value) }

    var memoryPromoteRepeatThreshold: Int
        get() = memoryPromoteRepeatThresholdRef.get()
        set(value) { memoryPromoteRepeatThresholdRef.set(value) }

    var memoryProjectFactPromoteRepeatThreshold: Int
        get() = memoryProjectFactPromoteRepeatThresholdRef.get()
        set(value) { memoryProjectFactPromoteRepeatThresholdRef.set(value) }

    var memoryWorkingMemorySlotsPerProject: Int
        get() = memoryWorkingMemorySlotsPerProjectRef.get()
        set(value) { memoryWorkingMemorySlotsPerProjectRef.set(value) }

    var memoryObservationDailyCap: Int
        get() = memoryObservationDailyCapRef.get()
        set(value) { memoryObservationDailyCapRef.set(value) }

    var memoryPromotedNodesDailyCap: Int
        get() = memoryPromotedNodesDailyCapRef.get()
        set(value) { memoryPromotedNodesDailyCapRef.set(value) }

    var memoryDreamEnabled: Boolean
        get() = memoryDreamEnabledRef.get()
        set(value) { memoryDreamEnabledRef.set(value) }

    var memoryAutoMaintenanceEnabled: Boolean
        get() = memoryAutoMaintenanceEnabledRef.get()
        set(value) { memoryAutoMaintenanceEnabledRef.set(value) }

    var memoryDreamDailyLimit: Int
        get() = memoryDreamDailyLimitRef.get()
        set(value) { memoryDreamDailyLimitRef.set(value) }

    var memoryDreamIdleHours: Int
        get() = memoryDreamIdleHoursRef.get()
        set(value) { memoryDreamIdleHoursRef.set(value) }

    var memoryDreamBatchMaxNodes: Int
        get() = memoryDreamBatchMaxNodesRef.get()
        set(value) { memoryDreamBatchMaxNodesRef.set(value) }

    var memoryDreamDryRunDailyLimit: Int
        get() = memoryDreamDryRunDailyLimitRef.get()
        set(value) { memoryDreamDryRunDailyLimitRef.set(value) }

    var memoryLongIdlePauseDays: Int
        get() = memoryLongIdlePauseDaysRef.get()
        set(value) { memoryLongIdlePauseDaysRef.set(value) }

    var memoryRecycleRetentionDays: Int
        get() = memoryRecycleRetentionDaysRef.get()
        set(value) { memoryRecycleRetentionDaysRef.set(value) }

    var memoryDreamRecallMaxTraces: Int
        get() = memoryDreamRecallMaxTracesRef.get()
        set(value) { memoryDreamRecallMaxTracesRef.set(value) }

    var memoryMaintenanceAggressiveness: String
        get() = memoryMaintenanceAggressivenessRef.get()
        set(value) {
            memoryMaintenanceAggressivenessRef.set(
                value.trim().lowercase().takeIf { it in setOf("standard", "aggressive") } ?: "aggressive"
            )
        }

    var memorySelfEnabled: Boolean
        get() = memorySelfEnabledRef.get()
        set(value) { memorySelfEnabledRef.set(value) }

    var memorySelfDirectUpdateEnabled: Boolean
        get() = memorySelfDirectUpdateEnabledRef.get()
        set(value) { memorySelfDirectUpdateEnabledRef.set(value) }

    var memorySelfRecallMaxNodes: Int
        get() = memorySelfRecallMaxNodesRef.get()
        set(value) { memorySelfRecallMaxNodesRef.set(value) }

    var memorySelfPromoteRepeatThreshold: Int
        get() = memorySelfPromoteRepeatThresholdRef.get()
        set(value) { memorySelfPromoteRepeatThresholdRef.set(value) }

    var memoryModelRecallEnabled: Boolean
        get() = memoryModelRecallEnabledRef.get()
        set(value) { memoryModelRecallEnabledRef.set(value) }

    var memoryRecallModelUrl: String
        get() = memoryRecallModelUrlRef.get()
        set(value) { memoryRecallModelUrlRef.set(value.trim()) }

    var memoryRecallModelKey: String
        get() = memoryRecallModelKeyRef.get()
        set(value) { memoryRecallModelKeyRef.set(value) }

    var memoryRecallModelModel: String
        get() = memoryRecallModelModelRef.get()
        set(value) { memoryRecallModelModelRef.set(value.trim()) }

    var memoryModelRecallFailureThreshold: Int
        get() = memoryModelRecallFailureThresholdRef.get()
        set(value) { memoryModelRecallFailureThresholdRef.set(value) }

    var memoryModelRecallCooldownSeconds: Int
        get() = memoryModelRecallCooldownSecondsRef.get()
        set(value) { memoryModelRecallCooldownSecondsRef.set(value) }

    var memoryModelRecallTraceRetention: Int
        get() = memoryModelRecallTraceRetentionRef.get()
        set(value) { memoryModelRecallTraceRetentionRef.set(value) }

    var memoryTrafficClassifierEnabled: Boolean
        get() = memoryTrafficClassifierEnabledRef.get()
        set(value) { memoryTrafficClassifierEnabledRef.set(value) }

    var memoryToolInternalBypassEnabled: Boolean
        get() = memoryToolInternalBypassEnabledRef.get()
        set(value) { memoryToolInternalBypassEnabledRef.set(value) }

    var memoryUnknownDisableWrite: Boolean
        get() = memoryUnknownDisableWriteRef.get()
        set(value) { memoryUnknownDisableWriteRef.set(value) }

    var memoryTaskDisableAffect: Boolean
        get() = memoryTaskDisableAffectRef.get()
        set(value) { memoryTaskDisableAffectRef.set(value) }

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
        val memoryPersonContextMaxClues: Int,
        val memoryBufferedIngestionEnabled: Boolean,
        val memoryObservationRetentionDays: Int,
        val memoryLowConfidenceObservationRetentionDays: Int,
        val memoryObservationMinConfidence: Double,
        val memoryPromoteRepeatThreshold: Int,
        val memoryProjectFactPromoteRepeatThreshold: Int,
        val memoryWorkingMemorySlotsPerProject: Int,
        val memoryObservationDailyCap: Int,
        val memoryPromotedNodesDailyCap: Int,
        val memoryDreamEnabled: Boolean,
        val memoryAutoMaintenanceEnabled: Boolean,
        val memoryDreamDailyLimit: Int,
        val memoryDreamIdleHours: Int,
        val memoryDreamBatchMaxNodes: Int,
        val memoryDreamDryRunDailyLimit: Int,
        val memoryLongIdlePauseDays: Int,
        val memoryRecycleRetentionDays: Int,
        val memoryDreamRecallMaxTraces: Int,
        val memoryMaintenanceAggressiveness: String,
        val memorySelfEnabled: Boolean,
        val memorySelfDirectUpdateEnabled: Boolean,
        val memorySelfRecallMaxNodes: Int,
        val memorySelfPromoteRepeatThreshold: Int,
        val memoryModelRecallEnabled: Boolean,
        val memoryRecallModelUrl: String,
        val memoryRecallModelKey: String,
        val memoryRecallModelModel: String,
        val memoryModelRecallFailureThreshold: Int,
        val memoryModelRecallCooldownSeconds: Int,
        val memoryModelRecallTraceRetention: Int,
        val memoryTrafficClassifierEnabled: Boolean,
        val memoryToolInternalBypassEnabled: Boolean,
        val memoryUnknownDisableWrite: Boolean,
        val memoryTaskDisableAffect: Boolean
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
                put("memory_buffered_ingestion_enabled", memoryBufferedIngestionEnabled)
                put("memory_observation_retention_days", memoryObservationRetentionDays)
                put("memory_low_confidence_observation_retention_days", memoryLowConfidenceObservationRetentionDays)
                put("memory_observation_min_confidence", memoryObservationMinConfidence)
                put("memory_promote_repeat_threshold", memoryPromoteRepeatThreshold)
                put("memory_project_fact_promote_repeat_threshold", memoryProjectFactPromoteRepeatThreshold)
                put("memory_working_memory_slots_per_project", memoryWorkingMemorySlotsPerProject)
                put("memory_observation_daily_cap", memoryObservationDailyCap)
                put("memory_promoted_nodes_daily_cap", memoryPromotedNodesDailyCap)
                put("memory_dream_enabled", memoryDreamEnabled)
                put("memory_auto_maintenance_enabled", memoryAutoMaintenanceEnabled)
                put("memory_dream_daily_limit", memoryDreamDailyLimit)
                put("memory_dream_idle_hours", memoryDreamIdleHours)
                put("memory_dream_batch_max_nodes", memoryDreamBatchMaxNodes)
                put("memory_dream_dry_run_daily_limit", memoryDreamDryRunDailyLimit)
                put("memory_long_idle_pause_days", memoryLongIdlePauseDays)
                put("memory_recycle_retention_days", memoryRecycleRetentionDays)
                put("memory_dream_recall_max_traces", memoryDreamRecallMaxTraces)
                put("memory_maintenance_aggressiveness", memoryMaintenanceAggressiveness)
                put("memory_self_enabled", memorySelfEnabled)
                put("memory_self_direct_update_enabled", memorySelfDirectUpdateEnabled)
                put("memory_self_recall_max_nodes", memorySelfRecallMaxNodes)
                put("memory_self_promote_repeat_threshold", memorySelfPromoteRepeatThreshold)
                put("memory_model_recall_enabled", memoryModelRecallEnabled)
                put("memory_recall_model_url", memoryRecallModelUrl)
                put("memory_recall_model_key", memoryRecallModelKey)
                put("memory_recall_model_model", memoryRecallModelModel)
                put("memory_model_recall_failure_threshold", memoryModelRecallFailureThreshold)
                put("memory_model_recall_cooldown_seconds", memoryModelRecallCooldownSeconds)
                put("memory_model_recall_trace_retention", memoryModelRecallTraceRetention)
                put("memory_traffic_classifier_enabled", memoryTrafficClassifierEnabled)
                put("memory_tool_internal_bypass_enabled", memoryToolInternalBypassEnabled)
                put("memory_unknown_disable_write", memoryUnknownDisableWrite)
                put("memory_task_disable_affect", memoryTaskDisableAffect)
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
            memoryPersonContextMaxClues = memoryPersonContextMaxClues,
            memoryBufferedIngestionEnabled = memoryBufferedIngestionEnabled,
            memoryObservationRetentionDays = memoryObservationRetentionDays,
            memoryLowConfidenceObservationRetentionDays = memoryLowConfidenceObservationRetentionDays,
            memoryObservationMinConfidence = memoryObservationMinConfidence,
            memoryPromoteRepeatThreshold = memoryPromoteRepeatThreshold,
            memoryProjectFactPromoteRepeatThreshold = memoryProjectFactPromoteRepeatThreshold,
            memoryWorkingMemorySlotsPerProject = memoryWorkingMemorySlotsPerProject,
            memoryObservationDailyCap = memoryObservationDailyCap,
            memoryPromotedNodesDailyCap = memoryPromotedNodesDailyCap,
            memoryDreamEnabled = memoryDreamEnabled,
            memoryAutoMaintenanceEnabled = memoryAutoMaintenanceEnabled,
            memoryDreamDailyLimit = memoryDreamDailyLimit,
            memoryDreamIdleHours = memoryDreamIdleHours,
            memoryDreamBatchMaxNodes = memoryDreamBatchMaxNodes,
            memoryDreamDryRunDailyLimit = memoryDreamDryRunDailyLimit,
            memoryLongIdlePauseDays = memoryLongIdlePauseDays,
            memoryRecycleRetentionDays = memoryRecycleRetentionDays,
            memoryDreamRecallMaxTraces = memoryDreamRecallMaxTraces,
            memoryMaintenanceAggressiveness = memoryMaintenanceAggressiveness,
            memorySelfEnabled = memorySelfEnabled,
            memorySelfDirectUpdateEnabled = memorySelfDirectUpdateEnabled,
            memorySelfRecallMaxNodes = memorySelfRecallMaxNodes,
            memorySelfPromoteRepeatThreshold = memorySelfPromoteRepeatThreshold,
            memoryModelRecallEnabled = memoryModelRecallEnabled,
            memoryRecallModelUrl = memoryRecallModelUrl,
            memoryRecallModelKey = memoryRecallModelKey,
            memoryRecallModelModel = memoryRecallModelModel,
            memoryModelRecallFailureThreshold = memoryModelRecallFailureThreshold,
            memoryModelRecallCooldownSeconds = memoryModelRecallCooldownSeconds,
            memoryModelRecallTraceRetention = memoryModelRecallTraceRetention,
            memoryTrafficClassifierEnabled = memoryTrafficClassifierEnabled,
            memoryToolInternalBypassEnabled = memoryToolInternalBypassEnabled,
            memoryUnknownDisableWrite = memoryUnknownDisableWrite,
            memoryTaskDisableAffect = memoryTaskDisableAffect
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
        memoryBufferedIngestionEnabled = snapshot.memoryBufferedIngestionEnabled
        memoryObservationRetentionDays = snapshot.memoryObservationRetentionDays
        memoryLowConfidenceObservationRetentionDays = snapshot.memoryLowConfidenceObservationRetentionDays
        memoryObservationMinConfidence = snapshot.memoryObservationMinConfidence
        memoryPromoteRepeatThreshold = snapshot.memoryPromoteRepeatThreshold
        memoryProjectFactPromoteRepeatThreshold = snapshot.memoryProjectFactPromoteRepeatThreshold
        memoryWorkingMemorySlotsPerProject = snapshot.memoryWorkingMemorySlotsPerProject
        memoryObservationDailyCap = snapshot.memoryObservationDailyCap
        memoryPromotedNodesDailyCap = snapshot.memoryPromotedNodesDailyCap
        memoryDreamEnabled = snapshot.memoryDreamEnabled
        memoryAutoMaintenanceEnabled = snapshot.memoryAutoMaintenanceEnabled
        memoryDreamDailyLimit = snapshot.memoryDreamDailyLimit
        memoryDreamIdleHours = snapshot.memoryDreamIdleHours
        memoryDreamBatchMaxNodes = snapshot.memoryDreamBatchMaxNodes
        memoryDreamDryRunDailyLimit = snapshot.memoryDreamDryRunDailyLimit
        memoryLongIdlePauseDays = snapshot.memoryLongIdlePauseDays
        memoryRecycleRetentionDays = snapshot.memoryRecycleRetentionDays
        memoryDreamRecallMaxTraces = snapshot.memoryDreamRecallMaxTraces
        memoryMaintenanceAggressiveness = snapshot.memoryMaintenanceAggressiveness
        memorySelfEnabled = snapshot.memorySelfEnabled
        memorySelfDirectUpdateEnabled = snapshot.memorySelfDirectUpdateEnabled
        memorySelfRecallMaxNodes = snapshot.memorySelfRecallMaxNodes
        memorySelfPromoteRepeatThreshold = snapshot.memorySelfPromoteRepeatThreshold
        memoryModelRecallEnabled = snapshot.memoryModelRecallEnabled
        memoryRecallModelUrl = snapshot.memoryRecallModelUrl
        memoryRecallModelKey = snapshot.memoryRecallModelKey
        memoryRecallModelModel = snapshot.memoryRecallModelModel
        memoryModelRecallFailureThreshold = snapshot.memoryModelRecallFailureThreshold
        memoryModelRecallCooldownSeconds = snapshot.memoryModelRecallCooldownSeconds
        memoryModelRecallTraceRetention = snapshot.memoryModelRecallTraceRetention
        memoryTrafficClassifierEnabled = snapshot.memoryTrafficClassifierEnabled
        memoryToolInternalBypassEnabled = snapshot.memoryToolInternalBypassEnabled
        memoryUnknownDisableWrite = snapshot.memoryUnknownDisableWrite
        memoryTaskDisableAffect = snapshot.memoryTaskDisableAffect
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
                    memoryPersonContextMaxClues = body.intValue("memory_person_context_max_clues") ?: memoryPersonContextMaxClues,
                    memoryBufferedIngestionEnabled = body.booleanValue("memory_buffered_ingestion_enabled") ?: memoryBufferedIngestionEnabled,
                    memoryObservationRetentionDays = body.intValue("memory_observation_retention_days") ?: memoryObservationRetentionDays,
                    memoryLowConfidenceObservationRetentionDays = body.intValue("memory_low_confidence_observation_retention_days") ?: memoryLowConfidenceObservationRetentionDays,
                    memoryObservationMinConfidence = body.doubleValue("memory_observation_min_confidence") ?: memoryObservationMinConfidence,
                    memoryPromoteRepeatThreshold = body.intValue("memory_promote_repeat_threshold") ?: memoryPromoteRepeatThreshold,
                    memoryProjectFactPromoteRepeatThreshold = body.intValue("memory_project_fact_promote_repeat_threshold") ?: memoryProjectFactPromoteRepeatThreshold,
                    memoryWorkingMemorySlotsPerProject = body.intValue("memory_working_memory_slots_per_project") ?: memoryWorkingMemorySlotsPerProject,
                    memoryObservationDailyCap = body.intValue("memory_observation_daily_cap") ?: memoryObservationDailyCap,
                    memoryPromotedNodesDailyCap = body.intValue("memory_promoted_nodes_daily_cap") ?: memoryPromotedNodesDailyCap,
                    memoryDreamEnabled = body.booleanValue("memory_dream_enabled") ?: memoryDreamEnabled,
                    memoryAutoMaintenanceEnabled = body.booleanValue("memory_auto_maintenance_enabled") ?: memoryAutoMaintenanceEnabled,
                    memoryDreamDailyLimit = body.intValue("memory_dream_daily_limit") ?: memoryDreamDailyLimit,
                    memoryDreamIdleHours = body.intValue("memory_dream_idle_hours") ?: memoryDreamIdleHours,
                    memoryDreamBatchMaxNodes = body.intValue("memory_dream_batch_max_nodes") ?: memoryDreamBatchMaxNodes,
                    memoryDreamDryRunDailyLimit = body.intValue("memory_dream_dry_run_daily_limit") ?: memoryDreamDryRunDailyLimit,
                    memoryLongIdlePauseDays = body.intValue("memory_long_idle_pause_days") ?: memoryLongIdlePauseDays,
                    memoryRecycleRetentionDays = body.intValue("memory_recycle_retention_days") ?: memoryRecycleRetentionDays,
                    memoryDreamRecallMaxTraces = body.intValue("memory_dream_recall_max_traces") ?: memoryDreamRecallMaxTraces,
                    memoryMaintenanceAggressiveness = body.stringValue("memory_maintenance_aggressiveness")
                        ?.trim()
                        ?.lowercase()
                        ?.takeIf { it in setOf("standard", "aggressive") }
                        ?: memoryMaintenanceAggressiveness,
                    memorySelfEnabled = body.booleanValue("memory_self_enabled") ?: memorySelfEnabled,
                    memorySelfDirectUpdateEnabled = body.booleanValue("memory_self_direct_update_enabled") ?: memorySelfDirectUpdateEnabled,
                    memorySelfRecallMaxNodes = body.intValue("memory_self_recall_max_nodes") ?: memorySelfRecallMaxNodes,
                    memorySelfPromoteRepeatThreshold = body.intValue("memory_self_promote_repeat_threshold") ?: memorySelfPromoteRepeatThreshold,
                    memoryModelRecallEnabled = body.booleanValue("memory_model_recall_enabled") ?: memoryModelRecallEnabled,
                    memoryRecallModelUrl = body.stringValue("memory_recall_model_url")
                        ?.let { sanitizePersistedUrl(it, "memory_recall_model_url") }
                        ?: memoryRecallModelUrl,
                    memoryRecallModelKey = body.stringValue("memory_recall_model_key") ?: memoryRecallModelKey,
                    memoryRecallModelModel = body.stringValue("memory_recall_model_model") ?: memoryRecallModelModel,
                    memoryModelRecallFailureThreshold = body.intValue("memory_model_recall_failure_threshold") ?: memoryModelRecallFailureThreshold,
                    memoryModelRecallCooldownSeconds = body.intValue("memory_model_recall_cooldown_seconds") ?: memoryModelRecallCooldownSeconds,
                    memoryModelRecallTraceRetention = body.intValue("memory_model_recall_trace_retention") ?: memoryModelRecallTraceRetention,
                    memoryTrafficClassifierEnabled = body.booleanValue("memory_traffic_classifier_enabled") ?: memoryTrafficClassifierEnabled,
                    memoryToolInternalBypassEnabled = body.booleanValue("memory_tool_internal_bypass_enabled") ?: memoryToolInternalBypassEnabled,
                    memoryUnknownDisableWrite = body.booleanValue("memory_unknown_disable_write") ?: memoryUnknownDisableWrite,
                    memoryTaskDisableAffect = body.booleanValue("memory_task_disable_affect") ?: memoryTaskDisableAffect
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
