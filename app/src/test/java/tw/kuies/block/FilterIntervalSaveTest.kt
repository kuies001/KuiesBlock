package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.filter.FilterAutoUpdateSettings
import tw.kuies.block.filter.FilterAutoUpdateStore
import tw.kuies.block.filter.FilterIntervalDraft
import tw.kuies.block.filter.FilterIntervalSavePolicy
import tw.kuies.block.filter.FilterUpdateRecord
import tw.kuies.block.filter.PeriodicUpdateScheduler
import tw.kuies.block.filter.UpdateCheckOutcome
import tw.kuies.block.filter.UpdateInterval

/**
 * The draft interval and the commit behind 「儲存並更新」.
 *
 * These tests exist to pin the promise the button makes: a radio tap changes nothing on disk, and a
 * confirmed change is stored, re-scheduled once and followed by exactly one refresh — while a failed
 * write, a failed schedule or a failed download never leaves the stored value and the screen telling
 * different stories.
 */
class FilterIntervalSaveTest {

    private class RecordingScheduler : PeriodicUpdateScheduler {
        val scheduled = mutableListOf<UpdateInterval>()
        var cancels = 0
        var failNext = false

        override fun ensureScheduled(interval: UpdateInterval) {
            scheduled += interval
            if (failNext) {
                failNext = false
                throw IllegalStateException("WorkManager unavailable")
            }
        }

        override fun cancel() {
            cancels++
        }
    }

    private fun store(prefs: FakeSharedPreferences) = FilterAutoUpdateStore(prefs)

    @Test
    fun `a fresh draft keeps the stored interval as both values`() {
        val draft = FilterIntervalDraft.of(UpdateInterval.HOURS_24)

        assertEquals(UpdateInterval.HOURS_24, draft.saved)
        assertEquals(UpdateInterval.HOURS_24, draft.selected)
        assertFalse(draft.hasPendingChange)
    }

    @Test
    fun `choosing another interval moves only the draft`() {
        val draft = FilterIntervalDraft.of(UpdateInterval.HOURS_24).select(UpdateInterval.HOURS_8)

        assertEquals(UpdateInterval.HOURS_24, draft.saved)
        assertEquals(UpdateInterval.HOURS_8, draft.selected)
        assertTrue(draft.hasPendingChange)
    }

    @Test
    fun `choosing the stored interval again clears the save offer`() {
        val draft = FilterIntervalDraft.of(UpdateInterval.HOURS_24)
            .select(UpdateInterval.HOURS_8)
            .select(UpdateInterval.HOURS_24)

        assertFalse(draft.hasPendingChange)
    }

    @Test
    fun `resetting to a saved interval discards an unconfirmed selection`() {
        val draft = FilterIntervalDraft.of(UpdateInterval.HOURS_24)
            .select(UpdateInterval.HOURS_8)
            .resetTo(UpdateInterval.HOURS_24)

        assertEquals(UpdateInterval.HOURS_24, draft.selected)
        assertFalse(draft.hasPendingChange)
    }

    @Test
    fun `confirming stores the interval, reschedules once and refreshes once`() {
        val prefs = FakeSharedPreferences()
        val scheduler = RecordingScheduler()
        var updates = 0

        val saved = FilterIntervalSavePolicy.save(
            interval = UpdateInterval.HOURS_8,
            store = store(prefs),
            scheduler = scheduler,
            onSaved = { updates++ }
        )

        assertTrue(saved)
        assertEquals(UpdateInterval.HOURS_8, store(prefs).load().interval)
        assertEquals(listOf(UpdateInterval.HOURS_8), scheduler.scheduled)
        assertEquals(1, updates)
    }

    @Test
    fun `a repeated confirmation starts no second refresh`() {
        val prefs = FakeSharedPreferences()
        val scheduler = RecordingScheduler()
        var updates = 0
        val save = {
            FilterIntervalSavePolicy.save(
                interval = UpdateInterval.HOURS_8,
                store = store(prefs),
                scheduler = scheduler,
                onSaved = { updates++ }
            )
        }

        assertTrue(save())
        assertTrue(save())

        assertEquals(1, updates)
        assertEquals(listOf(UpdateInterval.HOURS_8), scheduler.scheduled)
    }

    @Test
    fun `a failed write leaves the old interval, the job and the refresh untouched`() {
        val prefs = FakeSharedPreferences()
        val scheduler = RecordingScheduler()
        var updates = 0
        prefs.commitFails = true

        val saved = FilterIntervalSavePolicy.save(
            interval = UpdateInterval.HOURS_8,
            store = store(prefs),
            scheduler = scheduler,
            onSaved = { updates++ }
        )

        assertFalse(saved)
        assertEquals(UpdateInterval.HOURS_24, store(prefs).load().interval)
        assertEquals(0, scheduler.scheduled.size)
        assertEquals(0, updates)
    }

    @Test
    fun `a failed schedule rolls the interval back and can be retried`() {
        val prefs = FakeSharedPreferences()
        val scheduler = RecordingScheduler()
        var updates = 0
        scheduler.failNext = true

        val failed = FilterIntervalSavePolicy.save(
            interval = UpdateInterval.HOURS_8,
            store = store(prefs),
            scheduler = scheduler,
            onSaved = { updates++ }
        )

        assertFalse(failed)
        assertEquals(UpdateInterval.HOURS_24, store(prefs).load().interval)
        assertEquals(0, updates)
        // The attempted change, then the rollback that re-asserts the old interval.
        assertEquals(listOf(UpdateInterval.HOURS_8, UpdateInterval.HOURS_24), scheduler.scheduled)

        val retried = FilterIntervalSavePolicy.save(
            interval = UpdateInterval.HOURS_8,
            store = store(prefs),
            scheduler = scheduler,
            onSaved = { updates++ }
        )

        assertTrue(retried)
        assertEquals(UpdateInterval.HOURS_8, store(prefs).load().interval)
        assertEquals(1, updates)
    }

    @Test
    fun `a failed download does not undo the saved interval`() {
        val prefs = FakeSharedPreferences()
        val scheduler = RecordingScheduler()

        FilterIntervalSavePolicy.save(UpdateInterval.HOURS_8, store(prefs), scheduler)
        store(prefs).saveRecord(
            FilterUpdateRecord(checkedAtMillis = 1L, outcome = UpdateCheckOutcome.Failed(1))
        )

        assertEquals(UpdateInterval.HOURS_8, store(prefs).load().interval)
        assertEquals(UpdateCheckOutcome.Failed(1), store(prefs).loadRecord().outcome)
    }

    @Test
    fun `the interval survives a reload even while automatic updates are off`() {
        val prefs = FakeSharedPreferences()
        store(prefs).save(FilterAutoUpdateSettings(enabled = false, interval = UpdateInterval.HOURS_8))

        val reloaded = store(prefs).load()
        assertFalse(reloaded.enabled)
        assertEquals(UpdateInterval.HOURS_8, reloaded.interval)
    }
}
