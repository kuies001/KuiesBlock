package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.data.ProtectionIntentStore
import tw.kuies.block.vpn.AlwaysOnVpnProbe
import tw.kuies.block.vpn.BootAutostartPolicy
import tw.kuies.block.vpn.OtherVpnState

/**
 * Whether a restart may start protection, and the facts the decision is made from.
 *
 * Every gate is asserted on its own, because the feature's whole safety story is that a wrong "start"
 * cannot happen: the default is off, a deliberate turn-off survives, nothing is opened or taken from
 * another app — and, since this round, a slot the platform will not let us inspect is treated as
 * occupied rather than as free.
 */
class BootAutostartPolicyTest {

    /** All gates satisfied unless a test says otherwise, so each test changes exactly one thing. */
    private fun decide(
        preferenceEnabled: Boolean = true,
        authorizationGranted: Boolean = true,
        userWantsProtection: Boolean = true,
        serviceRunning: Boolean = false,
        alwaysOnVpn: Boolean = false,
        otherVpn: OtherVpnState = OtherVpnState.ABSENT
    ): BootAutostartPolicy.Decision = BootAutostartPolicy.decide(
        preferenceEnabled = preferenceEnabled,
        authorizationGranted = authorizationGranted,
        userWantsProtection = userWantsProtection,
        serviceRunning = serviceRunning,
        alwaysOnVpn = alwaysOnVpn,
        otherVpn = otherVpn
    )

    @Test
    fun `the preference defaults to off, so a fresh install starts nothing`() {
        val store = ProtectionIntentStore(FakeSharedPreferences())
        assertFalse(store.bootAutostartEnabled())
        assertEquals(BootAutostartPolicy.Decision.SKIP_NOT_ENABLED, decide(preferenceEnabled = false))
    }

    @Test
    fun `the preference and the protection intent both survive a reload`() {
        val prefs = FakeSharedPreferences()
        ProtectionIntentStore(prefs).setBootAutostartEnabled(true)
        ProtectionIntentStore(prefs).setProtectionDesiredOn(true)

        val reloaded = ProtectionIntentStore(prefs)
        assertTrue(reloaded.bootAutostartEnabled())
        assertTrue(reloaded.protectionDesiredOn())
    }

    @Test
    fun `a user who never started protection is not started for`() {
        // Default state: the switch was never turned on, so nothing may begin at boot.
        assertEquals(BootAutostartPolicy.Decision.SKIP_USER_TURNED_IT_OFF, decide(userWantsProtection = false))
    }

    @Test
    fun `every gate lined up starts exactly once`() {
        assertEquals(BootAutostartPolicy.Decision.START, decide())
    }

    @Test
    fun `without the authorization it skips instead of asking for it`() {
        assertEquals(BootAutostartPolicy.Decision.SKIP_NOT_AUTHORIZED, decide(authorizationGranted = false))
    }

    @Test
    fun `always-on VPN owns the start, so nothing is started twice`() {
        assertEquals(BootAutostartPolicy.Decision.SKIP_ALWAYS_ON, decide(alwaysOnVpn = true))
    }

    @Test
    fun `a tunnel that is already up is left alone`() {
        assertEquals(BootAutostartPolicy.Decision.SKIP_ALREADY_RUNNING, decide(serviceRunning = true))
    }

    @Test
    fun `another VPN holding the slot is respected`() {
        assertEquals(BootAutostartPolicy.Decision.SKIP_OTHER_VPN, decide(otherVpn = OtherVpnState.PRESENT))
    }

    @Test
    fun `a slot the platform will not let us inspect is treated as occupied`() {
        // The defect this round fixed: UNKNOWN used to arrive here as "no other VPN" and the boot would
        // proceed to take a slot it could not prove was free.
        assertEquals(
            BootAutostartPolicy.Decision.SKIP_OTHER_VPN_UNKNOWN,
            decide(otherVpn = OtherVpnState.UNKNOWN)
        )
    }

    @Test
    fun `only this package counts as the system's always-on VPN`() {
        assertTrue(AlwaysOnVpnProbe.isEnabledFor("tw.kuies.block", "tw.kuies.block"))
        assertFalse(AlwaysOnVpnProbe.isEnabledFor("tw.kuies.block", "com.adguard.android"))
        assertFalse(AlwaysOnVpnProbe.isEnabledFor("tw.kuies.block", null))
        assertFalse(AlwaysOnVpnProbe.isEnabledFor("tw.kuies.block", ""))
    }
}
