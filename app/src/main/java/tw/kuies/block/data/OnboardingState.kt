package tw.kuies.block.data

/**
 * How far the first-run guide got on this install.
 *
 * Three states, not a boolean, because "the user read it" and "the user waved it away" are different
 * facts about the same install: both mean the guide must not appear by itself again, while only
 * [NOT_SEEN] means it never appeared at all. The value is stored in the app's own private preference
 * file ([AppSettingsStore]) so it survives a restart without a second storage mechanism.
 */
enum class OnboardingState(val id: String) {
    /** The guide has never been finished or skipped here. */
    NOT_SEEN("not_seen"),

    /** The user reached the end of the guide. */
    COMPLETED("completed"),

    /** The user left the guide early. */
    SKIPPED("skipped");

    companion object {
        /**
         * Reads a persisted id.
         *
         * An id that cannot be read falls back to [NOT_SEEN], which is safe because this value is
         * never the only evidence that an install is new: [OnboardingGate.shouldShowAutomatically]
         * also requires that no VPN authorization was ever recorded, that protection was never
         * started and that no filter settings were ever stored. A single unreadable value therefore
         * cannot force the guide on a user who has been using the app.
         */
        fun fromId(id: String?): OnboardingState =
            entries.firstOrNull { it.id == id } ?: NOT_SEEN
    }
}
