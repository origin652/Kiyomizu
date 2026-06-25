package hifumi.kiyomizu

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

object ConfigApi {
    data class UpdateResult(
        val errors: List<String>,
        val responseBody: JsonObject = publicConfigJson()
    )

    fun publicConfigJson(): JsonObject {
        return buildJsonObject {
            put("preset", Config.preset)
            put("upstream", Config.upstream)
            put("cache_ttl", Config.cacheTtl)
            put("cache_mode", Config.cacheMode)
            put("cache_strategy", Config.cacheStrategy)
            put("cache_breakpoints", Config.cacheBreakpoints)

            put("memory_enabled", Config.memoryEnabled)
            put("memory_summary_url", Config.memorySummaryUrl)
            put("memory_summary_key_configured", Config.memorySummaryKey.isNotBlank())
            put("memory_summary_model", Config.memorySummaryModel)
            put("memory_summary_prompt", Config.memorySummaryPrompt)
            put("memory_decay_interval_hours", Config.memoryDecayIntervalHours)
            put("memory_decay_rate", Config.memoryDecayRate)
            put("memory_threshold", Config.memoryThreshold)
            put("memory_recovery_amount", Config.memoryRecoveryAmount)
            put("memory_max_strength", Config.memoryMaxStrength)
            put("memory_initial_strength", Config.memoryInitialStrength)
            put("intimacy_decay_rate", Config.intimacyDecayRate)
            put("trust_down_scale", Config.trustDownScale)
            put("trust_up_scale", Config.trustUpScale)
            put("memory_decay_tau_hours", Config.memoryDecayTauHours)
            put("memory_salience_k", Config.memorySalienceK)
            put("memory_recall_max_nodes", Config.memoryRecallMaxNodes)
            put("memory_deep_recall_enabled", Config.memoryDeepRecallEnabled)
            put("memory_deep_recall_max_candidates", Config.memoryDeepRecallMaxCandidates)
            put("memory_deep_recall_max_clues", Config.memoryDeepRecallMaxClues)
            put("memory_person_context_max_clues", Config.memoryPersonContextMaxClues)
            put("memory_buffered_ingestion_enabled", Config.memoryBufferedIngestionEnabled)
            put("memory_observation_retention_days", Config.memoryObservationRetentionDays)
            put("memory_low_confidence_observation_retention_days", Config.memoryLowConfidenceObservationRetentionDays)
            put("memory_observation_min_confidence", Config.memoryObservationMinConfidence)
            put("memory_promote_repeat_threshold", Config.memoryPromoteRepeatThreshold)
            put("memory_project_fact_promote_repeat_threshold", Config.memoryProjectFactPromoteRepeatThreshold)
            put("memory_working_memory_slots_per_project", Config.memoryWorkingMemorySlotsPerProject)
            put("memory_observation_daily_cap", Config.memoryObservationDailyCap)
            put("memory_promoted_nodes_daily_cap", Config.memoryPromotedNodesDailyCap)
            put("memory_dream_enabled", Config.memoryDreamEnabled)
            put("memory_auto_maintenance_enabled", Config.memoryAutoMaintenanceEnabled)
            put("memory_dream_daily_limit", Config.memoryDreamDailyLimit)
            put("memory_dream_idle_hours", Config.memoryDreamIdleHours)
            put("memory_dream_batch_max_nodes", Config.memoryDreamBatchMaxNodes)
            put("memory_dream_dry_run_daily_limit", Config.memoryDreamDryRunDailyLimit)
            put("memory_long_idle_pause_days", Config.memoryLongIdlePauseDays)
            put("memory_recycle_retention_days", Config.memoryRecycleRetentionDays)
            put("memory_dream_recall_max_traces", Config.memoryDreamRecallMaxTraces)
            put("memory_maintenance_aggressiveness", Config.memoryMaintenanceAggressiveness)
            put("memory_self_enabled", Config.memorySelfEnabled)
            put("memory_self_direct_update_enabled", Config.memorySelfDirectUpdateEnabled)
            put("memory_self_recall_max_nodes", Config.memorySelfRecallMaxNodes)
            put("memory_self_promote_repeat_threshold", Config.memorySelfPromoteRepeatThreshold)
            put("memory_model_recall_enabled", Config.memoryModelRecallEnabled)
            put("memory_recall_model_url", Config.memoryRecallModelUrl)
            put("memory_recall_model_key_configured", Config.memoryRecallModelKey.isNotBlank())
            put("memory_recall_model_model", Config.memoryRecallModelModel)
            put("memory_model_recall_failure_threshold", Config.memoryModelRecallFailureThreshold)
            put("memory_model_recall_cooldown_seconds", Config.memoryModelRecallCooldownSeconds)
            put("memory_model_recall_trace_retention", Config.memoryModelRecallTraceRetention)
            put("memory_local_recall_enhanced_enabled", Config.memoryLocalRecallEnhancedEnabled)
            put("memory_tag_graph_enabled", Config.memoryTagGraphEnabled)
            put("memory_tag_graph_max_expanded_terms", Config.memoryTagGraphMaxExpandedTerms)
            put("memory_timeline_recall_enabled", Config.memoryTimelineRecallEnabled)
            put("memory_summary_sanitize_internal_prompts", Config.memorySummarySanitizeInternalPrompts)
            put("memory_topic_enabled", Config.memoryTopicEnabled)
            put("memory_pinned_enabled", Config.memoryPinnedEnabled)
            put("memory_topic_unused_slot_cap", Config.memoryTopicUnusedSlotCap)
            put("memory_topic_candidate_pool", Config.memoryTopicCandidatePool)
            put("memory_topic_lru_window", Config.memoryTopicLruWindow)
            put("memory_topic_used_retention_days", Config.memoryTopicUsedRetentionDays)
            put("memory_topic_daily_limit", Config.memoryTopicDailyLimit)
            put("memory_topic_switch_keywords", Config.memoryTopicSwitchKeywords)
            put("memory_topic_cold_rounds", Config.memoryTopicColdRounds)
            put("memory_topic_switch_judge_prompt", Config.memoryTopicSwitchJudgePrompt)
            put("config_password_changeable", ConfigAuth.isChangeable())
        }
    }

