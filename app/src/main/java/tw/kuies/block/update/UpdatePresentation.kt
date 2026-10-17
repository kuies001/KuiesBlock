package tw.kuies.block.update

/**
 * The update section's wording, kept pure so what the user is told about versions can be unit tested.
 *
 * Nothing here claims a state the outcome does not carry: "目前已是最新版本" needs an equal version, a
 * failure says only that the check could not be made, and an installed version that is *ahead* of the
 * published one is named as such instead of being reported as an available update.
 */
object UpdatePresentation {

    /** The version as the section's subtitle shows it: `v` plus `versionName`, e.g. `v0.4.0`. */
    fun versionLabel(versionName: String): String {
        val trimmed = versionName.trim()
        return if (trimmed.startsWith("v", ignoreCase = true)) trimmed else "v$trimmed"
    }

    /**
     * The line under the check button: the result of the last check, or null before the first one and
     * while a duplicate tap is being ignored.
     */
    fun resultLabel(outcome: UpdateCheckOutcome?): String? = when (outcome) {
        null -> null
        is UpdateCheckOutcome.UpdateAvailable -> "發現新版本 ${outcome.release.version.tagText()}"
        UpdateCheckOutcome.UpToDate -> "目前已是最新版本"
        UpdateCheckOutcome.InstalledNewer -> "目前安裝的版本較新"
        is UpdateCheckOutcome.Unavailable -> outcome.reason.label
        UpdateCheckOutcome.Busy -> null
    }

    /** True when [outcome] is a failure, so the line can be shown in the error colour. */
    fun isFailure(outcome: UpdateCheckOutcome?): Boolean = outcome is UpdateCheckOutcome.Unavailable

    /**
     * The release page to open, or null when the release carries no address this app may open. The
     * decision is [ReleasePagePolicy]'s, so the dialog and the tap that follows it cannot disagree.
     */
    fun downloadableUrl(release: ReleaseInfo): String? = ReleasePagePolicy.officialUrlOrNull(release.pageUrl)
}
