package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.filter.DomainSet
import tw.kuies.block.filter.FilterDecision
import tw.kuies.block.filter.FilterSnapshot
import tw.kuies.block.filter.ManualRule
import tw.kuies.block.filter.MatchScope

class FilterSnapshotTest {

    private val blockRules = DomainSet.of(listOf("example.com", "ads.example.net", "tracker.io"))
    private val allowRules = DomainSet.of(listOf("shop.example.net"))

    private fun snapshot(
        manualAllow: List<ManualRule> = emptyList(),
        manualBlock: List<ManualRule> = emptyList(),
        subscriptionBlock: DomainSet = blockRules,
        subscriptionAllow: DomainSet = DomainSet.EMPTY
    ) = FilterSnapshot.build(
        manualAllow = manualAllow,
        manualBlock = manualBlock,
        subscriptionBlock = subscriptionBlock,
        subscriptionAllow = subscriptionAllow
    )

    // --- priority order: manual allow > manual block > subscription allow > subscription block --

    @Test
    fun aManualAllowBeatsEveryOtherSource() {
        val s = snapshot(
            manualAllow = listOf(ManualRule("example.com")),
            manualBlock = listOf(ManualRule("example.com")),
            subscriptionAllow = DomainSet.of(listOf("example.com"))
        )
        assertEquals(FilterDecision.ALLOW, s.decide("example.com"))
    }

    @Test
    fun aManualBlockBeatsTheSubscriptionExceptionAndBlock() {
        val s = snapshot(
            manualBlock = listOf(ManualRule("example.com")),
            subscriptionAllow = DomainSet.of(listOf("example.com"))
        )
        assertEquals(FilterDecision.BLOCK, s.decide("example.com"))
    }

    @Test
    fun aSubscriptionExceptionBeatsTheSubscriptionBlock() {
        val s = snapshot(
            subscriptionBlock = DomainSet.of(listOf("example.com", "ads.example.net")),
            subscriptionAllow = DomainSet.of(listOf("example.com"))
        )
        assertEquals(FilterDecision.ALLOW, s.decide("example.com"))
        assertEquals("unrelated rule still blocks", FilterDecision.BLOCK, s.decide("ads.example.net"))
    }

    @Test
    fun noMatchMeansTheQueryGoesUpstreamUntouched() {
        assertEquals(FilterDecision.UPSTREAM, snapshot().decide("unrelated.test"))
        assertEquals(FilterDecision.UPSTREAM, FilterSnapshot.EMPTY.decide("example.com"))
        assertEquals(FilterDecision.UPSTREAM, snapshot().decide(""))
    }

    // --- exact versus subdomain scope --------------------------------------------------------

    @Test
    fun aSubdomainRuleCoversTheDomainAndEverythingBelowIt() {
        val s = snapshot()
        assertEquals(FilterDecision.BLOCK, s.decide("example.com"))
        assertEquals(FilterDecision.BLOCK, s.decide("www.example.com"))
        assertEquals(FilterDecision.BLOCK, s.decide("a.b.c.example.com"))
    }

    @Test
    fun anExactManualRuleCoversOnlyThatHost() {
        // No subscription rules here, so the only thing that can block the subdomain is the rule
        // under test itself.
        val s = snapshot(
            manualBlock = listOf(ManualRule("example.com", MatchScope.EXACT)),
            subscriptionBlock = DomainSet.EMPTY
        )
        assertEquals(FilterDecision.BLOCK, s.decide("example.com"))
        assertEquals(FilterDecision.UPSTREAM, s.decide("www.example.com"))
    }

    @Test
    fun anExactManualAllowDoesNotOpenUpTheWholeSubtree() {
        val s = snapshot(manualAllow = listOf(ManualRule("example.com", MatchScope.EXACT)))
        assertEquals(FilterDecision.ALLOW, s.decide("example.com"))
        assertEquals(FilterDecision.BLOCK, s.decide("www.example.com"))
    }

    /** The classic DNS-filtering bug: a suffix test without a label boundary. */
    @Test
    fun aRuleForOneDomainCannotMatchALookalikeDomain() {
        val s = snapshot(subscriptionBlock = DomainSet.of(listOf("example.com")))
        assertEquals(FilterDecision.UPSTREAM, s.decide("notexample.com"))
        assertEquals(FilterDecision.UPSTREAM, s.decide("xexample.com"))
        assertEquals(FilterDecision.UPSTREAM, s.decide("example.com.evil.test"))
        assertEquals(FilterDecision.BLOCK, s.decide("example.com"))
    }

    // --- input hygiene ----------------------------------------------------------------------

    @Test
    fun manualRulesAreCanonicalisedAndInvalidOnesAreDropped() {
        val s = snapshot(
            manualBlock = listOf(
                ManualRule("Example.COM."),
                ManualRule("not a domain"),
                ManualRule(""),
                ManualRule("-bad.com")
            )
        )
        assertEquals("only the valid entry counts", 1, s.userRuleCount)
        assertEquals(FilterDecision.BLOCK, s.decide("example.com"))
    }

    @Test
    fun decideRawCanonicalisesBeforeMatching() {
        val s = snapshot()
        assertEquals(FilterDecision.BLOCK, s.decideRaw("WWW.Example.com."))
        assertEquals(FilterDecision.UPSTREAM, s.decideRaw("not a domain"))
    }

    @Test
    fun emptyAndPopulatedSnapshotsReportTheirState() {
        assertTrue(FilterSnapshot.EMPTY.isEmpty)
        assertFalse(snapshot().isEmpty)
        assertEquals(3, snapshot().subscriptionRuleCount)

        val withManual = snapshot(manualBlock = listOf(ManualRule("manual.test")))
        assertEquals(1, withManual.userRuleCount)
        assertEquals(
            "the exception set counts towards the subscription total",
            4,
            snapshot(
                subscriptionBlock = DomainSet.of(listOf("a.test", "b.test", "c.test")),
                subscriptionAllow = DomainSet.of(listOf("ok.test"))
            ).subscriptionRuleCount
        )
    }

    @Test
    fun theVersionIsCarriedThroughTheSnapshot() {
        val s = FilterSnapshot.build(subscriptionBlock = blockRules, version = 42L)
        assertEquals(42L, s.version)
        assertEquals(0L, FilterSnapshot.EMPTY.version)
    }
}
