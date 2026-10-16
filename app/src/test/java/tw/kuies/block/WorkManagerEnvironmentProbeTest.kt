package tw.kuies.block

import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The feasibility probe for this round's whole approach.
 *
 * Everything else — real scheduling, the worker actually running — depends on Robolectric and the
 * official WorkManager test harness loading in this project, on this JDK, at this compile SDK. That is
 * checked here first and on its own, so a compatibility problem costs one test rather than a round.
 *
 * `sdk = 35` is pinned on purpose: `compileSdk 37` is newer than any Robolectric Android image, and the
 * pinned level only changes the platform APIs the test runs against, not what is being verified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkManagerEnvironmentProbeTest {

    @Test
    fun `the official WorkManager test harness initialises here`() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        assertNotNull(WorkManager.getInstance(context))
    }
}
