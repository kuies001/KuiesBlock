package tw.kuies.block

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guards for the update item's place on the settings page.
 *
 * Whether a section is drawn last is a property of the composable's body, which a plain JVM test cannot
 * observe, so — as [HomeScreenSurfaceTest] does for the home screen — this reads the one file that
 * draws the page. It fails if the section is wired anywhere but at the end, or if its two labels stop
 * being the ones the requirement names.
 */
class SettingsScreenSurfaceTest {

    /**
     * 檢查 App 更新 must be the last item on the page: every other section is declared before it, and
     * none after.
     */
    @Test
    fun theUpdateSectionIsTheLastItemOnTheSettingsPage() {
        val sections = Regex("^ {8}([A-Za-z]+Section)\\(", RegexOption.MULTILINE)
            .findAll(settingsScreenSource())
            .map { it.groupValues[1] }
            .toList()

        assertTrue("no settings sections were found at all", sections.size > 1)
        assertEquals("AppUpdateSection", sections.last())
        assertEquals(1, sections.count { it == "AppUpdateSection" })
    }

    /** The section's title and its version subtitle are the exact strings the requirement names. */
    @Test
    fun theSectionUsesTheRequiredTitleAndSubtitle() {
        val strings = stringsXml()

        assertTrue(strings.contains("<string name=\"settings_update_section\">檢查 App 更新</string>"))
        assertTrue(strings.contains("<string name=\"settings_update_current\">目前版本 %1\$s</string>"))
    }

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
