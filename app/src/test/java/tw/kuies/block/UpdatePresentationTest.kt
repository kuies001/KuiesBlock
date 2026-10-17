package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.update.AppVersion
import tw.kuies.block.update.ReleaseInfo
import tw.kuies.block.update.ReleasePagePolicy
import tw.kuies.block.update.UpdateCheckFailure
import tw.kuies.block.update.UpdateCheckOutcome
import tw.kuies.block.update.UpdatePresentation

/**
 * The wording of the four comparison results, and the version subtitle.
 *
 * These strings are the requirements: "發現新版本", "目前已是最新版本", "目前安裝的版本較新" and
 * "暫時無法檢查更新" must each be reachable, and only from the outcome that means it.
 */
class UpdatePresentationTest {

    private fun release(tag: String = "v0.4.1", pageUrl: String? = null) = ReleaseInfo(
        version = requireNotNull(AppVersion.parse(tag)),
        tagName = tag,
        notes = "",
        pageUrl = pageUrl
    )

    @Test
    fun theSubtitleNamesTheInstalledVersionWithThePrefixGithubUses() {
        assertEquals("v0.4.0", UpdatePresentation.versionLabel("0.4.0"))
        assertEquals("v0.4.0", UpdatePresentation.versionLabel(" 0.4.0 "))
        // A prefix that is already there is not doubled.
        assertEquals("v0.4.0", UpdatePresentation.versionLabel("v0.4.0"))
        assertEquals("V0.4.0", UpdatePresentation.versionLabel("V0.4.0"))
    }

    @Test
    fun nothingIsSaidBeforeTheFirstCheck() {
        assertNull(UpdatePresentation.resultLabel(null))
        assertFalse(UpdatePresentation.isFailure(null))
    }

    @Test
    fun aNewerReleaseIsAnnouncedByName() {
        val outcome = UpdateCheckOutcome.UpdateAvailable(release("v0.4.1"))

        assertEquals("發現新版本 v0.4.1", UpdatePresentation.resultLabel(outcome))
        assertFalse(UpdatePresentation.isFailure(outcome))
    }

    @Test
    fun theSameVersionReadsAsUpToDate() {
        assertEquals("目前已是最新版本", UpdatePresentation.resultLabel(UpdateCheckOutcome.UpToDate))
        assertFalse(UpdatePresentation.isFailure(UpdateCheckOutcome.UpToDate))
    }

    @Test
    fun anInstalledVersionThatIsAheadIsNamedAsSuchNotAsAnUpdate() {
        val outcome = UpdateCheckOutcome.InstalledNewer

        assertEquals("目前安裝的版本較新", UpdatePresentation.resultLabel(outcome))
        assertFalse(UpdatePresentation.isFailure(outcome))
    }

    @Test
    fun aFailedCheckShowsItsOwnShortReason() {
        UpdateCheckFailure.entries.forEach { reason ->
            val outcome = UpdateCheckOutcome.Unavailable(reason)
            assertEquals(reason.label, UpdatePresentation.resultLabel(outcome))
            assertTrue(UpdatePresentation.isFailure(outcome))
        }
        assertEquals(
            "暫時無法檢查更新。",
            UpdatePresentation.resultLabel(UpdateCheckOutcome.Unavailable(UpdateCheckFailure.UNREADABLE))
        )
        assertEquals(
            "GitHub 暫時限制查詢次數，請稍後再試。",
            UpdatePresentation.resultLabel(UpdateCheckOutcome.Unavailable(UpdateCheckFailure.RATE_LIMITED))
        )
    }

    @Test
    fun anIgnoredDuplicateTapSaysNothingAtAll() {
        assertNull(UpdatePresentation.resultLabel(UpdateCheckOutcome.Busy))
    }

    @Test
    fun onlyThisProjectsOwnReleasePageCanBeOfferedForDownload() {
        val official = "https://github.com/kuies001/KuiesBlock/releases/tag/v0.4.1"

        assertEquals(official, UpdatePresentation.downloadableUrl(release(pageUrl = official)))
        // A query string stays on the same official page, so it is kept as it was written.
        assertEquals(
            "$official?utm=1",
            UpdatePresentation.downloadableUrl(release(pageUrl = "$official?utm=1"))
        )
        assertNull(UpdatePresentation.downloadableUrl(release(pageUrl = "https://evil.example/app.apk")))
        assertNull(UpdatePresentation.downloadableUrl(release(pageUrl = null)))
        assertNull(UpdatePresentation.downloadableUrl(release(pageUrl = "")))
    }

    @Test
    fun theDownloadDecisionIsTheSharedPolicyDecision() {
        val pageUrl = "https://github.com/kuies001/KuiesBlock/releases/tag/v0.4.1"

        assertEquals(
            ReleasePagePolicy.officialUrlOrNull(pageUrl),
            UpdatePresentation.downloadableUrl(release(pageUrl = pageUrl))
        )
    }
}
