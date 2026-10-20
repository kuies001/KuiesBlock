package tw.kuies.block.filter

/**
 * The user's automatic-update choice, and what the last automatic check did.
 *
 * Kept apart from [FilterSettings] on purpose: the settings object is what the engine publishes rules
 * from, and adding a scheduling concern to it would change its persisted schema and every test that
 * pins the snapshot behaviour. Nothing here can influence which rules are published.
 */

/** How often the lists are refreshed on their own. */
enum class UpdateInterval(val hours: Long) {
    HOURS_8(8L),
    HOURS_24(24L);

    companion object {
        /** The default the app ships with: once a day. */
        val DEFAULT = HOURS_24

        /** Parses a persisted hour count, falling back to [DEFAULT] so a stale value cannot break start-up. */
        fun fromHours(hours: Int?): UpdateInterval =
            entries.firstOrNull { it.hours == hours?.toLong() } ?: DEFAULT
    }
}

/**
 * The automatic-update preference.
 *
 * [enabled] defaults to `true`, but that alone downloads nothing: the worker only refreshes sources
 * whose content was already downloaded, so a fresh install still fetches no list until the user picks
 * a strength. See [FilterCoordinator.refreshAll].
 */
data class FilterAutoUpdateSettings(
    val enabled: Boolean = true,
    val interval: UpdateInterval = UpdateInterval.DEFAULT
)

/**
 * The interval the user has picked on screen, kept apart from the one that is actually stored.
 *
 * Tapping a period changes only [selected]; nothing is written and no job is touched until the screen
 * asks for [selected] to be saved. [saved] is what the summary and the scheduler still describe, so a
 * selection that is never confirmed cannot leak into the preference file.
 */
data class FilterIntervalDraft(
    val saved: UpdateInterval,
    val selected: UpdateInterval = saved
) {
    /** True exactly while the screen should offer 「儲存並更新」. */
    val hasPendingChange: Boolean
        get() = selected != saved

    /** Records a radio tap: the draft moves, the saved value does not. */
    fun select(interval: UpdateInterval): FilterIntervalDraft = copy(selected = interval)

    /** Starts again from [interval], discarding any unconfirmed selection. */
    fun resetTo(interval: UpdateInterval): FilterIntervalDraft =
        FilterIntervalDraft(saved = interval, selected = interval)

    companion object {
        fun of(saved: UpdateInterval): FilterIntervalDraft = FilterIntervalDraft(saved = saved)
    }
}

/** What the most recent automatic check actually did. */
sealed interface UpdateCheckOutcome {

    /** The automatic check has never run on this install. */
    data object Never : UpdateCheckOutcome

    /** It ran, but there was nothing downloaded to refresh — a fresh install, or every source removed. */
    data object NothingToCheck : UpdateCheckOutcome

    /** Every source it checked is already current (`HTTP 304`). */
    data object AllCurrent : UpdateCheckOutcome

    /** [count] sources were re-downloaded and replaced. */
    data class Updated(val count: Int) : UpdateCheckOutcome

    /** [count] sources failed; the previous lists stay in use. */
    data class Failed(val count: Int) : UpdateCheckOutcome

    /** Stable id for persistence. */
    val id: String
        get() = when (this) {
            Never -> "never"
            NothingToCheck -> "nothing_to_check"
            AllCurrent -> "all_current"
            is Updated -> "updated"
            is Failed -> "failed"
        }

    companion object {
        /** Rebuilds an outcome from its persisted id and count. */
        fun fromId(id: String?, count: Int): UpdateCheckOutcome = when (id) {
            "nothing_to_check" -> NothingToCheck
            "all_current" -> AllCurrent
            "updated" -> Updated(count)
            "failed" -> Failed(count)
            else -> Never
        }
    }
}

/**
 * The result of one automatically triggered sweep over the enabled sources.
 *
 * [considered] is how many sources the sweep was allowed to touch at all, so a run that found nothing
 * to do is distinguishable from one that found everything already current.
 */
data class FilterRefreshSummary(
    val considered: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0
) {
    /** The outcome this sweep should be reported as. */
    val outcome: UpdateCheckOutcome
        get() = when {
            considered == 0 -> UpdateCheckOutcome.NothingToCheck
            failed > 0 -> UpdateCheckOutcome.Failed(failed)
            updated > 0 -> UpdateCheckOutcome.Updated(updated)
            else -> UpdateCheckOutcome.AllCurrent
        }

    companion object {
        /** A sweep that never ran — another one held the coordinator. */
        val NOT_RUN = FilterRefreshSummary()
    }
}

/** The most recent automatic check, as the settings page reports it. */
data class FilterUpdateRecord(
    val checkedAtMillis: Long? = null,
    val outcome: UpdateCheckOutcome = UpdateCheckOutcome.Never
)

/**
 * Which sources one automatic sweep is allowed to touch.
 *
 * Pure, so every case that matters can be unit tested without Android. The rule has to answer five
 * questions at once, and the ordering below is what keeps them from contradicting each other:
 *  * an already-downloaded list is kept current (the ordinary case);
 *  * a source the **user added** gets its content even when the first attempt failed or never
 *    finished, so a failed first download can never strand it until a manual update;
 *  * an attempt that **failed** is retried — that is recovery, not a download nobody asked for;
 *  * a source with no stored content **and no attempt behind it** is left alone, which is exactly what
 *    keeps a fresh install from downloading anything before the user picks a strength.
 *
 * Disabled sources are never touched, whatever else is true of them.
 */
object FilterAutoUpdateSelection {

    fun shouldRefresh(subscription: FilterSubscription): Boolean =
        subscription.enabled && when {
            subscription.ruleCount > 0 -> true
            subscription.origin == SubscriptionOrigin.USER -> true
            subscription.state == SubscriptionState.FAILED -> true
            else -> false
        }

    /** The active sources of [settings] a sweep may touch, in configuration order. */
    fun select(settings: FilterSettings): List<FilterSubscription> =
        settings.effectiveSubscriptions.filter(::shouldRefresh)
}

