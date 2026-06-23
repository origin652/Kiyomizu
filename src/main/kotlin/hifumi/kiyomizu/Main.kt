package hifumi.kiyomizu

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.net.URI

fun main() {
    DatabaseService.initDatabase()
    ConfigAuth.loadPersisted(DatabaseService.loadConfigPassword())
    Config.loadPersisted(DatabaseService.loadConfig())

    if (Config.cacheMode == "automatic" && Config.preset != "anthropic") {
        println("Warning: CACHE_MODE was automatic but PRESET is not anthropic. Forcing cacheMode to explicit.")
        Config.cacheMode = "explicit"
    }

    println("Starting Kiyomizu Cache Proxy listening on http://${Config.host}:${Config.port}")
    println("Preset: ${Config.preset}")
    println("Upstream: ${Config.upstream.ifEmpty { "<unset>" }}")
    println("Cache mode: ${Config.cacheMode}")
    println("Send top-level cache_control: ${Config.sendTopLevelCacheControl}")
    println("Cache strategy: ${Config.cacheStrategy}")
    println("Cache breakpoints: ${Config.cacheBreakpoints}")
    println("Dynamic tail messages: ${Config.dynamicTailMessages}")
    println("Strip thinking: ${Config.stripThinking}")
    println("Model filter: ${Config.modelFilter}")
    if (Security.isPubliclyBound()) {
        if (!Security.isRemotePasswordSetupAllowed() && !ConfigAuth.isConfigured()) {
            println("Security: remote first-run password setup is disabled. Set KIYOMIZU_CONFIG_PASSWORD before exposing this server.")
        }
    }

    embeddedServer(Netty, port = Config.port, host = Config.host) {
        MemoryService.startDecayJob(this)
        MemoryService.startConsolidationJob(this)

        if (Security.shouldAllowBrowserCors()) {
            install(CORS) {
                allowHost("localhost")
                allowHost("127.0.0.1")
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.ContentType)
                allowHeader("http-referer")
                allowHeader("x-title")
                allowHeader("anthropic-beta")
                allowHeader(ConfigAuth.headerName)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Patch)
                allowMethod(HttpMethod.Delete)
                allowMethod(HttpMethod.Options)
                allowCredentials = true
                allowNonSimpleContentTypes = true
            }
        }

        install(createApplicationPlugin("SecurityHeaders") {
            onCallRespond { call ->
                call.response.headers.append("X-Content-Type-Options", "nosniff", safeOnly = true)
                call.response.headers.append("X-Frame-Options", "DENY", safeOnly = true)
                call.response.headers.append("Referrer-Policy", "no-referrer", safeOnly = true)
                call.response.headers.append("Permissions-Policy", "geolocation=(), microphone=(), camera=()", safeOnly = true)
                call.response.headers.append(
                    "Content-Security-Policy",
                    "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
                    safeOnly = true
                )
                if (call.request.path().startsWith("/api/")) {
                    call.response.headers.append(HttpHeaders.CacheControl, "no-store", safeOnly = true)
                }
            }
        })

        if (Security.shouldAllowBrowserCors()) {
            install(createApplicationPlugin("PrivateNetworkCORS") {
                onCallRespond { call ->
                    call.response.headers.append("Access-Control-Allow-Private-Network", "true", safeOnly = true)
                }
            })
        }

        routing {
            options("{...}") {
                call.respond(HttpStatusCode.NoContent)
            }

            get("/health") {
                val healthText = if (System.getenv("KIYOMIZU_VERBOSE_HEALTH") == "1") {
                    listOf(
                        "Kiyomizu Cache Proxy is running.",
                        "Preset: ${Config.preset}",
                        "Upstream: ${Config.upstream}",
                        "Prompt cache TTL: ${Config.cacheTtl}",
                        "Cache mode: ${Config.cacheMode}",
                        "Send top-level cache_control: ${Config.sendTopLevelCacheControl}",
                        "Cache strategy: ${Config.cacheStrategy}",
                        "Cache breakpoints: ${Config.cacheBreakpoints}",
                        "Dynamic tail messages: ${Config.dynamicTailMessages}",
                        "Strip thinking: ${Config.stripThinking}",
                        "Model filter: ${Config.modelFilter}"
                    ).joinToString("\n")
                } else {
                    "ok\n"
                }
                call.respondText(healthText, ContentType.Text.Plain)
            }

            get("/") {
                serveUi(call)
            }
            get("/ui") {
                serveUi(call)
            }
            get("/favicon.ico") {
                serveResource(call, "favicon.ico", ContentType.Image.XIcon)
            }

            route("/api/config") {
                get {
                    if (!ConfigAuth.isConfigured()) {
                        ConfigAuth.setupRequired(call)
                        return@get
                    }
                    if (!requireConfigAuth(call)) {
                        return@get
                    }
                    call.respondText(ConfigApi.publicConfigJson().toString(), ContentType.Application.Json)
                }
                post {
                    if (!ConfigAuth.isConfigured()) {
                        ConfigAuth.setupRequired(call)
                        return@post
                    }
                    if (!requireConfigAuth(call)) {
                        return@post
                    }
                    val bodyText = receiveTextLimited(call, Config.maxConfigRequestBytes) ?: return@post
                    val body = try { Json.parseToJsonElement(bodyText) as? JsonObject } catch(e: Exception) { null }
                    val updateResult = ConfigApi.applyUpdate(body)
                    if (updateResult.errors.isNotEmpty()) {
                        call.respondText(
                            buildJsonObject {
                                put("errors", buildJsonArray { updateResult.errors.forEach { add(it) } })
                            }.toString(),
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest
                        )
                    } else {
                        println("Config updated: preset=${Config.preset}, upstream=${Config.upstream.ifEmpty { "<unset>" }}, cacheTtl=${Config.cacheTtl}, cacheMode=${Config.cacheMode}, cacheStrategy=${Config.cacheStrategy}, cacheBreakpoints=${Config.cacheBreakpoints}, memoryEnabled=${Config.memoryEnabled}")
                        call.respondText(updateResult.responseBody.toString(), ContentType.Application.Json)
                    }
                }
            }

            post("/api/config/password") {
                if (ConfigAuth.isConfigured()) {
                    call.respondText(
                        buildJsonObject {
                            put("error", "config password is already configured")
                            put("config_password_setup_required", false)
                        }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.Conflict
                    )
                    return@post
                }
                if (!Security.isRemotePasswordSetupAllowed()) {
                    call.respondText(
                        buildJsonObject {
                            put("error", "remote first-run password setup is disabled; set KIYOMIZU_CONFIG_PASSWORD before exposing the server")
                            put("config_password_setup_required", true)
                        }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.Forbidden
                    )
                    return@post
                }

                val bodyText = receiveTextLimited(call, Config.maxConfigRequestBytes) ?: return@post
                val body = try { Json.parseToJsonElement(bodyText) as? JsonObject } catch(e: Exception) { null }
                val password = (body?.get("password") as? JsonPrimitive)?.contentOrNull?.trim()
                val confirmPassword = (body?.get("confirm_password") as? JsonPrimitive)?.contentOrNull?.trim()
                val errors = mutableListOf<String>()

                if (body == null) {
                    errors.add("body must be a JSON object")
                }
                if (password.isNullOrBlank()) {
                    errors.add("password must not be blank")
                }
                if (confirmPassword != null && confirmPassword != password) {
                    errors.add("password confirmation does not match")
                }

                if (errors.isNotEmpty()) {
                    call.respondText(
                        buildJsonObject {
                            put("errors", buildJsonArray { errors.forEach { add(it) } })
                            put("config_password_setup_required", true)
                        }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest
                    )
                    return@post
                }

                val result = ConfigAuth.configureInitialPassword(password!!)
                if (result.errors.isNotEmpty()) {
                    call.respondText(
                        buildJsonObject {
                            put("errors", buildJsonArray { result.errors.forEach { add(it) } })
                            put("config_password_setup_required", !ConfigAuth.isConfigured())
                        }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest
                    )
                    return@post
                }

                call.respondText(
                    buildJsonObject {
                        put("ok", true)
                        put("config_password_required", true)
                        put("config_password_setup_required", false)
                    }.toString(),
                    ContentType.Application.Json
                )
            }

            post("/api/config/password/change") {
                if (!ConfigAuth.isConfigured()) {
                    ConfigAuth.setupRequired(call)
                    return@post
                }
                if (!requireConfigAuth(call)) {
                    return@post
                }
                if (!ConfigAuth.isChangeable()) {
                    call.respondText(
                        buildJsonObject {
                            put("error", "config password is controlled by environment or system property")
                            put("config_password_changeable", false)
                        }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.Conflict
                    )
                    return@post
                }

                val bodyText = receiveTextLimited(call, Config.maxConfigRequestBytes) ?: return@post
                val body = try { Json.parseToJsonElement(bodyText) as? JsonObject } catch(e: Exception) { null }
                val currentPassword = (body?.get("current_password") as? JsonPrimitive)?.contentOrNull?.trim()
                val newPassword = (body?.get("new_password") as? JsonPrimitive)?.contentOrNull?.trim()
                val confirmPassword = (body?.get("confirm_password") as? JsonPrimitive)?.contentOrNull?.trim()
                val errors = mutableListOf<String>()

                if (body == null) {
                    errors.add("body must be a JSON object")
                }
                if (currentPassword.isNullOrBlank()) {
                    errors.add("current password must not be blank")
                }
                if (newPassword.isNullOrBlank()) {
                    errors.add("new password must not be blank")
                }
                if (confirmPassword != null && confirmPassword != newPassword) {
                    errors.add("password confirmation does not match")
                }

                if (errors.isNotEmpty()) {
                    call.respondText(
                        buildJsonObject {
                            put("errors", buildJsonArray { errors.forEach { add(it) } })
                            put("config_password_changeable", ConfigAuth.isChangeable())
                        }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest
                    )
                    return@post
                }

                val result = ConfigAuth.changePassword(currentPassword!!, newPassword!!)
                if (result.errors.isNotEmpty()) {
                    call.respondText(
                        buildJsonObject {
                            put("errors", buildJsonArray { result.errors.forEach { add(it) } })
                            put("config_password_changeable", ConfigAuth.isChangeable())
                        }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest
                    )
                    return@post
                }

                call.respondText(
                    buildJsonObject {
                        put("ok", true)
                        put("config_password_required", true)
                        put("config_password_setup_required", false)
                        put("config_password_changeable", ConfigAuth.isChangeable())
                    }.toString(),
                    ContentType.Application.Json
                )
            }

            val modelPaths = listOf("/models", "/model", "/v1/models", "/v1/model", "/api/v1/models", "/api/v1/model")
            modelPaths.forEach { path ->
                get(path) {
                    handleModelListProxy(call)
                }
            }

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
                val memories = DatabaseService.listMemoryNodes(q, uriPrefix, kind, disclosure, status, limit)
                call.respondText(buildJsonObject {
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
                            })
                        }
                    })
                }.toString(), ContentType.Application.Json)
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

            get("/api/logs") {
                if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@get }
                if (!requireConfigAuth(call)) return@get
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                val logs = DatabaseService.getRecentRequestLogs(limit)
                call.respondText(buildJsonObject {
                    put("logs", buildJsonArray {
                        logs.forEach { l ->
                            add(buildJsonObject {
                                put("id", l.id)
                                put("at", l.at)
                                put("method", l.method)
                                put("pathname", l.pathname)
                                put("patched", l.patched)
                                put("removed_thinking_blocks", l.removedThinkingBlocks)
                                if (l.model != null) put("model", l.model)
                                if (l.messageCount != null) put("message_count", l.messageCount)
                                if (l.explicitCacheBlocks != null) put("explicit_cache_blocks", l.explicitCacheBlocks)
                                if (l.cacheMode != null) put("cache_mode", l.cacheMode)
                                if (l.cacheStrategy != null) put("cache_strategy", l.cacheStrategy)
                                if (l.cacheBreakpoints != null) put("cache_breakpoints", l.cacheBreakpoints)
                                put("cache_breakpoint_indexes", buildJsonArray { l.cacheBreakpointIndexes.forEach { add(it) } })
                                if (l.patchEligible != null) put("patch_eligible", l.patchEligible)
                                if (l.inputTokens != null) put("input_tokens", l.inputTokens)
                                if (l.outputTokens != null) put("output_tokens", l.outputTokens)
                                if (l.cacheReadInputTokens != null) put("cache_read_input_tokens", l.cacheReadInputTokens)
                                if (l.cacheCreationInputTokens != null) put("cache_creation_input_tokens", l.cacheCreationInputTokens)
                                if (l.cachedPromptTokens != null) put("cached_prompt_tokens", l.cachedPromptTokens)
                                if (l.usageJson != null) put("usage_json", l.usageJson)
                            })
                        }
                    })
                }.toString(), ContentType.Application.Json)
            }

            get("/api/config/export") {
                if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@get }
                if (!requireConfigAuth(call)) return@get
                call.respondText(Config.snapshot().toJson().toString(), ContentType.Application.Json)
            }

            post("/api/config/import") {
                if (!ConfigAuth.isConfigured()) { ConfigAuth.setupRequired(call); return@post }
                if (!requireConfigAuth(call)) return@post
                val bodyText = receiveTextLimited(call, Config.maxConfigRequestBytes) ?: return@post
                val body = try { Json.parseToJsonElement(bodyText) as? JsonObject } catch (e: Exception) { null }
                val updateResult = ConfigApi.applyUpdate(body)
                if (updateResult.errors.isNotEmpty()) {
                    call.respondText(buildJsonObject {
                        put("errors", buildJsonArray { updateResult.errors.forEach { add(it) } })
                    }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                } else {
                    println("Config imported via /api/config/import")
                    call.respondText(updateResult.responseBody.toString(), ContentType.Application.Json)
                }
            }

            fallbackProxyRoute()
        }
    }.start(wait = true)
}

