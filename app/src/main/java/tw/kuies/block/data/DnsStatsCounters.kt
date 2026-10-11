package tw.kuies.block.data

/**
 * Pure daily DNS counters with a local-midnight roll-over.
 *
 * Only aggregate numbers are ever held: no domain, no address and no history. Kept free of
 * Android types so the roll-over and the "no blocked data" rule can be unit tested on the JVM.
 */
data class DnsStatsCounters(
    val dayEpochDay: Long,
    val queries: Int = 0,
    val failed: Int = 0
) {
    /** Resets to zero when the local day changed, otherwise returns this unchanged. */
    fun rollTo(epochDay: Long): DnsStatsCounters =
        if (epochDay == dayEpochDay) this else DnsStatsCounters(dayEpochDay = epochDay)

    fun recordQuery(epochDay: Long): DnsStatsCounters =
        rollTo(epochDay).let { it.copy(queries = it.queries + 1) }

    fun recordFailure(epochDay: Long): DnsStatsCounters =
        rollTo(epochDay).let { it.copy(failed = it.failed + 1) }

    /**
     * A blocked count is never derived: it is unmeasurable through plain DoH, so it stays null so
     * the UI keeps showing 「尚無資料」 instead of a plausible-looking invention.
     */
    fun toBlockStats(collectedAtMillis: Long): BlockStats = BlockStats(
        queriesToday = queries,
        blockedToday = null,
        failedToday = failed,
        collectedAtMillis = collectedAtMillis
    )

    companion object {
        const val MILLIS_PER_DAY = 86_400_000L

        val EMPTY = DnsStatsCounters(dayEpochDay = 0L)

        /** Local calendar day index for [epochMillis] given the zone offset at that instant. */
        fun epochDayOf(epochMillis: Long, zoneOffsetMillis: Int): Long =
            Math.floorDiv(epochMillis + zoneOffsetMillis, MILLIS_PER_DAY)
    }
}
