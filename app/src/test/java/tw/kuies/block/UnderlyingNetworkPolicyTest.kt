package tw.kuies.block

import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.vpn.UnderlyingCandidate
import tw.kuies.block.vpn.UnderlyingNetworkPolicy
import tw.kuies.block.vpn.UnderlyingNetworkReason
import tw.kuies.block.vpn.UnderlyingTransport

/**
 * Which non-VPN network the tunnel rides on, and when that choice is allowed to move.
 *
 * The measured defect this policy exists for: the app used to adopt whatever network reported last,
 * so a routine cellular link-property update took the tunnel off a validated Wi-Fi network that
 * Android was still using, and nothing ever moved it back. These tests pin the ordering, the
 * stability of the choice, and the "an event that changes nothing must change nothing" rule.
 */
class UnderlyingNetworkPolicyTest {

    private fun wifi(handle: Long = 100L, validated: Boolean = true, metered: Boolean = false) =
        UnderlyingCandidate(handle, UnderlyingTransport.LOCAL, validated, metered)

    private fun cellular(handle: Long = 50L, validated: Boolean = true, metered: Boolean = true) =
        UnderlyingCandidate(handle, UnderlyingTransport.CELLULAR, validated, metered)

    private fun other(handle: Long = 900L, validated: Boolean = true, metered: Boolean = false) =
        UnderlyingCandidate(handle, UnderlyingTransport.OTHER, validated, metered)

    private fun servers(address: String) = listOf(InetSocketAddress(address, 53))

    // --- the defect that was measured ---------------------------------------------------------

    @Test
    fun aCellularEventCannotDisplaceTheValidatedWifiInUse() {
        // Wi-Fi is in use; an event for the cellular network changes nothing.
        val decision = UnderlyingNetworkPolicy.decide(
            candidates = listOf(wifi(), cellular()),
            current = wifi()
        )

        assertEquals(wifi(), decision.candidate)
        assertEquals(UnderlyingNetworkReason.CURRENT_STILL_RANKS_HIGHEST, decision.reason)
    }

    @Test
    fun theWifiIsAdoptedWhenItAppearsWhileCellularIsInUse() {
        // Wi-Fi came back (a new network) while the tunnel was riding on cellular.
        val decision = UnderlyingNetworkPolicy.decide(
            candidates = listOf(wifi(handle = 327L), cellular()),
            current = cellular()
        )

        assertEquals(wifi(handle = 327L), decision.candidate)
        assertEquals(UnderlyingNetworkReason.BETTER_CHOICE_APPEARED, decision.reason)
    }

    @Test
    fun theSameNetworksInAnyOrderProduceTheSameChoice() {
        val networks = listOf(wifi(), cellular(), other())

        val forwards = UnderlyingNetworkPolicy.decide(networks, current = null).candidate
        val backwards = UnderlyingNetworkPolicy.decide(networks.reversed(), current = null).candidate

        // Enumeration order must never decide this: cellular is listed first on real devices.
        assertEquals(wifi(), forwards)
        assertEquals(forwards, backwards)
    }

    @Test
    fun aValidatedNetworkOutranksAnUnvalidatedOneRegardlessOfTransport() {
        // This mirrors Android yielding to a cellular network when the Wi-Fi is not validated.
        val decision = UnderlyingNetworkPolicy.decide(
            candidates = listOf(wifi(validated = false), cellular()),
            current = null
        )

        assertEquals(cellular(), decision.candidate)
        assertEquals(UnderlyingNetworkReason.FIRST_CHOICE, decision.reason)
    }

    @Test
    fun unmeteredWinsBetweenTwoNetworksOfTheSameTransport() {
        val decision = UnderlyingNetworkPolicy.decide(
            candidates = listOf(wifi(handle = 100L, metered = true), wifi(handle = 200L, metered = false)),
            current = null
        )

        assertEquals(wifi(handle = 200L), decision.candidate)
    }

    @Test
    fun tiesKeepTheNetworkAlreadyInUse() {
        val inUse = wifi(handle = 200L)
        val decision = UnderlyingNetworkPolicy.decide(
            candidates = listOf(wifi(handle = 100L), inUse),
            current = inUse
        )

        assertEquals(inUse, decision.candidate)
        assertEquals(UnderlyingNetworkReason.CURRENT_STILL_RANKS_HIGHEST, decision.reason)
        // With nothing in use the tie is broken deterministically, so it never flaps.
        assertEquals(wifi(handle = 100L), UnderlyingNetworkPolicy.decide(candidates = listOf(wifi(handle = 100L), wifi(handle = 200L)), current = null).candidate)
    }

    @Test
    fun losingTheCurrentNetworkFallsBackToTheBestRemaining() {
        val decision = UnderlyingNetworkPolicy.decide(
            candidates = listOf(cellular()),
            current = wifi()
        )

        assertEquals(cellular(), decision.candidate)
        assertEquals(UnderlyingNetworkReason.CURRENT_NO_LONGER_USABLE, decision.reason)
    }

