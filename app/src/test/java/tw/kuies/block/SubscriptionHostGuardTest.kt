package tw.kuies.block

import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.filter.BlockedHostException
import tw.kuies.block.filter.BuiltInSubscriptionHosts
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.SubscriptionFailure
import tw.kuies.block.filter.SubscriptionHostGuard
import tw.kuies.block.filter.SubscriptionOrigin

/**
 * The SSRF policy for subscription downloads.
 *
 * The resolver is injected, so nothing here touches a real name server and every address range the
 * policy claims to refuse is asserted explicitly.
 */
class SubscriptionHostGuardTest {

    private val publicAddress = InetAddress.getByName("93.184.216.34")

    /** Only names this map knows about resolve, and only to literal addresses. */
    private fun guard(resolutions: Map<String, List<String>> = emptyMap()) = SubscriptionHostGuard { host ->
        val addresses = resolutions[host] ?: throw UnknownHostException(host)
        addresses.map { InetAddress.getByName(it) }
    }

    private fun userCheck(url: String, guard: SubscriptionHostGuard = guard()) =
        guard.check(url, SubscriptionOrigin.USER)

    private fun userHostCheck(host: String, guard: SubscriptionHostGuard = guard()) =
        guard.checkHost(host, SubscriptionOrigin.USER)

    @Test
    fun onlyHttpsIsAccepted() {
        assertEquals(SubscriptionFailure.INSECURE_URL, userCheck("http://lists.example.com/ads.txt"))
        // OkHttp's URL type only carries http and https, so anything else is not a usable URL at all.
        assertEquals(SubscriptionFailure.INVALID_URL, userCheck("ftp://lists.example.com/ads.txt"))
        assertEquals(SubscriptionFailure.INVALID_URL, userCheck("lists.example.com/ads.txt"))
        assertEquals(SubscriptionFailure.INVALID_URL, userCheck(""))
        assertEquals(SubscriptionFailure.INVALID_URL, userCheck("https://"))
    }

    @Test
    fun everyPrivateAndLocalAddressRangeIsRefused() {
        val refused = listOf(
            "10.0.0.1",
            "10.255.255.255",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.0.1",
            "192.168.255.254",
            "127.0.0.1",
            "127.9.9.9",
            "169.254.10.10",
            "0.0.0.0",
            "100.64.0.1",
            "224.0.0.1",
            "255.255.255.255",
            "::1",
            "::",
            "fe80::1",
            "fd12:3456:789a::1",
            "fc00::1",
            "ff02::1",
            "::ffff:127.0.0.1",
            "::ffff:192.168.1.1"
        )

        for (literal in refused) {
            val host = if (literal.contains(':')) "[$literal]" else literal
            assertEquals(
                "$literal must be refused",
                SubscriptionFailure.BLOCKED_PRIVATE_ADDRESS,
                userCheck("https://$host/ads.txt")
            )
        }
    }

    @Test
    fun publicLiteralAddressesAndOrdinaryNamesAreAllowed() {
        assertNull(userCheck("https://93.184.216.34/ads.txt"))
        assertNull(userCheck("https://[2606:2800:220:1:248:1893:25c8:1946]/ads.txt"))
        assertNull(userCheck("https://lists.example.com/ads.txt", guard(mapOf("lists.example.com" to listOf("93.184.216.34")))))
    }

    @Test
    fun localNamesAreRefusedWhateverTheyResolveTo() {
        val names = listOf(
            "localhost",
            "localhost.",
            "localhost.localdomain",
            "list.local",
            "filters.internal",
            "srv.internal"
        )

        for (name in names) {
            assertEquals(
                "$name must be refused by name",
                SubscriptionFailure.BLOCKED_LOCAL_HOST,
                userHostCheck(name)
            )
        }
        // A name that merely ends with the same letters is fine.
        assertNull(
            userCheck(
                "https://notlocal.example.com/ads.txt",
                guard(mapOf("notlocal.example.com" to listOf("93.184.216.34")))
            )
        )
    }

    @Test
    fun aNameThatResolvesIntoAPrivateRangeIsRefused() {
        val resolutions = mapOf(
            "rebind.example.com" to listOf("93.184.216.34", "127.0.0.1"),
            "lan.example.com" to listOf("10.1.2.3"),
            "v6.example.com" to listOf("fd00::5")
        )
        val guard = guard(resolutions)

        assertEquals(
            SubscriptionFailure.BLOCKED_PRIVATE_ADDRESS,
            guard.check("https://rebind.example.com/ads.txt", SubscriptionOrigin.USER)
        )
        assertEquals(
            SubscriptionFailure.BLOCKED_PRIVATE_ADDRESS,
            guard.check("https://lan.example.com/ads.txt", SubscriptionOrigin.USER)
        )
        assertEquals(
            SubscriptionFailure.BLOCKED_PRIVATE_ADDRESS,
            guard.check("https://v6.example.com/ads.txt", SubscriptionOrigin.USER)
        )
    }

