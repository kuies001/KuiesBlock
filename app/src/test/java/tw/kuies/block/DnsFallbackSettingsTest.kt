package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.data.AppSettings
import tw.kuies.block.data.AppSettingsStore
import tw.kuies.block.data.ThemeMode

/** The backup switch is on for new and upgraded installs, and only an explicit off survives. */
class DnsFallbackSettingsTest {

    @Test
    fun aFreshOrUpgradedInstallDefaultsToEnabled() {
        val prefs = FakeSharedPreferences()
        assertTrue(AppSettingsStore(prefs).load().dnsAutoFallbackEnabled)
        assertTrue(AppSettings().dnsAutoFallbackEnabled)
    }

    @Test
    fun theDocumentedPreferenceKeyIsWhatGetsRead() {
        val prefs = FakeSharedPreferences()
        prefs.rawPut("dns_auto_fallback_enabled", false)
        assertFalse(AppSettingsStore(prefs).load().dnsAutoFallbackEnabled)

        prefs.rawPut("dns_auto_fallback_enabled", true)
        assertTrue(AppSettingsStore(prefs).load().dnsAutoFallbackEnabled)
    }

    @Test
    fun anExplicitOffSurvivesARestartAndCanBeTurnedBackOn() {
        val store = AppSettingsStore(FakeSharedPreferences())
        store.setDnsAutoFallbackEnabled(false)
        assertFalse(store.load().dnsAutoFallbackEnabled)

        store.setDnsAutoFallbackEnabled(true)
        assertTrue(store.load().dnsAutoFallbackEnabled)
    }

    @Test
    fun theSwitchDoesNotDisturbOtherPreferences() {
        val store = AppSettingsStore(FakeSharedPreferences())
        store.setThemeMode(ThemeMode.DARK)
        store.setProtectionStarted()

        store.setDnsAutoFallbackEnabled(false)

        val loaded = store.load()
        assertEquals(ThemeMode.DARK, loaded.themeMode)
        assertTrue(loaded.protectionStarted)
        assertFalse(loaded.dnsAutoFallbackEnabled)
    }
}
