package tw.kuies.block.filter

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * URL and address policy for subscription downloads (SSRF defence).
 *
 * A subscription is fetched from a URL that the app itself may have been told about by a user, so the
 * URL must not be usable to reach anything inside the device, the LAN or the carrier network. Every
 * hop is checked with [check] *before* a socket is opened, and [resolveForConnection] repeats the
 * address half of the check inside OkHttp's DNS hook so a name cannot pass the check and then resolve
 * somewhere else before the connection is made.
 *
 * The checks are pure byte arithmetic over the resolved addresses, so they behave identically in unit
 * tests and on the device, and [resolve] is injectable for tests.
 */
class SubscriptionHostGuard(
    private val resolve: (String) -> List<InetAddress> = { host ->
        InetAddress.getAllByName(host).toList()
    }
) {

    /**
     * Checks [url] for [origin], or null when it may be fetched.
     *
     * [allowedHosts] applies to [SubscriptionOrigin.BUILT_IN] only: a built-in list may contact just
     * the locations its tier publishes. An empty allow-list for a built-in source denies everything,
     * because a missing allow-list must never mean "any host".
     */
    fun check(
        url: String,
        origin: SubscriptionOrigin,
        allowedHosts: Set<String> = emptySet()
    ): SubscriptionFailure? {
        val parsed = url.toHttpUrlOrNull() ?: return SubscriptionFailure.INVALID_URL
        return check(parsed, origin, allowedHosts)
    }

    fun check(
        url: HttpUrl,
        origin: SubscriptionOrigin,
        allowedHosts: Set<String> = emptySet()
    ): SubscriptionFailure? {
        if (url.scheme != HTTPS_SCHEME) return SubscriptionFailure.INSECURE_URL
        val host = canonicalHost(url.host)
        checkHost(host, origin, allowedHosts)?.let { return it }
        // A literal address has just been judged; there is nothing left to resolve.
        if (literalAddressOrNull(host) != null) return null
        return checkResolvedAddresses(host)
    }

    /** Host-only checks: no DNS is performed here. */
    fun checkHost(
        host: String,
        origin: SubscriptionOrigin,
        allowedHosts: Set<String> = emptySet()
    ): SubscriptionFailure? {
        val canonical = canonicalHost(host)
        if (canonical.isEmpty()) return SubscriptionFailure.INVALID_URL
        if (isLocalName(canonical)) return SubscriptionFailure.BLOCKED_LOCAL_HOST

        literalAddressOrNull(canonical)?.let { literal ->
            addressFailure(literal)?.let { return it }
        }

        if (origin == SubscriptionOrigin.BUILT_IN) {
            if (canonical !in allowedHosts) return SubscriptionFailure.HOST_NOT_ALLOWED
        }
        return null
    }

    /** Resolves [host] and rejects it when any answer is not a public unicast address. */
    fun checkResolvedAddresses(host: String): SubscriptionFailure? {
        val addresses = try {
            resolve(host)
        } catch (_: UnknownHostException) {
            return SubscriptionFailure.CONNECT_FAILED
        }
        if (addresses.isEmpty()) return SubscriptionFailure.CONNECT_FAILED
        for (address in addresses) {
            addressFailure(address)?.let { return it }
        }
        return null
    }

    /**
     * The address half of the policy for one already-resolved address, or null when it is acceptable.
     *
     * Beyond the ranges the design names (10/8, 172.16/12, 192.168/16, 127/8, 169.254/16, 0.0.0.0,
     * ::1, fe80::/10, fc00::/7) this also rejects the CGNAT range 100.64/10, 0/8 and multicast, none
     * of which can host a public list. An IPv4-mapped IPv6 address is judged as the IPv4 address it
     * carries.
     */
    fun addressFailure(address: InetAddress): SubscriptionFailure? {
        val bytes = address.address
        return when (bytes.size) {
            IPV4_BYTES -> if (isBlockedIpv4(bytes)) BLOCKED else null
            IPV6_BYTES -> when {
                isIpv4Mapped(bytes) -> if (isBlockedIpv4(bytes, offset = 12)) BLOCKED else null
                isBlockedIpv6(bytes) -> BLOCKED
                else -> null
            }

            else -> BLOCKED
        }
    }

    /**
     * Resolves [host] for the HTTP client, rejecting anything the address policy refuses.
     *
     * OkHttp calls this for every connection it opens; because the check happens here as well as in
     * [check], a hostname that resolves differently between the two calls still cannot reach a private
     * address.
     */
    fun resolveForConnection(host: String): List<InetAddress> {
        val checked = checkHost(host, SubscriptionOrigin.USER)
        if (checked == null) {
            literalAddressOrNull(canonicalHost(host))?.let { literal ->
                // A literal never needs a lookup, and must not be looked up.
                if (addressFailure(literal) == null) return listOf(literal)
            }
        }
        checked?.let { throw BlockedHostException(it) }

        val addresses = try {
            resolve(host)
        } catch (_: UnknownHostException) {
            throw UnknownHostException(host)
        }
        for (address in addresses) {
            addressFailure(address)?.let { throw BlockedHostException(it) }
        }
        if (addresses.isEmpty()) throw UnknownHostException(host)
        return addresses
    }

    private fun canonicalHost(host: String): String =
        host.trim().removeSurrounding("[", "]").trimEnd('.').lowercase()

    private fun isLocalName(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal") ||
            host.endsWith(".localdomain")

    /**
     * Parses [host] as an address only when it cannot be a DNS name, so no lookup is attempted for a
     * hostname here: a colon means IPv6, and a digits-and-dots string means IPv4.
     */
    private fun literalAddressOrNull(host: String): InetAddress? {
        val looksLikeIpv6 = host.contains(':')
        val looksLikeIpv4 = host.all { it.isDigit() || it == '.' } && host.contains('.')
        if (!looksLikeIpv6 && !looksLikeIpv4) return null
        return try {
            InetAddress.getByName(host)
        } catch (_: UnknownHostException) {
            null
        }
    }

    private fun isIpv4Mapped(bytes: ByteArray): Boolean {
        for (index in 0 until 10) {
            if (bytes[index] != 0.toByte()) return false
        }
        return bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
    }

    private fun isBlockedIpv4(bytes: ByteArray, offset: Int = 0): Boolean {
        val a = bytes[offset].toInt() and 0xFF
        val b = bytes[offset + 1].toInt() and 0xFF
        return when {
            a == 0 -> true                        // 0.0.0.0/8 "this network"
            a == 10 -> true                       // 10/8
            a == 127 -> true                      // 127/8 loopback
            a == 169 && b == 254 -> true          // 169.254/16 link-local
            a == 172 && b in 16..31 -> true       // 172.16/12
            a == 192 && b == 168 -> true          // 192.168/16
            a == 100 && b in 64..127 -> true      // 100.64/10 carrier-grade NAT
            a in 224..239 -> true                 // 224/4 multicast
            a >= 240 -> true                      // 240/4 reserved, includes broadcast
            else -> false
        }
    }

    private fun isBlockedIpv6(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF

        if (bytes.all { it == 0.toByte() }) return true                        // :: unspecified
        if (bytes.take(15).all { it == 0.toByte() } && bytes[15] == 1.toByte()) return true // ::1
        if (first == 0xFE && (second and 0xC0) == 0x80) return true            // fe80::/10 link-local
        if ((first and 0xFE) == 0xFC) return true                              // fc00::/7 unique-local
        if (first == 0xFF) return true                                         // ff00::/8 multicast
        return false
    }

    companion object {
        const val HTTPS_SCHEME = "https"
        private const val IPV4_BYTES = 4
        private const val IPV6_BYTES = 16

        private val BLOCKED = SubscriptionFailure.BLOCKED_PRIVATE_ADDRESS
    }
}

/** Raised from OkHttp's DNS hook when a name resolves somewhere the policy refuses. */
class BlockedHostException(val reason: SubscriptionFailure) :
    UnknownHostException("subscription host refused: ${reason.label}")

/** The hosts a built-in source's own published URLs may be fetched from. */
object BuiltInSubscriptionHosts {

    fun forProfile(profile: FilterProfile): Set<String> {
        val hosts = LinkedHashSet<String>(2)
        hostOf(profile.primaryUrl)?.let(hosts::add)
        hostOf(profile.fallbackUrl)?.let(hosts::add)
        return hosts
    }

    /**
     * The single official host of [url].
     *
     * Each [FilterSource] has its own official address, which may live on a different host from the
     * HaGeZi tiers (AdGuard on github.io, OISD on oisd.nl). Built-in sources are therefore limited to
     * the host of the address they are actually configured with, never to a shared allow-list.
     */
    fun forUrl(url: String): Set<String> =
        hostOf(url)?.let { setOf(it) } ?: emptySet()

    fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host
}
