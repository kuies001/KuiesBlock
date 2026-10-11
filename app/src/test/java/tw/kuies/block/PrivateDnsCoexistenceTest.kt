package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.core.DnsPath
import tw.kuies.block.core.ProtectionState
import tw.kuies.block.dns.PrivateDnsSettings
import tw.kuies.block.dns.PrivateDnsState
import tw.kuies.block.ui.PrivateDnsTarget
import tw.kuies.block.ui.ProtectionPresentation
import tw.kuies.block.vpn.DnsExperimentMode
import tw.kuies.block.vpn.DnsExperimentPlanner
import tw.kuies.block.vpn.PrivateDnsChange
import tw.kuies.block.vpn.PrivateDnsCoexistencePolicy
import tw.kuies.block.vpn.TunnelDeclaration
import tw.kuies.block.vpn.VpnRoutePlan

/**
 * Reacting to Android's Private DNS setting while protection runs, and the wording that goes with it.
 * A rebuilt tunnel is expensive and disruptive, so the whole point is that only a real change of the
 * declaration may cause one.
 */
class PrivateDnsCoexistenceTest {

    private val plan = VpnRoutePlan.DEFAULT
    private val intercepting: TunnelDeclaration = DnsExperimentPlanner.baselineDeclaration(plan)
    private val steppingAside: TunnelDeclaration =
        DnsExperimentPlanner.declarationFor(DnsExperimentMode.E2_NO_DNS_DECLARATION, plan, null)!!

    private fun declarationForStrict(strict: Boolean): TunnelDeclaration =
        DnsExperimentPlanner.declarationFor(
            if (strict) DnsExperimentMode.E2_NO_DNS_DECLARATION else DnsExperimentMode.BASELINE,
            plan,
            realDotAddress = null
        )!!

    // --- the mode transitions the task asks about ---------------------------------------------

    @Test
    fun offToStrictRestartsTheTunnel() {
        assertEquals(PrivateDnsChange.RESTART_TUNNEL, PrivateDnsCoexistencePolicy.classify(intercepting, declarationForStrict(true)))
    }

    @Test
    fun automaticToStrictRestartsTheTunnel() {
        // "automatic" also declares the virtual DNS, so it takes the same path as "off".
        assertEquals(PrivateDnsChange.RESTART_TUNNEL, PrivateDnsCoexistencePolicy.classify(intercepting, declarationForStrict(true)))
    }

    @Test
    fun strictToAutomaticRestartsTheTunnel() {
        assertEquals(PrivateDnsChange.RESTART_TUNNEL, PrivateDnsCoexistencePolicy.classify(steppingAside, declarationForStrict(false)))
    }

    @Test
    fun strictToOffRestartsTheTunnel() {
        assertEquals(PrivateDnsChange.RESTART_TUNNEL, PrivateDnsCoexistencePolicy.classify(steppingAside, intercepting))
    }

    @Test
    fun renamingTheStrictHostnameDoesNotRestart() {
        // Strict hostname A -> strict hostname B yields the very same declaration: look only.
        assertEquals(
            PrivateDnsChange.REFRESH_STATUS,
            PrivateDnsCoexistencePolicy.classify(declarationForStrict(true), declarationForStrict(true))
        )
    }

    @Test
    fun aRepeatedNotificationDoesNotRestart() {
        repeat(3) {
            assertEquals(
                PrivateDnsChange.REFRESH_STATUS,
                PrivateDnsCoexistencePolicy.classify(intercepting, declarationForStrict(false))
            )
        }
    }

    @Test
    fun aChangeWhileStartingOrStoppingIsIgnored() {
        // Nothing is installed yet, so there is nothing to compare against and nothing to rebuild.
        assertEquals(PrivateDnsChange.NONE, PrivateDnsCoexistencePolicy.classify(null, declarationForStrict(true)))
        assertEquals(PrivateDnsChange.NONE, PrivateDnsCoexistencePolicy.classify(null, intercepting))
    }

    @Test
    fun aStaleCallbackAfterAStopIsIgnored() {
        // teardown() clears the applied declaration, so a late observer callback must do nothing —
        // in particular it must not resurrect a tunnel the user just stopped.
        assertEquals(PrivateDnsChange.NONE, PrivateDnsCoexistencePolicy.classify(null, null))
        assertEquals(PrivateDnsChange.NONE, PrivateDnsCoexistencePolicy.classify(null, declarationForStrict(true)))
    }

