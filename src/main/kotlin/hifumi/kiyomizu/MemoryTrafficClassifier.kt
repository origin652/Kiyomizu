package hifumi.kiyomizu

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

object MemoryTrafficClassifier {
    enum class TrafficClass(val wireName: String) {
        CONVERSATION("conversation"),
        TASK("task"),
        TOOL_INTERNAL("tool_internal"),
        UNKNOWN("unknown")
    }

    data class Actions(
        val injectCompanion: Boolean,
        val recallMemory: Boolean,
        val useModelRecall: Boolean,
        val extractMemory: Boolean,
        val updateAffect: Boolean
    ) {
        fun toJsonString(): String = buildJsonObject {
            put("inject_companion", injectCompanion)
            put("recall_memory", recallMemory)
            put("use_model_recall", useModelRecall)
            put("extract_memory", extractMemory)
            put("update_affect", updateAffect)
        }.toString()
    }

    data class Decision(
        val trafficClass: TrafficClass,
        val confidence: Double,
        val reasons: List<String>,
        val actions: Actions
    ) {
        val wireClass: String get() = trafficClass.wireName
        fun reasonsJsonString(): String = buildJsonArray {
            reasons.forEach { add(JsonPrimitive(it)) }
        }.toString()
    }

    private val conversationActions = Actions(
        injectCompanion = true,
        recallMemory = true,
        useModelRecall = true,
        extractMemory = true,
        updateAffect = true
    )

    private val toolBypassActions = Actions(
        injectCompanion = false,
        recallMemory = false,
        useModelRecall = false,
        extractMemory = false,
        updateAffect = false
    )

    private val readonlyTaskActions = Actions(
        injectCompanion = true,
        recallMemory = true,
        useModelRecall = false,
        extractMemory = false,
        updateAffect = false
    )

    private val readonlyUnknownActions = Actions(
        injectCompanion = true,
        recallMemory = true,
        useModelRecall = false,
        extractMemory = false,
        updateAffect = false
    )

    private data class Fingerprint(val phrase: String, val reason: String, val weight: Int)

    private val toolFingerprints = listOf(
        Fingerprint("review the conversation above and update the skill library", "skill_library_update_instruction", 3),
        Fingerprint("you can only call memory and skill management tools", "memory_skill_tools_only", 3),
        Fingerprint("target shape of the library", "skill_library_shape_instruction", 2),
        Fingerprint("protected skills", "protected_skills_instruction", 2),
        Fingerprint("skill_manage", "skill_manage_tool_reference", 2),
        Fingerprint("skills_list", "skills_list_tool_reference", 2),
        Fingerprint("skill_view", "skill_view_tool_reference", 2),
        Fingerprint("tools will be denied at runtime", "tool_runtime_constraint", 2),
        Fingerprint("class-level skills", "class_level_skill_instruction", 1),
        Fingerprint("session-specific detail", "session_specific_skill_detail", 1),
        Fingerprint("nothing to save.", "skill_noop_instruction", 1)
    )

    private val taskHints = listOf(
        "implement",
        "fix",
        "debug",
        "bug",
        "kotlin",
        "gradle",
        "repository",
        "codebase",
        "commit",
        "pull request",
        "帮我修",
        "实现",
        "提交",
        "代码",
        "项目"
    )

    fun classify(path: String, body: JsonObject?, userText: String?): Decision {
        if (!Config.memoryTrafficClassifierEnabled) {
            return Decision(
                trafficClass = TrafficClass.CONVERSATION,
                confidence = 1.0,
                reasons = listOf("classifier_disabled"),
                actions = conversationActions
            )
        }

        val normalizedUser = normalize(userText.orEmpty())
        val normalizedBody = normalize(body?.let(::collectJsonStrings).orEmpty())
        val fingerprintHaystack = listOf(normalizedUser, normalizedBody)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val instructionHaystack = normalizedUser.ifBlank { normalizedBody }
        if (instructionHaystack.isBlank()) {
            return unknownDecision("empty_user_text")
        }

        val matched = toolFingerprints.filter { fingerprintHaystack.contains(it.phrase) }
        val toolScore = matched.sumOf { it.weight }
        val toolReasons = matched.map { it.reason }
        if (toolScore >= 3 || matched.size >= 2) {
            return Decision(
                trafficClass = TrafficClass.TOOL_INTERNAL,
                confidence = (0.74 + toolScore * 0.05).coerceAtMost(0.98),
                reasons = toolReasons.ifEmpty { listOf("tool_internal_fingerprint") },
                actions = if (Config.memoryToolInternalBypassEnabled) toolBypassActions else conversationActions
            )
        }

        if (looksLikeAgentInstruction(instructionHaystack)) {
            return unknownDecision("agent_like_instruction")
        }

        if (looksLikeDevelopmentTask(normalizedUser)) {
            return Decision(
                trafficClass = TrafficClass.TASK,
                confidence = 0.72,
                reasons = listOf("development_task_hint"),
                actions = readonlyTaskActions.copy(updateAffect = !Config.memoryTaskDisableAffect)
            )
        }

        return Decision(
            trafficClass = TrafficClass.CONVERSATION,
            confidence = 0.70,
            reasons = listOf("default_conversation"),
            actions = conversationActions
        )
    }

    fun shouldRejectEvidence(path: String, body: JsonObject?, userText: String, responseText: String): Decision {
        val primary = classify(path, body, userText)
        if (!primary.actions.extractMemory) return primary
        val evidenceText = "$userText\n$responseText"
        return classify(path, body, evidenceText)
    }

    private fun unknownDecision(reason: String): Decision {
        val actions = if (Config.memoryUnknownDisableWrite) {
            readonlyUnknownActions.copy(updateAffect = false)
        } else {
            conversationActions.copy(useModelRecall = false)
        }
        return Decision(
            trafficClass = TrafficClass.UNKNOWN,
            confidence = 0.60,
            reasons = listOf(reason),
            actions = actions
        )
    }

    private fun looksLikeAgentInstruction(normalized: String): Boolean {
        if (normalized.length < 1000) return false
        val toolish = listOf("tool", "tools", "runtime", "memory", "skill", "instruction").count { normalized.contains(it) }
        val directive = listOf("must", "only", "do not", "never", "allowed", "denied").count { normalized.contains(it) }
        return toolish >= 2 && directive >= 2
    }

    private fun looksLikeDevelopmentTask(normalized: String): Boolean {
        val hits = taskHints.count { normalized.contains(it) }
        if (hits >= 2) return true
        return hits == 1 && (normalized.contains("file") || normalized.contains("test") || normalized.contains("函数"))
    }

    private fun normalize(value: String): String {
        return value
            .lowercase()
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun collectJsonStrings(element: JsonElement?, budget: Int = 16000): String {
        if (element == null || budget <= 0) return ""
        val out = StringBuilder()

        fun appendText(text: String) {
            if (out.length >= budget) return
            if (out.isNotEmpty()) out.append('\n')
            out.append(text.take(budget - out.length))
        }

        fun walk(value: JsonElement) {
            if (out.length >= budget) return
            when (value) {
                is JsonPrimitive -> value.contentOrNull?.let(::appendText)
                is JsonArray -> value.forEach(::walk)
                is JsonObject -> value.values.forEach(::walk)
            }
        }

        walk(element)
        return out.toString()
    }
}
