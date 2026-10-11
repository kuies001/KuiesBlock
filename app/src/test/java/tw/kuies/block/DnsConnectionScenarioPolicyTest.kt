package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kuies.block.dns.DnsCheckStatus
import tw.kuies.block.dns.DnsConnectionScenario
import tw.kuies.block.dns.DnsConnectionScenarioPolicy

class DnsConnectionScenarioPolicyTest {

    private fun scenarioOf(status: DnsCheckStatus, mayBypass: Boolean) =
        DnsConnectionScenarioPolicy.scenarioOf(status, mayBypass)

    @Test
    fun privateDnsOffWithWorkingDohIsConnected() {
        assertEquals(DnsConnectionScenario.CONNECTED, scenarioOf(DnsCheckStatus.OK, mayBypass = false))
    }

    @Test
    fun automaticPrivateDnsWithWorkingDohIsOnlyABypassRisk() {
        assertEquals(DnsConnectionScenario.BYPASS_RISK, scenarioOf(DnsCheckStatus.OK, mayBypass = true))
    }

    @Test
    fun hostnamePrivateDnsWithWorkingDohIsOnlyABypassRisk() {
        assertEquals(DnsConnectionScenario.BYPASS_RISK, scenarioOf(DnsCheckStatus.OK, mayBypass = true))
    }

    @Test
    fun privateDnsPinnedToOurOwnHostStillReportsOnlyTheBypassRisk() {
        // The same hostname does not prove the same filtering policy, so this stays a warning and
        // must never be presented as a plain success or as a failure.
        assertEquals(DnsConnectionScenario.BYPASS_RISK, scenarioOf(DnsCheckStatus.OK, mayBypass = true))
    }

    @Test
    fun privateDnsEnabledWithARealFailureStaysAFailure() {
        assertEquals(DnsConnectionScenario.FAILED, scenarioOf(DnsCheckStatus.ERROR, mayBypass = true))
        assertEquals(DnsConnectionScenario.FAILED, scenarioOf(DnsCheckStatus.WARNING, mayBypass = true))
    }

    @Test
    fun anUnresolvableHostIsNeverPresentedAsConnected() {
        // UnknownHostException surfaces as ERROR; it must not become CONNECTED even with Private DNS off.
        assertEquals(DnsConnectionScenario.FAILED, scenarioOf(DnsCheckStatus.ERROR, mayBypass = false))
    }

    @Test
    fun aTlsFailureStaysAFailure() {
        assertEquals(DnsConnectionScenario.FAILED, scenarioOf(DnsCheckStatus.WARNING, mayBypass = false))
        assertEquals(DnsConnectionScenario.FAILED, scenarioOf(DnsCheckStatus.ERROR, mayBypass = false))
    }

    @Test
    fun nothingCheckedYetIsUnknown() {
        assertEquals(DnsConnectionScenario.UNKNOWN, scenarioOf(DnsCheckStatus.NOT_CHECKED, mayBypass = false))
        assertEquals(DnsConnectionScenario.UNKNOWN, scenarioOf(DnsCheckStatus.CHECKING, mayBypass = false))
    }

    @Test
    fun aBypassWarningNeverTurnsAFailureIntoSuccess() {
        DnsCheckStatus.entries.forEach { status ->
            val withBypass = scenarioOf(status, mayBypass = true)
            val withoutBypass = scenarioOf(status, mayBypass = false)
            if (withoutBypass == DnsConnectionScenario.FAILED) {
                assertEquals(DnsConnectionScenario.FAILED, withBypass)
            }
        }
    }
}
