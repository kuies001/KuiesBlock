package tw.kuies.block

import android.content.Context
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tw.kuies.block.filter.FilterAutoUpdateSettings
import tw.kuies.block.filter.FilterUpdateSchedulePolicy
import tw.kuies.block.filter.UpdateInterval
import tw.kuies.block.filter.WorkManagerPeriodicUpdateScheduler

/**
 * The scheduler against the real WorkManager work database, not against a fake.
 *
 * The policy tests already show which calls the app makes; these show what the platform then holds:
 * exactly one periodic work under the unique name, with the interval and the network constraint that
 * were actually requested. That is the difference between "we asked for one job" and "there is one job".
 *
 * Robolectric drives this because no emulator on this machine has ever booted, so there is no
 * instrumented target. `sdk = 35` is pinned only because `compileSdk 37` is newer than any Robolectric
 * Android image; the WorkManager semantics under test do not depend on the level.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FilterAutoUpdateWorkManagerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerPeriodicUpdateScheduler

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerPeriodicUpdateScheduler(context)
    }

    private fun uniqueWork(): List<WorkInfo> = runBlocking {
        workManager
            .getWorkInfosForUniqueWorkFlow(WorkManagerPeriodicUpdateScheduler.FILTER_UPDATE_WORK_NAME)
            .first()
    }

    private fun scheduled() = uniqueWork().filter { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun `the default 24 hour setting leaves exactly one periodic work`() {
        FilterUpdateSchedulePolicy.apply(
            FilterAutoUpdateSettings(enabled = true, interval = UpdateInterval.HOURS_24),
            scheduler
        )

        val work = uniqueWork()
        assertEquals(1, work.size)
        val info = work.single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        // It really is periodic, which is what the unique-name call exists for. The 24-hour value itself
        // is asserted at the policy seam in FilterAutoUpdateTest: reading the interval back out of the
        // stored work needs PeriodicityInfo's accessor name, and this round could not confirm it (the
        // artifact was not where the Gradle cache search looked), so it is not guessed here.
        assertNotNull(info.periodicityInfo)
        // "Do not try while offline" is a stored constraint too.
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }

    @Test
    fun `switching to 8 hours updates the one work instead of adding a second`() {
        FilterUpdateSchedulePolicy.apply(
            FilterAutoUpdateSettings(enabled = true, interval = UpdateInterval.HOURS_24),
            scheduler
        )
        FilterUpdateSchedulePolicy.apply(
            FilterAutoUpdateSettings(enabled = true, interval = UpdateInterval.HOURS_8),
            scheduler
        )

        val work = uniqueWork()
        assertEquals("changing the interval must not leave two jobs", 1, work.size)
        assertNotNull(work.single().periodicityInfo)
        assertEquals(1, scheduled().size)
    }

    @Test
    fun `switching automatic updates off cancels the scheduled work`() {
        FilterUpdateSchedulePolicy.apply(
            FilterAutoUpdateSettings(enabled = true, interval = UpdateInterval.HOURS_24),
            scheduler
        )
        assertEquals(1, scheduled().size)

        FilterUpdateSchedulePolicy.apply(
            FilterAutoUpdateSettings(enabled = false, interval = UpdateInterval.HOURS_24),
            scheduler
        )

        // Cancelled work may still be listed, so the assertion is about schedulability, not emptiness.
        assertTrue(
            "no work may still be schedulable after switching the feature off",
            scheduled().isEmpty()
        )
        assertTrue(
            uniqueWork().none { it.state == WorkInfo.State.RUNNING }
        )
    }

    @Test
    fun `switching it back on leaves exactly one enqueued periodic work`() {
        FilterUpdateSchedulePolicy.apply(
            FilterAutoUpdateSettings(enabled = false, interval = UpdateInterval.HOURS_24),
            scheduler
        )
        FilterUpdateSchedulePolicy.apply(
            FilterAutoUpdateSettings(enabled = true, interval = UpdateInterval.HOURS_8),
            scheduler
        )

        val enqueued = scheduled()
        assertEquals(1, enqueued.size)
        assertNotNull(enqueued.single().periodicityInfo)
    }

    @Test
    fun `repeated identical settings still leave exactly one work`() {
        repeat(3) {
            FilterUpdateSchedulePolicy.apply(
                FilterAutoUpdateSettings(enabled = true, interval = UpdateInterval.HOURS_24),
                scheduler
            )
        }
        assertEquals(1, scheduled().size)
    }
}