    @Test
    fun anUnusableCandidateKeepsTheWorkingTunnel() {
        // For example E1 without a resolved address: never trade a working tunnel for a guess.
        assertEquals(PrivateDnsChange.NONE, PrivateDnsCoexistencePolicy.classify(intercepting, null))
    }

    @Test
    fun onlyARealDeclarationChangeEverRestarts() {
        for (applied in listOf(null, intercepting, steppingAside)) {
            for (candidate in listOf(null, intercepting, steppingAside)) {
                val change = PrivateDnsCoexistencePolicy.classify(applied, candidate)
                if (change == PrivateDnsChange.RESTART_TUNNEL) {
                    assertTrue("applied=$applied candidate=$candidate", applied != null && candidate != null)
                    assertFalse("applied=$applied", applied == candidate)
                }
            }
        }
    }

    // --- observed settings keys ---------------------------------------------------------------

    @Test
    fun theObservedKeysAreTheTwoPrivateDnsGlobals() {
        assertEquals(listOf("private_dns_mode", "private_dns_specifier"), PrivateDnsSettings.OBSERVED_GLOBAL_KEYS)
        assertEquals(
            PrivateDnsSettings.OBSERVED_GLOBAL_KEYS.size,
            PrivateDnsSettings.OBSERVED_GLOBAL_KEYS.toSet().size
        )
    }

    // --- wording: never overclaim -------------------------------------------------------------

    @Test
    fun onlyOurOwnHostMayBeClassifiedAsAdGuardFiltered() {
        val ours = PrivateDnsSettings.stateOf("hostname", "dns.kuies.tw")
        val other = PrivateDnsSettings.stateOf("hostname", "dns.google")
        val unknown = PrivateDnsSettings.stateOf("hostname", "   ")
        val notStrict = PrivateDnsSettings.stateOf("opportunistic", "dns.google")

        assertEquals(PrivateDnsTarget.OUR_HOSTNAME, ProtectionPresentation.privateDnsTarget(ours, "dns.kuies.tw"))
        assertEquals(PrivateDnsTarget.OTHER_HOSTNAME, ProtectionPresentation.privateDnsTarget(other, "dns.kuies.tw"))
        assertEquals(PrivateDnsTarget.UNKNOWN_HOSTNAME, ProtectionPresentation.privateDnsTarget(unknown, "dns.kuies.tw"))
        assertEquals(PrivateDnsTarget.NOT_STRICT, ProtectionPresentation.privateDnsTarget(notStrict, "dns.kuies.tw"))
    }

    @Test
    fun theHostnameComparisonIgnoresCaseAndATrailingDot() {
        val state = PrivateDnsSettings.stateOf("hostname", "Dns.Kuies.TW.")
        assertEquals(PrivateDnsTarget.OUR_HOSTNAME, ProtectionPresentation.privateDnsTarget(state, "dns.kuies.tw"))
    }

    @Test
    fun theStrictWordingNeverClaimsValidationOrInterception() {
        val text = ProtectionPresentation.description(ProtectionState.ACTIVE, null, DnsPath.SYSTEM_PRIVATE_DNS)
        assertFalse("text=$text", text.contains("已驗證"))
        assertFalse("text=$text", text.contains("驗證成功"))
        assertFalse("text=$text", text.contains("攔截中"))
        assertTrue("text=$text", text.contains("未攔截"))
    }

    @Test
    fun theStrictDescriptionStaysShort() {
        val text = ProtectionPresentation.description(ProtectionState.ACTIVE, null, DnsPath.SYSTEM_PRIVATE_DNS)
        assertTrue("length=${text.length} text=$text", text.length <= 40)
    }

    // --- the shipped configuration still does not regress ------------------------------------

    @Test
    fun theBaselineDeclarationIsStillTheShippedOne() {
        assertEquals(intercepting, DnsExperimentPlanner.declarationFor(DnsExperimentMode.BASELINE, plan, null))
        assertEquals("10.111.222.2", intercepting.dnsServer)
        assertEquals(32, intercepting.dnsRoute?.prefixLength)
    }

    @Test
    fun theStepAsideDeclarationStillDropsOnlyTheDnsServer() {
        val strictState: PrivateDnsState = PrivateDnsSettings.stateOf("hostname", "dns.kuies.tw")
        assertTrue(strictState.isStrictHostname)
        assertNull(steppingAside.dnsServer)
        assertEquals(intercepting.interfaceAddress, steppingAside.interfaceAddress)
        assertEquals(intercepting.dnsRoute, steppingAside.dnsRoute)
    }
}
