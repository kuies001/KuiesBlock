package tw.kuies.block.filter

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * What a mode or source change is doing right now, as the settings screen reports it.
 *
 * [PREPARING] means the newly chosen mode has no rules published yet and a list has to arrive before
 * it can take effect. [UPDATING] means rules are already in effect and the background check is only
 * refreshing them, so the screen must not imply that protection stopped. Both are shown as an
 * in-progress state; [SUCCEEDED] is the short-lived confirmation of the last request that finished
 * successfully, and is never emitted by a request that was superseded.
 */
enum class FilterSyncPhase {
    IDLE,
    PREPARING,
    UPDATING,
    SUCCEEDED
}

/**
 * The state a sync was started for.
 *
 * Two jobs with different keys are not the same request: a job whose key no longer matches the stored
 * settings is stale, and must neither download nor publish. Comparing the mode, the main list's URL
 * and the custom selection is what keeps 標準 → 嚴格 → 寬鬆 from ending on the tier that happened to
 * finish first.
 */
data class FilterSyncKey(
    val mode: FilterMode,
    val profileUrl: String,
    val selectedSources: Set<String>
)

/** The identity of the selection a sync would be serving. */
fun FilterSettings.syncKey(): FilterSyncKey = FilterSyncKey(
    mode = mode,
    profileUrl = builtInSubscription?.url ?: profile.primaryUrl,
    selectedSources = selectedSources
)

/**
 * Defers a sync until the user stops changing the selection.
 *
 * A call replaces any pending call, so ticking five sources in a row results in one download pass for
 * the final selection rather than five independent ones.
 */
fun interface FilterDebouncer {

    fun post(task: Runnable)

    companion object {
        /** Runs at once. The deterministic choice for tests and for work that must not wait. */
        val IMMEDIATE = FilterDebouncer { it.run() }
    }
}

/**
 * The production debouncer: one daemon thread, and only the latest task is kept.
 *
 * The delay is short on purpose. It has to cover a row of checkboxes being tapped, and it must stay
 * far below the time the user would notice before the first download starts.
 */
class ScheduledFilterDebouncer(
    private val delayMillis: Long = DEFAULT_DELAY_MILLIS
) : FilterDebouncer {

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "kuies-filter-debounce").apply { isDaemon = true }
    }

    private var pending: ScheduledFuture<*>? = null

    @Synchronized
    override fun post(task: Runnable) {
        pending?.cancel(false)
        pending = scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
    }

    companion object {
        /** Long enough to merge a row of taps, short enough to feel immediate. */
        const val DEFAULT_DELAY_MILLIS = 450L
    }
}
