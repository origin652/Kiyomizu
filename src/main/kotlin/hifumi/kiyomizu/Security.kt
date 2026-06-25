package hifumi.kiyomizu

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

object Security {
    private val allowRemotePasswordSetup = System.getenv("KIYOMIZU_ALLOW_REMOTE_PASSWORD_SETUP") == "1"
    private val allowBrowserCorsOverride = System.getenv("KIYOMIZU_ALLOW_BROWSER_CORS") == "1"
    private val allowPrivateOutbound = System.getenv("KIYOMIZU_ALLOW_PRIVATE_UPSTREAMS") == "1"

    fun isLocalBindHost(host: String): Boolean {
        val normalized = host.trim().lowercase().removeSurrounding("[", "]")
        return normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1"
    }

    fun isPubliclyBound(): Boolean {
        return !isLocalBindHost(Config.host)
    }

    fun shouldAllowBrowserCors(): Boolean {
        return allowBrowserCorsOverride || !isPubliclyBound()
    }

    /**
     * Hostnames permitted in browser CORS (plus loopback defaults).
     * Set KIYOMIZU_CORS_ALLOWED_HOSTS when the UI is opened via a public HTTPS reverse proxy
     * while Kiyomizu still binds 127.0.0.1.
     */
    fun corsAllowedHosts(): List<String> {
        val fromEnv = parseCorsAllowedHostsEnv(System.getenv("KIYOMIZU_CORS_ALLOWED_HOSTS"))
        return (listOf("localhost", "127.0.0.1") + fromEnv).distinct()
    }

    internal fun parseCorsAllowedHostsEnv(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',')
            .mapNotNull { token -> normalizeCorsHostToken(token) }
            .distinct()
    }

    private fun normalizeCorsHostToken(token: String): String? {
        var value = token.trim()
        if (value.isEmpty()) return null
        if (value.contains("://")) {
            val uri = runCatching { URI(value).normalize() }.getOrNull() ?: return null
            value = uri.host ?: return null
        } else if (value.contains("/")) {
            val uri = runCatching { URI("https://$value").normalize() }.getOrNull() ?: return null
            value = uri.host ?: return null
        }
        value = value.lowercase().removeSurrounding("[", "]")
        val hostOnly = value.substringBefore(':').trim()
        if (hostOnly.isEmpty()) return null
        return hostOnly
    }

    fun isRemotePasswordSetupAllowed(): Boolean {
        return allowRemotePasswordSetup || !isPubliclyBound()
    }

    fun validateOutboundBaseUrl(value: String, fieldName: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        val uri = parseUri(trimmed) ?: return "$fieldName must be a valid URL"
        val commonError = validateCommonUrlParts(uri, fieldName, baseUrl = true)
        if (commonError != null) return commonError
        return validatePublicUrlPolicy(uri, fieldName, resolveHost = false)
    }

    fun validateOutboundRequestUrl(value: String, fieldName: String): String? {
        val uri = parseUri(value) ?: return "$fieldName URL is invalid"
        val commonError = validateCommonUrlParts(uri, fieldName, baseUrl = false)
        if (commonError != null) return commonError
        return validatePublicUrlPolicy(uri, fieldName, resolveHost = true)
    }

    private fun parseUri(value: String): URI? {
        return runCatching { URI(value).normalize() }.getOrNull()
    }

    private fun validateCommonUrlParts(uri: URI, fieldName: String, baseUrl: Boolean): String? {
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("http", "https")) {
            return "$fieldName must use http or https"
        }
        if (uri.host.isNullOrBlank()) {
            return "$fieldName must include a host"
        }
        if (uri.userInfo != null) {
            return "$fieldName must not include username or password"
        }
        if (uri.rawFragment != null) {
            return "$fieldName must not include a fragment"
        }
        if (baseUrl && uri.rawQuery != null) {
            return "$fieldName must be a base URL without a query string"
        }
        return null
    }

    private fun validatePublicUrlPolicy(uri: URI, fieldName: String, resolveHost: Boolean): String? {
        val scheme = uri.scheme?.lowercase()
        if (!allowPrivateOutbound && scheme != "https") {
            return "$fieldName must use https unless KIYOMIZU_ALLOW_PRIVATE_UPSTREAMS=1 is set"
        }
        if (!allowPrivateOutbound && isPrivateOrSpecialHost(uri.host, resolveHost)) {
            return "$fieldName must not target localhost, private networks, link-local addresses, or metadata hosts"
        }
        return null
    }

    private fun isPrivateOrSpecialHost(host: String, resolveHost: Boolean): Boolean {
        val normalized = host.trim().lowercase().removeSurrounding("[", "]").trimEnd('.')
        if (normalized == "localhost" ||
            normalized.endsWith(".localhost") ||
            normalized.endsWith(".local") ||
            normalized.endsWith(".lan") ||
            normalized.endsWith(".home.arpa") ||
            normalized == "metadata.google.internal" ||
            normalized == "metadata" ||
            normalized.endsWith(".cluster.local")
        ) {
            return true
        }

        val literalAddress = parseLiteralAddress(normalized)
        if (literalAddress != null) {
            return isPrivateOrSpecialAddress(literalAddress)
        }

        if (!resolveHost) return false

        val resolved = runCatching { InetAddress.getAllByName(normalized).toList() }.getOrNull()
            ?: return true
        return resolved.isEmpty() || resolved.any { isPrivateOrSpecialAddress(it) }
    }

    private fun parseLiteralAddress(host: String): InetAddress? {
        val isIpv4 = host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
        val isIpv6 = host.contains(":")
        if (!isIpv4 && !isIpv6) return null
        return runCatching { InetAddress.getByName(host) }.getOrNull()
    }

    private fun isPrivateOrSpecialAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }

        if (address is Inet4Address) {
            val b = address.address.map { it.toInt() and 0xff }
            val first = b[0]
            val second = b[1]
            val third = b[2]
            return first == 0 ||
                first == 10 ||
                first == 127 ||
                (first == 100 && second in 64..127) ||
                (first == 169 && second == 254) ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168) ||
                (first == 192 && second == 0 && third == 0) ||
                (first == 192 && second == 0 && third == 2) ||
                (first == 198 && second in 18..19) ||
                (first == 198 && second == 51 && third == 100) ||
                (first == 203 && second == 0 && third == 113) ||
                first >= 224
        }

        if (address is Inet6Address) {
            val b = address.address.map { it.toInt() and 0xff }
            return (b[0] and 0xfe) == 0xfc || b.all { it == 0 }
        }

        return false
    }
}
