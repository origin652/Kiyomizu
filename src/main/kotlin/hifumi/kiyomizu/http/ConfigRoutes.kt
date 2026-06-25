package hifumi.kiyomizu.http

import hifumi.kiyomizu.Config
import hifumi.kiyomizu.ConfigApi
import hifumi.kiyomizu.ConfigAuth
import hifumi.kiyomizu.Security
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

fun Route.installConfigRoutes() {
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
            val body = try { Json.parseToJsonElement(bodyText) as? JsonObject } catch (e: Exception) { null }
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
        val body = try { Json.parseToJsonElement(bodyText) as? JsonObject } catch (e: Exception) { null }
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
        val body = try { Json.parseToJsonElement(bodyText) as? JsonObject } catch (e: Exception) { null }
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
}