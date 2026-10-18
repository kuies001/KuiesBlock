package tw.kuies.block

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Copy guard for the home screen's benefits block.
 *
 * The wording is a product decision: the block may describe a possible benefit, but it must not
 * promise one. A DNS-level filter cannot catch every ad an app serves itself, and nothing about the
 * power or data saving has been measured on a device, so the copy may not claim a guaranteed saving,
 * a percentage, or an ad-free experience. Pinning the approved text here keeps a later edit from
 * quietly turning a hedge back into a promise. The block has no heading of its own since v0.4.4.
 */
class HomeBenefitsCopyTest {

    private val approved = mapOf(
        "home_benefit_blocking_title" to "阻擋廣告",
        "home_benefit_blocking_body" to "減少廣告載入，有助節省流量與降低耗電。",
        "home_benefit_free_title" to "輕量・永久免費",
        "home_benefit_free_body" to "操作簡單、輕量運行，所有功能永久免費。"
    )

    @Test
    fun theBenefitBlockKeepsTheApprovedWording() {
        val strings = stringsXml()
        approved.forEach { (name, text) ->
            assertEquals("$name must keep the approved copy", text, stringValue(strings, name))
        }
    }

    /**
     * A measured number would be a claim the project has no data for, and an absolute would be a
     * claim the DNS layer cannot keep.
     */
    @Test
    fun theBenefitCopyPromisesNoPercentageAndNoAbsolute() {
        val strings = stringsXml()
        val forbidden = listOf("%", "保證", "完全無廣告", "全部廣告", "所有廣告", "零廣告", "必定")
        approved.keys.forEach { name ->
            val text = stringValue(strings, name)
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
