package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.SubscriptionContentStore
import tw.kuies.block.filter.SubscriptionDraft
import tw.kuies.block.filter.SubscriptionOrigin
import tw.kuies.block.filter.SubscriptionState

/**
 * Turning what the user typed into a subscription source.
 *
 * The property that matters most is not in the wording: the id becomes a file name under
 * `<filesDir>/filters` ([SubscriptionContentStore]), so this test asserts that a generated id is
 * accepted by that store's own guard, that it never contains a path separator or `..`, and that the
 * display name can never change it.
 */
class SubscriptionDraftTest {

    @Test
    fun anHttpsUrlIsAcceptedAsAUserSourceThatIsNotReadyYet() {
        val accepted = accept("https://lists.example.com/adblock.txt", "My list")

        assertEquals(SubscriptionOrigin.USER, accepted.origin)
        assertEquals(SubscriptionState.NOT_READY, accepted.state)
        // Nothing has been downloaded, so nothing may claim a rule count or a success time.
        assertEquals(0, accepted.ruleCount)
        assertEquals(null, accepted.lastSuccessAtMillis)
        assertEquals(null, accepted.lastError)
        assertTrue(accepted.enabled)
        assertEquals("My list", accepted.displayName)
        assertEquals("https://lists.example.com/adblock.txt", accepted.url)
    }

