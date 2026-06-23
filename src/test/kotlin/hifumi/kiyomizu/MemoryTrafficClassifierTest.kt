package hifumi.kiyomizu

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryTrafficClassifierTest {
    @BeforeTest
    fun resetConfig() {
        Config.memoryTrafficClassifierEnabled = true
        Config.memoryToolInternalBypassEnabled = true
        Config.memoryUnknownDisableWrite = true
        Config.memoryTaskDisableAffect = true
    }

    private fun chatBody(text: String) = buildJsonObject {
        put("model", "test-model")
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", text)
            })
        })
    }

    @Test
    fun hermesSkillMaintenanceFingerprintsBypassMemory() {
        val text = """
            Review the conversation above and update the skill library.
            You can only call memory and skill management tools.
            Target shape of the library: keep durable skills only.
        """.trimIndent()

        val decision = MemoryTrafficClassifier.classify("/v1/messages", chatBody(text), text)

        assertEquals(MemoryTrafficClassifier.TrafficClass.TOOL_INTERNAL, decision.trafficClass)
        assertFalse(decision.actions.injectCompanion)
        assertFalse(decision.actions.recallMemory)
        assertFalse(decision.actions.useModelRecall)
        assertFalse(decision.actions.extractMemory)
        assertFalse(decision.actions.updateAffect)
        assertTrue(decision.reasons.contains("skill_library_update_instruction"))
        assertTrue(decision.reasons.contains("memory_skill_tools_only"))
    }

    @Test
    fun strongFingerprintsCanBeDetectedOutsideLatestUserText() {
        val body = buildJsonObject {
            put("model", "test-model")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", "Review the conversation above and update the skill library. Protected skills must not be overwritten.")
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "ok")
                })
            })
        }

        val decision = MemoryTrafficClassifier.classify("/v1/messages", body, "ok")

        assertEquals(MemoryTrafficClassifier.TrafficClass.TOOL_INTERNAL, decision.trafficClass)
        assertFalse(decision.actions.extractMemory)
    }

    @Test
    fun ordinarySkillLibraryDiscussionIsNotToolInternal() {
        val text = "skill library 怎么设计比较好？"

        val decision = MemoryTrafficClassifier.classify("/v1/messages", chatBody(text), text)

        assertTrue(decision.trafficClass != MemoryTrafficClassifier.TrafficClass.TOOL_INTERNAL)
        assertTrue(decision.actions.injectCompanion)
    }

    @Test
    fun normalChatStaysConversation() {
        val text = "今天虽然夏天但不算太热"

        val decision = MemoryTrafficClassifier.classify("/v1/messages", chatBody(text), text)

        assertEquals(MemoryTrafficClassifier.TrafficClass.CONVERSATION, decision.trafficClass)
        assertTrue(decision.actions.useModelRecall)
        assertTrue(decision.actions.extractMemory)
        assertTrue(decision.actions.updateAffect)
    }

    @Test
    fun developmentTaskGetsReadonlyMemoryActions() {
        val text = "帮我修这个 Kotlin bug"

        val decision = MemoryTrafficClassifier.classify("/v1/messages", chatBody(text), text)

        assertEquals(MemoryTrafficClassifier.TrafficClass.TASK, decision.trafficClass)
        assertTrue(decision.actions.injectCompanion)
        assertTrue(decision.actions.recallMemory)
        assertFalse(decision.actions.useModelRecall)
        assertFalse(decision.actions.extractMemory)
        assertFalse(decision.actions.updateAffect)
    }
}