private suspend fun requireConfigAuth(call: ApplicationCall): Boolean {
    return when (ConfigAuth.authorizeConfigCall(call)) {
        ConfigAuth.AuthDecision.AUTHORIZED -> true
        ConfigAuth.AuthDecision.RATE_LIMITED -> {
            ConfigAuth.rejectRateLimited(call)
            false
        }
        ConfigAuth.AuthDecision.UNAUTHORIZED -> {
            ConfigAuth.reject(call)
            false
        }
    }
}

private suspend fun receiveTextLimited(call: ApplicationCall, maxBytes: Long): String? {
    val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (contentLength != null && contentLength > maxBytes) {
        respondPayloadTooLarge(call, maxBytes)
        return null
    }

    val channel = call.receiveChannel()
    val buffer = ByteArray(8192)
    val out = ByteArrayOutputStream()
    var total = 0L

    while (!channel.isClosedForRead) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) {
            respondPayloadTooLarge(call, maxBytes)
            return null
        }
        out.write(buffer, 0, read)
    }

    return out.toString(Charsets.UTF_8.name())
}

private fun memoryNodeJson(m: DatabaseService.MemoryNodeRecord): JsonObject {
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

private fun memoryObservationJson(observation: DatabaseService.MemoryObservationRecord): JsonObject {
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

private fun selfEventJson(event: DatabaseService.SelfMemoryEventRecord): JsonObject {
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

private suspend fun respondPayloadTooLarge(call: ApplicationCall, maxBytes: Long) {
    call.respondText(
        buildJsonObject {
            put("error", "request body is too large")
            put("max_bytes", maxBytes)
        }.toString(),
        ContentType.Application.Json,
        HttpStatusCode.PayloadTooLarge
    )
}

private suspend fun serveUi(call: ApplicationCall) {
    serveResource(call, "ui.html", ContentType.Text.Html)
}

private suspend fun serveResource(call: ApplicationCall, resourceName: String, contentType: ContentType) {
    val stream = Config::class.java.classLoader.getResourceAsStream(resourceName)
    if (stream != null) {
        when {
            contentType.match(ContentType.Text.Html) -> {
                val text = stream.bufferedReader().use { it.readText() }
                call.respondText(text, contentType)
            }
            else -> {
                val bytes = stream.use { it.readBytes() }
                call.respondBytes(bytes, contentType)
            }
        }
    } else {
        call.respondText("$resourceName not found in resources", ContentType.Text.Plain, HttpStatusCode.NotFound)
    }
}

private suspend fun handleModelListProxy(call: ApplicationCall) {
    val upstream = Config.upstream
    if (upstream.isBlank()) {
        call.respondText("Upstream URL is not configured", ContentType.Text.Plain, HttpStatusCode.BadRequest)
        return
    }
    val query = call.request.queryString()
    val upstreamUrl = "$upstream${ProxyService.normalizeUpstreamPath(call.request.path())}${if (query.isNotEmpty()) "?$query" else ""}"
    val validationError = Security.validateOutboundRequestUrl(upstreamUrl, "upstream")
    if (validationError != null) {
        call.respondText(validationError, ContentType.Text.Plain, HttpStatusCode.BadRequest)
        return
    }
    val cleanedHeaders = ProxyService.cleanHeaders(call.request.headers)
    val finalHeaders = ProxyService.adjustHeadersForUpstream(cleanedHeaders)
    try {
        val response = ProxyService.client.get(upstreamUrl) {
            finalHeaders.forEach { (k, v) ->
                if (k.lowercase() != "host") {
                    header(k, v)
                }
            }
            header("host", URI(Config.upstream).host)
        }
        val text = response.bodyAsText()
        val statusVal = response.status.value
        call.respondText(text, response.contentType() ?: ContentType.Application.Json, HttpStatusCode.fromValue(statusVal))
    } catch (e: Exception) {
        System.err.println("Model list proxy failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
        call.respondText("Proxy error", ContentType.Text.Plain, HttpStatusCode.BadGateway)
    }
}

private fun Route.fallbackProxyRoute() {
    route("{...}") {
        handle {
            MemoryService.touchLastRequest()
            val upstreamBase = Config.upstream
            if (upstreamBase.isBlank()) {
                call.respondText("Upstream URL is not configured", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                return@handle
            }
            val methodStr = call.request.httpMethod.value
            val path = call.request.path()
            val query = call.request.queryString()
            val upstreamPath = ProxyService.normalizeUpstreamPath(path)
            val upstreamUrl = "$upstreamBase$upstreamPath${if (query.isNotEmpty()) "?$query" else ""}"
            val validationError = Security.validateOutboundRequestUrl(upstreamUrl, "upstream")
            if (validationError != null) {
                call.respondText(validationError, ContentType.Text.Plain, HttpStatusCode.BadRequest)
                return@handle
            }
            
            val cleanedHeaders = ProxyService.cleanHeaders(call.request.headers)
            val mayHaveRequestBody = methodStr != "GET" && methodStr != "HEAD"
            val requestBodyText = if (mayHaveRequestBody) {
                receiveTextLimited(call, Config.maxProxyRequestBytes) ?: return@handle
            } else {
                ""
            }
            var finalBodyBytes = requestBodyText.toByteArray(Charsets.UTF_8)
            var hasBetaHeader = false
            var originalJson: JsonObject? = null
            var requestLogId: Int? = null

            val isJson = call.request.contentType().match(ContentType.Application.Json)
            if (requestBodyText.isNotEmpty() && isJson) {
                try {
                    val parsed = Json.parseToJsonElement(requestBodyText) as? JsonObject
                    if (parsed != null) {
                        originalJson = parsed
                        val patchedJson = MessagePatcher.patchJsonBody(path, parsed)
                        finalBodyBytes = patchedJson.toString().toByteArray(Charsets.UTF_8)
                        
                        if (Config.preset == "anthropic" && MessagePatcher.isAnthropicMessagesRequest(path, parsed)) {
                            hasBetaHeader = true
                        }
                        requestLogId = ProxyService.logRequest(call.request.httpMethod.value, path, parsed, patchedJson)
                    }
                } catch (e: Exception) {
                    System.err.println("Failed to parse request JSON: ${e.message}")
                }
            }

            try {
                val finalHeaders = ProxyService.adjustHeadersForUpstream(cleanedHeaders)
                val upstreamResponse = ProxyService.client.request(upstreamUrl) {
                    this.method = HttpMethod.parse(methodStr)
                    finalHeaders.forEach { (k, v) ->
                        if (k.lowercase() != "host" && k.lowercase() != "content-length") {
                            header(k, v)
                        }
                    }
                    header("host", URI(upstreamBase).host)
                    if (hasBetaHeader) {
                        header("anthropic-beta", Config.betaHeader)
                    }
                    if (mayHaveRequestBody && finalBodyBytes.isNotEmpty()) {
                        setBody(finalBodyBytes)
                    }
                }

                val responseHeaders = ProxyService.cleanHeaders(upstreamResponse.headers)
                responseHeaders.forEach { (k, v) ->
                    call.response.headers.append(k, v, safeOnly = true)
                }

                val statusVal = upstreamResponse.status.value
                val channel = upstreamResponse.bodyAsChannel()
                val responseContentType = upstreamResponse.contentType()
                val isTextResponse = responseContentType != null && (
                    responseContentType.match(ContentType.Application.Json) ||
                    responseContentType.match(ContentType.Text.EventStream) ||
                    responseContentType.match(ContentType.Text.Plain)
                )

                call.respondBytesWriter(
                    status = HttpStatusCode.fromValue(statusVal),
                    contentType = responseContentType ?: ContentType.Application.OctetStream
                ) {
                    val captured = channel.copyToAndCapture(this, isTextResponse)
                    val isSse = responseContentType?.match(ContentType.Text.EventStream) == true
                    if (requestLogId != null && isTextResponse) {
                        runCatching {
                            ProxyService.extractUsageDiagnostics(captured, isSse)?.let { usage ->
                                DatabaseService.updateRequestLogUsage(requestLogId!!, usage)
                            }
                        }.onFailure { e ->
                            System.err.println("Failed to record usage diagnostics: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
                        }
                    }
                    if (originalJson != null && isTextResponse && Config.memoryEnabled) {
                        runCatching {
                            val compiledText = compileResponseText(captured, isSse)
                            if (compiledText.isNotEmpty()) {
                                MemoryService.extractAndSaveMemoriesAsync(path, originalJson, compiledText)
                            }
                        }.onFailure { e ->
                            System.err.println("Failed to schedule memory extraction: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
                        }
                    }
                }
            } catch (e: Exception) {
                System.err.println("Proxy request failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
                call.respondText("Proxy error", ContentType.Text.Plain, HttpStatusCode.BadGateway)
            }
        }
    }
}

private suspend fun ByteReadChannel.copyToAndCapture(target: ByteWriteChannel, isText: Boolean): String {
    val buffer = ByteArray(4096)
    val out = java.io.ByteArrayOutputStream()
    var totalCaptured = 0
    while (!isClosedForRead) {
        val read = readAvailable(buffer, 0, buffer.size)
        if (read < 0) break
        if (read > 0) {
            target.writeFully(buffer, 0, read)
            if (isText && totalCaptured < 100000) { // Limit capture to 100KB
                out.write(buffer, 0, read)
                totalCaptured += read
            }
        }
    }
    return if (isText) out.toString("UTF-8") else ""
}

private fun compileSseResponse(sseText: String): String {
    val lines = sseText.split("\n")
    val sb = java.lang.StringBuilder()
    for (line in lines) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) continue
        val dataStr = trimmed.substring(5).trim()
        if (dataStr == "[DONE]") continue
        try {
            val json = Json.parseToJsonElement(dataStr) as? JsonObject ?: continue
            val openAiContent = json["choices"]?.jsonArray?.getOrNull(0)?.jsonObject
                ?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            if (openAiContent != null) {
                sb.append(openAiContent)
                continue
            }
            if (json["type"]?.jsonPrimitive?.contentOrNull == "content_block_delta") {
                val delta = json["delta"]?.jsonObject ?: continue
                if (delta["type"]?.jsonPrimitive?.contentOrNull == "text_delta") {
                    delta["text"]?.jsonPrimitive?.contentOrNull?.let { sb.append(it) }
                    continue
                }
            }
            val responsesDelta = json["delta"]?.jsonPrimitive?.contentOrNull
            if (responsesDelta != null) {
                sb.append(responsesDelta)
            }
        } catch (e: Exception) {
            // Ignore parse errors for keep-alives
        }
    }
    return sb.toString()
}

private fun compileResponseText(text: String, isSse: Boolean): String {
    if (isSse) {
        return compileSseResponse(text)
    }
    try {
        val json = Json.parseToJsonElement(text) as? JsonObject ?: return text
        val openAiContent = json["choices"]?.jsonArray?.getOrNull(0)?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        if (openAiContent != null) return openAiContent
        val responsesContent = json["output"]?.jsonArray?.mapNotNull { outputItem ->
            (outputItem as? JsonObject)?.get("content")?.jsonArray?.mapNotNull { contentItem ->
                (contentItem as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
            }?.joinToString("\n")
        }?.joinToString("\n")
        if (!responsesContent.isNullOrBlank()) return responsesContent
        val topLevelOutputText = json["output_text"]?.jsonPrimitive?.contentOrNull
        if (!topLevelOutputText.isNullOrBlank()) return topLevelOutputText
        val anthropicContent = json["content"]?.jsonArray
        if (anthropicContent != null) {
            return anthropicContent.mapNotNull { block ->
                (block as? JsonObject)?.let { o ->
                    if (o["type"]?.jsonPrimitive?.contentOrNull == "text") {
                        o["text"]?.jsonPrimitive?.contentOrNull
                    } else null
                }
            }.joinToString("\n")
        }
        val geminiContent = json["candidates"]?.jsonArray?.getOrNull(0)?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.mapNotNull { part ->
                (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
            }?.joinToString("\n")
        if (!geminiContent.isNullOrBlank()) return geminiContent
    } catch (e: Exception) {
        // Ignore
    }
    return text
}
