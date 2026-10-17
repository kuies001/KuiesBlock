package tw.kuies.block.update

import java.util.concurrent.atomic.AtomicBoolean

/** What the update check found, in the cases the settings screen distinguishes. */
sealed interface UpdateCheckOutcome {
    /** A newer stable release is published. */
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateCheckOutcome

    /** The installed version is the published one. */
    data object UpToDate : UpdateCheckOutcome

    /** The installed version is newer than the published one — a local or test build. */
    data object InstalledNewer : UpdateCheckOutcome

    /** No comparison could be made; [reason] carries the sentence the user is shown. */
    data class Unavailable(val reason: UpdateCheckFailure) : UpdateCheckOutcome

    /**
     * A check was already running when this one was asked for, so no second request was sent. The
     * caller keeps whatever it was already showing.
     */
    data object Busy : UpdateCheckOutcome
}

/**
 * Compares the installed version with the latest stable release.
 *
 * Two things decide the answer, and both live here rather than in the screen: the comparison is
 * numeric ([AppVersion]), and only one lookup can be in flight at a time — a user tapping the button
 * twice, or a recomposition, must not spend two of the caller's rate-limited requests. The second
 * caller is answered with [UpdateCheckOutcome.Busy] and nothing is sent.
 *
 * This class performs no I/O of its own: [source] owns the network, so every branch here is testable
 * without a server.
 */
class UpdateChecker(
    private val source: ReleaseSource,
    /** The installed `versionName`, or null when it is not a version this app can compare. */
    private val currentVersion: AppVersion?
) {
    private val inFlight = AtomicBoolean(false)

    fun check(): UpdateCheckOutcome {
        if (!inFlight.compareAndSet(false, true)) return UpdateCheckOutcome.Busy
        try {
            val current = currentVersion
                ?: return UpdateCheckOutcome.Unavailable(UpdateCheckFailure.UNREADABLE)
            return when (val lookup = source.latestStableRelease()) {
                is ReleaseLookup.Failed -> UpdateCheckOutcome.Unavailable(lookup.reason)
                is ReleaseLookup.Found -> compare(current, lookup.release)
            }
        } finally {
            inFlight.set(false)
        }
    }

    private fun compare(current: AppVersion, release: ReleaseInfo): UpdateCheckOutcome = when {
        release.version > current -> UpdateCheckOutcome.UpdateAvailable(release)
        release.version == current -> UpdateCheckOutcome.UpToDate
        else -> UpdateCheckOutcome.InstalledNewer
    }
}
