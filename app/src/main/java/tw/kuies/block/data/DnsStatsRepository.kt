package tw.kuies.block.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.TimeZone

/**
 * Process-wide, observable DNS counters shared by the VPN service and the UI.
 *
 * Design choices that keep this cheap and honest:
 *  * Only daily aggregates are stored — never a domain, an address or a history.
 *  * Counter updates happen on the packet paths, so the Compose value is published by coalescing
 *    onto the main thread instead of touching UI state from worker threads.
 *  * The aggregate is flushed to disk on a throttle, so "today" survives a restart without a disk
 *    write per query.
 */
object DnsStatsRepository {

    const val PREFS_NAME = "kuies_block_stats"
    private const val KEY_DAY = "day_epoch"
    private const val KEY_QUERIES = "queries"
    private const val KEY_FAILED = "failed"
    private const val PERSIST_INTERVAL_MILLIS = 5_000L

    var today: BlockStats by mutableStateOf(BlockStats.NONE)
        private set

    private val lock = Any()
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private var counters = DnsStatsCounters.EMPTY
    private var appContext: Context? = null
    private var lastPersistAtMillis = 0L
    private var publishScheduled = false

    /** Idempotent; called by both the Activity and the service so they share one counter set. */
    fun attach(context: Context) {
        val resolved = context.applicationContext
        val loaded = synchronized(lock) {
            if (appContext == null) {
                appContext = resolved
                val prefs = resolved.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                counters = DnsStatsCounters(
                    dayEpochDay = prefs.getLong(KEY_DAY, 0L),
                    queries = prefs.getInt(KEY_QUERIES, 0),
                    failed = prefs.getInt(KEY_FAILED, 0)
                )
            }
            counters
        }
        publish(loaded, System.currentTimeMillis(), force = false)
    }

    fun recordQuery(nowMillis: Long = System.currentTimeMillis()) {
        mutate(nowMillis, DnsStatsCounters::recordQuery)
    }

    fun recordFailure(nowMillis: Long = System.currentTimeMillis()) {
        mutate(nowMillis, DnsStatsCounters::recordFailure)
    }

    /** Recomputes the visible value, e.g. after the local day rolls over. */
    fun refresh(nowMillis: Long = System.currentTimeMillis()) {
        publish(synchronized(lock) { counters }, nowMillis, force = false)
    }

    fun persistNow(nowMillis: Long = System.currentTimeMillis()) {
        publish(synchronized(lock) { counters }, nowMillis, force = true)
    }

    private fun mutate(nowMillis: Long, transform: DnsStatsCounters.(Long) -> DnsStatsCounters) {
        val updated = synchronized(lock) {
            val day = epochDayOf(nowMillis)
            counters.transform(day).also { counters = it }
        }
        publish(updated, nowMillis, force = false)
    }

    private fun publish(newCounters: DnsStatsCounters, nowMillis: Long, force: Boolean) {
        schedulePublish()
        persist(newCounters, nowMillis, force)
    }

    private fun schedulePublish() {
        val shouldPost = synchronized(lock) {
            if (publishScheduled) {
                false
            } else {
                publishScheduled = true
                true
            }
        }
        if (!shouldPost) return
        mainHandler.post {
            val latest = synchronized(lock) {
                // Clear the flag before reading so an update that lands now schedules its own post.
                publishScheduled = false
                counters.toBlockStats(System.currentTimeMillis())
            }
            today = latest
        }
    }

    private fun persist(newCounters: DnsStatsCounters, nowMillis: Long, force: Boolean) {
        val context = appContext ?: return
        val due = synchronized(lock) {
            if (!force && nowMillis - lastPersistAtMillis < PERSIST_INTERVAL_MILLIS) {
                false
            } else {
                lastPersistAtMillis = nowMillis
                true
            }
        }
        if (!due) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DAY, newCounters.dayEpochDay)
            .putInt(KEY_QUERIES, newCounters.queries)
            .putInt(KEY_FAILED, newCounters.failed)
            .apply()
    }

    private fun epochDayOf(epochMillis: Long): Long =
        DnsStatsCounters.epochDayOf(epochMillis, TimeZone.getDefault().getOffset(epochMillis))
}
