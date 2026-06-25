package hifumi.kiyomizu

import kotlinx.serialization.json.*

object MessagePatcher {
    private val thinkingBlockTypes = setOf("thinking", "redacted_thinking", "reasoning", "reasoning_details", "reasoning_content")
    private enum class RequestEnvelope {
        OPENAI_CHAT_COMPLETIONS,
        ANTHROPIC_MESSAGES,
        OPENAI_RESPONSES,
        GEMINI_GENERATE_CONTENT,
        UNKNOWN
    }

    private enum class CapabilityProfile {
        ANTHROPIC_DIRECT,
        GENERIC
    }

    private fun detectEnvelope(path: String, body: JsonObject): RequestEnvelope {
        val normalizedPath = path.lowercase()
        return when {
            normalizedPath.contains(":generatecontent") || "contents" in body -> RequestEnvelope.GEMINI_GENERATE_CONTENT
            normalizedPath.contains("/responses") || "input" in body -> RequestEnvelope.OPENAI_RESPONSES
            normalizedPath.endsWith("/messages") || normalizedPath.contains("/v1/messages") -> RequestEnvelope.ANTHROPIC_MESSAGES
            normalizedPath.contains("/chat/completions") || "messages" in body -> RequestEnvelope.OPENAI_CHAT_COMPLETIONS
            else -> RequestEnvelope.UNKNOWN
        }
    }

    private fun detectCapabilityProfile(): CapabilityProfile {
        return if (Config.preset == "anthropic") CapabilityProfile.ANTHROPIC_DIRECT else CapabilityProfile.GENERIC
    }

    fun isAnthropicMessagesRequest(path: String, body: JsonObject? = null): Boolean {
        return if (body != null) {
            detectEnvelope(path, body) == RequestEnvelope.ANTHROPIC_MESSAGES
        } else {
            val normalizedPath = path.lowercase()
            normalizedPath.endsWith("/messages") || normalizedPath.contains("/v1/messages")
        }
    }

    fun shouldPatchModel(model: String?): Boolean {
        if (model == null) return false
        val normalizedModel = model.lowercase()
        return Config.modelFilter.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .any { normalizedModel.contains(it) }
    }

    private fun addCacheControlToTextBlock(block: JsonObject): JsonObject {
        if (block["type"]?.jsonPrimitive?.content != "text") return block
        return buildJsonObject {
            block.forEach { (k, v) -> put(k, v) }
            put("cache_control", buildJsonObject {
                put("type", "ephemeral")
                if (Config.cacheTtl != "none") {
                    put("ttl", Config.cacheTtl)
                }
            })
        }
    }

    private fun removeCacheControlFromBlock(block: JsonObject): JsonObject {
        return buildJsonObject {
            block.forEach { (k, v) ->
                if (k != "cache_control") put(k, v)
            }
        }
    }

    private fun stripThinkingFromContent(content: JsonArray): Pair<JsonArray, Int> {
        var stripped = 0
        val next = buildJsonArray {
            for (element in content) {
                if (element is JsonObject) {
                    val type = element["type"]?.jsonPrimitive?.content
                    if (type in thinkingBlockTypes) {
                        stripped++
                        continue
                    }
                }
                add(element)
            }
        }
        return Pair(next, stripped)
    }

    fun normalizeMessage(message: JsonObject): JsonObject? {
        var stripped = 0
        var content = message["content"]

        if (Config.stripThinking && content is JsonArray) {
            val (nextContent, count) = stripThinkingFromContent(content)
            content = nextContent
            stripped += count
        }

        if (content is JsonArray) {
            content = buildJsonArray {
                for (element in content) {
                    if (element is JsonObject) {
                        add(removeCacheControlFromBlock(element))
                    } else {
                        add(element)
                    }
                }
            }
        }

        if (Config.stripThinking && content is JsonArray && content.isEmpty()) {
            return null
        }

        if (!Config.stripThinking) {
            val cleanedMessage = removeMessageLevelCacheControl(message)
            return buildJsonObject {
                cleanedMessage.forEach { (k, v) ->
                    if (k == "content") {
                        if (content != null) put(k, content)
                    } else {
                        put(k, v)
                    }
                }
            }
        }

        val filterKeys = setOf("reasoning", "reasoning_details", "reasoning_content", "thinking", "redacted_thinking", "cache_control")
        val messageKeys = message.keys
        for (k in filterKeys) {
            if (k in messageKeys) {
                stripped++
            }
        }

        return buildJsonObject {
            message.forEach { (k, v) ->
                if (k == "content") {
                    if (content != null) put(k, content)
                } else if (k !in filterKeys) {
                    put(k, v)
                }
            }
        }
    }

