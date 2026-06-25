package hifumi.kiyomizu.http.companion

import hifumi.kiyomizu.DatabaseService
import kotlinx.serialization.json.*

fun memoryNodeJson(m: DatabaseService.MemoryNodeRecord): JsonObject {
    return buildJsonObject {
        put("id", m.id)
        put("uri", m.uri)
        put("content", m.content)
        put("kind", m.kind)
        put("source", m.source)
        put("status", m.status)
        put("priority", m.priority)
        put("confidence", m.confidence)
        put("strength", m.strength)
        put("person_uri", m.personUri ?: "")
        put("scope_hint", m.scopeHint ?: "")
        put("raw_evidence", m.rawEvidence ?: "")
        put("keywords", buildJsonArray { m.keywords.forEach { add(it) } })
        put("topics", buildJsonArray { m.topics.forEach { add(it) } })
        put("created_at", m.createdAt)
        put("updated_at", m.updatedAt)
    }
}

fun memoryObservationJson(observation: DatabaseService.MemoryObservationRecord): JsonObject {
    return buildJsonObject {
        put("id", observation.id)
        put("candidate_uri", observation.candidateUri ?: "")
        put("kind", observation.kind)
        put("content", observation.content)
        put("source", observation.source)
        put("status", observation.status)
        put("seen_count", observation.seenCount)
        put("priority", observation.priority)
        put("confidence", observation.confidence)
        put("person_uri", observation.personUri ?: "")
        put("scope_hint", observation.scopeHint ?: "")
        put("matched_node_id", observation.matchedNodeId ?: 0)
        put("raw_evidence", observation.rawEvidence ?: "")
        put("keywords", buildJsonArray { observation.keywords.forEach { add(it) } })
        put("topics", buildJsonArray { observation.topics.forEach { add(it) } })
        put("first_seen_at", observation.firstSeenAt)
        put("last_seen_at", observation.lastSeenAt)
        put("expires_at", observation.expiresAt)
    }
}

fun selfEventJson(event: DatabaseService.SelfMemoryEventRecord): JsonObject {
    return buildJsonObject {
        put("id", event.id)
        put("event_type", event.eventType)
        put("node_id", event.nodeId ?: 0)
        put("node_uri", event.nodeUri ?: "")
        put("observation_id", event.observationId ?: 0)
        put("previous_node_id", event.previousNodeId ?: 0)
        put("previous_node_uri", event.previousNodeUri ?: "")
        put("new_node_id", event.newNodeId ?: 0)
        put("new_node_uri", event.newNodeUri ?: "")
        put("source", event.source)
        put("reason", event.reason ?: "")
        put("content_before", event.contentBefore ?: "")
        put("content_after", event.contentAfter ?: "")
        put("created_at", event.createdAt)
    }
}