    @Test
    fun nothingUsableLeavesTheChoiceAlone() {
        val decision = UnderlyingNetworkPolicy.decide(candidates = emptyList(), current = wifi())

        assertNull(decision.candidate)
        assertEquals(UnderlyingNetworkReason.NO_USABLE_NETWORK, decision.reason)
    }

    // --- the system's own answer --------------------------------------------------------------

    @Test
    fun theSystemDefaultWinsWhenItIsOneOfTheCandidates() {
        // The system named cellular (for example it yielded to mobile data), so we follow it even
        // though a validated Wi-Fi is on offer.
        val decision = UnderlyingNetworkPolicy.decide(
            candidates = listOf(wifi(), cellular()),
            current = wifi(),
            systemDefault = cellular()
        )

        assertEquals(cellular(), decision.candidate)
        assertEquals(UnderlyingNetworkReason.SYSTEM_DEFAULT, decision.reason)
    }

    @Test
    fun theSystemDefaultIsIgnoredWhenItIsNotACandidate() {
        // For example the system's active network is a VPN (our own tunnel), which is never a
        // candidate; the ranking then decides.
        val foreign = wifi(handle = 999L)

        val decision = UnderlyingNetworkPolicy.decide(
            candidates = listOf(wifi(), cellular()),
            current = null,
            systemDefault = foreign
        )

        assertEquals(wifi(), decision.candidate)
        assertEquals(UnderlyingNetworkReason.FIRST_CHOICE, decision.reason)
    }

    @Test
    fun namingTheSystemDefaultAgainChangesNothing() {
        val decision = UnderlyingNetworkPolicy.decide(
            candidates = listOf(wifi(), cellular()),
            current = wifi(),
            systemDefault = wifi()
        )

        assertEquals(wifi(), decision.candidate)
        assertEquals(UnderlyingNetworkReason.CURRENT_STILL_RANKS_HIGHEST, decision.reason)
    }

    // --- when the tunnel has to be touched at all ---------------------------------------------

    @Test
    fun theFirstDeclarationAlwaysHappens() {
        assertTrue(
            UnderlyingNetworkPolicy.upstreamChangeRequired(
                appliedNetworkHandle = null,
                appliedDnsServers = emptyList(),
                chosenNetworkHandle = 100L,
                chosenDnsServers = servers("192.0.2.1")
            )
        )
    }

    @Test
    fun aRepeatEventNeedsNoWork() {
        assertFalse(
            UnderlyingNetworkPolicy.upstreamChangeRequired(
                appliedNetworkHandle = 100L,
                appliedDnsServers = servers("192.0.2.1"),
                chosenNetworkHandle = 100L,
                chosenDnsServers = servers("192.0.2.1")
            )
        )
    }

    @Test
    fun aNewNetworkNeedsWork() {
        assertTrue(
            UnderlyingNetworkPolicy.upstreamChangeRequired(
                appliedNetworkHandle = 100L,
                appliedDnsServers = servers("192.0.2.1"),
                chosenNetworkHandle = 200L,
                chosenDnsServers = servers("192.0.2.1")
            )
        )
    }

    @Test
    fun changedBootstrapServersNeedWork() {
        assertTrue(
            UnderlyingNetworkPolicy.upstreamChangeRequired(
                appliedNetworkHandle = 100L,
                appliedDnsServers = servers("192.0.2.1"),
                chosenNetworkHandle = 100L,
                chosenDnsServers = servers("192.0.2.2")
            )
        )
    }

    // --- stale events, and events after a stop ------------------------------------------------

    @Test
    fun anEventFromAnOlderGenerationIsNeverCurrent() {
        assertFalse(UnderlyingNetworkPolicy.isCurrentEvent(running = true, eventGeneration = 1, tunnelGeneration = 2))
    }

    @Test
    fun anEventForTheRunningTunnelIsCurrent() {
        assertTrue(UnderlyingNetworkPolicy.isCurrentEvent(running = true, eventGeneration = 2, tunnelGeneration = 2))
    }

    @Test
    fun anEventAfterProtectionStoppedIsNeverCurrent() {
        // A stop must not be undone by a network event that was already on its way.
        assertFalse(UnderlyingNetworkPolicy.isCurrentEvent(running = false, eventGeneration = 2, tunnelGeneration = 2))
    }

    // --- the diagnostic label stays safe to log ----------------------------------------------

    @Test
    fun theLabelNamesNoNetwork() {
        val label = UnderlyingNetworkPolicy.describe(wifi(handle = 1403565035533L, metered = true))

        assertEquals("local+validated", label)
        assertFalse(label.contains("1403565035533"))
        assertEquals("cellular+validated", UnderlyingNetworkPolicy.describe(cellular()))
        assertEquals("none", UnderlyingNetworkPolicy.describe(null))
    }
}
