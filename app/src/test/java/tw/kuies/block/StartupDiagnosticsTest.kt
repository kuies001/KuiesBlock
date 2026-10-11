package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.vpn.StartupBudget
import tw.kuies.block.vpn.StartupTimeline
import tw.kuies.block.vpn.StartupVerificationPolicy

/** The startup timeout, stage timing, and bounded-retry rules, tested with a fake clock. */
class StartupDiagnosticsTest {

    private class FakeClock(var now: Long = 0L) {
        fun advance(millis: Long) {
            now += millis
        }
    }

    // --- StartupBudget ------------------------------------------------------------------------

    @Test
    fun budgetIsNotExhaustedBeforeTheLimit() {
        val clock = FakeClock()
        val budget = StartupBudget(15_000L) { clock.now }
        clock.advance(14_999L)
        assertFalse(budget.isExhausted())
        assertEquals(1L, budget.remainingMillis())
    }

    @Test
    fun budgetIsExhaustedAtTheLimit() {
        val clock = FakeClock()
        val budget = StartupBudget(15_000L) { clock.now }
        clock.advance(15_000L)
        assertTrue(budget.isExhausted())
        assertEquals(0L, budget.remainingMillis())
    }

    @Test
    fun budgetRemainingNeverGoesNegative() {
        val clock = FakeClock()
        val budget = StartupBudget(1_000L) { clock.now }
        clock.advance(10_000L)
        assertTrue(budget.isExhausted())
        assertEquals(0L, budget.remainingMillis())
        assertEquals(10_000L, budget.elapsedMillis())
    }

    @Test
    fun aZeroBudgetIsExhaustedImmediately() {
        val budget = StartupBudget(0L) { 0L }
        assertTrue(budget.isExhausted())
    }

    @Test
    fun budgetElapsedNeverGoesNegativeIfTheClockJumpsBackwards() {
        val clock = FakeClock(now = 5_000L)
        val budget = StartupBudget(1_000L) { clock.now }
        clock.now = 4_000L
        assertEquals(0L, budget.elapsedMillis())
    }

    // --- StartupTimeline ----------------------------------------------------------------------

    @Test
    fun timelineRecordsTheFirstElapsedValuePerStage() {
        val clock = FakeClock()
        val timeline = StartupTimeline { clock.now }
        clock.advance(120L)
        timeline.mark("underlyingReady")
        clock.advance(400L)
        timeline.mark("transportReady")
        clock.advance(1_000L)
        // A second mark for the same stage must not overwrite the first.
        timeline.mark("underlyingReady")

        assertEquals(120L, timeline.stageMillis("underlyingReady"))
        assertEquals(520L, timeline.stageMillis("transportReady"))
        assertEquals(1_520L, timeline.elapsedMillis())
    }

    @Test
    fun timelineSummaryIsEmptyUntilAStageIsMarkedThenNamesEachStage() {
        val clock = FakeClock()
        val timeline = StartupTimeline { clock.now }
        assertEquals("", timeline.summary())
        assertNull(timeline.stageMillis("missing"))

        timeline.mark("tunnelReady")
        assertTrue(timeline.summary().contains("tunnelReady="))
    }

    // --- StartupVerificationPolicy ------------------------------------------------------------

    @Test
    fun retryingStopsAtTheAttemptCap() {
        assertFalse(StartupVerificationPolicy.mustStopRetrying(1, 3, budgetExhausted = false))
        assertFalse(StartupVerificationPolicy.mustStopRetrying(2, 3, budgetExhausted = false))
        assertTrue(StartupVerificationPolicy.mustStopRetrying(3, 3, budgetExhausted = false))
    }

    @Test
    fun retryingStopsWhenTheBudgetIsSpentEvenBeforeTheAttemptCap() {
        assertTrue(StartupVerificationPolicy.mustStopRetrying(1, 3, budgetExhausted = true))
    }

    @Test
    fun theRetryDelayNeverOutlivesTheRemainingBudget() {
        assertEquals(1_500L, StartupVerificationPolicy.nextRetryDelayMillis(1_500L, 5_000L))
        assertEquals(400L, StartupVerificationPolicy.nextRetryDelayMillis(1_500L, 400L))
        assertEquals(0L, StartupVerificationPolicy.nextRetryDelayMillis(1_500L, 0L))
        assertEquals(0L, StartupVerificationPolicy.nextRetryDelayMillis(1_500L, -50L))
    }
}
