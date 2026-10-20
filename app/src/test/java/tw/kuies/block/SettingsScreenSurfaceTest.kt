package tw.kuies.block

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guards for the settings page's shape.
 *
 * Whether a section is drawn last is a property of the composable's body, which a plain JVM test cannot
 * observe, so — as [HomeScreenSurfaceTest] does for the home screen — this reads the one file that
 * draws the page. It fails if the merged about card moves, if the removed diagnostics or update cards
 * come back, or if the required labels stop being the ones the requirement names.
 */
class SettingsScreenSurfaceTest {

    /**
     * 關於 must be the last item on the page, and it must be the only card carrying the version and
     * the manual update check.
     */
    @Test
    fun theAboutSectionIsTheLastAndOnlyVersionCard() {
        val sections = Regex("^ {8}([A-Za-z]+Section)\\(", RegexOption.MULTILINE)
            .findAll(settingsScreenSource())
            .map { it.groupValues[1] }
            .toList()

        assertTrue("no settings sections were found at all", sections.size > 1)
        assertEquals("AboutSection", sections.last())
        assertEquals(1, sections.count { it == "AboutSection" })
        assertEquals(0, sections.count { it == "AppUpdateSection" })
    }

    /** The DNS diagnostic UI is gone from the settings page; the code underneath stays. */
    @Test
    fun theSettingsPageNoLongerDrawsTheDnsDiagnosticsSection() {
        val source = settingsScreenSource()

        assertEquals(0, Regex("^ {8}DiagnosticsSection\\(", RegexOption.MULTILINE).findAll(source).count())
        assertTrue(!source.contains("DnsPresentation"))
        assertTrue(!source.contains("onRunDnsDiagnostics"))
    }

    /** The merged card uses the exact version line and keeps the check button. */
    @Test
    fun theAboutCardUsesTheRequiredVersionLineAndCheckButton() {
        val strings = stringsXml()

        assertTrue(strings.contains("<string name=\"settings_about_section\">關於</string>"))
        assertTrue(strings.contains("<string name=\"settings_update_current\">目前版本：%1\$s</string>"))
        assertTrue(strings.contains("<string name=\"settings_update_check\">檢查更新</string>"))
        assertTrue(!strings.contains("settings_update_section"))
        assertTrue(!strings.contains("settings_about_version"))
    }

    /**
     * A radio tap may only move the draft; the preference and the scheduler are reached exclusively
     * through the confirmed save action, which is offered only while the draft differs.
     */
    @Test
    fun theIntervalChoiceIsOnlyADraftUntilSaveIsPressed() {
        val controls = autoUpdateControlsSource()
        val strings = stringsXml()

        assertTrue(controls.contains("FilterIntervalDraft"))
        assertTrue(controls.contains("current.select(interval)"))
        assertTrue(controls.contains("current.hasPendingChange"))
        assertTrue(controls.contains("onSaveInterval(current.selected)"))
        assertFalse(controls.contains("onSetInterval("))
        assertTrue(strings.contains("<string name=\"filter_auto_update_save\">儲存並更新</string>"))
        assertTrue(
            strings.contains(
                "<string name=\"filter_auto_update_save_failed\">無法儲存更新週期，請再試一次</string>"
            )
        )
    }

    /** Closing the section or switching automatic updates off discards the unconfirmed pick. */
    @Test
    fun closingTheSectionOrTheSwitchDropsTheDraft() {
        val controls = autoUpdateControlsSource()

        assertEquals(
            "both the collapse and the off switch must reset the draft to the stored interval",
            2,
            Regex("FilterIntervalDraft\\.of\\(saved\\)").findAll(controls).count()
        )
    }

    /**
     * Selecting a preset collapses the strength card immediately. The progress line therefore has to
     * live in the card's header area, not inside its expandable body — that placement was the reason
     * the real-device screen showed nothing until the download had finished.
     */
    @Test
    fun theFilterProgressNoticeIsOutsideTheExpandableBody() {
        val source = settingsScreenSource()
        val section = source.substringAfter("private fun InterceptionStrengthSection")
            .substringBefore("The short line under the strength header")

        assertTrue(source.contains("status = { FilterSyncNotice(status, phase) }"))
        assertTrue(source.contains("status?.invoke()"))
        assertTrue(source.contains("CircularProgressIndicator"))
        assertFalse(
            "the hint must not be drawn only when the card is expanded",
            section.contains("FilterPresentation.syncHint")
        )
    }

    private fun autoUpdateControlsSource(): String = settingsScreenSource()
        .substringAfter("private fun AutoUpdateControls")
        .substringBefore("private fun autoUpdateIntervalLabel")

    private fun settingsScreenSource(): String =
        locate("src/main/java/tw/kuies/block/ui/SettingsScreen.kt").readText(Charsets.UTF_8)

    private fun stringsXml(): String =
        locate("src/main/res/values/strings.xml").readText(Charsets.UTF_8)

    /** Resolves a module-relative path through the directory the test task runs in. */
    private fun locate(relative: String): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            for (candidate in listOf(File(directory, relative), File(directory, "app/$relative"))) {
                if (candidate.isFile) return candidate
            }
            directory = directory.parentFile
        }
        throw AssertionError("could not locate $relative from ${File("").absolutePath}")
    }
}
