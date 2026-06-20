package hifumi.kiyomizu

import io.ktor.http.headersOf
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigAuthTest {
    @AfterTest
    fun cleanup() {
        System.clearProperty("kiyomizu.config.password")
        System.clearProperty("kiyomizu.db.file")
        ConfigAuth.resetForTests()
    }

    private fun withIsolatedDb(block: () -> Unit) {
        val tempDir = Files.createTempDirectory("kiyomizu-config-auth-test")
        val dbPath = tempDir.resolve("kiyomizu-config-auth.db").toString()
        System.setProperty("kiyomizu.db.file", dbPath)
        try {
            DatabaseService.initDatabase()
            ConfigAuth.resetForTests()
            ConfigAuth.loadPersisted(DatabaseService.loadConfigPassword())
            block()
        } finally {
            System.clearProperty("kiyomizu.db.file")
            ConfigAuth.resetForTests()
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun startupAllowsPasswordMissingUntilWebSetup() {
        assertFalse(ConfigAuth.isConfigured())
        assertFalse(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "anything")))
    }

    @Test
    fun customHeaderAuthorizesConfigApi() {
        System.setProperty("kiyomizu.config.password", "secret-pass-123")

        assertTrue(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "secret-pass-123")))
        assertFalse(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "wrong-pass")))
    }

    @Test
    fun basicAuthAlsoWorks() {
        System.setProperty("kiyomizu.config.password", "swordfish-pass")
        val encoded = java.util.Base64.getEncoder().encodeToString(":swordfish-pass".toByteArray())

        assertTrue(ConfigAuth.isAuthorized(headersOf("Authorization", "Basic $encoded")))
        assertTrue(ConfigAuth.isAuthorized(headersOf("Authorization", "basic $encoded")))
        assertFalse(ConfigAuth.isAuthorized(headersOf("Authorization", "Basic not-base64")))
    }

    @Test
    fun initialWebSetupPersistsHashedPassword() {
        withIsolatedDb {
            assertFalse(ConfigAuth.isConfigured())

            val result = ConfigAuth.configureInitialPassword("secret-pass-123")
            assertTrue(result.errors.isEmpty(), "initial password setup should succeed: ${result.errors}")
            assertTrue(ConfigAuth.isConfigured())
            assertTrue(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "secret-pass-123")))
            assertFalse(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "wrong-pass")))

            ConfigAuth.resetForTests()
            assertFalse(ConfigAuth.isConfigured(), "test sanity: reset should clear only in-memory auth")

            ConfigAuth.loadPersisted(DatabaseService.loadConfigPassword())
            assertTrue(ConfigAuth.isConfigured(), "stored password hash should be loaded from the database")
            assertTrue(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "secret-pass-123")))
            assertFalse(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "wrong-pass")))
        }
    }

    @Test
    fun initialWebSetupRejectsBlankAndSecondPassword() {
        withIsolatedDb {
            val blank = ConfigAuth.configureInitialPassword(" ")
            assertTrue(blank.errors.contains("config password must not be blank"))
            assertFalse(ConfigAuth.isConfigured())

            val short = ConfigAuth.configureInitialPassword("short")
            assertTrue(short.errors.contains("config password must be at least 12 characters"))
            assertFalse(ConfigAuth.isConfigured())

            val first = ConfigAuth.configureInitialPassword("secret-pass-123")
            assertTrue(first.errors.isEmpty())

            val second = ConfigAuth.configureInitialPassword("other-pass-123")
            assertTrue(second.errors.contains("config password is already configured"))
        }
    }

    @Test
    fun persistedPasswordCanBeChangedWithCurrentPassword() {
        withIsolatedDb {
            val first = ConfigAuth.configureInitialPassword("secret-pass-123")
            assertTrue(first.errors.isEmpty())
            assertTrue(ConfigAuth.isChangeable())

            val wrongCurrent = ConfigAuth.changePassword("wrong-pass", "new-secret-pass")
            assertTrue(wrongCurrent.errors.contains("current password is incorrect"))
            assertTrue(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "secret-pass-123")))

            val changed = ConfigAuth.changePassword("secret-pass-123", "new-secret-pass")
            assertTrue(changed.errors.isEmpty(), "password change should succeed: ${changed.errors}")
            assertFalse(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "secret-pass-123")))
            assertTrue(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "new-secret-pass")))

            ConfigAuth.resetForTests()
            ConfigAuth.loadPersisted(DatabaseService.loadConfigPassword())
            assertTrue(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "new-secret-pass")))
        }
    }

    @Test
    fun environmentPasswordCannotBeChangedFromWeb() {
        System.setProperty("kiyomizu.config.password", "fixed-secret")

        assertFalse(ConfigAuth.isChangeable())
        val changed = ConfigAuth.changePassword("fixed-secret", "new-secret")
        assertEquals(listOf("config password is controlled by environment or system property"), changed.errors)
        assertTrue(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "fixed-secret")))
        assertFalse(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "new-secret")))
    }
}