    @Test
    fun theGeneratedIdIsSafeAsAFileName() {
        val id = accept("https://lists.example.com/adblock.txt", "My list").id

        // The store builds `<safeId>.list`; an id it refuses would be a subscription that can never
        // be stored, so this is asserted against the store's own guard rather than a local copy.
        assertEquals(id, SubscriptionContentStore.safeId(id))
        assertNotNull(SubscriptionContentStore.forFilesDir(java.io.File("files")).fileFor(id))

        assertTrue(id, id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" })
        assertFalse("no path separator may survive: $id", id.contains('/'))
        assertFalse("no path separator may survive: $id", id.contains('\\'))
        assertFalse("no parent directory may survive: $id", id.contains(".."))
        assertFalse("no leading dot: $id", id.startsWith('.'))
        assertTrue("the id must fit the store's 64-character bound: $id", id.length <= 64)
        assertTrue("the id must not be blank", id.isNotBlank())
    }

    @Test
    fun theIdIsDeterministicFromTheUrlAndTheNameCannotInfluenceIt() {
        val first = accept("https://lists.example.com/adblock.txt", "Name one")
        val sameUrlOtherName = accept("https://lists.example.com/adblock.txt", "Name two")
        val otherUrl = accept("https://other.example.com/adblock.txt", "Name one")

        assertEquals(first.id, sameUrlOtherName.id)
        assertNotEquals(first.id, otherUrl.id)
    }

    @Test
    fun aHostDifferingOnlyInCaseIsTheSameId() {
        val lower = accept("https://lists.example.com/adblock.txt", "A")
        val mixed = accept("https://Lists.Example.COM/adblock.txt", "A")

        assertEquals(lower.id, mixed.id)
        assertEquals(lower.url, mixed.url)
    }

    @Test
    fun theNameIsSanitisedAndBlankNamesFallBackToTheHost() {
        assertEquals("lists.example.com", accept("https://lists.example.com/x.txt", "").displayName)
        assertEquals("lists.example.com", accept("https://lists.example.com/x.txt", "   ").displayName)
        assertEquals("My list", accept("https://lists.example.com/x.txt", "  My list  ").displayName)
        // A name with a line break or a control character must not become a broken or multi-line row.
        assertEquals(
            "Two words",
            accept("https://lists.example.com/x.txt", "Two\nwords\u0007").displayName
        )
        assertEquals(
            SubscriptionDraft.MAX_NAME_LENGTH,
            accept("https://lists.example.com/x.txt", "n".repeat(500)).displayName.length
        )
    }

    @Test
    fun httpAndSchemeLessUrlsAreRejectedAndNeverOfferedAWeakerConnection() {
        assertEquals(
            SubscriptionDraft.Rejection.NOT_HTTPS,
            reject("http://lists.example.com/adblock.txt").reason
        )
        assertEquals(
            SubscriptionDraft.Rejection.NOT_HTTPS,
            reject("ftp://lists.example.com/adblock.txt").reason
        )
        assertEquals(
            SubscriptionDraft.Rejection.NOT_HTTPS,
            reject("lists.example.com/adblock.txt").reason
        )
        assertEquals(SubscriptionDraft.Rejection.EMPTY_URL, reject("").reason)
        assertEquals(SubscriptionDraft.Rejection.EMPTY_URL, reject("   ").reason)
    }

    @Test
    fun aPathAndAQueryAreLegitimateForASubscriptionUrl() {
        val accepted = accept("https://lists.example.com/sub/filters/adblock.txt?token=abc", "Path list")

        assertEquals("https://lists.example.com/sub/filters/adblock.txt?token=abc", accepted.url)
        // A path must not leak into the file name either.
        assertFalse(accepted.id, accepted.id.contains('/'))
        assertEquals(accepted.id, SubscriptionContentStore.safeId(accepted.id))
    }

    @Test
    fun localAndPrivateHostsAreRejected() {
        val refused = listOf(
            "https://localhost/adblock.txt",
            "https://lists.local/adblock.txt",
            "https://lists.internal/adblock.txt",
            "https://127.0.0.1/adblock.txt",
            "https://10.0.0.5/adblock.txt",
            "https://192.168.1.10/adblock.txt",
            "https://169.254.10.10/adblock.txt",
            "https://[::1]/adblock.txt"
        )

        for (url in refused) {
            assertEquals("$url must be refused", SubscriptionDraft.Rejection.BAD_HOST, reject(url).reason)
        }
    }

    @Test
    fun anOverLongUrlIsRejected() {
        val tooLong = "https://lists.example.com/" + "a".repeat(SubscriptionDraft.MAX_URL_LENGTH)

        assertEquals(SubscriptionDraft.Rejection.URL_TOO_LONG, reject(tooLong).reason)
    }

    @Test
    fun aDuplicateUrlIsRejected() {
        val existing = accept("https://lists.example.com/adblock.txt", "Already there")

        assertEquals(
            SubscriptionDraft.Rejection.DUPLICATE,
            SubscriptionDraft.parse(
                "https://lists.example.com/adblock.txt",
                "Second copy",
                listOf(existing)
            ).rejectedReason()
        )
        // The same URL typed in different case is still the same source: one list, not two.
        assertEquals(
            SubscriptionDraft.Rejection.DUPLICATE,
            SubscriptionDraft.parse(
                "https://Lists.Example.com/adblock.txt",
                "Second copy",
                listOf(existing)
            ).rejectedReason()
        )
    }

    @Test
    fun aDuplicateOfTheBuiltInProfileUrlIsRejected() {
        val builtIn = FilterSubscription.forProfile(
            tw.kuies.block.filter.FilterProfile.STANDARD
        )

        assertEquals(
            SubscriptionDraft.Rejection.DUPLICATE,
            SubscriptionDraft.parse(builtIn.url, "Same as built-in", listOf(builtIn)).rejectedReason()
        )
    }

    @Test
    fun theCountLimitAppliesToCustomSourcesAndTheBuiltInEntryNeverConsumesASlot() {
        val full = (1..SubscriptionDraft.MAX_SUBSCRIPTIONS).map { userSource(it) }

        assertEquals(
            SubscriptionDraft.Rejection.TOO_MANY,
            SubscriptionDraft.parse("https://extra.example.com/x.txt", "", full).rejectedReason()
        )

        // One slot short of the limit is still fine, and the built-in entry does not count against it.
        val almostFull = full.drop(1) + FilterSubscription.forProfile(tw.kuies.block.filter.FilterProfile.STANDARD)
        val accepted = SubscriptionDraft.parse("https://extra.example.com/x.txt", "", almostFull)
        assertTrue(accepted.toString(), accepted is SubscriptionDraft.Result.Accepted)
    }

    @Test
    fun theIdIsStableAcrossWhateverOrderTheSourcesWereAddedIn() {
        val one = accept("https://a.example.com/x.txt", "A")
        val two = accept("https://b.example.com/x.txt", "B")

        assertNotEquals(one.id, two.id)
        assertEquals(one.id, accept("https://a.example.com/x.txt", "A").id)
        // Re-adding B after A must still produce B's own id, not a positional one.
        assertEquals(two.id, accept("https://b.example.com/x.txt", "B").id)
    }

    private fun userSource(index: Int): FilterSubscription = FilterSubscription(
        id = "user.$index",
        displayName = "Source $index",
        url = "https://lists$index.example.com/adblock.txt",
        origin = SubscriptionOrigin.USER,
        state = SubscriptionState.READY,
        ruleCount = 5
    )

    private fun accept(url: String, name: String): FilterSubscription = when (
        val result = SubscriptionDraft.parse(url, name, emptyList())
    ) {
        is SubscriptionDraft.Result.Accepted -> result.subscription
        is SubscriptionDraft.Result.Rejected -> throw AssertionError("$url was rejected: ${result.reason}")
    }

    private fun reject(url: String): SubscriptionDraft.Result.Rejected = when (
        val result = SubscriptionDraft.parse(url, "", emptyList())
    ) {
        is SubscriptionDraft.Result.Accepted -> throw AssertionError("$url was accepted")
        is SubscriptionDraft.Result.Rejected -> result
    }

    private fun SubscriptionDraft.Result.rejectedReason(): SubscriptionDraft.Rejection = when (this) {
        is SubscriptionDraft.Result.Accepted -> throw AssertionError("accepted: ${subscription.id}")
        is SubscriptionDraft.Result.Rejected -> reason
    }
}
