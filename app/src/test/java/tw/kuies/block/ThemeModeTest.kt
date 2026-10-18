package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kuies.block.data.AppSettings
import tw.kuies.block.data.AppSettingsStore
import tw.kuies.block.data.ThemeMode

/** The theme preference is absent on existing installs and must read as SYSTEM. */
class ThemeModeTest {

    @Test
    fun aFreshOrOlderInstallDefaultsToSystem() {
        assertEquals(ThemeMode.SYSTEM, AppSettingsStore(FakeSharedPreferences()).load().themeMode)
        assertEquals(ThemeMode.SYSTEM, AppSettings().themeMode)
    }

    @Test
    fun everyModeSurvivesTheStoreRoundTrip() {
        ThemeMode.entries.forEach { mode ->
            val store = AppSettingsStore(FakeSharedPreferences())
            store.setThemeMode(mode)
            assertEquals(mode, store.load().themeMode)
        }
    }

    @Test
    fun anUnknownOrNullIdFallsBackToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromId(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromId(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromId("rainbow"))
    }
}
