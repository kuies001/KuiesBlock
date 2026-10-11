package tw.kuies.block.data

/**
 * Today's DNS counters.
 *
 * [queriesToday] and [failedToday] are counted directly from intercepted traffic, so they are
 * always real numbers. [blockedToday] stays `null` in this version: AdGuard Home never reports
 * which answers it blocked, and inferring a block from NXDOMAIN would score genuinely missing
 * domains as blocked ads. Until it can be measured exactly, the UI shows 「尚無資料」.
 */
data class BlockStats(
    val queriesToday: Int,
    val blockedToday: Int?,
    val failedToday: Int,
    val collectedAtMillis: Long
) {
    val hasBlockedData: Boolean get() = blockedToday != null

    companion object {
        val NONE = BlockStats(
            queriesToday = 0,
            blockedToday = null,
            failedToday = 0,
            collectedAtMillis = 0L
        )
    }
}
