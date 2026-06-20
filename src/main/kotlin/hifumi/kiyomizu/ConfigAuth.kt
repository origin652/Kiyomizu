package hifumi.kiyomizu

import io.ktor.http.HttpHeaders
import io.ktor.http.Headers
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object ConfigAuth {
    const val headerName = "x-kiyomizu-config-password"
    private const val hashAlgorithm = "pbkdf2-sha256"
    private const val keyFactoryAlgorithm = "PBKDF2WithHmacSHA256"
    private const val passwordHashIterations = 600_000
    private const val passwordHashBits = 256
    private const val minPasswordLength = 12
    private const val maxAuthFailures = 10
    private const val authFailureWindowMillis = 10 * 60 * 1000L
    private const val authLockMillis = 10 * 60 * 1000L
    private val persistedPasswordRef = AtomicReference<DatabaseService.ConfigPasswordRecord?>(null)
    private val authFailures = ConcurrentHashMap<String, AuthFailureState>()

    enum class AuthDecision {
        AUTHORIZED,
        UNAUTHORIZED,
        RATE_LIMITED
    }

    private data class AuthFailureState(
        var attempts: Int,
        var windowStartedAtMillis: Long,
        var lockedUntilMillis: Long = 0L
    )

    data class PasswordSetupResult(
        val errors: List<String>
    )

    data class PasswordChangeResult(
        val errors: List<String>
    )

    fun configuredPassword(): String {
        return System.getProperty("kiyomizu.config.password")
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("KIYOMIZU_CONFIG_PASSWORD")
            ?.takeIf { it.isNotBlank() }
            ?: ""
    }

    fun loadPersisted(record: DatabaseService.ConfigPasswordRecord?) {
        persistedPasswordRef.set(record)
    }

    fun isConfigured(): Boolean {
        return configuredPassword().isNotEmpty() || persistedPasswordRef.get() != null
    }

    fun isChangeable(): Boolean {
        return configuredPassword().isEmpty() && persistedPasswordRef.get() != null
    }

    @Synchronized
    fun configureInitialPassword(password: String): PasswordSetupResult {
        if (isConfigured()) {
            return PasswordSetupResult(errors = listOf("config password is already configured"))
        }

        val normalized = password.trim()
        if (normalized.isEmpty()) {
            return PasswordSetupResult(errors = listOf("config password must not be blank"))
        }
        validateNewPassword(normalized)?.let {
            return PasswordSetupResult(errors = listOf(it))
        }

        val record = hashPassword(normalized)
        return try {
            DatabaseService.saveConfigPassword(record)
            persistedPasswordRef.set(record)
            PasswordSetupResult(errors = emptyList())
        } catch (e: Exception) {
            PasswordSetupResult(errors = listOf("failed to persist config password: ${e.message ?: "unknown error"}"))
        }
    }

    fun isAuthorized(headers: Headers): Boolean {
        val candidate = passwordCandidate(headers) ?: return false
        val plainPassword = configuredPassword()
        if (plainPassword.isNotEmpty()) {
            return secureEquals(candidate, plainPassword)
        }

        val persistedPassword = persistedPasswordRef.get() ?: return false
        return verifyPassword(candidate, persistedPassword)
    }

    fun authorizeConfigCall(call: ApplicationCall): AuthDecision {
        return authorizeCall(call, "config") {
            isAuthorized(call.request.headers)
        }
    }

    @Synchronized
    fun changePassword(currentPassword: String, newPassword: String): PasswordChangeResult {
        if (configuredPassword().isNotEmpty()) {
            return PasswordChangeResult(errors = listOf("config password is controlled by environment or system property"))
        }

        val persistedPassword = persistedPasswordRef.get()
            ?: return PasswordChangeResult(errors = listOf("config password is not configured"))

        val normalizedCurrent = currentPassword.trim()
        val normalizedNew = newPassword.trim()

        if (normalizedCurrent.isEmpty()) {
            return PasswordChangeResult(errors = listOf("current password must not be blank"))
        }
        if (normalizedNew.isEmpty()) {
            return PasswordChangeResult(errors = listOf("new password must not be blank"))
        }
        validateNewPassword(normalizedNew)?.let {
            return PasswordChangeResult(errors = listOf(it))
        }
        if (!verifyPassword(normalizedCurrent, persistedPassword)) {
            return PasswordChangeResult(errors = listOf("current password is incorrect"))
        }
        if (normalizedCurrent == normalizedNew) {
            return PasswordChangeResult(errors = listOf("new password must be different from current password"))
        }

        val record = hashPassword(normalizedNew)
        return try {
            DatabaseService.saveConfigPassword(record)
            persistedPasswordRef.set(record)
            PasswordChangeResult(errors = emptyList())
        } catch (e: Exception) {
            PasswordChangeResult(errors = listOf("failed to persist config password: ${e.message ?: "unknown error"}"))
        }
    }

    private fun passwordCandidate(headers: Headers): String? {
        headers[headerName]?.let { return it }

        return headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith("Basic ", ignoreCase = true) }
            ?.substring(6)
            ?.trim()
            ?.let { encoded ->
                runCatching {
                    val decoded = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                    decoded.substringAfter(':', "")
                }.getOrNull()
            }
    }

    private fun validateNewPassword(password: String): String? {
        if (password.length < minPasswordLength) {
            return "config password must be at least $minPasswordLength characters"
        }
        return null
    }

    private fun secureEquals(left: String, right: String): Boolean {
        return MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
    }

    private fun authorizeCall(call: ApplicationCall, scope: String, check: () -> Boolean): AuthDecision {
        val key = authFailureKey(call, scope)
        val now = System.currentTimeMillis()
        if (isRateLimited(key, now)) {
            return AuthDecision.RATE_LIMITED
        }

        val authorized = check()
        recordAuthResult(key, authorized, now)
        return if (authorized) AuthDecision.AUTHORIZED else AuthDecision.UNAUTHORIZED
    }

    private fun authFailureKey(call: ApplicationCall, scope: String): String {
        return "$scope:${call.request.origin.remoteHost}"
    }

    private fun isRateLimited(key: String, now: Long): Boolean {
        val state = authFailures[key] ?: return false
        if (state.lockedUntilMillis > now) return true
        if (state.lockedUntilMillis != 0L && state.lockedUntilMillis <= now) {
            authFailures.remove(key)
        }
        return false
    }

    private fun recordAuthResult(key: String, authorized: Boolean, now: Long) {
        if (authorized) {
            authFailures.remove(key)
            return
        }

        authFailures.compute(key) { _, existing ->
            val state = existing ?: AuthFailureState(attempts = 0, windowStartedAtMillis = now)
            if (now - state.windowStartedAtMillis > authFailureWindowMillis) {
                state.attempts = 0
                state.windowStartedAtMillis = now
                state.lockedUntilMillis = 0L
            }
            state.attempts += 1
            if (state.attempts >= maxAuthFailures) {
                state.lockedUntilMillis = now + authLockMillis
            }
            state
        }
    }

    suspend fun reject(call: ApplicationCall) {
        call.respondText(
            buildJsonObject {
                put("error", "config password required")
                put("config_password_required", true)
                put("config_password_setup_required", false)
                put("config_password_header", headerName)
            }.toString(),
            io.ktor.http.ContentType.Application.Json,
            io.ktor.http.HttpStatusCode.Unauthorized
        )
    }

    suspend fun rejectRateLimited(call: ApplicationCall) {
        call.respondText(
            buildJsonObject {
                put("error", "too many authentication attempts")
            }.toString(),
            io.ktor.http.ContentType.Application.Json,
            io.ktor.http.HttpStatusCode.TooManyRequests
        )
    }

    suspend fun setupRequired(call: ApplicationCall) {
        call.respondText(
            buildJsonObject {
                put("error", "config password setup required")
                put("config_password_required", true)
                put("config_password_setup_required", true)
            }.toString(),
            io.ktor.http.ContentType.Application.Json,
            io.ktor.http.HttpStatusCode(428, "Precondition Required")
        )
    }

    private fun hashPassword(password: String): DatabaseService.ConfigPasswordRecord {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val hash = deriveHash(password, salt, passwordHashIterations)
        return DatabaseService.ConfigPasswordRecord(
            algorithm = hashAlgorithm,
            iterations = passwordHashIterations,
            salt = Base64.getEncoder().encodeToString(salt),
            passwordHash = Base64.getEncoder().encodeToString(hash)
        )
    }

    private fun verifyPassword(password: String, record: DatabaseService.ConfigPasswordRecord): Boolean {
        if (record.algorithm != hashAlgorithm || record.iterations <= 0) return false
        return runCatching {
            val salt = Base64.getDecoder().decode(record.salt)
            val expectedHash = Base64.getDecoder().decode(record.passwordHash)
            val actualHash = deriveHash(password, salt, record.iterations)
            MessageDigest.isEqual(actualHash, expectedHash)
        }.getOrDefault(false)
    }

    private fun deriveHash(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, passwordHashBits)
        return try {
            SecretKeyFactory.getInstance(keyFactoryAlgorithm).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    internal fun resetForTests() {
        persistedPasswordRef.set(null)
        authFailures.clear()
    }
}
