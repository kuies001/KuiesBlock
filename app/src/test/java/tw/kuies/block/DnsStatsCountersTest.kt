package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import tw.kuies.block.data.DnsStatsCounters

class DnsStatsCountersTest {

    @Test
    fun countsQueriesAndFailures() {
        var counters = DnsStatsCounters(dayEpochDay = 10L)
        counters = counters.recordQuery(10L).recordQuery(10L).recordFailure(10L)
        assertEquals(2, counters.queries)
        assertEquals(1, counters.failed)
    }

    @Test
    fun aNewLocalDayResetsTheCounters() {
        val counters = DnsStatsCounters(dayEpochDay = 10L, queries = 7, failed = 2)
        val rolled = counters.rollTo(11L)
        assertEquals(11L, rolled.dayEpochDay)
        assertEquals(0, rolled.queries)
        assertEquals(0, rolled.failed)
        assertEquals(1, rolled.recordQuery(11L).queries)
    }

    @Test
    fun theSameDayKeepsTheCounters() {
        val counters = DnsStatsCounters(dayEpochDay = 10L, queries = 7)
        assertEquals(7, counters.rollTo(10L).queries)
    }

    @Test
    fun theBlockedCountIsNeverInvented() {
        val stats = DnsStatsCounters(dayEpochDay = 10L, queries = 100, failed = 3).toBlockStats(1L)
        assertEquals(100, stats.queriesToday)
        assertEquals(3, stats.failedToday)
        assertNull(stats.blockedToday)
        assertFalse(stats.hasBlockedData)
    }

    @Test
    fun epochDayFollowsTheLocalCalendar() {
        val day = DnsStatsCounters.MILLIS_PER_DAY
        val earlyUtc = 20 * day + 30 * 60_000L
        assertEquals(20L, DnsStatsCounters.epochDayOf(earlyUtc, 0))
        assertEquals(20L, DnsStatsCounters.epochDayOf(earlyUtc, 8 * 3_600_000))

        val lateUtc = 20 * day + 23 * 3_600_000L + 30 * 60_000L
        assertEquals(20L, DnsStatsCounters.epochDayOf(lateUtc, 0))
        assertEquals(21L, DnsStatsCounters.epochDayOf(lateUtc, 8 * 3_600_000))
    }
}
