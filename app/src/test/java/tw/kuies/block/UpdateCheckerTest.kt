package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.update.AppVersion
import tw.kuies.block.update.ReleaseInfo
import tw.kuies.block.update.ReleaseLookup
import tw.kuies.block.update.ReleaseSource
import tw.kuies.block.update.UpdateCheckFailure
import tw.kuies.block.update.UpdateCheckOutcome
import tw.kuies.block.update.UpdateChecker
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The comparison and the single-flight guard, both against a fake source.
 *
 * No server is involved: the wording and the version order are the whole subject here.
 */
class UpdateCheckerTest {

    private val installed = requireNotNull(AppVersion.parse("0.4.0"))

    private fun release(tag: String) = ReleaseInfo(
        version = requireNotNull(AppVersion.parse(tag)),
        tagName = tag,
        notes = "",
        pageUrl = null
    )

    private fun sourceOf(result: ReleaseLookup) = ReleaseSource { result }

    @Test
    fun aHigherPublishedVersionIsAnAvailableUpdate() {
        val checker = UpdateChecker(sourceOf(ReleaseLookup.Found(release("v0.4.1"))), installed)

        val outcome = checker.check()

        assertTrue(outcome is UpdateCheckOutcome.UpdateAvailable)
        assertEquals(
            "0.4.1",
            (outcome as UpdateCheckOutcome.UpdateAvailable).release.version.text()
        )
    }

    @Test
    fun theSameVersionIsUpToDate() {
        val checker = UpdateChecker(sourceOf(ReleaseLookup.Found(release("v0.4.0"))), installed)

        assertEquals(UpdateCheckOutcome.UpToDate, checker.check())
    }

    @Test
    fun anOlderPublishedVersionDoesNotAskForAnUpdate() {
        val checker = UpdateChecker(sourceOf(ReleaseLookup.Found(release("v0.3.9"))), installed)

        assertEquals(UpdateCheckOutcome.InstalledNewer, checker.check())
    }

    @Test
    fun aTwoDigitPatchIsTreatedAsNewer() {
        val checker = UpdateChecker(
            sourceOf(ReleaseLookup.Found(release("v0.4.10"))),
            requireNotNull(AppVersion.parse("0.4.9"))
        )

        assertTrue(checker.check() is UpdateCheckOutcome.UpdateAvailable)
    }

    @Test
    fun aFailedLookupKeepsItsReason() {
        val checker = UpdateChecker(
            sourceOf(ReleaseLookup.Failed(UpdateCheckFailure.RATE_LIMITED)),
            installed
        )

        val outcome = checker.check()

        assertEquals(
            UpdateCheckOutcome.Unavailable(UpdateCheckFailure.RATE_LIMITED),
            outcome
        )
    }

    @Test
    fun anUnparsableInstalledVersionCannotBeCompared() {
        val checker = UpdateChecker(sourceOf(ReleaseLookup.Found(release("v0.4.1"))), null)

        assertEquals(
            UpdateCheckOutcome.Unavailable(UpdateCheckFailure.UNREADABLE),
            checker.check()
        )
    }

    @Test
    fun aSecondCheckWhileOneIsRunningSendsNoSecondRequest() {
        val calls = AtomicInteger(0)
        val entered = CountDownLatch(1)
        val releaseIt = CountDownLatch(1)
        val source = ReleaseSource {
            calls.incrementAndGet()
            entered.countDown()
            releaseIt.await(5, TimeUnit.SECONDS)
            ReleaseLookup.Found(release("v0.4.1"))
        }
        val checker = UpdateChecker(source, installed)

        var first: UpdateCheckOutcome? = null
        val worker = Thread { first = checker.check() }
        worker.start()
        assertTrue("the first check should have reached the source", entered.await(5, TimeUnit.SECONDS))

        // The duplicate tap: answered immediately, with nothing sent.
        assertEquals(UpdateCheckOutcome.Busy, checker.check())

        releaseIt.countDown()
        worker.join(5_000)

        assertEquals("exactly one request must have been made", 1, calls.get())
        assertTrue(first is UpdateCheckOutcome.UpdateAvailable)
    }

    @Test
    fun theGuardIsReleasedSoANewCheckCanRunAfterwards() {
        var calls = 0
        val checker = UpdateChecker(ReleaseSource { calls++; ReleaseLookup.Found(release("v0.4.0")) }, installed)

        checker.check()
        checker.check()

        assertEquals(2, calls)
    }
}
