package tw.kuies.block.filter

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * The one thing the app needs from a periodic scheduler, behind an interface so the decision of
 * *whether* and *how often* to schedule can be unit tested without WorkManager or Robolectric.
 */
interface PeriodicUpdateScheduler {

    /** Ensures exactly one periodic job exists for [interval], updating the existing one in place. */
    fun ensureScheduled(interval: UpdateInterval)

    /** Removes the periodic job. Manual updates are unaffected — they never touch the scheduler. */
    fun cancel()
}

/** The decision of what the stored preference implies for the scheduler, kept pure and testable. */
object FilterUpdateSchedulePolicy {

    fun apply(settings: FilterAutoUpdateSettings, scheduler: PeriodicUpdateScheduler) {
        if (settings.enabled) {
            scheduler.ensureScheduled(settings.interval)
        } else {
            scheduler.cancel()
        }
    }
}

/**
 * Commits an interval the user confirmed with 「儲存並更新」.
 *
 * The order matters: persist first, then make the one periodic job agree, and only then run the one
 * extra refresh the button promises. A failure at either step leaves the previous interval in force —
 * the preference is rolled back if scheduling fails, so the stored value, the schedule and the summary
 * cannot disagree. [onSaved] is invoked exactly once and only for a real change, which is what keeps
 * a double tap or a repeated confirmation from starting a second download.
 */
object FilterIntervalSavePolicy {

    fun save(
        interval: UpdateInterval,
        store: FilterAutoUpdateStore,
        scheduler: PeriodicUpdateScheduler,
        onSaved: () -> Unit = {}
    ): Boolean {
        val current = store.load()
        if (current.interval == interval) return true

        if (!store.saveInterval(interval)) return false
        val updated = current.copy(interval = interval)

        val scheduled = runCatching { FilterUpdateSchedulePolicy.apply(updated, scheduler) }
        if (scheduled.isFailure) {
            // Put the previous interval back, then re-assert its schedule so a half-applied change
            // cannot survive. The caller reports the failure and the screen keeps the draft.
            store.saveInterval(current.interval)
            runCatching { FilterUpdateSchedulePolicy.apply(current, scheduler) }
            return false
        }

        onSaved()
        return true
    }
}

/**
 * The production scheduler: one unique periodic WorkManager job.
 *
 * The guarantees come from WorkManager rather than from bookkeeping of our own:
 *  * a **unique** name means repeated settings, repeated app starts or a tier change can never leave
 *    two jobs behind;
 *  * [ExistingPeriodicWorkPolicy.UPDATE] is what makes an 8 h → 24 h change replace the existing
 *    job's specification instead of queuing a second one;
 *  * a `CONNECTED` constraint is what keeps an offline device from retrying into a wall.
 *
 * WorkManager's period is inexact by design: the platform batches it with other work and may delay it
 * while the device is dozing. That is the intended behaviour here — the app never claims an exact
 * time, and nothing depends on the update being punctual.
 */
class WorkManagerPeriodicUpdateScheduler(context: Context) : PeriodicUpdateScheduler {

    private val appContext = context.applicationContext

    override fun ensureScheduled(interval: UpdateInterval) {
        val request = PeriodicWorkRequestBuilder<FilterUpdateWorker>(interval.hours, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            FILTER_UPDATE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    override fun cancel() {
        WorkManager.getInstance(appContext).cancelUniqueWork(FILTER_UPDATE_WORK_NAME)
    }

    companion object {
        /** Unique across the app: one automatic-update job, whatever the interval. */
        const val FILTER_UPDATE_WORK_NAME = "kuies_filter_auto_update"
    }
}
