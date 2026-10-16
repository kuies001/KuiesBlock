package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.filter.ManualRule
import tw.kuies.block.filter.ManualRuleDraft
import tw.kuies.block.filter.MatchScope

/**
 * What may become a manual rule, and what must be refused instead.
 *
 * The load-bearing property is the negative one: a rejection never turns into a rule. The engine
 * only ever matches canonical names, so an entry that [ManualRuleDraft] does not accept would
 * otherwise be stored, shown to the user, and silently never match anything.
 */
class ManualRuleDraftTest {

    @Test
    fun theLengthLimitMatchesTheDnsWireLimit() {
        assertEquals(253, ManualRuleDraft.MAX_DOMAIN_LENGTH)
    }

    @Test
    fun anExactEntryKeepsTheExactScope() {
        assertEquals(
            ManualRuleDraft.Result.Accepted(ManualRule("ads.example.com", MatchScope.EXACT)),
            ManualRuleDraft.parse("ads.example.com", MatchScope.EXACT, emptyList())
        )
    }

    @Test
    fun aSubdomainEntryKeepsTheSubdomainScope() {
        assertEquals(
            ManualRuleDraft.Result.Accepted(ManualRule("example.com", MatchScope.SUBDOMAIN)),
            ManualRuleDraft.parse("example.com", MatchScope.SUBDOMAIN, emptyList())
        )
    }

    @Test
    fun theStoredDomainIsTheCanonicalForm() {
        assertEquals(
            ManualRuleDraft.Result.Accepted(ManualRule("ads.example.com", MatchScope.EXACT)),
            ManualRuleDraft.parse("  Ads.Example.COM.  ", MatchScope.EXACT, emptyList())
        )
    }

    @Test
    fun anInternationalisedNameIsStoredAsPunycode() {
        assertEquals(
            ManualRuleDraft.Result.Accepted(ManualRule("xn--bcher-kva.de", MatchScope.SUBDOMAIN)),
            ManualRuleDraft.parse("Bücher.de", MatchScope.SUBDOMAIN, emptyList())
        )
    }

    @Test
    fun anEmptyEntryIsRefused() {
        assertEquals(ManualRuleDraft.Rejection.EMPTY, reject(""))
        assertEquals(ManualRuleDraft.Rejection.EMPTY, reject("   "))
    }

    @Test
    fun anEntryLongerThanTheLimitIsRefusedForItsLength() {
        val tooLong = "a".repeat(250) + ".com"

        assertEquals(254, tooLong.length)
        assertEquals(ManualRuleDraft.Rejection.TOO_LONG, reject(tooLong))
    }

    @Test
    fun anEntryExactlyAtTheLimitIsStillAccepted() {
        val longest = ("a".repeat(63) + ".").repeat(3) + "a".repeat(61)

        assertEquals(ManualRuleDraft.MAX_DOMAIN_LENGTH, longest.length)
        assertTrue(
            ManualRuleDraft.parse(longest, MatchScope.SUBDOMAIN, emptyList())
                is ManualRuleDraft.Result.Accepted
        )
    }

    @Test
    fun anIpv4AddressIsRefusedAsAnAddressNotAsAName() {
        assertEquals(ManualRuleDraft.Rejection.IP_ADDRESS, reject("192.168.0.1"))
        assertEquals(ManualRuleDraft.Rejection.IP_ADDRESS, reject("0.0.0.0"))
        assertEquals(ManualRuleDraft.Rejection.IP_ADDRESS, reject("255.255.255.255"))
    }

    @Test
    fun anIpv6AddressIsRefusedAsAnAddressRatherThanAsAUrl() {
        assertEquals(ManualRuleDraft.Rejection.IP_ADDRESS, reject("2001:db8::1"))
        assertEquals(ManualRuleDraft.Rejection.IP_ADDRESS, reject("::1"))
        assertEquals(ManualRuleDraft.Rejection.IP_ADDRESS, reject("fe80::1"))
    }

