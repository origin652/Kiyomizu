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
            put("memory_decay_tau_hours", Config.memoryDecayTauHours)
            put("memory_salience_k", Config.memorySalienceK)
            put("memory_recall_max_nodes", Config.memoryRecallMaxNodes)
            put("memory_deep_recall_enabled", Config.memoryDeepRecallEnabled)
            put("memory_deep_recall_max_candidates", Config.memoryDeepRecallMaxCandidates)
            put("memory_deep_recall_max_clues", Config.memoryDeepRecallMaxClues)
            put("memory_person_context_max_clues", Config.memoryPersonContextMaxClues)
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
        var nextMemoryDecayTauHours = Config.memoryDecayTauHours
        var nextMemorySalienceK = Config.memorySalienceK
        var nextMemoryRecallMaxNodes = Config.memoryRecallMaxNodes
        var nextMemoryDeepRecallEnabled = Config.memoryDeepRecallEnabled
        var nextMemoryDeepRecallMaxCandidates = Config.memoryDeepRecallMaxCandidates
        var nextMemoryDeepRecallMaxClues = Config.memoryDeepRecallMaxClues
        var nextMemoryPersonContextMaxClues = Config.memoryPersonContextMaxClues

        var nextMemorySummaryKey = Config.memorySummaryKey
        var replaceSummaryKey: String? = null
        var clearSummaryKey = false

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

        body.readString("memory_summary_key", errors) {
            replaceSummaryKey = it
        }
        body.readBoolean("clear_memory_summary_key", errors) {
            clearSummaryKey = it
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

        if (nextPreset != "anthropic" && nextCacheMode == "automatic") {
            errors.add("automatic cache mode is only supported with the anthropic preset")
        }

        if (nextMemoryInitialStrength > nextMemoryMaxStrength) {
            errors.add("memory_initial_strength must be less than or equal to memory_max_strength")
        }

        if (nextMemoryDeepRecallMaxClues > nextMemoryDeepRecallMaxCandidates) {
            errors.add("memory_deep_recall_max_clues must be less than or equal to memory_deep_recall_max_candidates")
        }

        if (clearSummaryKey && !replaceSummaryKey.isNullOrBlank()) {
            errors.add("memory_summary_key cannot be replaced and cleared in the same request")
        }

        if (errors.isNotEmpty()) {
            return UpdateResult(errors = errors)
        }

        if (clearSummaryKey) {
            nextMemorySummaryKey = ""
        } else if (!replaceSummaryKey.isNullOrBlank()) {
            nextMemorySummaryKey = replaceSummaryKey!!.trim()
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
            memoryDecayTauHours = nextMemoryDecayTauHours,
            memorySalienceK = nextMemorySalienceK,
            memoryRecallMaxNodes = nextMemoryRecallMaxNodes,
            memoryDeepRecallEnabled = nextMemoryDeepRecallEnabled,
            memoryDeepRecallMaxCandidates = nextMemoryDeepRecallMaxCandidates,
            memoryDeepRecallMaxClues = nextMemoryDeepRecallMaxClues,
            memoryPersonContextMaxClues = nextMemoryPersonContextMaxClues
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
