package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.dns.PrivateDnsMode
import tw.kuies.block.dns.PrivateDnsSettings
import tw.kuies.block.dns.PrivateDnsState

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

    @Test
    fun aSpecifierIsOnlyKeptForStrictHostnameMode() {
        // Android keeps the last specifier around, so trusting it in any other mode would be a guess.
        assertEquals("dns.kuies.tw", PrivateDnsSettings.stateOf("hostname", "dns.kuies.tw").hostname)
        assertNull(PrivateDnsSettings.stateOf("opportunistic", "dns.kuies.tw").hostname)
        assertNull(PrivateDnsSettings.stateOf("off", "dns.kuies.tw").hostname)
        assertNull(PrivateDnsSettings.stateOf("hostname", "   ").hostname)
        assertNull(PrivateDnsSettings.stateOf("hostname", null).hostname)
    }

    @Test
    fun aTrailingDotAndCaseDoNotHideAMatchingHost() {
        val strict = PrivateDnsSettings.stateOf("hostname", " DNS.Kuies.TW. ")
        assertTrue(strict.pinsHostname("dns.kuies.tw"))
        assertTrue(strict.isStrictHostname)
    }

    @Test
    fun onlyAStrictlyPinnedIdenticalHostCountsAsTheSameServer() {
        assertFalse(PrivateDnsSettings.stateOf("hostname", "private.example").pinsHostname("dns.kuies.tw"))
        assertFalse(PrivateDnsSettings.stateOf("opportunistic", "dns.kuies.tw").pinsHostname("dns.kuies.tw"))
        assertFalse(PrivateDnsSettings.stateOf("hostname", null).pinsHostname("dns.kuies.tw"))
        assertFalse(PrivateDnsState.UNKNOWN.pinsHostname("dns.kuies.tw"))
        assertFalse(PrivateDnsSettings.stateOf("hostname", "dns.kuies.tw").pinsHostname(""))
    }
}
