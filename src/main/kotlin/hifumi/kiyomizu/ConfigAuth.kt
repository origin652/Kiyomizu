package hifumi.kiyomizu

import io.ktor.http.HttpHeaders
import io.ktor.http.Headers
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object ConfigAuth {
    const val headerName = "x-kiyomizu-config-password"
    private const val hashAlgorithm = "pbkdf2-sha256"
    private const val keyFactoryAlgorithm = "PBKDF2WithHmacSHA256"
    private const val passwordHashIterations = 120_000
    private const val passwordHashBits = 256
    private val persistedPasswordRef = AtomicReference<DatabaseService.ConfigPasswordRecord?>(null)

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
            return candidate == plainPassword
        }

        val persistedPassword = persistedPasswordRef.get() ?: return false
        return verifyPassword(candidate, persistedPassword)
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
            ?.removePrefix("Basic ")
            ?.trim()
            ?.let { encoded ->
                runCatching {
                    val decoded = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                    decoded.substringAfter(':', "")
                }.getOrNull()
            }
    }

    suspend fun reject(call: ApplicationCall) {
        call.response.headers.append(HttpHeaders.WWWAuthenticate, """Basic realm="Kiyomizu Config"""", safeOnly = false)
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
        return SecretKeyFactory.getInstance(keyFactoryAlgorithm).generateSecret(spec).encoded
    }

    internal fun resetForTests() {
        persistedPasswordRef.set(null)
    }
}
