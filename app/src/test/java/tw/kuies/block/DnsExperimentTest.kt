package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.core.ProtectionStateRules
import tw.kuies.block.vpn.DnsExperiment
import tw.kuies.block.vpn.DnsExperimentMode
import tw.kuies.block.vpn.DnsExperimentPlanner
import tw.kuies.block.vpn.VpnRoutePlan

/**
 * The three Private-DNS experiment configurations. BASELINE must be exactly today's shipped plan,
 * E2 must drop only the DNS declaration, E1 must use the real DoT address without routing it — and
 * a release build must never be able to leave BASELINE.
 */
class DnsExperimentTest {

    private val baseline = VpnRoutePlan.DEFAULT

    /** RFC 5737 documentation address: never a real server, and not the virtual DNS address. */
    private val realDotAddress = "203.0.113.10"

    // --- BASELINE is unchanged ----------------------------------------------------------------

    @Test
    fun baselineDeclarationMatchesTheShippedPlan() {
        val declaration = DnsExperimentPlanner.baselineDeclaration(baseline)
        assertEquals("10.111.222.1", declaration.interfaceAddress)
        assertEquals(32, declaration.interfacePrefixLength)
        assertEquals("10.111.222.2", declaration.dnsServer)
        assertEquals("10.111.222.2", declaration.dnsRoute?.address)
        assertEquals(32, declaration.dnsRoute?.prefixLength)
        assertEquals(baseline.mtu, declaration.mtu)
        assertEquals(declaration, DnsExperimentPlanner.declarationFor(DnsExperimentMode.BASELINE, baseline, null))
    }

    // --- E2 -----------------------------------------------------------------------------------

    @Test
    fun e2DeclaresNoDnsServerButKeepsEverythingElse() {
        val e2 = DnsExperimentPlanner.declarationFor(DnsExperimentMode.E2_NO_DNS_DECLARATION, baseline, null)!!
        assertNull("E2 must not call addDnsServer", e2.dnsServer)
        // Everything else stays as close to BASELINE as possible.
        val shipped = DnsExperimentPlanner.baselineDeclaration(baseline)
        assertEquals(shipped.interfaceAddress, e2.interfaceAddress)
        assertEquals(shipped.interfacePrefixLength, e2.interfacePrefixLength)
        assertEquals(shipped.dnsRoute, e2.dnsRoute)
        assertEquals(shipped.mtu, e2.mtu)
    }

    // --- E1 -----------------------------------------------------------------------------------

    @Test
    fun e1UsesTheRealAddressAndAddsNoRouteForIt() {
        val e1 = DnsExperimentPlanner.declarationFor(DnsExperimentMode.E1_REAL_DOT_ADDRESS, baseline, realDotAddress)!!
        assertEquals(realDotAddress, e1.dnsServer)
        assertNull("E1 must not route the real DoT address", e1.dnsRoute)
        // The interface address is still kept, so the IPv4 family stays declared.
        assertEquals("10.111.222.1", e1.interfaceAddress)
        assertFalse(e1.hasCatchAllRoute())
    }

    @Test
    fun e1IsRefusedWithoutAUsableRealAddress() {
        assertNull(DnsExperimentPlanner.declarationFor(DnsExperimentMode.E1_REAL_DOT_ADDRESS, baseline, null))
        assertNull(DnsExperimentPlanner.declarationFor(DnsExperimentMode.E1_REAL_DOT_ADDRESS, baseline, ""))
        assertNull(DnsExperimentPlanner.declarationFor(DnsExperimentMode.E1_REAL_DOT_ADDRESS, baseline, "   "))
        assertNull(
            "an invalid address must never be declared",
            DnsExperimentPlanner.declarationFor(DnsExperimentMode.E1_REAL_DOT_ADDRESS, baseline, "not-an-ip")
        )
    }

    @Test
    fun e1RefusesTheVirtualDnsAddressItself() {
        // Declaring the virtual DNS again would just reproduce the broken strict-mode case.
        assertNull(
            DnsExperimentPlanner.declarationFor(
                DnsExperimentMode.E1_REAL_DOT_ADDRESS,
                baseline,
                baseline.dnsServerAddress
            )
        )
    }

    // --- Safety invariants --------------------------------------------------------------------

    @Test
    fun noDeclarationEverDeclaresACatchAllRoute() {
        for (mode in DnsExperimentMode.entries) {
            val declaration =
                DnsExperimentPlanner.declarationFor(mode, baseline, realDotAddress) ?: continue
            assertFalse("mode=$mode", declaration.hasCatchAllRoute())
            assertTrue("mode=$mode", (declaration.dnsRoute?.prefixLength ?: 32) > 0)
            assertTrue("mode=$mode", declaration.interfacePrefixLength > 0)
        }
    }