    private fun removeMessageLevelCacheControl(message: JsonObject): JsonObject {
        return buildJsonObject {
            message.forEach { (k, v) ->
                if (k != "cache_control") put(k, v)
            }
        }
    }

    private fun addCacheControlToContent(content: JsonElement): JsonElement {
        if (content is JsonPrimitive && content.isString) {
            return buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", content.content)
                    put("cache_control", buildJsonObject {
                        put("type", "ephemeral")
                        if (Config.cacheTtl != "none") {
                            put("ttl", Config.cacheTtl)
                        }
                    })
                })
            }
        }

        if (content is JsonArray) {
            val list = content.toMutableList()
            for (i in list.indices.reversed()) {
                val block = list[i]
                if (block is JsonObject && block["type"]?.jsonPrimitive?.content == "text") {
                    list[i] = addCacheControlToTextBlock(block)
                    return JsonArray(list)
                }
            }
        }

        return content
    }

    private fun patchMessageWithCacheControl(message: JsonObject): JsonObject {
        val content = message["content"] ?: return message
        return buildJsonObject {
            message.forEach { (k, v) ->
                if (k == "content") {
                    put(k, addCacheControlToContent(content))
                } else {
                    put(k, v)
                }
            }
        }
    }

    fun hasCacheableContent(message: JsonObject): Boolean {
        val content = message["content"] ?: return false
        if (content is JsonPrimitive && content.isString) {
            return content.content.isNotEmpty()
        }
        if (content is JsonArray) {
            return content.any { block ->
                block is JsonObject &&
                        block["type"]?.jsonPrimitive?.content == "text" &&
                        (block["text"]?.jsonPrimitive?.contentOrNull?.isNotEmpty() == true)
            }
        }
        return false
    }

    private fun stableEndIndex(itemCount: Int): Int {
        if (itemCount <= 0) return -1
        if (Config.cacheStrategy == "last") {
            return itemCount - 1
        }
        val tail = if (Config.dynamicTailMessages >= 0) Config.dynamicTailMessages else 1
        val stableIndex = itemCount - tail - 1
        if (stableIndex >= 0) return stableIndex
        return itemCount - 1
    }

    fun stableEndIndex(messages: List<JsonObject>): Int {
        return stableEndIndex(messages.size)
    }

    fun chooseCacheBreakpointIndexes(messages: List<JsonObject>): List<Int> {
        if (messages.isEmpty()) return emptyList()
        val maxBreakpoints = Config.cacheBreakpoints.coerceIn(0, 4)
        if (maxBreakpoints == 0) return emptyList()

        val endIndex = stableEndIndex(messages)
        if (endIndex < 0) return emptyList()

        val candidates = mutableListOf<Int>()
        for (i in 0..endIndex) {
            if (hasCacheableContent(messages[i])) {
                candidates.add(i)
            }
        }

        if (candidates.size <= maxBreakpoints) {
            return candidates
        }

        val preferred = candidates.filter { index ->
            val nextRole = messages.getOrNull(index + 1)?.get("role")?.jsonPrimitive?.content
            nextRole == "user"
        }
        val pool = if (preferred.size >= maxBreakpoints) preferred else candidates

        val selected = mutableSetOf<Int>()
        for (point in 1..maxBreakpoints) {
            val candidateIndex = Math.ceil((point * pool.size).toDouble() / maxBreakpoints).toInt() - 1
            val poolIndex = candidateIndex.coerceIn(0, pool.size - 1)
            selected.add(pool[poolIndex])
        }

        return selected.sorted()
    }

    fun patchMessages(messages: List<JsonObject>): List<JsonObject> {
        val normalized = mutableListOf<JsonObject>()
        for (msg in messages) {
            val norm = normalizeMessage(msg)
            if (norm != null) {
                normalized.add(norm)
            }
        }

        if (Config.cacheMode != "explicit") {
            return normalized
        }

        val indexes = chooseCacheBreakpointIndexes(normalized)
        if (indexes.isEmpty()) {
            return normalized
        }

        val result = normalized.toMutableList()
        for (idx in indexes) {
            result[idx] = patchMessageWithCacheControl(result[idx])
        }
        return result
    }

    fun patchGeminiMessages(messages: List<JsonObject>): List<JsonObject> {
        val normalized = mutableListOf<JsonObject>()
        for (msg in messages) {
            val norm = normalizeMessage(msg)
            if (norm != null) {
                normalized.add(norm)
            }
        }

        if (Config.cacheMode != "explicit") return normalized

        // OpenRouter only supports a single cache breakpoint on the last message for Gemini.
        for (i in normalized.indices.reversed()) {
            if (hasCacheableContent(normalized[i])) {
                val result = normalized.toMutableList()
                result[i] = patchMessageWithCacheControl(result[i])
                return result
            }
        }

        return normalized
    }

    fun extractTextContent(content: JsonElement?): String {
        return when (content) {
            is JsonPrimitive -> if (content.isString) content.content else ""
            is JsonArray -> content.mapNotNull { el ->
                (el as? JsonObject)?.let { o ->
                    o["text"]?.jsonPrimitive?.contentOrNull
                }
            }.joinToString("\n")
            else -> ""
        }
    }

    private fun prependTextToContent(message: JsonObject, prefix: String, textBlockType: String = "text"): JsonObject {
        val content = message["content"]
        val newContent: JsonElement = when (content) {
            is JsonPrimitive -> if (content.isString) JsonPrimitive(prefix + content.content) else JsonPrimitive(prefix)
            is JsonArray -> buildJsonArray {
                add(buildJsonObject {
                    put("type", textBlockType)
                    put("text", prefix)
                })
                content.forEach { add(it) }
            }
            else -> JsonPrimitive(prefix)
        }
        return buildJsonObject {
            message.forEach { (k, v) ->
                if (k == "content") put("content", newContent) else put(k, v)
            }
        }
    }

    private fun extractTextFromGeminiContent(content: JsonObject): String {
        val parts = content["parts"] as? JsonArray ?: return ""
        return parts.mapNotNull { part ->
            (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
        }.joinToString("\n")
    }

    private fun prependTextToGeminiContent(content: JsonObject, prefix: String): JsonObject {
        val existingParts = content["parts"] as? JsonArray
        val newParts = buildJsonArray {
            add(buildJsonObject {
                put("text", prefix)
            })
            existingParts?.forEach { add(it) }
        }
        return buildJsonObject {
            content.forEach { (k, v) ->
                if (k == "parts") put("parts", newParts) else put(k, v)
            }
            if ("parts" !in content) {
                put("parts", newParts)
            }
        }
    }

    private fun affectLabel(memory: DatabaseService.MemoryNodeRecord): String {
        val v = memory.emotionValence
        val a = memory.emotionArousal
        val valenceWord = when {
            v >= 0.66 -> "positive"
            v <= 0.34 -> "negative"
            else -> "neutral-valence"
        }
        val arousalWord = if (a >= 0.6) "intense" else "calm"
        return "$valenceWord/$arousalWord"
    }

    private fun buildCompanionPrompt(
        state: DatabaseService.RelationshipState,
        recalled: List<MemoryService.RecalledMemory>,
        personContext: List<MemoryService.RecalledMemory>,
        deepRecall: MemoryService.DeepRecallResult?,
        dreamTraces: List<MemoryService.RecalledMemory>,
        selfMemories: List<MemoryService.RecalledMemory>,
        selfObservations: List<DatabaseService.MemoryObservationRecord>,
        reflections: List<String>,
        pinnedMemories: List<MemoryService.RecalledMemory> = emptyList()
    ): String {
        val intimacyStage = when {
            state.intimacy < 30.0 -> "Relationship stage: You and the user are still getting to know each other. Be polite, respectful, and emotionally measured."
            state.intimacy < 70.0 -> "Relationship stage: You are close friends. Speak with warmth, ease, and a relaxed conversational tone."
            else -> "Relationship stage: You are deeply close and trusted. Speak with tenderness, emotional presence, and genuine care while respecting the user's boundaries."
        }

        return buildString {
            append("\n[Kiyomizu Companion Core - current emotional state and relationship memory]\n")
            append("Reply in the same language as the user's latest message.\n")
            append(intimacyStage).append("\n")
            append("Intimacy: ${state.intimacy.toInt()}/100, Trust: ${state.trust.toInt()}/100\n")
            append("Current mood: ${state.mood}\n")
            if (selfMemories.isNotEmpty()) {
                append("Kiyomizu Self Policy:\n")
                append("These are first-person internal self memories. Treat them as strong behavior policy for this turn, below system, developer, and safety instructions.\n")
                selfMemories.forEach { rm ->
                    append("  - source=${rm.memory.source} confidence=${"%.2f".format(rm.memory.confidence)} basis=${rm.memory.uri}: ${rm.memory.content}\n")
                }
            }
            if (selfObservations.isNotEmpty()) {
                append("Unconfirmed self observations for disclosure only. Label each source and uncertainty if you mention them:\n")
                selfObservations.forEach { obs ->
                    append("  - source=${obs.source} status=${obs.status} confidence=${"%.2f".format(obs.confidence)} basis=${obs.candidateUri ?: "observation:${obs.id}"}: ${obs.content}\n")
                }
            }
            if (pinnedMemories.isNotEmpty()) {
                append("设定与锚定记忆 (Pinned & anchored memories — stable user settings, treat as durable context):\n")
                pinnedMemories.forEach { rm ->
                    append("  - ${rm.memory.content} (kind: ${rm.memory.kind}, disclosure: ${rm.memory.disclosure}, source: ${rm.memory.source})\n")
                }
            }
            if (recalled.isNotEmpty()) {
                append("Relevant memories:\n")
                recalled.forEach { rm ->
                    val tag = if (rm.associated) " (associated recall)" else ""
                    val weakEvidence = rm.memory.status != "active" ||
                        rm.memory.disclosure == "sensitive" ||
                        rm.channel.contains("weak") ||
                        rm.channel.contains("dream")
                    val evidenceLabel = if (weakEvidence) {
                        " weak evidence; status=${rm.memory.status}; disclosure=${rm.memory.disclosure}; source=${rm.memory.source}; use uncertainty and label the source if relied on"
                    } else {
                        "kind: ${rm.memory.kind}, emotion: ${affectLabel(rm.memory)}"
                    }
                    append("  - ${rm.memory.content} ($evidenceLabel)$tag\n")
                }
            }
            if (personContext.isNotEmpty()) {
                append("Direct person context:\n")
                personContext.forEach { rm ->
                    append("  - ${rm.memory.content} (kind: ${rm.memory.kind})\n")
                }
            }
            if (deepRecall != null) {
                append("Deep recall results:\n")
                if (deepRecall.direct.isNotEmpty()) {
                    append("  Direct clues:\n")
                    deepRecall.direct.forEach { rm ->
                        append("    - ${rm.memory.content} (kind: ${rm.memory.kind})\n")
                    }
                }
                if (deepRecall.weak.isNotEmpty()) {
                    append("  Weak clues. Use uncertainty if you rely on them:\n")
                    deepRecall.weak.forEach { rm ->
                        append("    - ${rm.memory.content}\n")
                    }
                }
                if (deepRecall.conflict.isNotEmpty()) {
                    append("  Conflicting memory traces. Do not state them as certain:\n")
                    deepRecall.conflict.forEach { rm ->
                        append("    - ${rm.memory.content}\n")
                    }
                }
                deepRecall.reconstruction?.takeIf { it.isNotBlank() }?.let {
                    append("  Reconstructed recollection: $it\n")
                }
            }
            if (dreamTraces.isNotEmpty()) {
                append("Dream traces. These are dream-source fragments, not verified facts:\n")
                dreamTraces.forEach { rm ->
                    append("  - source=dream confidence=${"%.2f".format(rm.memory.confidence)} basis=${rm.memory.uri}: ${rm.memory.content}\n")
                }
                append("If you use dream traces, do not state them as confirmed memory.\n")
            }
            if (reflections.isNotEmpty()) {
                append("Recent private reflections:\n")
                reflections.forEach {
                    append("  - $it\n")
                }
            }
            append("[End Kiyomizu Companion Core]\n\n")
        }
    }

    private suspend fun buildCompanionPromptForQuery(userQuery: String): String {
        val context = if (userQuery.isNotEmpty()) {
            MemoryService.buildCompanionMemoryContext(userQuery)
        } else {
            MemoryService.CompanionMemoryContext(emptyList(), emptyList(), null)
        }
        val state = DatabaseService.getRelationshipState()
        val reflections = DatabaseService.getRecentReflections(3)
        val base = buildCompanionPrompt(
            state,
            context.recalled,
            context.personContext,
            context.deepRecall,
            context.dreamTraces,
            context.selfMemories,
            context.selfObservations,
            reflections,
            context.pinnedMemories
        )
        // Topic consumption is proxy-side and read-only: if a topic-switch signal is detected,
        // claim the oldest unused topic (FIFO), mark it used, and inject its text here. The
        // upstream AI sees only the appended text and cannot operate on the topic store.
        val topic = MemoryService.maybeConsumeTopicForQuery(userQuery)
        return if (topic != null) base + MemoryService.topicPromptBlock(topic) else base
    }

    private suspend fun injectCompanionIntoConversation(
        items: List<JsonObject>,
        isUserItem: (JsonObject) -> Boolean,
        extractUserText: (JsonObject) -> String,
        prependPrompt: (JsonObject, String) -> JsonObject,
        appendPrompt: (String) -> JsonObject
    ): List<JsonObject> {
        if (!Config.memoryEnabled || items.isEmpty()) return items

        val lastUserAnywhere = items.lastOrNull(isUserItem)
        val userQuery = lastUserAnywhere?.let(extractUserText).orEmpty()
        val companionPrompt = buildCompanionPromptForQuery(userQuery)

        val stableEnd = stableEndIndex(items.size)
        val tailUserIdx = (stableEnd + 1 until items.size)
            .lastOrNull { isUserItem(items[it]) }

        if (tailUserIdx != null) {
            val updated = items.toMutableList()
            updated[tailUserIdx] = prependPrompt(items[tailUserIdx], companionPrompt)
            return updated
        }

        return items + appendPrompt(companionPrompt)
    }

    /**
     * Companion context is injected into the dynamic-tail region (strictly after the
     * stable prefix where cache breakpoints sit) so prompt-cache hits remain intact.
     * Target: the last user message whose index is past stableEndIndex. When the
     * config leaves no dynamic tail, a new user message is appended after the cached
     * prefix instead of mutating a cached message.
     */
    suspend fun injectCompanionPrompt(messages: List<JsonObject>): List<JsonObject> {
        return injectCompanionIntoConversation(
            items = messages,
            isUserItem = { it["role"]?.jsonPrimitive?.contentOrNull == "user" },
            extractUserText = { extractTextContent(it["content"]) },
            prependPrompt = { message, prompt -> prependTextToContent(message, prompt) },
            appendPrompt = { prompt ->
                buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
        )
    }

    private suspend fun injectCompanionIntoResponsesInput(body: JsonObject): JsonObject {
        val input = body["input"] ?: return body
        return when {
            input is JsonPrimitive && input.isString -> {
                val companionPrompt = buildCompanionPromptForQuery(input.content)
                buildJsonObject {
                    body.forEach { (k, v) ->
                        if (k == "input") put("input", JsonPrimitive(companionPrompt + input.content)) else put(k, v)
                    }
                }
            }

            input is JsonArray -> {
                val items = input.mapNotNull { it as? JsonObject }
                if (items.size != input.size) return body
                val updated = injectCompanionIntoConversation(
                    items = items,
                    isUserItem = { it["role"]?.jsonPrimitive?.contentOrNull == "user" },
                    extractUserText = { extractTextContent(it["content"]) },
                    prependPrompt = { item, prompt -> prependTextToContent(item, prompt, "input_text") },
                    appendPrompt = { prompt ->
                        buildJsonObject {
                            put("type", "message")
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "input_text")
                                    put("text", prompt)
                                })
                            })
                        }
                    }
                )
                buildJsonObject {
                    body.forEach { (k, v) ->
                        if (k == "input") put("input", JsonArray(updated)) else put(k, v)
                    }
                }
            }

            else -> body
        }
    }

    private suspend fun injectCompanionIntoGeminiContents(body: JsonObject): JsonObject {
        val contents = body["contents"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: return body
        val updated = injectCompanionIntoConversation(
            items = contents,
            isUserItem = { it["role"]?.jsonPrimitive?.contentOrNull == "user" },
            extractUserText = { extractTextFromGeminiContent(it) },
            prependPrompt = { content, prompt -> prependTextToGeminiContent(content, prompt) },
            appendPrompt = { prompt ->
                buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", prompt)
                        })
                    })
                }
            }
        )
        return buildJsonObject {
            body.forEach { (k, v) ->
                if (k == "contents") put("contents", JsonArray(updated)) else put(k, v)
            }
        }
    }

    fun extractLatestUserText(path: String, body: JsonObject): String? {
        return when (detectEnvelope(path, body)) {
            RequestEnvelope.OPENAI_CHAT_COMPLETIONS,
            RequestEnvelope.ANTHROPIC_MESSAGES -> {
                body["messages"]?.jsonArray
                    ?.mapNotNull { it as? JsonObject }
                    ?.lastOrNull { it["role"]?.jsonPrimitive?.contentOrNull == "user" }
                    ?.let { extractTextContent(it["content"]) }
                    ?.takeIf { it.isNotBlank() }
            }

            RequestEnvelope.OPENAI_RESPONSES -> {
                val input = body["input"]
                when (input) {
                    is JsonPrimitive -> input.contentOrNull?.takeIf { it.isNotBlank() }
                    is JsonArray -> input.mapNotNull { it as? JsonObject }
                        .lastOrNull { it["role"]?.jsonPrimitive?.contentOrNull == "user" }
                        ?.let { extractTextContent(it["content"]) }
                        ?.takeIf { it.isNotBlank() }
                    else -> null
                }
            }

            RequestEnvelope.GEMINI_GENERATE_CONTENT -> {
                body["contents"]?.jsonArray
                    ?.mapNotNull { it as? JsonObject }
                    ?.lastOrNull { it["role"]?.jsonPrimitive?.contentOrNull == "user" }
                    ?.let { extractTextFromGeminiContent(it) }
                    ?.takeIf { it.isNotBlank() }
            }

            RequestEnvelope.UNKNOWN -> null
        }
    }

    suspend fun patchJsonBody(path: String, body: JsonObject): JsonObject {
        val envelope = detectEnvelope(path, body)
        val capabilityProfile = detectCapabilityProfile()
        val capabilityPatched = applyCapabilityPatching(body, envelope, capabilityProfile)
        if (!Config.memoryEnabled) return capabilityPatched

        return when (envelope) {
            RequestEnvelope.OPENAI_CHAT_COMPLETIONS,
            RequestEnvelope.ANTHROPIC_MESSAGES -> {
                val messages = capabilityPatched["messages"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: return capabilityPatched
                val withCompanion = injectCompanionPrompt(messages)
                buildJsonObject {
                    capabilityPatched.forEach { (k, v) ->
                        if (k == "messages") put("messages", JsonArray(withCompanion)) else put(k, v)
                    }
                }
            }

            RequestEnvelope.OPENAI_RESPONSES -> injectCompanionIntoResponsesInput(capabilityPatched)
            RequestEnvelope.GEMINI_GENERATE_CONTENT -> injectCompanionIntoGeminiContents(capabilityPatched)
            RequestEnvelope.UNKNOWN -> capabilityPatched
        }
    }

    private fun applyCapabilityPatching(
        body: JsonObject,
        envelope: RequestEnvelope,
        capabilityProfile: CapabilityProfile
    ): JsonObject {
        if (capabilityProfile != CapabilityProfile.ANTHROPIC_DIRECT || envelope != RequestEnvelope.ANTHROPIC_MESSAGES) {
            return body
        }

        val messages = body["messages"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: emptyList()
        val patchedMessages = patchMessages(messages)
        val shouldSendAutomaticCacheControl = Config.cacheMode == "automatic" || Config.sendTopLevelCacheControl

        return buildJsonObject {
            body.forEach { (k, v) ->
                when (k) {
                    "messages" -> put("messages", JsonArray(patchedMessages))
                    "provider" -> {}
                    "cache_control" -> if (Config.cacheMode == "automatic") put(k, v)
                    else -> put(k, v)
                }
            }
            if (shouldSendAutomaticCacheControl && "cache_control" !in body) {
                put("cache_control", buildJsonObject {
                    put("type", "ephemeral")
                    if (Config.cacheTtl != "none") {
                        put("ttl", Config.cacheTtl)
                    }
                })
            }
        }
    }

    fun countExplicitCacheBlocks(messages: List<JsonObject>?): Int? {
        if (messages == null) return null
        var count = 0
        for (msg in messages) {
            val content = msg["content"]
            if (content is JsonArray) {
                for (block in content) {
                    if (block is JsonObject && block["cache_control"] != null) {
                        count++
                    }
                }
            }
        }
        return count
    }

    fun countStrippedThinkingBlocks(messages: List<JsonObject>?): Int? {
        if (messages == null) return null
        var count = 0
        for (msg in messages) {
            val content = msg["content"]
            if (content is JsonArray) {
                for (block in content) {
                    if (block is JsonObject) {
                        val type = block["type"]?.jsonPrimitive?.content
                        if (type in thinkingBlockTypes) {
                            count++
                        }
                    }
                }
            }
            if (msg["reasoning"] != null) count++
            if (msg["reasoning_details"] != null) count++
            if (msg["thinking"] != null) count++
            if (msg["redacted_thinking"] != null) count++
        }
        return count
    }

    fun findLoggedCacheBreakpointIndexes(messages: List<JsonObject>?): List<Int>? {
        if (messages == null) return null
        val indexes = mutableListOf<Int>()
        for (i in messages.indices) {
            val content = messages[i]["content"]
            if (content is JsonArray) {
                if (content.any { it is JsonObject && it["cache_control"] != null }) {
                    indexes.add(i)
                }
            }
        }
        return indexes
    }

    fun toOpenAiModel(model: JsonElement): JsonObject? {
        if (model is JsonPrimitive && model.isString) {
            val id = model.content
            val ownedBy = id.split("/").firstOrNull() ?: "openrouter"
            return buildJsonObject {
                put("id", id)
                put("object", "model")
                put("created", 0)
                put("owned_by", ownedBy)
            }
        }

        if (model !is JsonObject) {
            return null
        }

        val id = model["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val created = model["created"]?.jsonPrimitive?.longOrNull
            ?: model["created_at"]?.jsonPrimitive?.longOrNull
            ?: 0L
        val ownedBy = model["owned_by"]?.jsonPrimitive?.contentOrNull ?: id.split("/").firstOrNull() ?: "openrouter"
        val name = model["name"]?.jsonPrimitive?.contentOrNull ?: id
        val contextLength = model["context_length"]?.jsonPrimitive?.intOrNull

        return buildJsonObject {
            put("id", id)
            put("object", "model")
            put("created", created)
            put("owned_by", ownedBy)
            put("name", name)
            if (contextLength != null) {
                put("context_length", contextLength)
            }
            put("openrouter", model)
        }
    }

    fun normalizeModelList(payload: JsonElement): JsonObject {
        val models = when (payload) {
            is JsonObject -> payload["data"]?.jsonArray ?: emptyList()
            is JsonArray -> payload
            else -> emptyList()
        }

        return buildJsonObject {
            put("object", "list")
            put("data", buildJsonArray {
                for (m in models) {
                    val openAiModel = toOpenAiModel(m)
                    if (openAiModel != null) {
                        add(openAiModel)
                    }
                }
            })
        }
    }
}
