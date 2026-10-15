package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kuies.block.dns.PrivateDnsMode
import tw.kuies.block.filter.LocalFilterPolicy
import tw.kuies.block.filter.LocalFilterState

/**
 * What the settings page is allowed to claim about the local filter.
 *
 * The three Android Private DNS modes are the whole point: strict hostname mode takes DNS away from
 * the tunnel, so no rule can run and the UI must say so instead of promising filtering.
 */
class LocalFilterPolicyTest {

    @Test
    fun filteringIsActiveWhenTheTunnelResolvesAndRulesExist() {
        assertEquals(LocalFilterState.ACTIVE, LocalFilterPolicy.state(PrivateDnsMode.OFF, 1))
        assertEquals(LocalFilterState.ACTIVE, LocalFilterPolicy.state(PrivateDnsMode.OFF, 161_902))
        assertEquals(LocalFilterState.ACTIVE, LocalFilterPolicy.state(PrivateDnsMode.OPPORTUNISTIC, 1))
        assertEquals(
            "an unreadable setting behaves like the intercepting modes",
            LocalFilterState.ACTIVE,
            LocalFilterPolicy.state(PrivateDnsMode.UNKNOWN, 1)
        )
    }

    @Test
    fun strictPrivateDnsPausesTheFilter() {
        assertEquals(
            LocalFilterState.PAUSED_PRIVATE_DNS,
            LocalFilterPolicy.state(PrivateDnsMode.HOSTNAME, 161_902)
        )
    }

    /** Strict mode is the more consequential problem, so it is reported even when rules are missing. */
    @Test
    fun strictPrivateDnsIsReportedBeforeAMissingRuleSet() {
        assertEquals(
            LocalFilterState.PAUSED_PRIVATE_DNS,
            LocalFilterPolicy.state(PrivateDnsMode.HOSTNAME, 0)
        )
    }

    @Test
    fun noStoredRulesMeansNothingCanBeClaimedYet() {
        assertEquals(LocalFilterState.NO_RULES, LocalFilterPolicy.state(PrivateDnsMode.OFF, 0))
        assertEquals(LocalFilterState.NO_RULES, LocalFilterPolicy.state(PrivateDnsMode.OPPORTUNISTIC, 0))
        assertEquals(LocalFilterState.NO_RULES, LocalFilterPolicy.state(PrivateDnsMode.UNKNOWN, 0))
    }
}
