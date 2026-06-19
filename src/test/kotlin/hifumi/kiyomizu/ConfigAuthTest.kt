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
        System.setProperty("kiyomizu.config.password", "secret-pass")

        assertTrue(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "secret-pass")))
        assertFalse(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "wrong-pass")))
    }

    @Test
    fun basicAuthAlsoWorks() {
        System.setProperty("kiyomizu.config.password", "swordfish")
        val encoded = java.util.Base64.getEncoder().encodeToString(":swordfish".toByteArray())

        assertTrue(ConfigAuth.isAuthorized(headersOf("Authorization", "Basic $encoded")))
        assertFalse(ConfigAuth.isAuthorized(headersOf("Authorization", "Basic not-base64")))
    }

    @Test
    fun initialWebSetupPersistsHashedPassword() {
        withIsolatedDb {
            assertFalse(ConfigAuth.isConfigured())

            val result = ConfigAuth.configureInitialPassword("secret-pass")
            assertTrue(result.errors.isEmpty(), "initial password setup should succeed: ${result.errors}")
            assertTrue(ConfigAuth.isConfigured())
            assertTrue(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "secret-pass")))
            assertFalse(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "wrong-pass")))

            ConfigAuth.resetForTests()
            assertFalse(ConfigAuth.isConfigured(), "test sanity: reset should clear only in-memory auth")

            ConfigAuth.loadPersisted(DatabaseService.loadConfigPassword())
            assertTrue(ConfigAuth.isConfigured(), "stored password hash should be loaded from the database")
            assertTrue(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "secret-pass")))
            assertFalse(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "wrong-pass")))
        }
    }

    @Test
    fun initialWebSetupRejectsBlankAndSecondPassword() {
        withIsolatedDb {
            val blank = ConfigAuth.configureInitialPassword(" ")
            assertTrue(blank.errors.contains("config password must not be blank"))
            assertFalse(ConfigAuth.isConfigured())

            val first = ConfigAuth.configureInitialPassword("secret-pass")
            assertTrue(first.errors.isEmpty())

            val second = ConfigAuth.configureInitialPassword("other-pass")
            assertTrue(second.errors.contains("config password is already configured"))
        }
    }

    @Test
    fun persistedPasswordCanBeChangedWithCurrentPassword() {
        withIsolatedDb {
            val first = ConfigAuth.configureInitialPassword("secret-pass")
            assertTrue(first.errors.isEmpty())
            assertTrue(ConfigAuth.isChangeable())

            val wrongCurrent = ConfigAuth.changePassword("wrong-pass", "new-secret")
            assertTrue(wrongCurrent.errors.contains("current password is incorrect"))
            assertTrue(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "secret-pass")))

            val changed = ConfigAuth.changePassword("secret-pass", "new-secret")
            assertTrue(changed.errors.isEmpty(), "password change should succeed: ${changed.errors}")
            assertFalse(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "secret-pass")))
            assertTrue(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "new-secret")))

            ConfigAuth.resetForTests()
            ConfigAuth.loadPersisted(DatabaseService.loadConfigPassword())
            assertTrue(ConfigAuth.isAuthorized(headersOf(ConfigAuth.headerName, "new-secret")))
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