    fun applyUpdate(body: JsonObject?): UpdateResult {
        if (body == null) {
            return UpdateResult(errors = listOf("body must be a JSON object"))
        }

        val errors = mutableListOf<String>()

        var nextPreset = Config.preset
        var nextUpstream = Config.upstream
        var nextCacheTtl = Config.cacheTtl
        var nextCacheMode = Config.cacheMode
        var nextCacheStrategy = Config.cacheStrategy
        var nextCacheBreakpoints = Config.cacheBreakpoints

        var nextMemoryEnabled = Config.memoryEnabled
        var nextMemorySummaryUrl = Config.memorySummaryUrl
        var nextMemorySummaryModel = Config.memorySummaryModel
        var nextMemorySummaryPrompt = Config.memorySummaryPrompt
        var nextMemoryDecayIntervalHours = Config.memoryDecayIntervalHours
        var nextMemoryDecayRate = Config.memoryDecayRate
        var nextMemoryThreshold = Config.memoryThreshold
        var nextMemoryRecoveryAmount = Config.memoryRecoveryAmount
        var nextMemoryMaxStrength = Config.memoryMaxStrength
        var nextMemoryInitialStrength = Config.memoryInitialStrength
        var nextIntimacyDecayRate = Config.intimacyDecayRate
        var nextTrustDownScale = Config.trustDownScale
        var nextTrustUpScale = Config.trustUpScale
        var nextMemoryDecayTauHours = Config.memoryDecayTauHours
        var nextMemorySalienceK = Config.memorySalienceK
        var nextMemoryRecallMaxNodes = Config.memoryRecallMaxNodes
        var nextMemoryDeepRecallEnabled = Config.memoryDeepRecallEnabled
        var nextMemoryDeepRecallMaxCandidates = Config.memoryDeepRecallMaxCandidates
        var nextMemoryDeepRecallMaxClues = Config.memoryDeepRecallMaxClues
        var nextMemoryPersonContextMaxClues = Config.memoryPersonContextMaxClues
        var nextMemoryBufferedIngestionEnabled = Config.memoryBufferedIngestionEnabled
        var nextMemoryObservationRetentionDays = Config.memoryObservationRetentionDays
        var nextMemoryLowConfidenceObservationRetentionDays = Config.memoryLowConfidenceObservationRetentionDays
        var nextMemoryObservationMinConfidence = Config.memoryObservationMinConfidence
        var nextMemoryPromoteRepeatThreshold = Config.memoryPromoteRepeatThreshold
        var nextMemoryProjectFactPromoteRepeatThreshold = Config.memoryProjectFactPromoteRepeatThreshold
        var nextMemoryWorkingMemorySlotsPerProject = Config.memoryWorkingMemorySlotsPerProject
        var nextMemoryObservationDailyCap = Config.memoryObservationDailyCap
        var nextMemoryPromotedNodesDailyCap = Config.memoryPromotedNodesDailyCap
        var nextMemoryDreamEnabled = Config.memoryDreamEnabled
        var nextMemoryAutoMaintenanceEnabled = Config.memoryAutoMaintenanceEnabled
        var nextMemoryDreamDailyLimit = Config.memoryDreamDailyLimit
        var nextMemoryDreamIdleHours = Config.memoryDreamIdleHours
        var nextMemoryDreamBatchMaxNodes = Config.memoryDreamBatchMaxNodes
        var nextMemoryDreamDryRunDailyLimit = Config.memoryDreamDryRunDailyLimit
        var nextMemoryLongIdlePauseDays = Config.memoryLongIdlePauseDays
        var nextMemoryRecycleRetentionDays = Config.memoryRecycleRetentionDays
        var nextMemoryDreamRecallMaxTraces = Config.memoryDreamRecallMaxTraces
        var nextMemoryMaintenanceAggressiveness = Config.memoryMaintenanceAggressiveness
        var nextMemorySelfEnabled = Config.memorySelfEnabled
        var nextMemorySelfDirectUpdateEnabled = Config.memorySelfDirectUpdateEnabled
        var nextMemorySelfRecallMaxNodes = Config.memorySelfRecallMaxNodes
        var nextMemorySelfPromoteRepeatThreshold = Config.memorySelfPromoteRepeatThreshold
        var nextMemoryModelRecallEnabled = Config.memoryModelRecallEnabled
        var nextMemoryRecallModelUrl = Config.memoryRecallModelUrl
        var nextMemoryRecallModelModel = Config.memoryRecallModelModel
        var nextMemoryModelRecallFailureThreshold = Config.memoryModelRecallFailureThreshold
        var nextMemoryModelRecallCooldownSeconds = Config.memoryModelRecallCooldownSeconds
        var nextMemoryModelRecallTraceRetention = Config.memoryModelRecallTraceRetention
        var nextMemoryLocalRecallEnhancedEnabled = Config.memoryLocalRecallEnhancedEnabled
        var nextMemoryTagGraphEnabled = Config.memoryTagGraphEnabled
        var nextMemoryTagGraphMaxExpandedTerms = Config.memoryTagGraphMaxExpandedTerms
        var nextMemoryTimelineRecallEnabled = Config.memoryTimelineRecallEnabled
        var nextMemorySummarySanitizeInternalPrompts = Config.memorySummarySanitizeInternalPrompts
        var nextMemoryTopicEnabled = Config.memoryTopicEnabled
        var nextMemoryPinnedEnabled = Config.memoryPinnedEnabled
        var nextMemoryTopicUnusedSlotCap = Config.memoryTopicUnusedSlotCap
        var nextMemoryTopicCandidatePool = Config.memoryTopicCandidatePool
        var nextMemoryTopicLruWindow = Config.memoryTopicLruWindow
        var nextMemoryTopicUsedRetentionDays = Config.memoryTopicUsedRetentionDays
        var nextMemoryTopicDailyLimit = Config.memoryTopicDailyLimit
        var nextMemoryTopicSwitchKeywords = Config.memoryTopicSwitchKeywords
        var nextMemoryTopicColdRounds = Config.memoryTopicColdRounds
        var nextMemoryTopicSwitchJudgePrompt = Config.memoryTopicSwitchJudgePrompt

        var nextMemorySummaryKey = Config.memorySummaryKey
        var nextMemoryRecallModelKey = Config.memoryRecallModelKey
        var replaceSummaryKey: String? = null
        var replaceRecallModelKey: String? = null
        var clearSummaryKey = false
        var clearRecallModelKey = false

        body.readString("preset", errors) {
            if (it in listOf("anthropic", "custom")) {
                nextPreset = it
            } else {
                errors.add("preset must be one of: anthropic, custom")
            }
        }
        body.readString("upstream", errors) {
            nextUpstream = it.trim()
        }
        body.readString("cache_ttl", errors) {
            if (it in listOf("5m", "1h", "none")) {
                nextCacheTtl = it
            } else {
                errors.add("cache_ttl must be one of: 5m, 1h, none")
            }
        }
        body.readString("cache_mode", errors) {
            if (it in listOf("explicit", "automatic")) {
                nextCacheMode = it
            } else {
                errors.add("cache_mode must be one of: explicit, automatic")
            }
        }
        body.readString("cache_strategy", errors) {
            if (it in listOf("stable-prefix", "last")) {
                nextCacheStrategy = it
            } else {
                errors.add("cache_strategy must be one of: stable-prefix, last")
            }
        }
        body.readInt("cache_breakpoints", errors) {
            if (it in 0..4) {
                nextCacheBreakpoints = it
            } else {
                errors.add("cache_breakpoints must be an integer 0-4")
            }
        }

        body.readBoolean("memory_enabled", errors) {
            nextMemoryEnabled = it
        }
        body.readString("memory_summary_url", errors) {
            nextMemorySummaryUrl = it.trim()
        }
        body.readString("memory_summary_model", errors) {
            nextMemorySummaryModel = it.trim()
        }
        body.readString("memory_summary_prompt", errors) {
            nextMemorySummaryPrompt = it
        }
        body.readInt("memory_decay_interval_hours", errors) {
            if (it in 1..720) {
                nextMemoryDecayIntervalHours = it
            } else {
                errors.add("memory_decay_interval_hours must be an integer 1-720")
            }
        }
        body.readDouble("memory_decay_rate", errors) {
            if (it in 0.0..1.0) {
                nextMemoryDecayRate = it
            } else {
                errors.add("memory_decay_rate must be between 0.0 and 1.0")
            }
        }
        body.readDouble("memory_threshold", errors) {
            if (it in 0.0..1.0) {
                nextMemoryThreshold = it
            } else {
                errors.add("memory_threshold must be between 0.0 and 1.0")
            }
        }
        body.readDouble("memory_recovery_amount", errors) {
            if (it in 0.0..1.0) {
                nextMemoryRecoveryAmount = it
            } else {
                errors.add("memory_recovery_amount must be between 0.0 and 1.0")
            }
        }
        body.readDouble("memory_max_strength", errors) {
            if (it in 0.0..1.0) {
                nextMemoryMaxStrength = it
            } else {
                errors.add("memory_max_strength must be between 0.0 and 1.0")
            }
        }
        body.readDouble("memory_initial_strength", errors) {
            if (it in 0.0..1.0) {
                nextMemoryInitialStrength = it
            } else {
                errors.add("memory_initial_strength must be between 0.0 and 1.0")
            }
        }
        body.readDouble("intimacy_decay_rate", errors) {
            if (it in 0.0..10.0) {
                nextIntimacyDecayRate = it
            } else {
                errors.add("intimacy_decay_rate must be between 0.0 and 10.0")
            }
        }
        body.readDouble("trust_down_scale", errors) {
            if (it in 0.1..5.0) {
                nextTrustDownScale = it
            } else {
                errors.add("trust_down_scale must be between 0.1 and 5.0")
            }
        }
        body.readDouble("trust_up_scale", errors) {
            if (it in 0.1..5.0) {
                nextTrustUpScale = it
            } else {
                errors.add("trust_up_scale must be between 0.1 and 5.0")
            }
        }
        body.readDouble("memory_decay_tau_hours", errors) {
            if (it in 1.0..8760.0) {
                nextMemoryDecayTauHours = it
            } else {
                errors.add("memory_decay_tau_hours must be between 1.0 and 8760.0")
            }
        }
        body.readDouble("memory_salience_k", errors) {
            if (it in 0.0..10.0) {
                nextMemorySalienceK = it
            } else {
                errors.add("memory_salience_k must be between 0.0 and 10.0")
            }
        }
        body.readInt("memory_recall_max_nodes", errors) {
            if (it in 0..20) {
                nextMemoryRecallMaxNodes = it
            } else {
                errors.add("memory_recall_max_nodes must be an integer 0-20")
            }
        }
        body.readBoolean("memory_deep_recall_enabled", errors) {
            nextMemoryDeepRecallEnabled = it
        }
        body.readInt("memory_deep_recall_max_candidates", errors) {
            if (it in 1..100) {
                nextMemoryDeepRecallMaxCandidates = it
            } else {
                errors.add("memory_deep_recall_max_candidates must be an integer 1-100")
            }
        }
        body.readInt("memory_deep_recall_max_clues", errors) {
            if (it in 1..20) {
                nextMemoryDeepRecallMaxClues = it
            } else {
                errors.add("memory_deep_recall_max_clues must be an integer 1-20")
            }
        }
        body.readInt("memory_person_context_max_clues", errors) {
            if (it in 0..10) {
                nextMemoryPersonContextMaxClues = it
            } else {
                errors.add("memory_person_context_max_clues must be an integer 0-10")
            }
        }
        body.readBoolean("memory_buffered_ingestion_enabled", errors) {
            nextMemoryBufferedIngestionEnabled = it
        }
        body.readInt("memory_observation_retention_days", errors) {
            if (it in 1..365) {
                nextMemoryObservationRetentionDays = it
            } else {
                errors.add("memory_observation_retention_days must be an integer 1-365")
            }
        }
        body.readInt("memory_low_confidence_observation_retention_days", errors) {
            if (it in 1..365) {
                nextMemoryLowConfidenceObservationRetentionDays = it
            } else {
                errors.add("memory_low_confidence_observation_retention_days must be an integer 1-365")
            }
        }
        body.readDouble("memory_observation_min_confidence", errors) {
            if (it in 0.0..1.0) {
                nextMemoryObservationMinConfidence = it
            } else {
                errors.add("memory_observation_min_confidence must be between 0.0 and 1.0")
            }
        }
        body.readInt("memory_promote_repeat_threshold", errors) {
            if (it in 1..20) {
                nextMemoryPromoteRepeatThreshold = it
            } else {
                errors.add("memory_promote_repeat_threshold must be an integer 1-20")
            }
        }
        body.readInt("memory_project_fact_promote_repeat_threshold", errors) {
            if (it in 1..20) {
                nextMemoryProjectFactPromoteRepeatThreshold = it
            } else {
                errors.add("memory_project_fact_promote_repeat_threshold must be an integer 1-20")
            }
        }
        body.readInt("memory_working_memory_slots_per_project", errors) {
            if (it in 1..20) {
                nextMemoryWorkingMemorySlotsPerProject = it
            } else {
                errors.add("memory_working_memory_slots_per_project must be an integer 1-20")
            }
        }
        body.readInt("memory_observation_daily_cap", errors) {
            if (it in 0..10000) {
                nextMemoryObservationDailyCap = it
            } else {
                errors.add("memory_observation_daily_cap must be an integer 0-10000")
            }
        }
        body.readInt("memory_promoted_nodes_daily_cap", errors) {
            if (it in 0..1000) {
                nextMemoryPromotedNodesDailyCap = it
            } else {
                errors.add("memory_promoted_nodes_daily_cap must be an integer 0-1000")
            }
        }
        body.readBoolean("memory_dream_enabled", errors) {
            nextMemoryDreamEnabled = it
        }
        body.readBoolean("memory_auto_maintenance_enabled", errors) {
            nextMemoryAutoMaintenanceEnabled = it
        }
        body.readInt("memory_dream_daily_limit", errors) {
            if (it in 0..24) {
                nextMemoryDreamDailyLimit = it
            } else {
                errors.add("memory_dream_daily_limit must be an integer 0-24")
            }
        }
        body.readInt("memory_dream_idle_hours", errors) {
            if (it in 1..720) {
                nextMemoryDreamIdleHours = it
            } else {
                errors.add("memory_dream_idle_hours must be an integer 1-720")
            }
        }
        body.readInt("memory_dream_batch_max_nodes", errors) {
            if (it in 1..200) {
                nextMemoryDreamBatchMaxNodes = it
            } else {
                errors.add("memory_dream_batch_max_nodes must be an integer 1-200")
            }
        }
        body.readInt("memory_dream_dry_run_daily_limit", errors) {
            if (it in 0..100) {
                nextMemoryDreamDryRunDailyLimit = it
            } else {
                errors.add("memory_dream_dry_run_daily_limit must be an integer 0-100")
            }
        }
        body.readInt("memory_long_idle_pause_days", errors) {
            if (it in 1..365) {
                nextMemoryLongIdlePauseDays = it
            } else {
                errors.add("memory_long_idle_pause_days must be an integer 1-365")
            }
        }
        body.readInt("memory_recycle_retention_days", errors) {
            if (it in 1..3650) {
                nextMemoryRecycleRetentionDays = it
            } else {
                errors.add("memory_recycle_retention_days must be an integer 1-3650")
            }
        }
        body.readInt("memory_dream_recall_max_traces", errors) {
            if (it in 0..10) {
                nextMemoryDreamRecallMaxTraces = it
            } else {
                errors.add("memory_dream_recall_max_traces must be an integer 0-10")
            }
        }
        body.readString("memory_maintenance_aggressiveness", errors) {
            val normalized = it.trim().lowercase()
            if (normalized in setOf("standard", "aggressive")) {
                nextMemoryMaintenanceAggressiveness = normalized
            } else {
                errors.add("memory_maintenance_aggressiveness must be one of: standard, aggressive")
            }
        }
        body.readBoolean("memory_self_enabled", errors) {
            nextMemorySelfEnabled = it
        }
        body.readBoolean("memory_self_direct_update_enabled", errors) {
            nextMemorySelfDirectUpdateEnabled = it
        }
        body.readInt("memory_self_recall_max_nodes", errors) {
            if (it in 0..20) {
                nextMemorySelfRecallMaxNodes = it
            } else {
                errors.add("memory_self_recall_max_nodes must be an integer 0-20")
            }
        }
        body.readInt("memory_self_promote_repeat_threshold", errors) {
            if (it in 1..20) {
                nextMemorySelfPromoteRepeatThreshold = it
            } else {
                errors.add("memory_self_promote_repeat_threshold must be an integer 1-20")
            }
        }
        body.readBoolean("memory_model_recall_enabled", errors) {
            nextMemoryModelRecallEnabled = it
        }
        body.readString("memory_recall_model_url", errors) {
            nextMemoryRecallModelUrl = it.trim()
        }
        body.readString("memory_recall_model_model", errors) {
            nextMemoryRecallModelModel = it.trim()
        }
        body.readInt("memory_model_recall_failure_threshold", errors) {
            if (it in 1..20) {
                nextMemoryModelRecallFailureThreshold = it
            } else {
                errors.add("memory_model_recall_failure_threshold must be an integer 1-20")
            }
        }
        body.readInt("memory_model_recall_cooldown_seconds", errors) {
            if (it in 0..86400) {
                nextMemoryModelRecallCooldownSeconds = it
            } else {
                errors.add("memory_model_recall_cooldown_seconds must be an integer 0-86400")
            }
        }
        body.readInt("memory_model_recall_trace_retention", errors) {
            if (it in 1..5000) {
                nextMemoryModelRecallTraceRetention = it
            } else {
                errors.add("memory_model_recall_trace_retention must be an integer 1-5000")
            }
        }
        body.readBoolean("memory_local_recall_enhanced_enabled", errors) {
            nextMemoryLocalRecallEnhancedEnabled = it
        }
        body.readBoolean("memory_tag_graph_enabled", errors) {
            nextMemoryTagGraphEnabled = it
        }
        body.readInt("memory_tag_graph_max_expanded_terms", errors) {
            if (it in 0..128) {
                nextMemoryTagGraphMaxExpandedTerms = it
            } else {
                errors.add("memory_tag_graph_max_expanded_terms must be an integer 0-128")
            }
        }
        body.readBoolean("memory_timeline_recall_enabled", errors) {
            nextMemoryTimelineRecallEnabled = it
        }
        body.readBoolean("memory_summary_sanitize_internal_prompts", errors) {
            nextMemorySummarySanitizeInternalPrompts = it
        }

        body.readBoolean("memory_topic_enabled", errors) {
            nextMemoryTopicEnabled = it
        }
        body.readBoolean("memory_pinned_enabled", errors) {
            nextMemoryPinnedEnabled = it
        }
        body.readInt("memory_topic_unused_slot_cap", errors) {
            if (it in 0..64) nextMemoryTopicUnusedSlotCap = it else errors.add("memory_topic_unused_slot_cap must be an integer 0-64")
        }
        body.readInt("memory_topic_candidate_pool", errors) {
            if (it in 2..200) nextMemoryTopicCandidatePool = it else errors.add("memory_topic_candidate_pool must be an integer 2-200")
        }
        body.readInt("memory_topic_lru_window", errors) {
            if (it in 0..200) nextMemoryTopicLruWindow = it else errors.add("memory_topic_lru_window must be an integer 0-200")
        }
        body.readInt("memory_topic_used_retention_days", errors) {
            if (it in 1..3650) nextMemoryTopicUsedRetentionDays = it else errors.add("memory_topic_used_retention_days must be an integer 1-3650")
        }
        body.readInt("memory_topic_daily_limit", errors) {
            if (it in 0..48) nextMemoryTopicDailyLimit = it else errors.add("memory_topic_daily_limit must be an integer 0-48")
        }
        body.readString("memory_topic_switch_keywords", errors) {
            nextMemoryTopicSwitchKeywords = it
        }
        body.readInt("memory_topic_cold_rounds", errors) {
            if (it in 1..20) nextMemoryTopicColdRounds = it else errors.add("memory_topic_cold_rounds must be an integer 1-20")
        }
        body.readString("memory_topic_switch_judge_prompt", errors) {
            nextMemoryTopicSwitchJudgePrompt = it
        }

        body.readString("memory_summary_key", errors) {
            replaceSummaryKey = it
        }
        body.readBoolean("clear_memory_summary_key", errors) {
            clearSummaryKey = it
        }
        body.readString("memory_recall_model_key", errors) {
            replaceRecallModelKey = it
        }
        body.readBoolean("clear_memory_recall_model_key", errors) {
            clearRecallModelKey = it
        }

        if (nextPreset == "custom" && nextUpstream.isBlank()) {
            errors.add("upstream is required when preset is custom")
        }

        if (nextUpstream.isNotBlank()) {
            Security.validateOutboundBaseUrl(nextUpstream, "upstream")?.let { errors.add(it) }
        }

        if (nextMemorySummaryUrl.isNotBlank()) {
            Security.validateOutboundBaseUrl(nextMemorySummaryUrl, "memory_summary_url")?.let { errors.add(it) }
        }
        if (nextMemoryRecallModelUrl.isNotBlank()) {
            Security.validateOutboundBaseUrl(nextMemoryRecallModelUrl, "memory_recall_model_url")?.let { errors.add(it) }
        }

        if (nextPreset != "anthropic" && nextCacheMode == "automatic") {
            errors.add("automatic cache mode is only supported with the anthropic preset")
        }

        if (nextMemoryInitialStrength > nextMemoryMaxStrength) {
            errors.add("memory_initial_strength must be less than or equal to memory_max_strength")
        }

        if (nextMemoryDeepRecallMaxClues > nextMemoryDeepRecallMaxCandidates) {
            errors.add("memory_deep_recall_max_clues must be less than or equal to memory_deep_recall_max_candidates")
        }

        if (nextMemoryLowConfidenceObservationRetentionDays > nextMemoryObservationRetentionDays) {
            errors.add("memory_low_confidence_observation_retention_days must be less than or equal to memory_observation_retention_days")
        }

        if (clearSummaryKey && !replaceSummaryKey.isNullOrBlank()) {
            errors.add("memory_summary_key cannot be replaced and cleared in the same request")
        }
        if (clearRecallModelKey && !replaceRecallModelKey.isNullOrBlank()) {
            errors.add("memory_recall_model_key cannot be replaced and cleared in the same request")
        }

        if (errors.isNotEmpty()) {
            return UpdateResult(errors = errors)
        }

        if (clearSummaryKey) {
            nextMemorySummaryKey = ""
        } else if (!replaceSummaryKey.isNullOrBlank()) {
            nextMemorySummaryKey = replaceSummaryKey!!.trim()
        }
        if (clearRecallModelKey) {
            nextMemoryRecallModelKey = ""
        } else if (!replaceRecallModelKey.isNullOrBlank()) {
            nextMemoryRecallModelKey = replaceRecallModelKey!!.trim()
        }

        val nextSnapshot = Config.Snapshot(
            preset = nextPreset,
            upstream = nextUpstream,
            cacheTtl = nextCacheTtl,
            cacheMode = nextCacheMode,
            cacheStrategy = nextCacheStrategy,
            cacheBreakpoints = nextCacheBreakpoints,
            memoryEnabled = nextMemoryEnabled,
            memorySummaryUrl = nextMemorySummaryUrl,
            memorySummaryKey = nextMemorySummaryKey,
            memorySummaryModel = nextMemorySummaryModel,
            memorySummaryPrompt = nextMemorySummaryPrompt,
            memoryDecayIntervalHours = nextMemoryDecayIntervalHours,
            memoryDecayRate = nextMemoryDecayRate,
            memoryThreshold = nextMemoryThreshold,
            memoryRecoveryAmount = nextMemoryRecoveryAmount,
            memoryMaxStrength = nextMemoryMaxStrength,
            memoryInitialStrength = nextMemoryInitialStrength,
            intimacyDecayRate = nextIntimacyDecayRate,
            trustDownScale = nextTrustDownScale,
            trustUpScale = nextTrustUpScale,
            memoryDecayTauHours = nextMemoryDecayTauHours,
            memorySalienceK = nextMemorySalienceK,
            memoryRecallMaxNodes = nextMemoryRecallMaxNodes,
            memoryDeepRecallEnabled = nextMemoryDeepRecallEnabled,
            memoryDeepRecallMaxCandidates = nextMemoryDeepRecallMaxCandidates,
            memoryDeepRecallMaxClues = nextMemoryDeepRecallMaxClues,
            memoryPersonContextMaxClues = nextMemoryPersonContextMaxClues,
            memoryBufferedIngestionEnabled = nextMemoryBufferedIngestionEnabled,
            memoryObservationRetentionDays = nextMemoryObservationRetentionDays,
            memoryLowConfidenceObservationRetentionDays = nextMemoryLowConfidenceObservationRetentionDays,
            memoryObservationMinConfidence = nextMemoryObservationMinConfidence,
            memoryPromoteRepeatThreshold = nextMemoryPromoteRepeatThreshold,
            memoryProjectFactPromoteRepeatThreshold = nextMemoryProjectFactPromoteRepeatThreshold,
            memoryWorkingMemorySlotsPerProject = nextMemoryWorkingMemorySlotsPerProject,
            memoryObservationDailyCap = nextMemoryObservationDailyCap,
            memoryPromotedNodesDailyCap = nextMemoryPromotedNodesDailyCap,
            memoryDreamEnabled = nextMemoryDreamEnabled,
            memoryAutoMaintenanceEnabled = nextMemoryAutoMaintenanceEnabled,
            memoryDreamDailyLimit = nextMemoryDreamDailyLimit,
            memoryDreamIdleHours = nextMemoryDreamIdleHours,
            memoryDreamBatchMaxNodes = nextMemoryDreamBatchMaxNodes,
            memoryDreamDryRunDailyLimit = nextMemoryDreamDryRunDailyLimit,
            memoryLongIdlePauseDays = nextMemoryLongIdlePauseDays,
            memoryRecycleRetentionDays = nextMemoryRecycleRetentionDays,
            memoryDreamRecallMaxTraces = nextMemoryDreamRecallMaxTraces,
            memoryMaintenanceAggressiveness = nextMemoryMaintenanceAggressiveness,
            memorySelfEnabled = nextMemorySelfEnabled,
            memorySelfDirectUpdateEnabled = nextMemorySelfDirectUpdateEnabled,
            memorySelfRecallMaxNodes = nextMemorySelfRecallMaxNodes,
            memorySelfPromoteRepeatThreshold = nextMemorySelfPromoteRepeatThreshold,
            memoryModelRecallEnabled = nextMemoryModelRecallEnabled,
            memoryRecallModelUrl = nextMemoryRecallModelUrl,
            memoryRecallModelKey = nextMemoryRecallModelKey,
            memoryRecallModelModel = nextMemoryRecallModelModel,
            memoryModelRecallFailureThreshold = nextMemoryModelRecallFailureThreshold,
            memoryModelRecallCooldownSeconds = nextMemoryModelRecallCooldownSeconds,
            memoryModelRecallTraceRetention = nextMemoryModelRecallTraceRetention,
            memoryLocalRecallEnhancedEnabled = nextMemoryLocalRecallEnhancedEnabled,
            memoryTagGraphEnabled = nextMemoryTagGraphEnabled,
            memoryTagGraphMaxExpandedTerms = nextMemoryTagGraphMaxExpandedTerms,
            memoryTimelineRecallEnabled = nextMemoryTimelineRecallEnabled,
            memorySummarySanitizeInternalPrompts = nextMemorySummarySanitizeInternalPrompts,
            memoryTopicEnabled = nextMemoryTopicEnabled,
            memoryPinnedEnabled = nextMemoryPinnedEnabled,
            memoryTopicUnusedSlotCap = nextMemoryTopicUnusedSlotCap,
            memoryTopicCandidatePool = nextMemoryTopicCandidatePool,
            memoryTopicLruWindow = nextMemoryTopicLruWindow,
            memoryTopicUsedRetentionDays = nextMemoryTopicUsedRetentionDays,
            memoryTopicDailyLimit = nextMemoryTopicDailyLimit,
            memoryTopicSwitchKeywords = nextMemoryTopicSwitchKeywords,
            memoryTopicColdRounds = nextMemoryTopicColdRounds,
            memoryTopicSwitchJudgePrompt = nextMemoryTopicSwitchJudgePrompt
        )

        try {
            DatabaseService.saveConfig(nextSnapshot.toJson().toString())
        } catch (e: Exception) {
            return UpdateResult(errors = listOf("failed to persist config: ${e.message ?: "unknown error"}"))
        }

        Config.applySnapshot(nextSnapshot)
        return UpdateResult(errors = emptyList())
    }

