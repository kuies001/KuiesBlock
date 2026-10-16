package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.filter.FilterAutoUpdateSettings
import tw.kuies.block.filter.FilterAutoUpdateStore
import tw.kuies.block.filter.FilterRefreshSummary
import tw.kuies.block.filter.FilterUpdateRecord
import tw.kuies.block.filter.FilterUpdateSchedulePolicy
import tw.kuies.block.filter.PeriodicUpdateScheduler
import tw.kuies.block.filter.UpdateCheckOutcome
import tw.kuies.block.filter.UpdateInterval
import tw.kuies.block.ui.FilterPresentation

/**
 * The automatic-update preference, its scheduler decision and the wording of its last result.
 *
 * These are the parts that can be decided without Android: what the defaults are, what a change to the
 * interval implies for the one job, and what the screen is allowed to say afterwards.
 */
class FilterAutoUpdateTest {

    /** Records what the policy asked the platform to do, so "no duplicate job" is observable. */
    private class RecordingScheduler : PeriodicUpdateScheduler {
        val scheduled = mutableListOf<UpdateInterval>()
        var cancels = 0

        override fun ensureScheduled(interval: UpdateInterval) {
            scheduled += interval
        }

        override fun cancel() {
            cancels++
        }
    }

    @Test
    fun `a fresh install defaults to enabled every 24 hours`() {
        val settings = FilterAutoUpdateStore(FakeSharedPreferences()).load()
        assertTrue(settings.enabled)
        assertEquals(UpdateInterval.HOURS_24, settings.interval)
        assertEquals(24L, settings.interval.hours)
    }

    @Test
    fun `switching automatic updates off survives a reload`() {
        val prefs = FakeSharedPreferences()
        FilterAutoUpdateStore(prefs).setEnabled(false)
        assertFalse(FilterAutoUpdateStore(prefs).load().enabled)
    }

    @Test
    fun `8 and 24 hours round-trip through the store`() {
        val prefs = FakeSharedPreferences()
        val store = FilterAutoUpdateStore(prefs)
        store.setInterval(UpdateInterval.HOURS_8)
        assertEquals(UpdateInterval.HOURS_8, FilterAutoUpdateStore(prefs).load().interval)
        store.setInterval(UpdateInterval.HOURS_24)
        assertEquals(UpdateInterval.HOURS_24, FilterAutoUpdateStore(prefs).load().interval)
    }

    @Test
    fun `an unknown stored interval falls back to the default`() {
        assertEquals(UpdateInterval.HOURS_24, UpdateInterval.fromHours(null))
        assertEquals(UpdateInterval.HOURS_24, UpdateInterval.fromHours(7))
        assertEquals(UpdateInterval.HOURS_8, UpdateInterval.fromHours(8))
    }

    @Test
    fun `enabling schedules the interval, changing it re-schedules, disabling only cancels`() {
        val scheduler = RecordingScheduler()

        FilterUpdateSchedulePolicy.apply(
            FilterAutoUpdateSettings(enabled = true, interval = UpdateInterval.HOURS_8),
            scheduler
        )
        assertEquals(listOf(UpdateInterval.HOURS_8), scheduler.scheduled)
        assertEquals(0, scheduler.cancels)

        FilterUpdateSchedulePolicy.apply(
            FilterAutoUpdateSettings(enabled = true, interval = UpdateInterval.HOURS_24),
            scheduler
        )
        // The interval change asks the one unique job to update; it never asks for a second one.
        assertEquals(listOf(UpdateInterval.HOURS_8, UpdateInterval.HOURS_24), scheduler.scheduled)
        assertEquals(0, scheduler.cancels)

        FilterUpdateSchedulePolicy.apply(
            FilterAutoUpdateSettings(enabled = false, interval = UpdateInterval.HOURS_24),
            scheduler
        )
        assertEquals(1, scheduler.cancels)
        assertEquals(2, scheduler.scheduled.size)
    }

    @Test
    fun `a sweep reports the outcome the user is shown`() {
        assertEquals(UpdateCheckOutcome.NothingToCheck, FilterRefreshSummary().outcome)
        assertEquals(
            UpdateCheckOutcome.AllCurrent,
            FilterRefreshSummary(considered = 2, unchanged = 2).outcome
        )
        assertEquals(
            UpdateCheckOutcome.Updated(1),
            FilterRefreshSummary(considered = 2, updated = 1, unchanged = 1).outcome
        )
        // A failure outranks an update: the user has to hear about the one that did not work.
        assertEquals(
            UpdateCheckOutcome.Failed(1),
            FilterRefreshSummary(considered = 2, updated = 1, failed = 1).outcome
        )
    }

    @Test
    fun `an outcome and its time survive the store round trip`() {
        val prefs = FakeSharedPreferences()
        FilterAutoUpdateStore(prefs).saveRecord(
            FilterUpdateRecord(checkedAtMillis = 1_000L, outcome = UpdateCheckOutcome.Updated(3))
        )
        val reloaded = FilterAutoUpdateStore(prefs).loadRecord()
        assertEquals(1_000L, reloaded.checkedAtMillis)
        assertEquals(UpdateCheckOutcome.Updated(3), reloaded.outcome)
    }

    @Test
    fun `a store that was never written reads as never checked`() {
        val record = FilterAutoUpdateStore(FakeSharedPreferences()).loadRecord()
        assertNull(record.checkedAtMillis)
        assertEquals(UpdateCheckOutcome.Never, record.outcome)
    }

    @Test
    fun `the last-check line never calls a failure a success`() {
        val now = 10L * 60_000
        val label = FilterPresentation.lastCheckLabel(
            FilterUpdateRecord(checkedAtMillis = now - 60_000, outcome = UpdateCheckOutcome.Failed(2)),
            now
        )
        assertTrue(label, label.contains("2 項失敗"))
        assertTrue(label, label.contains("仍使用上一次成功的清單"))
        assertFalse(label, label.contains("全部為最新"))
    }

    @Test
    fun `a check that found nothing downloaded says exactly that`() {
        val label = FilterPresentation.lastCheckLabel(
            FilterUpdateRecord(checkedAtMillis = 1L, outcome = UpdateCheckOutcome.NothingToCheck),
            2L
        )
        assertEquals("上次檢查：沒有已下載的清單可更新。", label)
    }
}
