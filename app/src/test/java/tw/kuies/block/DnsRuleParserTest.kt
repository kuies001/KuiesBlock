package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.filter.DnsRuleParser
import tw.kuies.block.filter.RuleLine
import tw.kuies.block.filter.RuleRejection

class DnsRuleParserTest {

    private fun blocked(line: String) = (DnsRuleParser.classifyLine(line) as RuleLine.Blocked).domain
    private fun allowed(line: String) = (DnsRuleParser.classifyLine(line) as RuleLine.Allowed).domain
    private fun rejected(line: String) =
        (DnsRuleParser.classifyLine(line) as RuleLine.Rejected).reason

    // --- the shapes we support -------------------------------------------------------------

    @Test
    fun adblockStyleBlockRulesAreAccepted() {
        assertEquals("example.com", blocked("||example.com^"))
        assertEquals("missing anchor is tolerated", "example.com", blocked("||example.com"))
        assertEquals("example.com", blocked("||EXAMPLE.com^"))
        assertEquals("ads.tracker.example.com", blocked("||ads.tracker.example.com^"))
    }

    @Test
    fun adblockStyleExceptionRulesAreAccepted() {
        assertEquals("example.com", allowed("@@||example.com^"))
        assertEquals("example.com", allowed("@@||example.com"))
        assertEquals("shop.example.com", allowed("@@shop.example.com"))
    }

    @Test
    fun plainDomainListIsAccepted() {
        assertEquals("example.com", blocked("example.com"))
        assertEquals("example.com", blocked("example.com."))
        assertEquals("example.com", blocked("  example.com  "))
    }

    @Test
    fun hostsFormatIsAccepted() {
        assertEquals("example.com", blocked("0.0.0.0 example.com"))
        assertEquals("example.com", blocked("127.0.0.1 example.com"))
        assertEquals("example.com", blocked("::1 example.com"))
        assertEquals("ads.example.com", blocked("0.0.0.0\tads.example.com"))
    }

    @Test
    fun commentsAndBlankLinesAreNotRules() {
        assertEquals(RuleLine.Blank, DnsRuleParser.classifyLine(""))
        assertEquals(RuleLine.Blank, DnsRuleParser.classifyLine("   "))
        assertEquals(RuleLine.Comment, DnsRuleParser.classifyLine("! Title: something"))
        assertEquals(RuleLine.Comment, DnsRuleParser.classifyLine("[Adblock Plus]"))
        assertEquals(RuleLine.Comment, DnsRuleParser.classifyLine("   ! indented comment"))
    }

    // --- everything we do NOT support must be rejected, never misread as a domain -----------

    @Test
    fun unsupportedAdblockSyntaxIsRejectedWithAReason() {
        assertEquals(RuleRejection.MODIFIER, rejected("||example.com^\$third-party"))
        assertEquals(RuleRejection.MODIFIER, rejected("||example.com^\$important"))
        assertEquals(RuleRejection.WILDCARD, rejected("||*.example.com^"))
        assertEquals(RuleRejection.WILDCARD, rejected("*.example.com"))
        assertEquals(RuleRejection.REGEX, rejected("/ads[0-9]+/"))
        assertEquals("a URL is not a domain rule", RuleRejection.REGEX, rejected("http://example.com"))
        assertEquals(RuleRejection.CSS, rejected("example.com##.ad-banner"))
        assertEquals(RuleRejection.CSS, rejected("example.com#@#.ad"))
    }

    @Test
    fun malformedRulesAreRejected() {
        assertEquals(RuleRejection.INVALID_DOMAIN, rejected("||"))
        assertEquals(RuleRejection.INVALID_DOMAIN, rejected("@@"))
        assertEquals(RuleRejection.INVALID_DOMAIN, rejected("||exa^mple.com^"))
        assertEquals(RuleRejection.INVALID_DOMAIN, rejected("||example.com^|"))
        assertEquals("not a hosts block IP", RuleRejection.INVALID_DOMAIN, rejected("10.0.0.1 example.com"))
        assertEquals(RuleRejection.INVALID_DOMAIN, rejected("0.0.0.0 example.com extra"))
        assertEquals(RuleRejection.INVALID_DOMAIN, rejected("http:  example.com"))
    }

