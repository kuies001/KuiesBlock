package tw.kuies.block

import java.net.IDN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.filter.DomainName

class DomainNameTest {

    @Test
    fun namesBecomeLowercaseAndLoseTheTrailingDot() {
        assertEquals("example.com", DomainName.normalise("example.com"))
        assertEquals("example.com", DomainName.normalise("EXAMPLE.COM"))
        assertEquals("example.com", DomainName.normalise("Example.Com."))
        assertEquals("example.com", DomainName.normalise("  example.com  "))
    }

    @Test
    fun illegalNamesAreRejected() {
        assertNull(DomainName.normalise(""))
        assertNull(DomainName.normalise("   "))
        assertNull(DomainName.normalise("."))
        assertNull(DomainName.normalise("a..b"))
        assertNull(DomainName.normalise("-leading.com"))
        assertNull(DomainName.normalise("trailing-.com"))
        assertNull(DomainName.normalise("has space.com"))
        assertNull(DomainName.normalise("pipe|char.com"))
        assertNull(DomainName.normalise("caret^.com"))
        assertNull(DomainName.normalise("slash/.com"))
    }

    /** A single label is a legal DNS name (and a legitimate TLD-level block). */
    @Test
    fun aSingleLabelIsAccepted() {
        assertEquals("localhost", DomainName.normalise("localhost"))
        assertEquals("zip", DomainName.normalise("ZIP"))
    }

    @Test
    fun nonAsciiInputIsPunycodedAndStaysCaseInsensitive() {
        val lower = DomainName.normalise("münchen.de")
        assertNotNull(lower)
        assertEquals(lower, DomainName.normalise("MÜNCHEN.DE"))
        assertTrue("must be pure ASCII", lower!!.all { it.code < 0x80 })
        assertEquals(lower, lower.lowercase())
        assertEquals("münchen.de", IDN.toUnicode(lower))
    }

    @Test
    fun lengthLimitsAreEnforced() {
        val label63 = "a".repeat(63)
        val atLimit = "$label63.$label63.$label63." + "d".repeat(61)
        assertEquals(253, atLimit.length)
        assertNotNull(DomainName.normalise(atLimit))

        val overLimit = "$label63.$label63.$label63." + "d".repeat(62)
        assertEquals(254, overLimit.length)
        assertNull(DomainName.normalise(overLimit))

        assertNull("label longer than 63", DomainName.normalise("a".repeat(64) + ".com"))
    }

    @Test
    fun suffixMatchingRespectsTheLabelBoundary() {
        assertTrue(DomainName.isSubdomainOrSelf("a.b.example.com", "example.com"))
        assertTrue(DomainName.isSubdomainOrSelf("example.com", "example.com"))
        assertFalse("not a subdomain", DomainName.isSubdomainOrSelf("notexample.com", "example.com"))
        assertFalse("not a subdomain", DomainName.isSubdomainOrSelf("xexample.com", "example.com"))
        assertFalse("shorter name", DomainName.isSubdomainOrSelf("com", "example.com"))
        assertFalse("rule longer than name", DomainName.isSubdomainOrSelf("example.com", "a.example.com"))
    }

    @Test
    fun theSuffixChainWalksFromTheFullNameToTheLastLabel() {
        assertEquals(
            listOf("a.b.example.com", "b.example.com", "example.com", "com"),
            DomainName.suffixChain("a.b.example.com")
        )
        assertEquals(
            "the last label is part of the chain so a TLD-level rule can match",
            listOf("example.com", "com"),
            DomainName.suffixChain("example.com")
        )
        assertEquals(listOf("com"), DomainName.suffixChain("com"))
    }
}
