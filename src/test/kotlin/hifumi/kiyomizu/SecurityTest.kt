package hifumi.kiyomizu

import kotlin.test.Test
import kotlin.test.assertEquals

class SecurityTest {
    @Test
    fun parseCorsAllowedHostsEnv_splitsAndNormalizes() {
        assertEquals(
            listOf("app.example.com", "other.example.org"),
            Security.parseCorsAllowedHostsEnv("https://app.example.com, other.example.org "),
        )
        assertEquals(emptyList(), Security.parseCorsAllowedHostsEnv(null))
        assertEquals(emptyList(), Security.parseCorsAllowedHostsEnv("  "))
    }

    @Test
    fun corsAllowedHosts_includesLoopbackAndParsedHosts() {
        val merged = (listOf("localhost", "127.0.0.1") + Security.parseCorsAllowedHostsEnv("app.example.com")).distinct()
        assertEquals(listOf("localhost", "127.0.0.1", "app.example.com"), merged)
    }
}