    @Test
    fun aUrlOrAPathIsRefusedAsUrlSyntax() {
        assertEquals(ManualRuleDraft.Rejection.URL_OR_PATH, reject("https://example.com"))
        assertEquals(ManualRuleDraft.Rejection.URL_OR_PATH, reject("example.com/ads"))
        assertEquals(ManualRuleDraft.Rejection.URL_OR_PATH, reject("example.com:8080"))
        assertEquals(ManualRuleDraft.Rejection.URL_OR_PATH, reject("example.com?q=1"))
        assertEquals(ManualRuleDraft.Rejection.URL_OR_PATH, reject("example.com#top"))
        assertEquals(ManualRuleDraft.Rejection.URL_OR_PATH, reject("exa mple.com"))
        // Two pasted lines are one entry as far as the field is concerned; neither may become a rule.
        assertEquals(ManualRuleDraft.Rejection.URL_OR_PATH, reject("example.com\nexample.org"))
    }

    @Test
    fun anythingTheNormaliserRefusesIsRefusedAsNotADomain() {
        assertEquals(ManualRuleDraft.Rejection.NOT_A_DOMAIN, reject("exa!mple.com"))
        assertEquals(ManualRuleDraft.Rejection.NOT_A_DOMAIN, reject("-example.com"))
        assertEquals(ManualRuleDraft.Rejection.NOT_A_DOMAIN, reject("example..com"))
        assertEquals(ManualRuleDraft.Rejection.NOT_A_DOMAIN, reject("example.com-"))
        assertEquals(ManualRuleDraft.Rejection.NOT_A_DOMAIN, reject("."))
    }

    @Test
    fun theSameDomainInTheSameScopeIsADuplicate() {
        val existing = listOf(ManualRule("example.com", MatchScope.SUBDOMAIN))

        assertEquals(ManualRuleDraft.Rejection.DUPLICATE, reject("example.com", existing = existing))
        // A differently spelled duplicate is still a duplicate: comparison is on the canonical name.
        assertEquals(ManualRuleDraft.Rejection.DUPLICATE, reject("EXAMPLE.com.", existing = existing))
        assertEquals(
            ManualRuleDraft.Rejection.DUPLICATE,
            reject("example.com", existing = listOf(ManualRule("Example.COM.", MatchScope.SUBDOMAIN)))
        )
    }

    @Test
    fun theSameDomainInTheOtherScopeIsADifferentRule() {
        val existing = listOf(ManualRule("example.com", MatchScope.SUBDOMAIN))

        assertEquals(
            ManualRuleDraft.Result.Accepted(ManualRule("example.com", MatchScope.EXACT)),
            ManualRuleDraft.parse("example.com", MatchScope.EXACT, existing)
        )
    }

    @Test
    fun searchIsCaseInsensitiveAndKeepsTheListOrder() {
        val ads = ManualRule("ads.example.com", MatchScope.SUBDOMAIN)
        val tracker = ManualRule("tracker.net", MatchScope.EXACT)
        val org = ManualRule("Example.org", MatchScope.SUBDOMAIN)
        val rules = listOf(ads, tracker, org)

        assertEquals(listOf(ads, org), ManualRuleDraft.search(rules, "EXAMPLE"))
        assertEquals(listOf(ads), ManualRuleDraft.search(rules, "ads.ex"))
        assertEquals(listOf(org), ManualRuleDraft.search(rules, "example.org"))
        assertEquals(emptyList<ManualRule>(), ManualRuleDraft.search(rules, "nothing"))
    }

    @Test
    fun aBlankQueryShowsEveryRule() {
        val rules = listOf(
            ManualRule("ads.example.com"),
            ManualRule("tracker.net", MatchScope.EXACT)
        )

        assertEquals(rules, ManualRuleDraft.search(rules, ""))
        assertEquals(rules, ManualRuleDraft.search(rules, "   "))
    }

    @Test
    fun aQueryIsTrimmedBeforeItIsMatched() {
        val ads = ManualRule("ads.example.com")

        assertEquals(listOf(ads), ManualRuleDraft.search(listOf(ads), " example "))
    }

    /** Parses [raw] and asserts it was refused, returning the reason it was refused for. */
    private fun reject(
        raw: String,
        scope: MatchScope = MatchScope.SUBDOMAIN,
        existing: List<ManualRule> = emptyList()
    ): ManualRuleDraft.Rejection {
        val result = ManualRuleDraft.parse(raw, scope, existing)
        assertTrue("expected '$raw' to be refused, but got $result", result is ManualRuleDraft.Result.Rejected)
        return (result as ManualRuleDraft.Result.Rejected).reason
    }
}
