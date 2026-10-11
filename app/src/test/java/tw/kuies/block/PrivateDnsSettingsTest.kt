package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.dns.PrivateDnsMode
import tw.kuies.block.dns.PrivateDnsSettings

class PrivateDnsSettingsTest {

    @Test
    fun mapsTheDocumentedModeStrings() {
        assertEquals(PrivateDnsMode.OFF, PrivateDnsSettings.modeOf("off"))
        assertEquals(PrivateDnsMode.OPPORTUNISTIC, PrivateDnsSettings.modeOf("opportunistic"))
        assertEquals(PrivateDnsMode.HOSTNAME, PrivateDnsSettings.modeOf("hostname"))
        assertEquals(PrivateDnsMode.OFF, PrivateDnsSettings.modeOf(" OFF "))
    }

    @Test
    fun anUnreadableOrUnknownSettingIsNotOff() {
        assertEquals(PrivateDnsMode.UNKNOWN, PrivateDnsSettings.modeOf(null))
        assertEquals(PrivateDnsMode.UNKNOWN, PrivateDnsSettings.modeOf("something-else"))
    }

    @Test
    fun onlyAnExplicitOffMeansInterceptionCannotBeBypassed() {
        assertFalse(PrivateDnsSettings.mayBypassInterception(PrivateDnsMode.OFF))
        assertTrue(PrivateDnsSettings.mayBypassInterception(PrivateDnsMode.OPPORTUNISTIC))
        assertTrue(PrivateDnsSettings.mayBypassInterception(PrivateDnsMode.HOSTNAME))
        assertTrue(PrivateDnsSettings.mayBypassInterception(PrivateDnsMode.UNKNOWN))
    }
}
