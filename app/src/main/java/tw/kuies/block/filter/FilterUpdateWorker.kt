package tw.kuies.block.filter

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One automatic refresh of the lists that are already downloaded.
 *
 * Correct after the process has been recreated — which is the whole point of running here rather
 * than in the UI: the worker is handed a fresh process, builds the coordinator from the application
 * context and therefore reads the preference file, the settings and the stored list text **from
 * disk**. Nothing is carried over from a previous run and nothing needs the app to be in the
 * foreground.
 *
 * Failure policy: never [Result.retry]. An offline device is already excluded by the scheduler's
 * network constraint, and a list that failed to download must not be hammered — the next period
 * tries again, and the updater keeps the previous content and snapshot in use meanwhile.
 */
class FilterUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = FilterAutoUpdateStore(applicationContext)

        // A run that outlived the user switching automatic updates off must do nothing at all.
        if (!store.load().enabled) return Result.success()

        val summary = withTimeoutOrNull(REFRESH_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                FilterCoordinatorHolder.get(applicationContext).refreshAll { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
            }
        }

        // A run that never finished leaves the previous record alone: reporting "nothing to check"
        // for a sweep that was cut short would be a claim the run cannot support.
        if (summary != null) {
            store.saveRecord(
                FilterUpdateRecord(
                    checkedAtMillis = System.currentTimeMillis(),
                    outcome = summary.outcome
                )
            )
        }
        return Result.success()
    }

    companion object {
        /** Long enough for a slow list on a bad connection, short enough to fit a WorkManager slot. */
        private const val REFRESH_TIMEOUT_MILLIS = 5L * 60L * 1000L
    }
}
