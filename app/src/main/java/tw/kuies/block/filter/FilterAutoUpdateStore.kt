package tw.kuies.block.filter

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the automatic-update preference and the last check result in their own private preference
 * file.
 *
 * Takes the `SharedPreferences` rather than the `Context` it came from (the production constructor
 * derives it), so both the defaults and the persistence can be unit tested on the JVM with a fake,
 * exactly like [FilterSettingsStore] and [tw.kuies.block.data.AppSettingsStore].
 */
class FilterAutoUpdateStore(private val prefs: SharedPreferences) {

    /** Production constructor: the app's private preference file, as with the other stores. */
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun load(): FilterAutoUpdateSettings = FilterAutoUpdateSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        interval = UpdateInterval.fromHours(
            prefs.getInt(KEY_INTERVAL_HOURS, UpdateInterval.DEFAULT.hours.toInt())
        )
    )

    fun save(settings: FilterAutoUpdateSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putInt(KEY_INTERVAL_HOURS, settings.interval.hours.toInt())
            .apply()
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setInterval(interval: UpdateInterval) {
        prefs.edit().putInt(KEY_INTERVAL_HOURS, interval.hours.toInt()).apply()
    }

    /**
     * Stores one interval and reports whether the write really happened.
     *
     * Uses `commit()` because the caller has to distinguish "saved" from "not saved" before it can
     * claim the new period is in force; the file is a few bytes, so the write is not a main-thread
     * cost worth avoiding.
     */
    fun saveInterval(interval: UpdateInterval): Boolean =
        prefs.edit().putInt(KEY_INTERVAL_HOURS, interval.hours.toInt()).commit()

    /** The last check, or [UpdateCheckOutcome.Never] when none has run yet. */
    fun loadRecord(): FilterUpdateRecord = FilterUpdateRecord(
        checkedAtMillis = if (prefs.contains(KEY_CHECKED_AT)) {
            prefs.getLong(KEY_CHECKED_AT, 0L)
        } else {
            null
        },
        outcome = UpdateCheckOutcome.fromId(
            prefs.getString(KEY_CHECK_OUTCOME, null),
            prefs.getInt(KEY_CHECK_COUNT, 0)
        )
    )

    fun saveRecord(record: FilterUpdateRecord) {
        val editor = prefs.edit().putString(KEY_CHECK_OUTCOME, record.outcome.id)
        val at = record.checkedAtMillis
        if (at == null) {
            editor.remove(KEY_CHECKED_AT)
        } else {
            editor.putLong(KEY_CHECKED_AT, at)
        }
        when (val outcome = record.outcome) {
            is UpdateCheckOutcome.Updated -> editor.putInt(KEY_CHECK_COUNT, outcome.count)
            is UpdateCheckOutcome.Failed -> editor.putInt(KEY_CHECK_COUNT, outcome.count)
            else -> editor.remove(KEY_CHECK_COUNT)
        }
        editor.apply()
    }

    companion object {
        const val PREFS_NAME = "kuies_block_filter_updates"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL_HOURS = "interval_hours"
        private const val KEY_CHECKED_AT = "last_check_at"
        private const val KEY_CHECK_OUTCOME = "last_check_outcome"
        private const val KEY_CHECK_COUNT = "last_check_count"
    }
}
