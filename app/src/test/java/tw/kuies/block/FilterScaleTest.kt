package tw.kuies.block

import java.io.File
import kotlin.system.measureNanoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tw.kuies.block.filter.DnsRuleParser
import tw.kuies.block.filter.DomainSet
import tw.kuies.block.filter.FilterDecision
import tw.kuies.block.filter.FilterRepository
import tw.kuies.block.filter.FilterSnapshot
import tw.kuies.block.filter.RuleLine

/**
 * Cost checks for the rule engine.
 *
 * A synthetic set always runs, because the point is that lookup cost does not grow with the rule
 * count. The real HaGeZi list is exercised only when a local copy is named by the
 * `KUIESBLOCK_FILTER_SAMPLE` environment variable: the lists are GPL-3.0, so they are never copied
 * into this repository.
 */
class FilterScaleTest {

    @Test
    fun aLargeRuleSetIsBuiltAndQueriedInBoundedTime() {
        val rules = DomainSet.ofNormalised((0 until 20_000).map { "host$it.blocked-example.com" })

        lateinit var snapshot: FilterSnapshot
        val buildNanos = measureNanoTime {
            snapshot = FilterSnapshot.build(subscriptionBlock = rules)
        }
        assertEquals(20_000, snapshot.subscriptionRuleCount)

        val queries = 20_000
        val lookupsNanos = measureNanoTime {
            for (i in 0 until queries) {
                snapshot.decide("host${i % 20_000}.blocked-example.com")
                snapshot.decide("unrelated-$i.example.net")
            }
        }
        val perLookupMicros = lookupsNanos / 1000.0 / (queries * 2)

        println(
            "FilterScaleTest: 20k rules published in ${buildNanos / 1_000_000} ms, " +
                "40k lookups in ${lookupsNanos / 1_000_000} ms " +
                "(${String.format("%.2f", perLookupMicros)} us/lookup)"
        )

        assertTrue("publishing 20k rules must stay well under a second", buildNanos < 2_000_000_000L)
        assertTrue("40k lookups must stay well under 5 seconds", lookupsNanos < 5_000_000_000L)
    }

    @Test
    fun repositoryPublicationBumpsTheVersionAndSwapsAtomically() {
        val repository = FilterRepository()
        assertEquals(0L, repository.version)
        assertTrue(repository.snapshot().isEmpty)

        val first = repository.publish(subscriptionBlock = DomainSet.of(listOf("example.com")))
        assertEquals(1L, first.version)
        assertEquals(FilterDecision.BLOCK, repository.snapshot().decide("example.com"))
        assertTrue("the published snapshot is the one readers get", first === repository.snapshot())

        val second = repository.publish(subscriptionBlock = DomainSet.of(listOf("other.example")))
        assertEquals(2L, second.version)
        assertEquals(FilterDecision.UPSTREAM, repository.snapshot().decide("example.com"))
        assertEquals(FilterDecision.BLOCK, repository.snapshot().decide("other.example"))

        repository.clear()
        assertEquals(3L, repository.version)
        assertTrue(repository.snapshot().isEmpty)
    }

    /** Opt-in against a real list, e.g. KUIESBLOCK_FILTER_SAMPLE=%TEMP%\multi.txt. */
    @Test
    fun aRealUpstreamListParsesAndAnswersInBoundedTime() {
        val path = System.getenv("KUIESBLOCK_FILTER_SAMPLE")
        assumeTrue("set KUIESBLOCK_FILTER_SAMPLE to a downloaded list to run this", !path.isNullOrBlank())
        val file = File(path!!)
        assumeTrue("sample file not found: $path", file.isFile)

        val text = file.readText()
        val runtime = Runtime.getRuntime()

        System.gc()
        val beforeBytes = runtime.totalMemory() - runtime.freeMemory()

        lateinit var parsed: tw.kuies.block.filter.ParsedRules
        lateinit var snapshot: FilterSnapshot
        val totalNanos = measureNanoTime {
            parsed = DnsRuleParser.parse(text)
            snapshot = FilterSnapshot.build(subscriptionBlock = parsed.blocked)
        }
        System.gc()
        val afterBytes = runtime.totalMemory() - runtime.freeMemory()

        println(
            "FilterScaleTest real list: ${parsed.stats.lines} lines, ${parsed.stats.blocked} blocked, " +
                "${parsed.stats.allowed} allowed, ${parsed.stats.rejected} rejected, " +
                "${parsed.stats.duplicates} duplicates, truncated=${parsed.stats.truncated}"
        )
        println(
            "FilterScaleTest real list: parse+build ${totalNanos / 1_000_000} ms, " +
                "heap delta ~${(afterBytes - beforeBytes) / (1024 * 1024)} MB, " +
                "rules ${snapshot.subscriptionRuleCount}"
        )

        assertTrue("the real list must not be truncated by the cap", snapshot.subscriptionRuleCount > 10_000)
        assertEquals("a canonical upstream list must parse with nothing rejected", 0, parsed.stats.rejected)

        // Take the sample from the file, not from the engine, so this checks the whole path.
        val sample = file.readLines()
            .asSequence()
            .mapNotNull { line -> (DnsRuleParser.classifyLine(line) as? RuleLine.Blocked)?.domain }
            .take(20)
            .toList()
        assertTrue("the list must yield block rules", sample.isNotEmpty())

        // Enough repetitions that the per-lookup figure is not dominated by JIT warm-up.
        val iterations = 1_000
        var blocked = 0
        var lookalikes = 0
        val lookupsNanos = measureNanoTime {
            repeat(iterations) {
                for (name in sample) {
                    if (snapshot.decide(name) == FilterDecision.BLOCK) blocked++
                    if (snapshot.decide("sub.$name") == FilterDecision.BLOCK) blocked++
                    if (snapshot.decide("not$name") == FilterDecision.UPSTREAM) lookalikes++
                }
            }
            snapshot.decide("this-host-is-not-in-any-list.example")
        }
        val lookups = sample.size * 3 * iterations + 1

        println("FilterScaleTest real list: $lookups lookups in ${lookupsNanos / 1_000_000} ms")
        assertEquals(
            "every known host and its subdomain must be blocked",
            sample.size * 2 * iterations,
            blocked
        )
        assertEquals("a look-alike name must never match", sample.size * iterations, lookalikes)
        assertTrue("lookups against a real list must stay fast", lookupsNanos < 10_000_000_000L)

        println(
            "FilterScaleTest real list: " +
                "${String.format("%.2f", lookupsNanos / 1000.0 / lookups)} us/lookup " +
                "against ${snapshot.subscriptionRuleCount} rules"
        )
    }
}
