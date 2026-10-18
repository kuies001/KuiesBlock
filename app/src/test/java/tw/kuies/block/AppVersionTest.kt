package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.update.AppVersion

/**
 * The version comparison the whole update check rests on.
 *
 * The two-digit case is the point of comparing numbers rather than strings: lexicographic order calls
 * `0.4.9` newer than `0.4.10`, which would tell a user on 0.4.9 that nothing is waiting for them.
 */
class AppVersionTest {

    private fun version(raw: String): AppVersion = requireNotNull(AppVersion.parse(raw)) {
        "'$raw' should have parsed as a version"
    }

    @Test
    fun aNewerPatchIsGreater() {
        assertTrue(version("v0.4.1") > version("0.4.0"))
        assertTrue(version("0.4.0") < version("v0.4.1"))
    }

    @Test
    fun aThreePartPatchWithTwoDigitsIsComparedNumerically() {
        // 0.4.41 is a three-part version, not the decimal 0.4.41 = 0.441.
        assertTrue(version("0.4.41") > version("0.4.4"))
        assertTrue(version("0.4.42") > version("0.4.41"))
        assertTrue(version("0.5.0") > version("0.4.42"))
        assertTrue(version("0.4.4") < version("0.4.41"))
        assertEquals("0.4.41", version("v0.4.41").text())
        assertEquals("v0.4.41", version("0.4.41").tagText())
    }

    @Test
    fun aTwoDigitPatchIsComparedAsANumberNotAsText() {
        assertTrue(version("v0.4.10") > version("v0.4.9"))
        assertTrue(version("v0.4.9") < version("v0.4.10"))
    }

    @Test
    fun equalVersionsAreEqualWhateverThePrefix() {
        assertEquals(version("0.4.0"), version("v0.4.0"))
        assertEquals(version("V1.2.3"), version("1.2.3"))
        assertEquals(0, version("v0.4.0").compareTo(version("0.4.0")))
    }

    @Test
    fun anInstalledVersionAheadOfThePublishedOneIsGreater() {
        assertTrue(version("0.5.0") > version("v0.4.0"))
    }

    @Test
    fun eachPartDecidesOnItsOwn() {
        assertTrue(version("1.0.0") > version("0.99.99"))
        assertTrue(version("0.10.0") > version("0.9.9"))
        assertTrue(version("0.4.1") < version("0.5.0"))
    }

    @Test
    fun aWronglyShapedVersionIsNotAVersion() {
        val invalid = listOf(
            "",
            " ",
            "0.4",
            "0.4.0.1",
            "v",
            "v0.4",
            "0.4.x",
            "abc",
            "0.4.0-beta",
            "0.4.0+1",
            "version 0.4.0",
            "0.4.0 0.4.1",
            "-1.2.3",
            // Ten digits cannot be held in an Int, so it is refused rather than silently wrapped.
            "9999999999.0.0"
        )
        invalid.forEach { raw ->
            assertNull("'$raw' must not parse as a version", AppVersion.parse(raw))
        }
        assertNull(AppVersion.parse(null))
    }

    @Test
    fun theTextFormsDropAndAddTheGithubPrefix() {
        assertEquals("0.4.1", version("v0.4.1").text())
        assertEquals("v0.4.1", version("0.4.1").tagText())
    }
}