    @Test
    fun everyDeclarationKeepsAnIpv4InterfaceAddress() {
        // VpnService blocks a whole address family when it has no address, route or DNS of that
        // family, so every experiment must keep the IPv4 interface address.
        for (mode in DnsExperimentMode.entries) {
            val declaration =
                DnsExperimentPlanner.declarationFor(mode, baseline, realDotAddress) ?: continue
            assertTrue("mode=$mode", declaration.interfaceAddress.isNotBlank())
            assertEquals("mode=$mode", 32, declaration.interfacePrefixLength)
        }
    }

    @Test
    fun stoppingIsIndependentOfTheExperimentMode() {
        // STOP must be able to end an experiment tunnel exactly like a baseline one.
        assertEquals(ProtectionState.AUTHORIZED_IDLE, ProtectionStateRules.afterServiceStopped())
        assertFalse(ProtectionStateRules.isInterceptionActive(ProtectionStateRules.afterServiceStopped()))
    }

    // --- Release can never run an experiment ---------------------------------------------------

    @Test
    fun aReleaseBuildAlwaysFollowsTheCoexistenceRule() {
        // Nothing armed may change a release build; only strict Private DNS does.
        val armable = listOf(
            null,
            DnsExperimentMode.BASELINE,
            DnsExperimentMode.E2_NO_DNS_DECLARATION,
            DnsExperimentMode.E1_REAL_DOT_ADDRESS
        )
        for (armed in armable) {
            assertEquals(
                "armed=$armed",
                DnsExperimentMode.BASELINE,
                DnsExperiment.effectiveMode(isDebuggable = false, armed = armed, strictPrivateDns = false)
            )
            assertEquals(
                "armed=$armed strict",
                DnsExperimentMode.E2_NO_DNS_DECLARATION,
                DnsExperiment.effectiveMode(isDebuggable = false, armed = armed, strictPrivateDns = true)
            )
        }
    }

    @Test
    fun strictPrivateDnsIsTheOnlyModeThatStepsAside() {
        assertEquals(
            DnsExperimentMode.E2_NO_DNS_DECLARATION,
            DnsExperiment.effectiveMode(true, armed = null, strictPrivateDns = true)
        )
        // Off and automatic keep the intercepting configuration.
        assertEquals(
            DnsExperimentMode.BASELINE,
            DnsExperiment.effectiveMode(true, armed = null, strictPrivateDns = false)
        )
        assertEquals(
            DnsExperimentMode.BASELINE,
            DnsExperiment.effectiveMode(false, armed = null, strictPrivateDns = false)
        )
    }

    @Test
    fun aDebugBuildMayArmAnOverrideToReproduceTheBrokenCase() {
        // Forcing BASELINE while strict is exactly how the original failure is reproduced.
        assertEquals(
            DnsExperimentMode.BASELINE,
            DnsExperiment.effectiveMode(true, armed = DnsExperimentMode.BASELINE, strictPrivateDns = true)
        )
        assertEquals(
            DnsExperimentMode.E2_NO_DNS_DECLARATION,
            DnsExperiment.effectiveMode(
                true,
                armed = DnsExperimentMode.E2_NO_DNS_DECLARATION,
                strictPrivateDns = false
            )
        )
        assertEquals(
            DnsExperimentMode.E1_REAL_DOT_ADDRESS,
            DnsExperiment.effectiveMode(true, armed = DnsExperimentMode.E1_REAL_DOT_ADDRESS, strictPrivateDns = false)
        )
    }

    @Test
    fun anOverrideIsNeverReplayedFromStorage() {
        // The regression this guards: the first experiment build persisted its mode, so a stale
        // "e2" silently replaced the shipped rule days later. Arming is process-scoped now, so a
        // fresh process starts with nothing armed.
        assertEquals(null, DnsExperiment.armedMode())
        assertEquals(
            DnsExperimentMode.E2_NO_DNS_DECLARATION,
            DnsExperiment.effectiveMode(true, armed = DnsExperiment.armedMode(), strictPrivateDns = true)
        )
        assertEquals(
            DnsExperimentMode.BASELINE,
            DnsExperiment.effectiveMode(true, armed = DnsExperiment.armedMode(), strictPrivateDns = false)
        )
    }

    @Test
    fun storageKeysRoundTripAndUnknownValuesFallBackToBaseline() {
        for (mode in DnsExperimentMode.entries) {
            assertEquals(mode, DnsExperimentMode.fromStorageKey(mode.storageKey))
        }
        // Case and surrounding whitespace are tolerated.
        assertEquals(DnsExperimentMode.E2_NO_DNS_DECLARATION, DnsExperimentMode.fromStorageKey("  E2 "))
        assertEquals(DnsExperimentMode.BASELINE, DnsExperimentMode.fromStorageKey("nonsense"))
        assertEquals(DnsExperimentMode.BASELINE, DnsExperimentMode.fromStorageKey(null))
    }
}
