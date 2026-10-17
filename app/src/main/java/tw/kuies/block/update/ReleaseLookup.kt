package tw.kuies.block.update

/**
 * Why a release lookup produced no version.
 *
 * An enum of sentences, like [tw.kuies.block.filter.SubscriptionFailure]: the screen shows [label] and
 * the rest of the app matches on the value, so no reason ever carries a URL, a status code, a server
 * message or an exception.
 */
enum class UpdateCheckFailure(val label: String) {
    /** GitHub answered 403 or 429: the anonymous request budget for this address is spent. */
    RATE_LIMITED("GitHub 暫時限制查詢次數，請稍後再試。"),

    /** GitHub answered 404: no published release was found for the project. */
    NOT_FOUND("在 GitHub 上找不到可用的版本資訊。"),

    /** Any other non-success answer. */
    SERVER("GitHub 暫時無法回應，請稍後再試。"),

    /** The request ran out of time. */
    TIMEOUT("檢查更新逾時，請稍後再試。"),

    /** The connection could not be made at all. */
    NETWORK("無法連線至 GitHub 檢查更新。"),

    /** The response could not be understood, or the installed version could not be compared. */
    UNREADABLE("暫時無法檢查更新。")
}

/** The outcome of asking for the latest stable release. */
sealed interface ReleaseLookup {
    data class Found(val release: ReleaseInfo) : ReleaseLookup
    data class Failed(val reason: UpdateCheckFailure) : ReleaseLookup
}

/** The lookup seam, so the comparison can be exercised without a network. */
fun interface ReleaseSource {
    fun latestStableRelease(): ReleaseLookup
}
