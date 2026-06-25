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
            - Do not create observations from framework, system, developer, tool-maintenance, skill-management, memory-management, or agent-instruction prompts. Treat them as execution context, not user memory.
            - Ignore instructions about how an agent should use tools, update skills, manage protected skills, call memory tools, shape a skill library, or decide whether "Nothing to save." applies.
            - If the exchange is only framework/tool instructions and contains no durable user fact, preference, relationship, project state, or explicit remember request, return an empty observations array.
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

    // scoreNode weights — env-only tunables (not persisted in Snapshot, not exposed via ConfigApi).
    // Defaults reproduce the prior hardcoded values in MemoryService.scoreNode exactly.
    private val memoryKindBiasIdentityRef = AtomicReference(System.getenv("MEMORY_KIND_BIAS_IDENTITY")?.toDoubleOrNull() ?: 0.35)
    private val memoryKindBiasPreferenceRef = AtomicReference(System.getenv("MEMORY_KIND_BIAS_PREFERENCE")?.toDoubleOrNull() ?: 0.30)
    private val memoryKindBiasRelationshipRef = AtomicReference(System.getenv("MEMORY_KIND_BIAS_RELATIONSHIP")?.toDoubleOrNull() ?: 0.28)
    private val memoryKindBiasProjectFactRef = AtomicReference(System.getenv("MEMORY_KIND_BIAS_PROJECT_FACT")?.toDoubleOrNull() ?: 0.12)
    private val memoryKindBiasEpisodicEventRef = AtomicReference(System.getenv("MEMORY_KIND_BIAS_EPISODIC_EVENT")?.toDoubleOrNull() ?: 0.08)
    private val memoryKindBiasWorkingMemoryRef = AtomicReference(System.getenv("MEMORY_KIND_BIAS_WORKING_MEMORY")?.toDoubleOrNull() ?: 0.06)
    private val memoryKindBiasDefaultRef = AtomicReference(System.getenv("MEMORY_KIND_BIAS_DEFAULT")?.toDoubleOrNull() ?: 0.10)
    private val memoryScoreOverlapWeightRef = AtomicReference(System.getenv("MEMORY_SCORE_OVERLAP_WEIGHT")?.toDoubleOrNull() ?: 2.6)
    private val memoryScoreTriggerHitsWeightRef = AtomicReference(System.getenv("MEMORY_SCORE_TRIGGER_HITS_WEIGHT")?.toDoubleOrNull() ?: 0.35)
    private val memoryScoreUriHitsWeightRef = AtomicReference(System.getenv("MEMORY_SCORE_URI_HITS_WEIGHT")?.toDoubleOrNull() ?: 0.18)
    private val memoryScorePriorityWeightRef = AtomicReference(System.getenv("MEMORY_SCORE_PRIORITY_WEIGHT")?.toDoubleOrNull() ?: 0.55)
    private val memoryScoreConfidenceWeightRef = AtomicReference(System.getenv("MEMORY_SCORE_CONFIDENCE_WEIGHT")?.toDoubleOrNull() ?: 0.55)
    private val memoryScoreRecencyWeightRef = AtomicReference(System.getenv("MEMORY_SCORE_RECENCY_WEIGHT")?.toDoubleOrNull() ?: 0.9)
    private val memoryScoreSalienceWeightRef = AtomicReference(System.getenv("MEMORY_SCORE_SALIENCE_WEIGHT")?.toDoubleOrNull() ?: 0.2)
    private val memorySensitivePenaltyRef = AtomicReference(System.getenv("MEMORY_SENSITIVE_PENALTY")?.toDoubleOrNull() ?: -1.2)
    private val memoryNonDeepEpisodicPenaltyRef = AtomicReference(System.getenv("MEMORY_NON_DEEP_EPISODIC_PENALTY")?.toDoubleOrNull() ?: 0.45)
    private val memoryWorkingMemoryStaleDaysRef = AtomicReference(System.getenv("MEMORY_WORKING_MEMORY_STALE_DAYS")?.toDoubleOrNull() ?: 14.0)
    private val memoryWorkingMemoryStalePenaltyRef = AtomicReference(System.getenv("MEMORY_WORKING_MEMORY_STALE_PENALTY")?.toDoubleOrNull() ?: 0.7)

    // per-node stability (FSRS-inspired, env-only) — tunables for self-adapting decay speed.
    // stability multiplies effectiveTauHours; grows on recall boost, regresses toward min when untouched.
    private val memoryStabilityGrowthKRef = AtomicReference(System.getenv("MEMORY_STABILITY_GROWTH_K")?.toDoubleOrNull() ?: 0.6)
    private val memoryStabilityMinRef = AtomicReference(System.getenv("MEMORY_STABILITY_MIN")?.toDoubleOrNull() ?: 1.0)
    private val memoryStabilityMaxRef = AtomicReference(System.getenv("MEMORY_STABILITY_MAX")?.toDoubleOrNull() ?: 8.0)
    private val memoryStabilityRegressRateRef = AtomicReference(System.getenv("MEMORY_STABILITY_REGRESS_RATE")?.toDoubleOrNull() ?: 0.05)
    // spacing effect (FSRS): when a node is recalled while nearly forgotten (low retrievability), stability grows
    // more. multiplier = (1 + spacingK*(1-R)). default 0 => multiplier 1 => no spacing, behavior identical to round 1.
    private val memoryStabilitySpacingKRef = AtomicReference(System.getenv("MEMORY_STABILITY_SPACING_K")?.toDoubleOrNull() ?: 0.0)
    // stabilization decay (FSRS): the larger stability already is, the smaller each recall's incremental growth.
    // multiplier = (1 - decayK * stability/(stability+1)). default 0 => multiplier 1 => no decay, behavior identical to round 1.
    private val memoryStabilityStabilizationDecayRef = AtomicReference(System.getenv("MEMORY_STABILITY_STABILIZATION_DECAY")?.toDoubleOrNull() ?: 0.0)
    // working_memory→factual consolidation: when a project's active working_memory node count reaches this
    // fraction of memoryWorkingMemorySlotsPerProject, buildMaintenanceSuggestions emits a "consolidate"
    // suggestion feeding create_consolidated_node. default 1.0 = only at full slot capacity.
    private val memoryConsolidationWmThresholdRef = AtomicReference(System.getenv("MEMORY_CONSOLIDATION_WM_THRESHOLD")?.toDoubleOrNull() ?: 1.0)
    // B-档 feedback signal (env-only): detect user corrections to previously-injected memories and penalize
    // their stability. All default-off / default-safe so behavior is identical to round 1 until enabled.
    private val memoryFeedbackCorrectionEnabledRef = AtomicReference(System.getenv("MEMORY_FEEDBACK_CORRECTION_ENABLED") == "1")
    private val memoryFeedbackCorrectionPatternsRef = AtomicReference(
        System.getenv("MEMORY_FEEDBACK_CORRECTION_PATTERNS")
            ?.takeIf { it.isNotBlank() }
            ?: "不是这个;不是这样;记错了;我没说过;你记错了;不对;that's not right;I never said that;you remembered it wrong;wrong"
    )
    private val memoryFeedbackPenaltyKRef = AtomicReference(System.getenv("MEMORY_FEEDBACK_PENALTY_K")?.toDoubleOrNull() ?: 0.3)
    private val memoryFeedbackResolveLookbackHoursRef = AtomicReference(System.getenv("MEMORY_FEEDBACK_RESOLVE_LOOKBACK_HOURS")?.toDoubleOrNull() ?: 48.0)

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
    private val memoryLocalRecallEnhancedEnabledRef = AtomicReference(System.getenv("MEMORY_LOCAL_RECALL_ENHANCED_ENABLED") != "0")
    private val memoryTagGraphEnabledRef = AtomicReference(System.getenv("MEMORY_TAG_GRAPH_ENABLED") != "0")
    private val memoryTagGraphMaxExpandedTermsRef = AtomicInteger(System.getenv("MEMORY_TAG_GRAPH_MAX_EXPANDED_TERMS")?.toIntOrNull() ?: 16)
    private val memoryTimelineRecallEnabledRef = AtomicReference(System.getenv("MEMORY_TIMELINE_RECALL_ENABLED") != "0")
    private val memorySummarySanitizeInternalPromptsRef = AtomicReference(System.getenv("MEMORY_SUMMARY_SANITIZE_INTERNAL_PROMPTS") != "0")

    // 话题 (Topics) — proxy-side chat-starter prompts. Generated by the consolidation model after dream,
    // capped by unused-slot count, consumed only on a "user wants to switch topic" signal. Upstream AI never operates.
    private val memoryTopicEnabledRef = AtomicReference(System.getenv("MEMORY_TOPIC_ENABLED") == "1")
    // 设定与锚定记忆 (Pinned/stable user memory) — user-pinned nodes exempt from all automatic
    // decay/archive/delete, injected as a dedicated durable-context prompt section. Default on.
    private val memoryPinnedEnabledRef = AtomicReference(System.getenv("MEMORY_PINNED_ENABLED")?.let { it != "0" } ?: true)
    private val memoryTopicUnusedSlotCapRef = AtomicInteger(System.getenv("MEMORY_TOPIC_UNUSED_SLOT_CAP")?.toIntOrNull() ?: 5)
    private val memoryTopicCandidatePoolRef = AtomicInteger(System.getenv("MEMORY_TOPIC_CANDIDATE_POOL")?.toIntOrNull() ?: 20)
    private val memoryTopicLruWindowRef = AtomicInteger(System.getenv("MEMORY_TOPIC_LRU_WINDOW")?.toIntOrNull() ?: 20)
    private val memoryTopicUsedRetentionDaysRef = AtomicInteger(System.getenv("MEMORY_TOPIC_USED_RETENTION_DAYS")?.toIntOrNull() ?: 30)
    private val memoryTopicDailyLimitRef = AtomicInteger(System.getenv("MEMORY_TOPIC_DAILY_LIMIT")?.toIntOrNull() ?: 4)
    private val memoryTopicSwitchKeywordsRef = AtomicReference(
        System.getenv("MEMORY_TOPIC_SWITCH_KEYWORDS")?.takeIf { it.isNotBlank() }
            ?: "聊点什么;聊点什么吧;换话题;换个话题;有什么聊的;有什么可聊的;无聊了;无聊;不知道聊什么;想聊天;随便聊点;talk about something;let's talk about;anything to talk about;bored;something to chat about;change the topic;switch topic"
    )
    // lightweight "wants topic switch" detection: only run the model judge when suspected.
    // suspected = keyword-table hit OR conversation-coldness heuristic. Length is NOT used as a signal.
    private val memoryTopicColdRoundsRef = AtomicInteger(System.getenv("MEMORY_TOPIC_COLD_ROUNDS")?.toIntOrNull() ?: 3)
    private val memoryTopicSwitchJudgePromptRef = AtomicReference(
        System.getenv("MEMORY_TOPIC_SWITCH_JUDGE_PROMPT")?.takeIf { it.isNotBlank() } ?: """
            You are a lightweight intent classifier for a companion chat. Decide whether the user is asking to change / start a topic or wants something to talk about.
            Answer with ONLY a JSON object: {"want_topic_switch": true} or {"want_topic_switch": false}.
            Return true only if the user clearly wants to switch or start a conversation topic now (e.g. "聊点什么吧", "换话题", "好无聊", "anything to talk about"). False if they are continuing the current topic, asking a factual question, or giving instructions. Output only JSON. No markdown.
        """.trimIndent()
    )

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

    // scoreNode weights — env-only tunables. See refs above.
    var memoryKindBiasIdentity: Double
        get() = memoryKindBiasIdentityRef.get()
        set(value) { memoryKindBiasIdentityRef.set(value) }
    var memoryKindBiasPreference: Double
        get() = memoryKindBiasPreferenceRef.get()
        set(value) { memoryKindBiasPreferenceRef.set(value) }
    var memoryKindBiasRelationship: Double
        get() = memoryKindBiasRelationshipRef.get()
        set(value) { memoryKindBiasRelationshipRef.set(value) }
    var memoryKindBiasProjectFact: Double
        get() = memoryKindBiasProjectFactRef.get()
        set(value) { memoryKindBiasProjectFactRef.set(value) }
    var memoryKindBiasEpisodicEvent: Double
        get() = memoryKindBiasEpisodicEventRef.get()
        set(value) { memoryKindBiasEpisodicEventRef.set(value) }
    var memoryKindBiasWorkingMemory: Double
        get() = memoryKindBiasWorkingMemoryRef.get()
        set(value) { memoryKindBiasWorkingMemoryRef.set(value) }
    var memoryKindBiasDefault: Double
        get() = memoryKindBiasDefaultRef.get()
        set(value) { memoryKindBiasDefaultRef.set(value) }
    var memoryScoreOverlapWeight: Double
        get() = memoryScoreOverlapWeightRef.get()
        set(value) { memoryScoreOverlapWeightRef.set(value) }
    var memoryScoreTriggerHitsWeight: Double
        get() = memoryScoreTriggerHitsWeightRef.get()
        set(value) { memoryScoreTriggerHitsWeightRef.set(value) }
    var memoryScoreUriHitsWeight: Double
        get() = memoryScoreUriHitsWeightRef.get()
        set(value) { memoryScoreUriHitsWeightRef.set(value) }
    var memoryScorePriorityWeight: Double
        get() = memoryScorePriorityWeightRef.get()
        set(value) { memoryScorePriorityWeightRef.set(value) }
    var memoryScoreConfidenceWeight: Double
        get() = memoryScoreConfidenceWeightRef.get()
        set(value) { memoryScoreConfidenceWeightRef.set(value) }
    var memoryScoreRecencyWeight: Double
        get() = memoryScoreRecencyWeightRef.get()
        set(value) { memoryScoreRecencyWeightRef.set(value) }
    var memoryScoreSalienceWeight: Double
        get() = memoryScoreSalienceWeightRef.get()
        set(value) { memoryScoreSalienceWeightRef.set(value) }
    var memorySensitivePenalty: Double
        get() = memorySensitivePenaltyRef.get()
        set(value) { memorySensitivePenaltyRef.set(value) }
    var memoryNonDeepEpisodicPenalty: Double
        get() = memoryNonDeepEpisodicPenaltyRef.get()
        set(value) { memoryNonDeepEpisodicPenaltyRef.set(value) }
    var memoryWorkingMemoryStaleDays: Double
        get() = memoryWorkingMemoryStaleDaysRef.get()
        set(value) { memoryWorkingMemoryStaleDaysRef.set(value) }
    var memoryWorkingMemoryStalePenalty: Double
        get() = memoryWorkingMemoryStalePenaltyRef.get()
        set(value) { memoryWorkingMemoryStalePenaltyRef.set(value) }
    // per-node stability tunables — see refs above.
    var memoryStabilityGrowthK: Double
        get() = memoryStabilityGrowthKRef.get()
        set(value) { memoryStabilityGrowthKRef.set(value) }
    var memoryStabilityMin: Double
        get() = memoryStabilityMinRef.get()
        set(value) { memoryStabilityMinRef.set(value) }
    var memoryStabilityMax: Double
        get() = memoryStabilityMaxRef.get()
        set(value) { memoryStabilityMaxRef.set(value) }
    var memoryStabilityRegressRate: Double
        get() = memoryStabilityRegressRateRef.get()
        set(value) { memoryStabilityRegressRateRef.set(value) }
    var memoryStabilitySpacingK: Double
        get() = memoryStabilitySpacingKRef.get()
        set(value) { memoryStabilitySpacingKRef.set(value) }
    var memoryStabilityStabilizationDecay: Double
        get() = memoryStabilityStabilizationDecayRef.get()
        set(value) { memoryStabilityStabilizationDecayRef.set(value) }
    var memoryConsolidationWmThreshold: Double
        get() = memoryConsolidationWmThresholdRef.get()
        set(value) { memoryConsolidationWmThresholdRef.set(value) }
    var memoryFeedbackCorrectionEnabled: Boolean
        get() = memoryFeedbackCorrectionEnabledRef.get()
        set(value) { memoryFeedbackCorrectionEnabledRef.set(value) }
    var memoryFeedbackCorrectionPatterns: String
        get() = memoryFeedbackCorrectionPatternsRef.get()
        set(value) { memoryFeedbackCorrectionPatternsRef.set(value) }
    var memoryFeedbackPenaltyK: Double
        get() = memoryFeedbackPenaltyKRef.get()
        set(value) { memoryFeedbackPenaltyKRef.set(value) }
    var memoryFeedbackResolveLookbackHours: Double
        get() = memoryFeedbackResolveLookbackHoursRef.get()
        set(value) { memoryFeedbackResolveLookbackHoursRef.set(value) }

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

    var memoryLocalRecallEnhancedEnabled: Boolean
        get() = memoryLocalRecallEnhancedEnabledRef.get()
        set(value) { memoryLocalRecallEnhancedEnabledRef.set(value) }

    var memoryTagGraphEnabled: Boolean
        get() = memoryTagGraphEnabledRef.get()
        set(value) { memoryTagGraphEnabledRef.set(value) }

    var memoryTagGraphMaxExpandedTerms: Int
        get() = memoryTagGraphMaxExpandedTermsRef.get()
        set(value) { memoryTagGraphMaxExpandedTermsRef.set(value.coerceIn(0, 128)) }

    var memoryTimelineRecallEnabled: Boolean
        get() = memoryTimelineRecallEnabledRef.get()
        set(value) { memoryTimelineRecallEnabledRef.set(value) }

    var memorySummarySanitizeInternalPrompts: Boolean
        get() = memorySummarySanitizeInternalPromptsRef.get()
        set(value) { memorySummarySanitizeInternalPromptsRef.set(value) }

    // 话题 (Topics) knobs.
    var memoryTopicEnabled: Boolean
        get() = memoryTopicEnabledRef.get()
        set(value) { memoryTopicEnabledRef.set(value) }
    var memoryPinnedEnabled: Boolean
        get() = memoryPinnedEnabledRef.get()
        set(value) { memoryPinnedEnabledRef.set(value) }
    var memoryTopicUnusedSlotCap: Int
        get() = memoryTopicUnusedSlotCapRef.get()
        set(value) { memoryTopicUnusedSlotCapRef.set(value.coerceIn(0, 64)) }
    var memoryTopicCandidatePool: Int
        get() = memoryTopicCandidatePoolRef.get()
        set(value) { memoryTopicCandidatePoolRef.set(value.coerceIn(2, 200)) }
    var memoryTopicLruWindow: Int
        get() = memoryTopicLruWindowRef.get()
        set(value) { memoryTopicLruWindowRef.set(value.coerceIn(0, 200)) }
    var memoryTopicUsedRetentionDays: Int
        get() = memoryTopicUsedRetentionDaysRef.get()
        set(value) { memoryTopicUsedRetentionDaysRef.set(value.coerceAtLeast(1)) }
    var memoryTopicDailyLimit: Int
        get() = memoryTopicDailyLimitRef.get()
        set(value) { memoryTopicDailyLimitRef.set(value.coerceAtLeast(0)) }
    var memoryTopicSwitchKeywords: String
        get() = memoryTopicSwitchKeywordsRef.get()
        set(value) { memoryTopicSwitchKeywordsRef.set(value) }
    var memoryTopicColdRounds: Int
        get() = memoryTopicColdRoundsRef.get()
        set(value) { memoryTopicColdRoundsRef.set(value.coerceAtLeast(1)) }
    var memoryTopicSwitchJudgePrompt: String
        get() = memoryTopicSwitchJudgePromptRef.get()
        set(value) { memoryTopicSwitchJudgePromptRef.set(value) }

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
        val memoryLocalRecallEnhancedEnabled: Boolean,
        val memoryTagGraphEnabled: Boolean,
        val memoryTagGraphMaxExpandedTerms: Int,
        val memoryTimelineRecallEnabled: Boolean,
        val memorySummarySanitizeInternalPrompts: Boolean,
        val memoryTopicEnabled: Boolean,
        val memoryPinnedEnabled: Boolean,
        val memoryTopicUnusedSlotCap: Int,
        val memoryTopicCandidatePool: Int,
        val memoryTopicLruWindow: Int,
        val memoryTopicUsedRetentionDays: Int,
        val memoryTopicDailyLimit: Int,
        val memoryTopicSwitchKeywords: String,
        val memoryTopicColdRounds: Int,
        val memoryTopicSwitchJudgePrompt: String
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
                put("memory_local_recall_enhanced_enabled", memoryLocalRecallEnhancedEnabled)
                put("memory_tag_graph_enabled", memoryTagGraphEnabled)
                put("memory_tag_graph_max_expanded_terms", memoryTagGraphMaxExpandedTerms)
                put("memory_timeline_recall_enabled", memoryTimelineRecallEnabled)
                put("memory_summary_sanitize_internal_prompts", memorySummarySanitizeInternalPrompts)
                put("memory_topic_enabled", memoryTopicEnabled)
                put("memory_pinned_enabled", memoryPinnedEnabled)
                put("memory_topic_unused_slot_cap", memoryTopicUnusedSlotCap)
                put("memory_topic_candidate_pool", memoryTopicCandidatePool)
                put("memory_topic_lru_window", memoryTopicLruWindow)
                put("memory_topic_used_retention_days", memoryTopicUsedRetentionDays)
                put("memory_topic_daily_limit", memoryTopicDailyLimit)
                put("memory_topic_switch_keywords", memoryTopicSwitchKeywords)
                put("memory_topic_cold_rounds", memoryTopicColdRounds)
                put("memory_topic_switch_judge_prompt", memoryTopicSwitchJudgePrompt)
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
            memoryLocalRecallEnhancedEnabled = memoryLocalRecallEnhancedEnabled,
            memoryTagGraphEnabled = memoryTagGraphEnabled,
            memoryTagGraphMaxExpandedTerms = memoryTagGraphMaxExpandedTerms,
            memoryTimelineRecallEnabled = memoryTimelineRecallEnabled,
            memorySummarySanitizeInternalPrompts = memorySummarySanitizeInternalPrompts,
            memoryTopicEnabled = memoryTopicEnabled,
            memoryPinnedEnabled = memoryPinnedEnabled,
            memoryTopicUnusedSlotCap = memoryTopicUnusedSlotCap,
            memoryTopicCandidatePool = memoryTopicCandidatePool,
            memoryTopicLruWindow = memoryTopicLruWindow,
            memoryTopicUsedRetentionDays = memoryTopicUsedRetentionDays,
            memoryTopicDailyLimit = memoryTopicDailyLimit,
            memoryTopicSwitchKeywords = memoryTopicSwitchKeywords,
            memoryTopicColdRounds = memoryTopicColdRounds,
            memoryTopicSwitchJudgePrompt = memoryTopicSwitchJudgePrompt
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
        memoryLocalRecallEnhancedEnabled = snapshot.memoryLocalRecallEnhancedEnabled
        memoryTagGraphEnabled = snapshot.memoryTagGraphEnabled
        memoryTagGraphMaxExpandedTerms = snapshot.memoryTagGraphMaxExpandedTerms
        memoryTimelineRecallEnabled = snapshot.memoryTimelineRecallEnabled
        memorySummarySanitizeInternalPrompts = snapshot.memorySummarySanitizeInternalPrompts
        memoryTopicEnabled = snapshot.memoryTopicEnabled
        memoryPinnedEnabled = snapshot.memoryPinnedEnabled
        memoryTopicUnusedSlotCap = snapshot.memoryTopicUnusedSlotCap
        memoryTopicCandidatePool = snapshot.memoryTopicCandidatePool
        memoryTopicLruWindow = snapshot.memoryTopicLruWindow
        memoryTopicUsedRetentionDays = snapshot.memoryTopicUsedRetentionDays
        memoryTopicDailyLimit = snapshot.memoryTopicDailyLimit
        memoryTopicSwitchKeywords = snapshot.memoryTopicSwitchKeywords
        memoryTopicColdRounds = snapshot.memoryTopicColdRounds
        memoryTopicSwitchJudgePrompt = snapshot.memoryTopicSwitchJudgePrompt
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
                    memoryLocalRecallEnhancedEnabled = body.booleanValue("memory_local_recall_enhanced_enabled") ?: memoryLocalRecallEnhancedEnabled,
                    memoryTagGraphEnabled = body.booleanValue("memory_tag_graph_enabled") ?: memoryTagGraphEnabled,
                    memoryTagGraphMaxExpandedTerms = body.intValue("memory_tag_graph_max_expanded_terms")
                        ?.coerceIn(0, 128)
                        ?: memoryTagGraphMaxExpandedTerms,
                    memoryTimelineRecallEnabled = body.booleanValue("memory_timeline_recall_enabled") ?: memoryTimelineRecallEnabled,
                    memorySummarySanitizeInternalPrompts = body.booleanValue("memory_summary_sanitize_internal_prompts") ?: memorySummarySanitizeInternalPrompts,
                    memoryTopicEnabled = body.booleanValue("memory_topic_enabled") ?: memoryTopicEnabled,
                    memoryPinnedEnabled = body.booleanValue("memory_pinned_enabled") ?: memoryPinnedEnabled,
                    memoryTopicUnusedSlotCap = body.intValue("memory_topic_unused_slot_cap") ?: memoryTopicUnusedSlotCap,
                    memoryTopicCandidatePool = body.intValue("memory_topic_candidate_pool") ?: memoryTopicCandidatePool,
                    memoryTopicLruWindow = body.intValue("memory_topic_lru_window") ?: memoryTopicLruWindow,
                    memoryTopicUsedRetentionDays = body.intValue("memory_topic_used_retention_days") ?: memoryTopicUsedRetentionDays,
                    memoryTopicDailyLimit = body.intValue("memory_topic_daily_limit") ?: memoryTopicDailyLimit,
                    memoryTopicSwitchKeywords = body.stringValue("memory_topic_switch_keywords") ?: memoryTopicSwitchKeywords,
                    memoryTopicColdRounds = body.intValue("memory_topic_cold_rounds") ?: memoryTopicColdRounds,
                    memoryTopicSwitchJudgePrompt = body.stringValue("memory_topic_switch_judge_prompt") ?: memoryTopicSwitchJudgePrompt
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
