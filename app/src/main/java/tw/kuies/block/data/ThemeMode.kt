package tw.kuies.block.data

/** The user's theme choice; [SYSTEM] is the default for new and existing installs. */
enum class ThemeMode(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        /** Reads a stored id, falling back to [SYSTEM] for a missing or unknown value. */
        fun fromId(raw: String?): ThemeMode = entries.firstOrNull { it.id == raw } ?: SYSTEM
    }
}
