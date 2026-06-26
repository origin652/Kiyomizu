package hifumi.kiyomizu.http.companion

import hifumi.kiyomizu.Config
import hifumi.kiyomizu.ConfigAuth
import hifumi.kiyomizu.DatabaseService
import hifumi.kiyomizu.MemoryService
import hifumi.kiyomizu.http.receiveTextLimited
import hifumi.kiyomizu.http.requireConfigAuth
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

fun Route.installCompanionRoutes() {
    get("/api/companion/state") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@get }
    if (!requireConfigAuth(call)) return@get
    val state = DatabaseService.getRelationshipState()
    val reflections = DatabaseService.getRecentReflectionsDetailed(20)
    val graphNodeCount = DatabaseService.getGraphNodeCount()
    val activeGraphNodeCount = DatabaseService.getGraphNodeCount("active")
    val dreamNodeCount = DatabaseService.getGraphNodeCount("dream")
    val archivedNodeCount = DatabaseService.getGraphNodeCount("archived")
    val tombstoneNodeCount = DatabaseService.getGraphNodeCount("tombstone")
    val graphEdgeCount = DatabaseService.getGraphEdgeCount()
    val searchTermCount = DatabaseService.getSearchTermCount()
    val bufferedObservationCount = DatabaseService.getBufferedObservationCount()
    val workingMemoryCount = DatabaseService.getWorkingMemoryCount()
    val affect = DatabaseService.getGraphAffectDistribution()
    val deepRecall = MemoryService.lastDeepRecallSummary()
    val dream = MemoryService.lastConsolidationSummary()
    val maintenanceDiagnostics = MemoryService.autoMaintenanceDiagnostics()
    val longIdlePaused = MemoryService.isLongIdleMaintenancePaused()
    val modelRecallDiagnostics = MemoryService.modelRecallDiagnostics()
    call.respondText(buildJsonObject {
    put("intimacy", state.intimacy)
    put("trust", state.trust)
    put("mood", state.mood)
    put("last_interaction_at", state.lastInteractionAt)
    put("graph_node_count", graphNodeCount)
    put("active_graph_node_count", activeGraphNodeCount)
    put("dream_node_count", dreamNodeCount)
    put("archived_node_count", archivedNodeCount)
    put("tombstone_node_count", tombstoneNodeCount)
    put("graph_edge_count", graphEdgeCount)
    put("search_term_count", searchTermCount)
    put("buffered_observation_count", bufferedObservationCount)
    put("working_memory_count", workingMemoryCount)
    put("last_deep_recall_at", deepRecall["at"]?.jsonPrimitive?.longOrNull ?: 0L)
    put("last_deep_recall_candidates", deepRecall["candidates"]?.jsonPrimitive?.intOrNull ?: 0)
    put("last_deep_recall_clues", deepRecall["clues"]?.jsonPrimitive?.intOrNull ?: 0)
    put("last_dream_at", dream["at"]?.jsonPrimitive?.longOrNull ?: 0L)
    put("last_dream_status", dream["status"]?.jsonPrimitive?.contentOrNull ?: "never")
    put("last_dream_mode", dream["mode"]?.jsonPrimitive?.contentOrNull ?: "")
    put("last_dream_input_nodes", dream["input_nodes"]?.jsonPrimitive?.intOrNull ?: 0)
    put("last_dream_archived", dream["archived"]?.jsonPrimitive?.intOrNull ?: 0)
    put("last_dream_created_dream", dream["created_dream"]?.jsonPrimitive?.intOrNull ?: 0)
    put("last_dream_created_consolidated", dream["created_consolidated"]?.jsonPrimitive?.intOrNull ?: 0)
    put("last_dream_skipped", dream["skipped"]?.jsonPrimitive?.intOrNull ?: 0)
    put("last_dream_error", dream["error"]?.jsonPrimitive?.contentOrNull ?: "")
    put("last_dream_summary", dream["dream_summary"]?.jsonPrimitive?.contentOrNull ?: "")
    put("last_dream_journal", dream["dream_journal"]?.jsonPrimitive?.contentOrNull ?: "")
    put("dream_next_allowed_at", dream["next_allowed_at"]?.jsonPrimitive?.longOrNull ?: 0L)
    put("memory_long_idle_paused", longIdlePaused)
    put("auto_maintenance_diagnostics", maintenanceDiagnostics)
    put("model_recall_diagnostics", modelRecallDiagnostics)
    put("affect_distribution", affect)
    put("reflections", buildJsonArray {
    reflections.forEach { r ->
    add(buildJsonObject {
    put("id", r.id)
    put("diary_entry", r.diaryEntry)
    put("created_at", r.createdAt)
    })
    }
    })
    }.toString(), ContentType.Application.Json)
    }
    
    get("/api/companion/memory-index") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@get }
    if (!requireConfigAuth(call)) return@get
    call.respondText(MemoryService.memoryIndexJson().toString(), ContentType.Application.Json)
    }
    
    post("/api/companion/memory-index/rebuild") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@post }
    if (!requireConfigAuth(call)) return@post
    call.respondText(MemoryService.rebuildMemoryIndex().toString(), ContentType.Application.Json)
    }
    
    get("/api/companion/recall-traces") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@get }
    if (!requireConfigAuth(call)) return@get
    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 50
    call.respondText(MemoryService.recentModelRecallTracesJson(limit).toString(), ContentType.Application.Json)
    }
    
    get("/api/companion/memories") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@get }
    if (!requireConfigAuth(call)) return@get
    val q = call.request.queryParameters["q"]
    val uriPrefix = call.request.queryParameters["uri_prefix"]
    val kind = call.request.queryParameters["kind"]
    val disclosure = call.request.queryParameters["disclosure"]
    val status = call.request.queryParameters["status"]
    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 100
    val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val total = call.request.queryParameters["total"]?.toIntOrNull()?.let { if (it == 1) true else false } ?: false
    val memories = DatabaseService.listMemoryNodes(q, uriPrefix, kind, disclosure, status, limit, offset)
    val aggregate = if (total) DatabaseService.countMemoryNodes(q, uriPrefix, kind, disclosure, status) else null
    call.respondText(buildJsonObject {
    if (aggregate != null) put("total", aggregate)
    put("memories", buildJsonArray {
    memories.forEach { m ->
    add(buildJsonObject {
    put("id", m.id)
    put("uri", m.uri)
    put("content", m.content)
    put("kind", m.kind)
    put("emotion_valence", m.emotionValence)
    put("emotion_arousal", m.emotionArousal)
    put("priority", m.priority)
    put("confidence", m.confidence)
    put("disclosure", m.disclosure)
    put("strength", m.strength)
    put("access_count", m.accessCount)
    put("status", m.status)
    put("scope_hint", m.scopeHint ?: "")
    put("person_uri", m.personUri ?: "")
    put("project_uri", m.projectUri ?: "")
    put("keywords", buildJsonArray { m.keywords.forEach { add(it) } })
    put("aliases", buildJsonArray { m.aliases.forEach { add(it) } })
    put("entities", buildJsonArray { m.entities.forEach { add(it) } })
    put("topics", buildJsonArray { m.topics.forEach { add(it) } })
    put("trigger_phrases", buildJsonArray { m.triggerPhrases.forEach { add(it) } })
    put("created_at", m.createdAt)
    put("updated_at", m.updatedAt)
    put("last_accessed_at", m.lastAccessedAt)
    put("stability", m.stability)
    put("pinned", m.pinned)
    put("always_inject", m.alwaysInject)
    })
    }
    })
    }.toString(), ContentType.Application.Json)
    }
    
    // ---- Memory graph: create / edit / delete / neighbors / export / import ----
    // Literal sub-paths must be declared before {id} wildcard.
    
    post("/api/companion/memories") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@post }
    if (!requireConfigAuth(call)) return@post
    val bodyText = receiveTextLimited(call, Config.maxConfigRequestBytes) ?: return@post
    val body = try { Json.parseToJsonElement(bodyText) as? JsonObject } catch (e: Exception) { null }
    if (body == null) {
    call.respondText(buildJsonObject { put("error", "body must be a JSON object") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    return@post
    }
    val node = MemoryService.createMemoryNode(body)
    if (node == null) {
    call.respondText(buildJsonObject { put("error", "invalid memory payload (content blank or unknown kind)") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    } else {
    call.respondText(buildJsonObject { put("memory", MemoryService.memoryNodeBundleJson(node)) }.toString(), ContentType.Application.Json)
    }
    }
    
    get("/api/companion/memories/export") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@get }
    if (!requireConfigAuth(call)) return@get
    val q = call.request.queryParameters["q"]
    val uriPrefix = call.request.queryParameters["uri_prefix"]
    val kind = call.request.queryParameters["kind"]
    val disclosure = call.request.queryParameters["disclosure"]
    val status = call.request.queryParameters["status"]
    val bundle = MemoryService.exportMemoryBundle(q, uriPrefix, kind, disclosure, status)
    val ts = java.time.Instant.now().epochSecond
    call.response.header("Content-Disposition", "attachment; filename=\"memories-$ts.json\"")
    call.respondText(bundle.toString(), ContentType.Application.Json)
    }
    
    post("/api/companion/memories/import/preview") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@post }
    if (!requireConfigAuth(call)) return@post
    val bodyText = receiveTextLimited(call, Config.maxConfigRequestBytes) ?: return@post
    val body = try { Json.parseToJsonElement(bodyText) as? JsonObject } catch (e: Exception) { null }
    if (body == null) {
    call.respondText(buildJsonObject { put("error", "body must be a JSON object") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    return@post
    }
    call.respondText(MemoryService.previewImportBundle(body).toString(), ContentType.Application.Json)
    }
    
    post("/api/companion/memories/import") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@post }
    if (!requireConfigAuth(call)) return@post
    val bodyText = receiveTextLimited(call, Config.maxConfigRequestBytes) ?: return@post
    val body = try { Json.parseToJsonElement(bodyText) as? JsonObject } catch (e: Exception) { null }
    if (body == null) {
    call.respondText(buildJsonObject { put("error", "body must be a JSON object") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    return@post
    }
    call.respondText(MemoryService.importMemoryBundle(body).toString(), ContentType.Application.Json)
    }
    
    get("/api/companion/memories/edges/{edgeId}") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@get }
    if (!requireConfigAuth(call)) return@get
    val edgeId = call.parameters["edgeId"]?.toIntOrNull()
    if (edgeId == null) {
    call.respondText(buildJsonObject { put("error", "invalid edge id") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    return@get
    }
    val edge = MemoryService.memoryEdgeDetail(edgeId)
    if (edge == null) {
    call.respondText(buildJsonObject { put("error", "edge not found") }.toString(), ContentType.Application.Json, HttpStatusCode.NotFound)
    } else {
    val fromNode = DatabaseService.getMemoryNodeById(edge.fromNodeId)
    val toNode = DatabaseService.getMemoryNodeById(edge.toNodeId)
    call.respondText(buildJsonObject {
    put("edge", buildJsonObject {
    put("id", edge.id)
    put("from_id", edge.fromNodeId)
    put("to_id", edge.toNodeId)
    put("from_uri", fromNode?.uri ?: "")
    put("to_uri", toNode?.uri ?: "")
    put("relation", edge.relation)
    put("weight", edge.weight)
    put("created_at", edge.createdAt)
    put("updated_at", edge.updatedAt)
    })
    }.toString(), ContentType.Application.Json)
    }
    }
    
    get("/api/companion/memories/{id}") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@get }
    if (!requireConfigAuth(call)) return@get
    val id = call.parameters["id"]?.toIntOrNull()
    if (id == null) {
    call.respondText(buildJsonObject { put("error", "invalid memory id") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    return@get
    }
    val node = DatabaseService.getMemoryNodeById(id)
    if (node == null) {
    call.respondText(buildJsonObject { put("error", "memory not found") }.toString(), ContentType.Application.Json, HttpStatusCode.NotFound)
    } else {
    call.respondText(buildJsonObject { put("memory", MemoryService.memoryNodeBundleJson(node)) }.toString(), ContentType.Application.Json)
    }
    }
    
    get("/api/companion/memories/{id}/neighbors") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@get }
    if (!requireConfigAuth(call)) return@get
    val id = call.parameters["id"]?.toIntOrNull()
    if (id == null) {
    call.respondText(buildJsonObject { put("error", "invalid memory id") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    return@get
    }
    val neighbors = MemoryService.getMemoryNodeNeighbors(id, 500)
    if (neighbors == null) {
    call.respondText(buildJsonObject { put("error", "memory not found") }.toString(), ContentType.Application.Json, HttpStatusCode.NotFound)
    } else {
    val idToUri = listOf(neighbors.node) + neighbors.neighbors
    val uriById = idToUri.associate { it.id to it.uri }
    call.respondText(buildJsonObject {
    put("node", MemoryService.memoryNodeBundleJson(neighbors.node))
    put("neighbors", buildJsonArray {
    neighbors.neighbors.forEach { add(MemoryService.memoryNodeBundleJson(it)) }
    })
    put("edges", buildJsonArray {
    neighbors.edges.forEach { e ->
    add(buildJsonObject {
    put("id", e.id)
    put("from_id", e.fromNodeId)
    put("to_id", e.toNodeId)
    put("from_uri", uriById[e.fromNodeId] ?: "")
    put("to_uri", uriById[e.toNodeId] ?: "")
    put("relation", e.relation)
    put("weight", e.weight)
    })
    }
    })
    }.toString(), ContentType.Application.Json)
    }
    }
    
    patch("/api/companion/memories/{id}") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@patch }
    if (!requireConfigAuth(call)) return@patch
    val id = call.parameters["id"]?.toIntOrNull()
    if (id == null) {
    call.respondText(buildJsonObject { put("error", "invalid memory id") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    return@patch
    }
    val bodyText = receiveTextLimited(call, Config.maxConfigRequestBytes) ?: return@patch
    val body = try { Json.parseToJsonElement(bodyText) as? JsonObject } catch (e: Exception) { null }
    if (body == null) {
    call.respondText(buildJsonObject { put("error", "body must be a JSON object") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    return@patch
    }
    val node = MemoryService.editMemoryNode(id, body)
    if (node == null) {
    call.respondText(buildJsonObject { put("error", "memory not found or patch disallowed") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    } else {
    call.respondText(buildJsonObject { put("memory", MemoryService.memoryNodeBundleJson(node)) }.toString(), ContentType.Application.Json)
    }
    }
    
    delete("/api/companion/memories/{id}") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@delete }
    if (!requireConfigAuth(call)) return@delete
    val id = call.parameters["id"]?.toIntOrNull()
    if (id == null) {
    call.respondText(buildJsonObject { put("error", "invalid memory id") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    return@delete
    }
    val bodyText = runCatching { call.receiveText() }.getOrNull()
    val reason = try { bodyText?.let { (Json.parseToJsonElement(it) as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull } } catch (_: Exception) { null }
    ?: "manual memory delete"
    val ok = MemoryService.softDeleteMemoryNode(id, reason)
    call.respondText(buildJsonObject { put("ok", ok) }.toString(), ContentType.Application.Json, if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound)
    }
    
    post("/api/companion/memories/{id}/purge") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@post }
    if (!requireConfigAuth(call)) return@post
    val id = call.parameters["id"]?.toIntOrNull()
    if (id == null) {
    call.respondText(buildJsonObject { put("error", "invalid memory id") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    return@post
    }
    val ok = MemoryService.purgeMemoryNode(id)
    call.respondText(buildJsonObject { put("ok", ok) }.toString(), ContentType.Application.Json, if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound)
    }
    
    post("/api/companion/memories/{id}/restore") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@post }
    if (!requireConfigAuth(call)) return@post
    val id = call.parameters["id"]?.toIntOrNull()
    if (id == null) {
    call.respondText(buildJsonObject { put("error", "invalid memory id") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    return@post
    }
    val ok = MemoryService.restoreMemoryNode(id)
    call.respondText(buildJsonObject { put("ok", ok) }.toString(), ContentType.Application.Json, if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound)
    }
    
    delete("/api/companion/memories/edges/{edgeId}") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@delete }
    if (!requireConfigAuth(call)) return@delete
    val edgeId = call.parameters["edgeId"]?.toIntOrNull()
    if (edgeId == null) {
    call.respondText(buildJsonObject { put("error", "invalid edge id") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    return@delete
    }
    val ok = MemoryService.deleteMemoryEdge(edgeId)
    call.respondText(buildJsonObject { put("ok", ok) }.toString(), ContentType.Application.Json, if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound)
    }
    
    get("/api/companion/observations") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@get }
    if (!requireConfigAuth(call)) return@get
    val q = call.request.queryParameters["q"]
    val status = call.request.queryParameters["status"] ?: "buffered"
    val kind = call.request.queryParameters["kind"]
    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
    val observations = DatabaseService.listMemoryObservations(q, status, kind, limit)
    call.respondText(buildJsonObject {
    put("observations", buildJsonArray {
    observations.forEach { observation ->
    add(buildJsonObject {
    put("id", observation.id)
    put("candidate_uri", observation.candidateUri ?: "")
    put("kind", observation.kind)
    put("content", observation.content)
    put("status", observation.status)
    put("seen_count", observation.seenCount)
    put("priority", observation.priority)
    put("confidence", observation.confidence)
    put("person_uri", observation.personUri ?: "")
    put("project_uri", observation.projectUri ?: "")
    put("scope_hint", observation.scopeHint ?: "")
    put("matched_node_id", observation.matchedNodeId ?: 0)
    put("first_seen_at", observation.firstSeenAt)
    put("last_seen_at", observation.lastSeenAt)
    put("expires_at", observation.expiresAt)
    put("keywords", buildJsonArray { observation.keywords.forEach { add(it) } })
    put("topics", buildJsonArray { observation.topics.forEach { add(it) } })
    })
    }
    })
    }.toString(), ContentType.Application.Json)
    }
    
    get("/api/companion/self") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@get }
    if (!requireConfigAuth(call)) return@get
    val stable = DatabaseService.listSelfMemoryNodes("active", 100).filter { it.uri.startsWith("self://") }
    val buffered = DatabaseService.listSelfMemoryObservations("buffered", 100)
    val conflicts = DatabaseService.listSelfMemoryObservations("conflict", 100)
    val dreamSource = buffered.filter { it.source.contains("dream", ignoreCase = true) }
    val events = DatabaseService.listSelfMemoryEvents(50)
    call.respondText(buildJsonObject {
    put("stable_self", buildJsonArray { stable.forEach { add(memoryNodeJson(it)) } })
    put("buffered_self", buildJsonArray { buffered.forEach { add(memoryObservationJson(it)) } })
    put("dream_source_self", buildJsonArray { dreamSource.forEach { add(memoryObservationJson(it)) } })
    put("conflicts", buildJsonArray { conflicts.forEach { add(memoryObservationJson(it)) } })
    put("recent_events", buildJsonArray { events.forEach { add(selfEventJson(it)) } })
    }.toString(), ContentType.Application.Json)
    }
    
    post("/api/companion/self") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@post }
    if (!requireConfigAuth(call)) return@post
    val bodyText = receiveTextLimited(call, Config.maxConfigRequestBytes) ?: return@post
    val body = try { Json.parseToJsonElement(bodyText) as? JsonObject } catch (e: Exception) { null }
    val content = body?.get("content")?.jsonPrimitive?.contentOrNull?.trim()
    if (body == null || content.isNullOrBlank()) {
    call.respondText(buildJsonObject { put("error", "content must not be blank") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    return@post
    }
    val node = MemoryService.createSelfMemory(
    content = content,
    uri = body["uri"]?.jsonPrimitive?.contentOrNull,
    kind = body["kind"]?.jsonPrimitive?.contentOrNull,
    priority = body["priority"]?.jsonPrimitive?.doubleOrNull ?: 0.85,
    confidence = body["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.9,
    source = "config",
    reason = "manual self memory create"
    )
    if (node == null) {
    call.respondText(buildJsonObject { put("error", "self memory was not created") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    } else {
    call.respondText(buildJsonObject { put("self", memoryNodeJson(node)) }.toString(), ContentType.Application.Json)
    }
    }
    
    patch("/api/companion/self/{id}") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@patch }
    if (!requireConfigAuth(call)) return@patch
    val id = call.parameters["id"]?.toIntOrNull()
    if (id == null) {
    call.respondText(buildJsonObject { put("error", "invalid self id") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    return@patch
    }
    val bodyText = receiveTextLimited(call, Config.maxConfigRequestBytes) ?: return@patch
    val body = try { Json.parseToJsonElement(bodyText) as? JsonObject } catch (e: Exception) { null }
    if (body == null) {
    call.respondText(buildJsonObject { put("error", "body must be a JSON object") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    return@patch
    }
    val node = MemoryService.editSelfMemory(
    nodeId = id,
    content = body["content"]?.jsonPrimitive?.contentOrNull,
    uri = body["uri"]?.jsonPrimitive?.contentOrNull,
    priority = body["priority"]?.jsonPrimitive?.doubleOrNull,
    confidence = body["confidence"]?.jsonPrimitive?.doubleOrNull
    )
    if (node == null) {
    call.respondText(buildJsonObject { put("error", "self memory not found") }.toString(), ContentType.Application.Json, HttpStatusCode.NotFound)
    } else {
    call.respondText(buildJsonObject { put("self", memoryNodeJson(node)) }.toString(), ContentType.Application.Json)
    }
    }
    
    post("/api/companion/self/{id}/archive") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@post }
    if (!requireConfigAuth(call)) return@post
    val id = call.parameters["id"]?.toIntOrNull()
    if (id == null) {
    call.respondText(buildJsonObject { put("error", "invalid self id") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    return@post
    }
    val ok = MemoryService.archiveSelfMemory(id)
    call.respondText(buildJsonObject { put("ok", ok) }.toString(), ContentType.Application.Json, if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound)
    }
    
    post("/api/companion/self/observations/{id}/confirm") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@post }
    if (!requireConfigAuth(call)) return@post
    val id = call.parameters["id"]?.toIntOrNull()
    if (id == null) {
    call.respondText(buildJsonObject { put("error", "invalid observation id") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    return@post
    }
    val node = MemoryService.confirmSelfObservation(id)
    if (node == null) {
    call.respondText(buildJsonObject { put("error", "self observation not found or blocked by higher-priority self memory") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    } else {
    call.respondText(buildJsonObject { put("self", memoryNodeJson(node)) }.toString(), ContentType.Application.Json)
    }
    }
    
    post("/api/companion/self/events/{id}/revert") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@post }
    if (!requireConfigAuth(call)) return@post
    val id = call.parameters["id"]?.toIntOrNull()
    if (id == null) {
    call.respondText(buildJsonObject { put("error", "invalid event id") }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    return@post
    }
    val result = MemoryService.revertSelfMemoryEvent(id)
    val ok = result["ok"]?.jsonPrimitive?.booleanOrNull == true
    call.respondText(result.toString(), ContentType.Application.Json, if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest)
    }
    
    get("/api/companion/recycle-bin") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@get }
    if (!requireConfigAuth(call)) return@get
    val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
    val records = DatabaseService.listRecycleBin(limit)
    call.respondText(buildJsonObject {
    put("items", buildJsonArray {
    records.forEach { item ->
    add(buildJsonObject {
    put("id", item.id)
    put("node_id", item.nodeId)
    put("uri", item.uri)
    put("content", item.content ?: "")
    put("reason", item.reason ?: "")
    put("created_at", item.createdAt)
    put("purge_after", item.purgeAfter)
    put("keywords", buildJsonArray { item.keywords.forEach { add(it) } })
    put("topics", buildJsonArray { item.topics.forEach { add(it) } })
    })
    }
    })
    }.toString(), ContentType.Application.Json)
    }
    
    // ---- 话题 (Topics) -------------------------------------------------------
    get("/api/companion/topics") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@get }
    if (!requireConfigAuth(call)) return@get
    call.respondText(MemoryService.topicsStateJson().toString(), ContentType.Application.Json)
    }
    
    post("/api/companion/topics/generate") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@post }
    if (!requireConfigAuth(call)) return@post
    if (!Config.memoryEnabled || !Config.memoryTopicEnabled) {
    call.respondText(
    buildJsonObject { put("error", "topics are disabled") }.toString(),
    ContentType.Application.Json,
    HttpStatusCode.BadRequest
    )
    return@post
    }
    val result = MemoryService.runTopicGeneration()
    call.respondText(result.toString(), ContentType.Application.Json)
    }
    
    delete("/api/companion/topics/{id}") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@delete }
    if (!requireConfigAuth(call)) return@delete
    val id = call.parameters["id"]?.toIntOrNull()
    if (id == null) {
    call.respondText(
    buildJsonObject { put("error", "invalid id") }.toString(),
    ContentType.Application.Json,
    HttpStatusCode.BadRequest
    )
    return@delete
    }
    val ok = DatabaseService.deleteTopic(id)
    call.respondText(
    buildJsonObject { put("ok", ok) }.toString(),
    ContentType.Application.Json,
    if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound
    )
    }
    
    post("/api/companion/dream/dry-run") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@post }
    if (!requireConfigAuth(call)) return@post
    val todayStart = (java.time.Instant.now().epochSecond / 86400L) * 86400L
    if (DatabaseService.countDreamRunsSince("dry_run", todayStart) >= Config.memoryDreamDryRunDailyLimit) {
    call.respondText(buildJsonObject {
    put("error", "dream dry-run daily limit reached")
    }.toString(), ContentType.Application.Json, HttpStatusCode.TooManyRequests)
    return@post
    }
    val result = MemoryService.runDreamDryRun()
    call.respondText(result.toString(), ContentType.Application.Json)
    }
    
    post("/api/companion/dream/run") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@post }
    if (!requireConfigAuth(call)) return@post
    if (!Config.memoryEnabled) {
    call.respondText(buildJsonObject {
    put("error", "memory is disabled")
    }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
    return@post
    }
    val result = MemoryService.runManualDream()
    call.respondText(result.toString(), ContentType.Application.Json)
    }
    
    post("/api/companion/dream/confirm") {
    if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@post }
    if (!requireConfigAuth(call)) return@post
    val bodyText = receiveTextLimited(call, Config.maxConfigRequestBytes) ?: return@post
    val body = try { Json.parseToJsonElement(bodyText) as? JsonObject } catch (e: Exception) { null }
    if (body == null) {
    call.respondText(
    buildJsonObject { put("error", "body must be a JSON object") }.toString(),
    ContentType.Application.Json,
    HttpStatusCode.BadRequest
    )
    return@post
    }
    val result = MemoryService.confirmDreamTrace(
    dreamNodeId = body["dream_node_id"]?.jsonPrimitive?.intOrNull,
    dreamUri = body["dream_uri"]?.jsonPrimitive?.contentOrNull,
    targetUri = body["target_uri"]?.jsonPrimitive?.contentOrNull,
    kind = body["kind"]?.jsonPrimitive?.contentOrNull,
    content = body["content"]?.jsonPrimitive?.contentOrNull
    )
    val ok = result["ok"]?.jsonPrimitive?.booleanOrNull == true
    call.respondText(
    result.toString(),
    ContentType.Application.Json,
    if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest
    )
    }

    // Trust history curve: append-only samples written from the trust-change entry points.
    get("/api/companion/trust-history") {
        if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@get }
        if (!requireConfigAuth(call)) return@get
        val hours = (call.parameters["hours"]?.toIntOrNull() ?: 168).coerceIn(1, 24 * 365)
        val limit = (call.parameters["limit"]?.toIntOrNull() ?: 500).coerceIn(1, 5000)
        val since = java.time.Instant.now().epochSecond - hours.toLong() * 3600L
        val samples = DatabaseService.listTrustHistory(since, limit)
        call.respondText(
            buildJsonObject {
                put("hours", hours)
                put("samples", buildJsonArray {
                    samples.forEach { s ->
                        add(buildJsonObject {
                            put("at", s.at)
                            put("trust", s.trust)
                            put("delta", s.delta)
                            put("source", s.source)
                        })
                    }
                })
            }.toString(),
            ContentType.Application.Json
        )
    }

    // Dream diary: recent dream runs with their journals/symbols/emotions, newest first.
    // Only completed/skipped runs that actually produced a journal are listed.
    get("/api/companion/dream-runs") {
        if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@get }
        if (!requireConfigAuth(call)) return@get
        val limit = (call.parameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 200)
        val runs = DatabaseService.listDreamRuns(limit, onlyCompleted = true)
        call.respondText(
            buildJsonObject {
                put("runs", buildJsonArray {
                    runs.forEach { run ->
                        add(buildJsonObject {
                            put("id", run.id)
                            put("started_at", run.startedAt)
                            put("finished_at", run.finishedAt ?: 0L)
                            put("mode", run.mode)
                            put("status", run.status)
                            put("dream_summary", run.dreamSummary ?: "")
                            put("dream_journal", run.dreamJournal ?: "")
                            put("dream_symbols", buildJsonArray { run.dreamSymbols.forEach { add(JsonPrimitive(it)) } })
                            put("dream_emotions", buildJsonArray { run.dreamEmotions.forEach { add(JsonPrimitive(it)) } })
                            put("merged", run.mergedCount)
                            put("archived", run.archivedCount)
                            put("created_dream", run.createdDreamCount)
                            put("created_consolidated", run.createdConsolidatedCount)
                        })
                    }
                })
            }.toString(),
            ContentType.Application.Json
        )
    }
}