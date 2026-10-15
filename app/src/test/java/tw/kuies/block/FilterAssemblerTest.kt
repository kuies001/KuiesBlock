package tw.kuies.block

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tw.kuies.block.filter.FilterAssembler
import tw.kuies.block.filter.FilterDecision
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterRepository
import tw.kuies.block.filter.FilterSettings
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.ManualRule
import tw.kuies.block.filter.MatchScope
import tw.kuies.block.filter.SubscriptionContentStore
import tw.kuies.block.filter.SubscriptionOrigin
import tw.kuies.block.filter.SubscriptionState

class FilterAssemblerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var contentStore: SubscriptionContentStore
    private lateinit var repository: FilterRepository
    private lateinit var assembler: FilterAssembler

    private val relaxedOnly = "||relaxed-only.example^\n"
    private val strictOnly = "||strict-only.example^\n"

    private val customSource = FilterSubscription(
        id = "mine",
        displayName = "My list",
        url = "https://lists.example.com/ads.txt",
        origin = SubscriptionOrigin.USER
    )

    @Before
    fun setUp() {
        contentStore = SubscriptionContentStore(
            File(temporaryFolder.root, SubscriptionContentStore.DIRECTORY_NAME)
        )
        repository = FilterRepository()
        assembler = FilterAssembler(repository, contentStore)
    }

    private fun mainList(
        profile: FilterProfile,
        text: String,
        ruleCount: Int = text.count { it == '\n' },
        state: SubscriptionState = SubscriptionState.READY,
        enabled: Boolean = true
    ): FilterSettings {
        val subscription = FilterSubscription.forProfile(profile).copy(
            state = state,
            enabled = enabled,
            ruleCount = ruleCount
        )
        contentStore.write(subscription.id, text)
        return FilterSettings.forProfile(profile).withSubscription(subscription)
    }

    private fun withCustom(
        settings: FilterSettings,
        text: String,
        state: SubscriptionState = SubscriptionState.READY,
        enabled: Boolean = true
    ): FilterSettings {
        contentStore.write(customSource.id, text)
        return settings.withSubscription(
            customSource.copy(
                state = state,
                enabled = enabled,
                ruleCount = text.count { it == '\n' }
            )
        )
    }

    @Test
    fun aDownloadedMainListIsPublished() {
        val snapshot = assembler.assemble(mainList(FilterProfile.RELAXED, relaxedOnly))

        assertEquals(FilterDecision.BLOCK, snapshot.decide("relaxed-only.example"))
        assertEquals(FilterDecision.BLOCK, snapshot.decide("sub.relaxed-only.example"))
        assertEquals(FilterDecision.UPSTREAM, snapshot.decide("strict-only.example"))
        assertEquals(1, snapshot.subscriptionRuleCount)
    }

    @Test
    fun aDisabledSubscriptionContributesNoRules() {
        val disabled = withCustom(
            FilterSettings.forProfile(FilterProfile.STANDARD),
            relaxedOnly,
            enabled = false
        )

        assertTrue(assembler.assemble(disabled).isEmpty)

        val enabled = disabled.withSubscription(
            disabled.customSubscriptions.single().copy(enabled = true)
        )
        assertEquals(FilterDecision.BLOCK, assembler.assemble(enabled).decide("relaxed-only.example"))
    }

    @Test
    fun aSubscriptionThatWasNeverDownloadedContributesNoRules() {
        val subscription = customSource.copy(state = SubscriptionState.NOT_READY, ruleCount = 0)
        val settings = FilterSettings.forProfile(FilterProfile.STANDARD).withSubscription(subscription)

        assertTrue(assembler.assemble(settings).isEmpty)
    }

    @Test
    fun aSubscriptionWhoseStoredTextDisappearedContributesNoRules() {
        val settings = withCustom(mainList(FilterProfile.STANDARD, strictOnly), relaxedOnly)
        assertTrue(contentStore.delete(customSource.id))

        val snapshot = assembler.assemble(settings)

        assertEquals(FilterDecision.UPSTREAM, snapshot.decide("relaxed-only.example"))
        assertEquals(FilterDecision.BLOCK, snapshot.decide("strict-only.example"))
    }

    @Test
    fun storedContentLongerThanTheParserAcceptsIsNotPartlyPublished() {
        val long = (1..10).joinToString("") { "||host$it.example^\n" }
        contentStore.write(customSource.id, long)
        val tight = FilterAssembler(repository, contentStore, maxRules = 4)
        val settings = FilterSettings.forProfile(FilterProfile.STANDARD).withSubscription(
            customSource.copy(state = SubscriptionState.READY, ruleCount = 10)
        )

        val snapshot = tight.assemble(settings)

        assertTrue("a truncated list must not become a partial rule set", snapshot.isEmpty)
    }

    @Test
    fun theThreeTiersAreNeverCombined() {
        val relaxed = mainList(FilterProfile.RELAXED, relaxedOnly)
        val published = assembler.assemble(relaxed)
        assertEquals(FilterDecision.BLOCK, published.decide("relaxed-only.example"))

        // Switching the tier clears the main list's state, so the file still holding the previous
        // tier's text must contribute nothing at all.
        val switched = relaxed.withProfile(FilterProfile.STRICT)
        val afterSwitch = assembler.assemble(switched)
        assertTrue("the previous tier's list must not stay in effect", afterSwitch.isEmpty)
        assertEquals(FilterDecision.UPSTREAM, afterSwitch.decide("relaxed-only.example"))

        // Once the new tier has been downloaded, only its own rules are in effect.
        val downloaded = mainList(FilterProfile.STRICT, strictOnly)
        val afterDownload = assembler.assemble(downloaded)
        assertEquals(FilterDecision.BLOCK, afterDownload.decide("strict-only.example"))
        assertEquals(
            "the previous tier's domains must not be blocked after the switch",
            FilterDecision.UPSTREAM,
            afterDownload.decide("relaxed-only.example")
        )
        assertEquals(1, afterDownload.subscriptionRuleCount)
    }

    @Test
    fun manualRulesReachTheSnapshotWithTheirOwnScope() {
        val settings = mainList(FilterProfile.STANDARD, strictOnly)
            .addManualAllow(ManualRule("allowed.example", MatchScope.EXACT))
            .addManualAllow(ManualRule("family.example", MatchScope.SUBDOMAIN))
            .addManualBlock(ManualRule("blocked.example", MatchScope.EXACT))
            .addManualBlock(ManualRule("ads.example", MatchScope.SUBDOMAIN))

        val snapshot = assembler.assemble(settings)

        assertEquals(FilterDecision.ALLOW, snapshot.decide("allowed.example"))
        assertEquals(
            "an exact allow must not cover a subdomain",
            FilterDecision.UPSTREAM,
            snapshot.decide("sub.allowed.example")
        )
        assertEquals(FilterDecision.ALLOW, snapshot.decide("sub.family.example"))
        assertEquals(FilterDecision.BLOCK, snapshot.decide("blocked.example"))
        assertEquals(
            "an exact block must not cover a subdomain",
            FilterDecision.UPSTREAM,
            snapshot.decide("sub.blocked.example")
        )
        assertEquals(FilterDecision.BLOCK, snapshot.decide("sub.ads.example"))
        assertEquals(4, snapshot.userRuleCount)
    }

    @Test
    fun aManualAllowWinsOverASubscriptionBlock() {
        val blocked = "||allowed.example^\n||other.example^\n"
        val settings = mainList(FilterProfile.STANDARD, blocked)
            .addManualAllow(ManualRule("allowed.example", MatchScope.SUBDOMAIN))

        val snapshot = assembler.assemble(settings)

        assertEquals(FilterDecision.ALLOW, snapshot.decide("allowed.example"))
        assertEquals(FilterDecision.BLOCK, snapshot.decide("other.example"))
    }

    @Test
    fun aUserSourceAndTheMainListAreBothInEffect() {
        val settings = withCustom(mainList(FilterProfile.STANDARD, strictOnly), relaxedOnly)

        val snapshot = assembler.assemble(settings)

        assertEquals(FilterDecision.BLOCK, snapshot.decide("strict-only.example"))
        assertEquals(FilterDecision.BLOCK, snapshot.decide("relaxed-only.example"))
        assertEquals(2, snapshot.subscriptionRuleCount)
    }

    @Test
    fun aUserSourceCannotClaimTheMainListSlot() {
        val impostor = FilterSubscription(
            id = FilterSubscription.PROFILE_SUBSCRIPTION_ID,
            displayName = "Not a tier",
            url = "https://evil.example/list.txt",
            origin = SubscriptionOrigin.USER,
            state = SubscriptionState.READY,
            ruleCount = 1
        )
        contentStore.write(impostor.id, "||evil.example^\n")
        val settings = FilterSettings.forProfile(FilterProfile.STANDARD)
            .withSubscription(impostor)

        val snapshot = assembler.assemble(settings)

        assertEquals(FilterDecision.UPSTREAM, snapshot.decide("evil.example"))
        assertTrue(snapshot.isEmpty)
    }

    @Test
    fun handingOverAnotherTierAsTheMainListDoesNotSwitchTheActiveTier() {
        val stale = FilterSubscription.forProfile(FilterProfile.RELAXED).copy(
            state = SubscriptionState.READY,
            ruleCount = 1
        )
        contentStore.write(FilterSubscription.PROFILE_SUBSCRIPTION_ID, relaxedOnly)

        val settings = FilterSettings.forProfile(FilterProfile.STANDARD).withSubscription(stale)
        val main = requireNotNull(settings.builtInSubscription)

        assertEquals(FilterProfile.STANDARD.primaryUrl, main.url)
        assertEquals(
            "a copy describing another tier's text cannot be marked ready",
            SubscriptionState.NOT_READY,
            main.state
        )
        assertEquals(0, main.ruleCount)
        assertTrue(assembler.assemble(settings).isEmpty)
    }

    @Test
    fun aHandBuiltSnapshotOfTheWrongTierIsStillBlockedByTheUrlGuard() {
        val crafted = FilterSettings(
            profile = FilterProfile.STANDARD,
            subscriptions = listOf(
                FilterSubscription.forProfile(FilterProfile.RELAXED).copy(
                    state = SubscriptionState.READY,
                    ruleCount = 1
                )
            )
        )
        contentStore.write(FilterSubscription.PROFILE_SUBSCRIPTION_ID, relaxedOnly)

        val snapshot = assembler.assemble(crafted)

        assertTrue("only the active tier's own list may contribute", snapshot.isEmpty)
    }

    @Test
    fun theRepositoryKeepsTheLatestSnapshotAndItsVersion() {
        val first = assembler.assemble(mainList(FilterProfile.STANDARD, strictOnly))
        val second = assembler.assemble(mainList(FilterProfile.STANDARD, strictOnly))

        assertTrue(second.version > first.version)
        assertTrue(second === repository.snapshot())
    }
}
