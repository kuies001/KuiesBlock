package tw.kuies.block

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tw.kuies.block.filter.FilterAutoUpdateStore
import tw.kuies.block.filter.FilterUpdateWorker
import tw.kuies.block.filter.UpdateCheckOutcome

/**
 * The worker's own run, through the official test harness.
 *
 * What this proves, in the round's vocabulary: the worker is **executed** (not merely created, not
 * merely scheduled); it builds its collaborator from the application context rather than from anything
 * held in memory; it reads the stored preference file from disk; and it **persists** the outcome of the
 * run, which is the evidence a later process can read back.
 *
 * This test's app has no stored filter settings, so the sweep finds nothing to refresh and makes **no
 * network request at all** — deliberate, so the test is hermetic and cannot touch a real list.
 *
 * The limit of the claim: Robolectric re-creates an Android environment inside the test JVM. That is a
 * *component rebuild and persistence reload* test. It is **not** evidence about a real Android process
 * death, and it is not reported as such.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FilterUpdateWorkerExecutionTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    private fun stateOf(id: java.util.UUID): WorkInfo.State? = runBlocking {
        workManager.getWorkInfoByIdFlow(id).first()?.state
    }

    /**
     * Waits on evidence the component itself produces, rather than sleeping a fixed time.
     *
     * The worker awaits the coordinator's own single thread, so it is still RUNNING the instant the
     * constraints are met; the record it persists *before* returning is the signal that its body really
     * ran, and the state turning SUCCEEDED is the signal that WorkManager is done with it. Both waits are
     * bounded, so a genuine failure still fails instead of hanging.
     */
    private fun awaitRecordOutcome(
        expected: UpdateCheckOutcome,
        timeoutMillis: Long = 15_000
    ): UpdateCheckOutcome {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var outcome = FilterAutoUpdateStore(context).loadRecord().outcome
        while (outcome != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            outcome = FilterAutoUpdateStore(context).loadRecord().outcome
        }
        return outcome
    }

    private fun awaitState(
        id: java.util.UUID,
        expected: WorkInfo.State,
        timeoutMillis: Long = 15_000
    ): WorkInfo.State? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var state = stateOf(id)
        while (state != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            state = stateOf(id)
        }
        return state
    }

    @Test
    fun `the worker runs, reads the stored preference from disk and persists the outcome`() {
        val store = FilterAutoUpdateStore(context)
        // A fresh install: automatic updates are on by default, and no check has happened yet.
        assertEquals(UpdateCheckOutcome.Never, store.loadRecord().outcome)
        assertNull(store.loadRecord().checkedAtMillis)

        val request = OneTimeWorkRequestBuilder<FilterUpdateWorker>().build()
        workManager.enqueue(request)
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(request.id)

        assertEquals(
            UpdateCheckOutcome.NothingToCheck,
            awaitRecordOutcome(UpdateCheckOutcome.NothingToCheck)
        )
        assertEquals(WorkInfo.State.SUCCEEDED, awaitState(request.id, WorkInfo.State.SUCCEEDED))

        // Persisted by the worker itself, read back through a brand-new store on the same file.
        val record = FilterAutoUpdateStore(context).loadRecord()
        assertNotNull("the worker did not record a check time", record.checkedAtMillis)
        assertEquals(UpdateCheckOutcome.NothingToCheck, record.outcome)
    }

    @Test
    fun `the worker does nothing when automatic updates have been switched off`() {
        FilterAutoUpdateStore(context).setEnabled(false)

        val request = OneTimeWorkRequestBuilder<FilterUpdateWorker>().build()
        workManager.enqueue(request)
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(request.id)

        assertEquals(WorkInfo.State.SUCCEEDED, awaitState(request.id, WorkInfo.State.SUCCEEDED))
        // A run that outlived the switch must not claim to have checked anything.
        assertEquals(UpdateCheckOutcome.Never, FilterAutoUpdateStore(context).loadRecord().outcome)
    }
}
