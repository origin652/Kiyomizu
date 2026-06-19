package hifumi.kiyomizu

import kotlinx.serialization.json.*

object MessagePatcher {
    private val thinkingBlockTypes = setOf("thinking", "redacted_thinking", "reasoning", "reasoning_details", "reasoning_content")
    private val providerMap = mapOf(
        "anthropic" to "anthropic",
        "bedrock" to "amazon-bedrock",
        "vertex" to "google-vertex",
        "aistudio" to "google-ai-studio"
    )

    fun resolvedProvider(): String {
        return providerMap[Config.forceProvider] ?: Config.forceProvider
    }

    fun isCacheProvider(): Boolean {
        return Config.forceProvider == "anthropic"
    }

    fun isGeminiModel(model: String?): Boolean {
        if (model == null) return false
        val normalizedModel = model.lowercase()
        return Config.geminiModelFilter.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .any { normalizedModel.contains(it) }
    }

    fun resolvedGeminiProvider(): String {
        return providerMap[Config.geminiProvider] ?: Config.geminiProvider
    }

    fun mergeGeminiProvider(provider: JsonElement?): JsonObject {
        val current = if (provider is JsonObject) provider else emptyMap()
        return buildJsonObject {
            current.forEach { (k, v) -> put(k, v) }
            put("only", buildJsonArray { add(resolvedGeminiProvider()) })
        }
    }

    fun mergeProvider(provider: JsonElement?): JsonObject {
        val current = if (provider is JsonObject) provider else emptyMap()
        return buildJsonObject {
            current.forEach { (k, v) -> put(k, v) }
            put("only", buildJsonArray { add(resolvedProvider()) })
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

    fun stableEndIndex(messages: List<JsonObject>): Int {
        if (messages.isEmpty()) return -1
        if (Config.cacheStrategy == "last") {
            return messages.size - 1
        }
        val tail = if (Config.dynamicTailMessages >= 0) Config.dynamicTailMessages else 1
        val stableIndex = messages.size - tail - 1
        if (stableIndex >= 0) return stableIndex
        return messages.size - 1
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

    fun patchJsonBody(body: JsonObject): JsonObject {
        val model = body["model"]?.jsonPrimitive?.contentOrNull
        if (isGeminiModel(model)) {
            val messages = body["messages"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: emptyList()
            val patchedMessages = patchGeminiMessages(messages)
            val provider = body["provider"]
            return buildJsonObject {
                body.forEach { (k, v) ->
                    when (k) {
                        "messages" -> put("messages", JsonArray(patchedMessages))
                        "provider" -> put("provider", mergeGeminiProvider(provider))
                        else -> put(k, v)
                    }
                }
                if ("provider" !in body) {
                    put("provider", mergeGeminiProvider(null))
                }
            }
        }

        if (!shouldPatchModel(model)) {
            return body
        }

        val messages = body["messages"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: emptyList()
        val patchedMessages = patchMessages(messages)
        val provider = body["provider"]

        return buildJsonObject {
            body.forEach { (k, v) ->
                when (k) {
                    "messages" -> put("messages", JsonArray(patchedMessages))
                    "provider" -> put("provider", mergeProvider(provider))
                    else -> put(k, v)
                }
            }
            if ("provider" !in body) {
                put("provider", mergeProvider(null))
            }
            if (Config.sendTopLevelCacheControl && "cache_control" !in body) {
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