    @Test
    fun overLongInputIsRejectedRatherThanTruncated() {
        assertEquals(RuleRejection.TOO_LONG, rejected("a".repeat(DnsRuleParser.MAX_LINE_LENGTH + 1)))
        assertEquals(RuleRejection.TOO_LONG, rejected("||" + "a".repeat(300) + ".com^"))
    }

    // --- whole-file parsing ----------------------------------------------------------------

    @Test
    fun aListHeaderIsCountedAsComments() {
        // The text ends with a newline, so the parser sees a fourth, empty line; it is counted as a
        // blank rather than silently dropped.
        val parsed = DnsRuleParser.parse("[Adblock Plus]\n! Title: list\n||a.com^\n")
        assertEquals(4, parsed.stats.lines)
        assertEquals(1, parsed.stats.blanks)
        assertEquals(2, parsed.stats.comments)
        assertEquals(1, parsed.stats.blocked)
    }

    @Test
    fun duplicatesAreCountedAndCollapsed() {
        val parsed = DnsRuleParser.parse("||a.com^\n||a.com^\na.com\n||b.com^\n@@||a.com^\n")
        assertEquals(2, parsed.blocked.size)
        assertEquals(1, parsed.allowed.size)
        assertEquals("three block lines collapse to two rules", 2, parsed.stats.duplicates)
        assertTrue(parsed.blocked.contains("a.com"))
        assertTrue(parsed.blocked.contains("b.com"))
    }

    @Test
    fun statisticsSeparateEveryOutcome() {
        val text = listOf(
            "[Adblock Plus]",
            "! comment",
            "||a.com^",
            "",
            "0.0.0.0 b.com",
            "@@||a.com^",
            "||c.com^\$third-party",
            "/regex/"
        ).joinToString("\n")
        val parsed = DnsRuleParser.parse(text)
        assertEquals(8, parsed.stats.lines)
        assertEquals(2, parsed.stats.comments)
        assertEquals(1, parsed.stats.blanks)
        assertEquals(2, parsed.stats.blocked)
        assertEquals(1, parsed.stats.allowed)
        assertEquals(1, parsed.stats.rejectionCount(RuleRejection.MODIFIER))
        assertEquals(1, parsed.stats.rejectionCount(RuleRejection.REGEX))
        assertEquals(2, parsed.stats.rejected)
    }

    @Test
    fun parsingStopsAtTheRuleCapAndSaysSo() {
        val text = (1..10).joinToString("\n") { "||host$it.example.com^" }
        val parsed = DnsRuleParser.parse(text, maxRules = 3)
        assertTrue(parsed.stats.truncated)
        assertEquals(3, parsed.blocked.size)
    }

    @Test
    fun anEmptyListParsesToEmptySets() {
        val parsed = DnsRuleParser.parse("")
        assertTrue(parsed.blocked.isEmpty)
        assertTrue(parsed.allowed.isEmpty)
        assertFalse(parsed.stats.truncated)
    }

    /** The built-in lists are pure `||domain^`; canonical input must parse with nothing rejected. */
    @Test
    fun theBuiltInListShapeParsesWithoutRejections() {
        val text = buildString {
            append("[Adblock Plus]\n")
            append("! Title: HaGeZi's Multi NORMAL\n")
            append("! Homepage: https://github.com/hagezi/dns-blocklists\n")
            append("! License: https://github.com/hagezi/dns-blocklists/blob/main/LICENSE\n")
            repeat(1000) { append("||host$it.example.com^\n") }
        }
        val parsed = DnsRuleParser.parse(text)
        assertEquals(1000, parsed.stats.blocked)
        assertEquals(0, parsed.stats.rejected)
        assertEquals(0, parsed.stats.duplicates)
        assertFalse(parsed.stats.truncated)
    }
}