    private fun JsonObject.readString(field: String, errors: MutableList<String>, onValue: (String) -> Unit) {
        if (field !in this) return
        val value = (this[field] as? JsonPrimitive)?.contentOrNull
        if (value != null) {
            onValue(value)
        } else {
            errors.add("$field must be a string")
        }
    }

    private fun JsonObject.readBoolean(field: String, errors: MutableList<String>, onValue: (Boolean) -> Unit) {
        if (field !in this) return
        val value = (this[field] as? JsonPrimitive)?.booleanOrNull
        if (value != null) {
            onValue(value)
        } else {
            errors.add("$field must be a boolean")
        }
    }

    private fun JsonObject.readInt(field: String, errors: MutableList<String>, onValue: (Int) -> Unit) {
        if (field !in this) return
        val value = (this[field] as? JsonPrimitive)?.intOrNull
        if (value != null) {
            onValue(value)
        } else {
            errors.add("$field must be an integer")
        }
    }

    private fun JsonObject.readDouble(field: String, errors: MutableList<String>, onValue: (Double) -> Unit) {
        if (field !in this) return
        val value = (this[field] as? JsonPrimitive)?.doubleOrNull
        if (value != null) {
            onValue(value)
        } else {
            errors.add("$field must be a number")
        }
    }
}