    @Test
    fun aNameThatDoesNotResolveIsAConnectionFailureNotAnAcceptance() {
        assertEquals(
            SubscriptionFailure.CONNECT_FAILED,
            guard().check("https://nowhere.example.com/ads.txt", SubscriptionOrigin.USER)
        )
        assertEquals(
            SubscriptionFailure.CONNECT_FAILED,
            guard(mapOf("empty.example.com" to emptyList())).check(
                "https://empty.example.com/ads.txt",
                SubscriptionOrigin.USER
            )
        )
    }

    @Test
    fun aBuiltInSourceMayOnlyUseTheHostsItsOwnTierPublishes() {
        val guard = guard(mapOf("cdn.jsdelivr.net" to listOf("151.101.1.229")))
        val allowed = BuiltInSubscriptionHosts.forProfile(FilterProfile.STANDARD)

        assertNull(guard.check(FilterProfile.STANDARD.primaryUrl, SubscriptionOrigin.BUILT_IN, allowed))
        assertEquals(
            SubscriptionFailure.HOST_NOT_ALLOWED,
            guard.check("https://evil.example.com/ads.txt", SubscriptionOrigin.BUILT_IN, allowed)
        )
        assertEquals(
            "a built-in source without an allow-list must be denied, not trusted",
            SubscriptionFailure.HOST_NOT_ALLOWED,
            guard.check(FilterProfile.STANDARD.primaryUrl, SubscriptionOrigin.BUILT_IN)
        )
    }

    @Test
    fun aUserSourceMayUseAnyPublicHost() {
        assertNull(
            userCheck(
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/multi.txt",
                guard(mapOf("raw.githubusercontent.com" to listOf("185.199.108.133")))
            )
        )
        assertEquals(
            "the same host is still address-checked for a user source",
            SubscriptionFailure.BLOCKED_PRIVATE_ADDRESS,
            userCheck("https://my.router.example@127.0.0.1/ads.txt")
        )
    }

    @Test
    fun everyTierPublishesTheSameTwoHostsAndTheyAreTheDocumentedOnes() {
        for (profile in FilterProfile.entries) {
            assertEquals(
                setOf("cdn.jsdelivr.net", "raw.githubusercontent.com"),
                BuiltInSubscriptionHosts.forProfile(profile)
            )
        }
        assertEquals("cdn.jsdelivr.net", BuiltInSubscriptionHosts.hostOf(FilterProfile.RELAXED.primaryUrl))
        assertNull(BuiltInSubscriptionHosts.hostOf("not a url"))
    }

    @Test
    fun theConnectionHookRefusesPrivateAnswersAndAcceptsPublicOnes() {
        val guard = guard(
            mapOf(
                "public.example.com" to listOf("93.184.216.34"),
                "rebind.example.com" to listOf("192.168.1.5")
            )
        )

        assertEquals(listOf(publicAddress), guard.resolveForConnection("public.example.com"))
        assertEquals(
            listOf(InetAddress.getByName("93.184.216.34")),
            guard.resolveForConnection("93.184.216.34")
        )

        val rebound = runCatching { guard.resolveForConnection("rebind.example.com") }.exceptionOrNull()
        assertEquals(
            SubscriptionFailure.BLOCKED_PRIVATE_ADDRESS,
            (rebound as BlockedHostException).reason
        )
        val literals = runCatching { guard.resolveForConnection("127.0.0.1") }.exceptionOrNull()
        assertTrue(literals is BlockedHostException)
        val unknown = runCatching { guard.resolveForConnection("nowhere.example.com") }.exceptionOrNull()
        assertTrue(unknown is UnknownHostException)
    }

    @Test
    fun theAddressPolicyIsAppliedPerAddressRange() {
        val guard = guard()
        val blocked = SubscriptionFailure.BLOCKED_PRIVATE_ADDRESS

        assertNull(guard.addressFailure(publicAddress))
        assertNull("172.15.0.9 is public", guard.addressFailure(InetAddress.getByName("172.15.0.9")))
        assertEquals(blocked, guard.addressFailure(InetAddress.getByName("172.20.0.9")))
        assertNull("100.63.0.1 is public", guard.addressFailure(InetAddress.getByName("100.63.0.1")))
        assertEquals(blocked, guard.addressFailure(InetAddress.getByName("100.127.1.1")))
    }
}
