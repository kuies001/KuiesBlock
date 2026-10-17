package tw.kuies.block.update

/**
 * A three-part application version, compared numerically.
 *
 * KuiesBlock versions are `major.minor.patch` with an optional leading `v` — GitHub tags carry one,
 * `versionName` does not — and nothing else. A build suffix, a date or a fourth part makes a string
 * *not* a version as far as this app is concerned, rather than a version that would sort somewhere
 * unexpected and quietly look newer or older than it is.
 *
 * Comparison is by the three numbers, never by the string: `0.4.10` is newer than `0.4.9`, which
 * lexicographic order gets backwards.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    /** The version without a leading `v`, which is how `versionName` and the app's own copy write it. */
    fun text(): String = "$major.$minor.$patch"

    /** The version as GitHub tags it, for wording that names a published release. */
    fun tagText(): String = "v${text()}"

    companion object {
        /**
         * Exactly three numeric parts, optionally prefixed with `v`. Each part is capped at nine
         * digits so it always fits an `Int`; a longer run of digits is simply not a version here.
         */
        private val PATTERN = Regex("""^[vV]?(\d{1,9})\.(\d{1,9})\.(\d{1,9})$""")

        /** Parses [raw] into a version, or returns null when it is not one this app can compare. */
        fun parse(raw: String?): AppVersion? {
            val text = raw?.trim() ?: return null
            val (major, minor, patch) = PATTERN.matchEntire(text)?.destructured ?: return null
            return AppVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}
