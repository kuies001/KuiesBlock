package tw.kuies.block

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tw.kuies.block.filter.FilterAssembler
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterRepository
import tw.kuies.block.filter.FilterSettings
import tw.kuies.block.filter.FilterStatus
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.ManualRule
import tw.kuies.block.filter.MatchScope
import tw.kuies.block.filter.SubscriptionContentStore
import tw.kuies.block.filter.SubscriptionOrigin
import tw.kuies.block.filter.SubscriptionState

class FilterStatusTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var contentStore: SubscriptionContentStore
    private lateinit var repository: FilterRepository
    private lateinit var assembler: FilterAssembler

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

    @Test
    fun theStatusDescribesEverySourceAndWhatIsReallyInEffect() {
        val main = FilterSubscription.forProfile(FilterProfile.STRICT).copy(
            state = SubscriptionState.READY,
            ruleCount = 3,
            lastSuccessAtMillis = 1_700_000_000_000L
        )
        contentStore.write(main.id, "||one.example^\n||two.example^\n||three.example^\n")
        val custom = customSource.copy(
            state = SubscriptionState.FAILED,
            ruleCount = 1,
            lastSuccessAtMillis = 1_699_000_000_000L,
            lastError = "network error"
        )
        contentStore.write(custom.id, "||four.example^\n")
        val settings = FilterSettings.forProfile(FilterProfile.STRICT)
            .withSubscription(main)
            .withSubscription(custom)
            .withManualBlock(
                listOf(
                    ManualRule("manual.example", MatchScope.EXACT),
                    ManualRule("invalid domain", MatchScope.SUBDOMAIN)
                )
            )

        val snapshot = assembler.assemble(settings)
        val status = FilterStatus.of(settings, snapshot)

        assertEquals(FilterProfile.STRICT, status.profile)
        assertEquals(FilterProfile.STRICT.listName, status.profileListName)
        assertEquals(snapshot.version, status.snapshotVersion)
        assertEquals(2, status.subscriptions.size)

        val mainStatus = status.subscriptions.first { it.id == main.id }
        assertEquals("HaGeZi Multi PRO++", mainStatus.displayName)
        assertEquals(SubscriptionOrigin.BUILT_IN, mainStatus.origin)
        assertTrue(mainStatus.enabled)
        assertEquals(SubscriptionState.READY, mainStatus.state)
        assertEquals(3, mainStatus.ruleCount)
        assertEquals(1_700_000_000_000L, mainStatus.lastSuccessAtMillis ?: 0L)
        assertNull(mainStatus.lastError)

        val customStatus = status.subscriptions.first { it.id == custom.id }
        assertEquals("My list", customStatus.displayName)
        assertEquals(SubscriptionOrigin.USER, customStatus.origin)
        assertEquals(SubscriptionState.FAILED, customStatus.state)
        assertEquals(1, customStatus.ruleCount)
        assertEquals(1_699_000_000_000L, customStatus.lastSuccessAtMillis ?: 0L)
        assertEquals("network error", customStatus.lastError)

        assertEquals(4, status.subscriptionRuleCount)
        assertEquals("an unusable manual rule must not be counted", 1, status.manualRuleCount)
        assertEquals(5, status.effectiveRuleCount)
        assertTrue(status.hasUsableRules)
    }

    @Test
    fun withNothingDownloadedTheStatusSaysThereAreNoUsableRules() {
        val settings = FilterSettings.forProfile(FilterProfile.STANDARD)

        val status = FilterStatus.of(settings, assembler.assemble(settings))

        assertEquals(0, status.subscriptionRuleCount)
        assertEquals(0, status.manualRuleCount)
        assertEquals(0, status.effectiveRuleCount)
        assertFalse("an empty rule set must never be presented as working", status.hasUsableRules)
        assertEquals(1, status.subscriptions.size)
        assertEquals(SubscriptionState.NOT_READY, status.subscriptions.single().state)
    }

    @Test
    fun manualRulesAloneStillCountAsUsable() {
        val settings = FilterSettings.forProfile(FilterProfile.STANDARD)
            .withManualBlock(listOf(ManualRule("blocked.example", MatchScope.SUBDOMAIN)))

        val status = FilterStatus.of(settings, assembler.assemble(settings))

        assertEquals(0, status.subscriptionRuleCount)
        assertEquals(1, status.manualRuleCount)
        assertEquals(1, status.effectiveRuleCount)
        assertTrue(status.hasUsableRules)
    }
}
