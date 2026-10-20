package tw.kuies.block

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Copy guard for the home screen's battery-exemption card.
 *
 * The wording is the requirement's, and the card must sell an option rather than a guarantee: Android
 * may still stop an app for low memory, a vendor's own limits or a force-stop, so the text may not
 * promise that the app is never cleared or that it runs in the background forever.
 */
class HomeBatteryExemptionCopyTest {

    private val approved = mapOf(
        "home_background_title" to "背景執行保護",
        "home_background_body" to "減少系統限制，讓廣告攔截持續運作。",
        "home_background_pending" to "尚未允許",
        "home_background_action" to "允許背景執行",
        "home_background_granted_description" to "已允許背景執行"
    )

    @Test
    fun theCardKeepsTheApprovedWording() {
        val strings = stringsXml()
        approved.forEach { (name, text) ->
            assertEquals("$name must keep the approved copy", text, stringValue(strings, name))
        }
    }

    /**
     * The granted state is an icon and nothing else. Keeping the old text resource would let a later
     * edit draw 「已允許 ✓」 again, which is exactly the crowding this round removes.
     */
    @Test
    fun theGrantedStateHasNoVisibleTextResource() {
        val strings = stringsXml()
        assertEquals(0, Regex("<string name=\"home_background_granted\">").findAll(strings).count())
        assertEquals(0, Regex("已允許 ✓").findAll(strings).count())
    }

    @Test
    fun theCardPromisesNoPermanentSurvival() {
        val strings = stringsXml()
        val forbidden = listOf("永不被清除", "100%", "永久", "完全不受", "保證", "絕不")
        approved.forEach { (name, text) ->
            forbidden.forEach { token ->
                assertFalse("$name must not contain '$token' (actual: $text)", text.contains(token))
            }
        }
    }

    /** Reads one `<string name="...">` value out of the default resource file. */
    private fun stringValue(strings: String, name: String): String {
        val match = Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(strings)
            ?: throw AssertionError("strings.xml has no <string name=\"$name\">")
        return match.groupValues[1]
    }

    /** Resolves the file through the module directory the test task runs in. */
    private fun stringsXml(): String {
        val relative = "src/main/res/values/strings.xml"
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            for (candidate in listOf(File(directory, relative), File(directory, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            }
            directory = directory.parentFile
        }
        throw AssertionError("could not locate $relative from ${File("").absolutePath}")
    }
}
