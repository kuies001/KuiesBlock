package tw.kuies.block

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tw.kuies.block.filter.FilterAssembler
import tw.kuies.block.filter.FilterCoordinator
import tw.kuies.block.filter.FilterMode
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterRepository
import tw.kuies.block.filter.FilterSettings
import tw.kuies.block.filter.FilterSettingsStore
import tw.kuies.block.filter.FilterStatus
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.ManualRule
import tw.kuies.block.filter.MatchScope
import tw.kuies.block.filter.SubscriptionContentStore
import tw.kuies.block.filter.SubscriptionFailure
import tw.kuies.block.filter.SubscriptionFetchRequest
import tw.kuies.block.filter.SubscriptionFetchResult
import tw.kuies.block.filter.SubscriptionOrigin
import tw.kuies.block.filter.SubscriptionSource
import tw.kuies.block.filter.SubscriptionState
import tw.kuies.block.filter.SubscriptionUpdater

/**
 * The wiring that makes the local filter actually take effect: no rule published and nothing
 * downloaded until the user chooses a strength, one download when they do, and the stored list kept
 * afterwards.
 */
class FilterCoordinatorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val source = FakeSubscriptionSource()
    private val store = FilterSettingsStore(FakeSharedPreferences())
    private val sameThread = Executor { it.run() }
    private lateinit var contentStore: SubscriptionContentStore
    private lateinit var repository: FilterRepository

    @Before
    fun setUp() {
        contentStore = SubscriptionContentStore.forFilesDir(folder.newFolder("files"))
        repository = FilterRepository()
    }

    private fun coordinator() = FilterCoordinator(
        store = store,
        updater = SubscriptionUpdater(
            source = source,
            contentStore = contentStore,
            dispatcher = sameThread
        ),
        assembler = FilterAssembler(repository, contentStore),
        contentStore = contentStore,
        dispatcher = sameThread
    )

    /** A user-added source, as `SubscriptionDraft` would hand one over. */
    private val customSource = FilterSubscription(
        id = "user.test",
        displayName = "My list",
        url = "https://lists.example.com/ads.txt",
        origin = SubscriptionOrigin.USER,
        state = SubscriptionState.NOT_READY
    )

    @Test
    fun aManualBlockRuleIsStoredButNoLongerReachesTheSnapshot() {
        source.body = "||ads.example^\n"
        val coordinator = coordinator()
        coordinator.selectStrength(FilterProfile.STANDARD)
        val downloads = source.requests.size

        val seen = mutableListOf<FilterStatus>()
        coordinator.addManualBlock(ManualRule("Tracker.Example", MatchScope.SUBDOMAIN)) { seen.add(it) }

        assertEquals("a manual rule must never trigger a download", downloads, source.requests.size)
        assertEquals("the removed editor must not leave hidden rules active", 0, repository.snapshot().userRuleCount)
        assertEquals(0, seen.last().manualRuleCount)
        assertEquals(1, store.load().manualBlock.size)
    }

    @Test
    fun aStoredManualRuleSurvivesARestartWithoutAffectingTheSnapshot() {
        val coordinator = coordinator()
        coordinator.selectStrength(FilterProfile.STANDARD)
        coordinator.addManualAllow(ManualRule("ads.example", MatchScope.EXACT))

        // A second coordinator reads the persisted settings, as a restart would.
        coordinator().publishCurrent()

        assertEquals(1, store.load().manualAllow.size)
        assertEquals(0, repository.snapshot().userRuleCount)

        coordinator.removeManualAllow("ADS.example")
        assertEquals(0, store.load().manualAllow.size)
    }

    @Test
    fun aCustomSourceContributesNothingUntilItIsDownloaded() {
        val coordinator = coordinator()
        coordinator.selectMode(FilterMode.CUSTOM)
        val baseline = repository.snapshot().subscriptionRuleCount

        coordinator.addSubscription(customSource)

        assertEquals("an undownloaded source must add no rules", baseline, repository.snapshot().subscriptionRuleCount)
        assertEquals(2, store.load().subscriptions.size)
    }

    @Test
    fun updatingACustomSourcePublishesItsRulesAndDisablingRemovesThemAgain() {
        source.body = "||builtin.example^\n"
        val coordinator = coordinator()
        coordinator.selectMode(FilterMode.CUSTOM)
        val baseline = repository.snapshot().subscriptionRuleCount
        // The custom source downloads a *different* list: two sources contributing the same domain
        // would collapse into one published rule, and the counts would then say nothing about the
        // wiring under test.
        source.body = "||custom.example^\n"

        coordinator.addSubscription(customSource)
        val seen = mutableListOf<FilterStatus>()
        coordinator.updateSubscription(customSource) { seen.add(it) }

        assertEquals(baseline + 1, repository.snapshot().subscriptionRuleCount)
        assertEquals(baseline + 1, seen.last().effectiveRuleCount)

        coordinator.setSubscriptionEnabled(store.load().subscriptions.last(), enabled = false)
        assertEquals(
            "disabling a source must drop its rules from the very next snapshot",
            baseline,
            repository.snapshot().subscriptionRuleCount
        )
    }

    @Test
    fun removingACustomSourceDeletesItsStoredListAndItsRules() {
        source.body = "||builtin.example^\n"
        val coordinator = coordinator()
        coordinator.selectMode(FilterMode.CUSTOM)
        val baseline = repository.snapshot().subscriptionRuleCount
        source.body = "||custom.example^\n"
        coordinator.addSubscription(customSource)
        coordinator.updateSubscription(customSource)
        assertTrue("the download must be on disk first", contentStore.exists(customSource.id))
        assertEquals(baseline + 1, repository.snapshot().subscriptionRuleCount)

        coordinator.removeSubscription(customSource.id)

        assertFalse("the stored list must go with the entry", contentStore.exists(customSource.id))
        assertEquals(1, store.load().subscriptions.size)
        assertEquals(
            "its rules must leave the published snapshot too",
            baseline,
            repository.snapshot().subscriptionRuleCount
        )
    }

    @Test
    fun aFreshInstallPublishesNothingAndDownloadsNothing() {
        val status = coordinator().publishCurrent()

        // "Configured" is no longer an on/off: there is no 關閉 choice to store, so a fresh install
        // reads as configured while having nothing to apply. What it must not do is fetch or publish
        // anything — that is what the two assertions below pin down.
        assertTrue(status.enabled)
        assertTrue("no rules may be published before the user chooses", repository.snapshot().isEmpty)
        assertTrue("nothing may be downloaded before the user chooses", source.requests.isEmpty())
    }

    /**
     * The migration decided when the 關閉 option was removed (2026-10-16, option A): an install that
     * had stored `enabled=false` keeps its tier and its downloaded list, and reads as configured — but
     * nothing here starts protection. Only the home switch starts the tunnel (`KuiesVpnService` is
     * started from the UI and nowhere else), so publishing is the most this path may do.
     */
    @Test
    fun aLegacyStoredOffInstallIsConfiguredWithoutDownloadingOrStartingAnything() {
        contentStore.write(FilterSubscription.PROFILE_SUBSCRIPTION_ID, "||ads.example^\n")
        store.save(
            FilterSettings.forProfile(FilterProfile.STANDARD)
                .withEnabled(false)
                .withSubscription(
                    FilterSubscription.forProfile(FilterProfile.STANDARD).copy(
                        state = SubscriptionState.READY,
                        ruleCount = 1
                    )
                )
        )

        val status = coordinator().publishCurrent()

        assertEquals("the chosen tier survives the upgrade", FilterProfile.STANDARD, status.profile)
        assertTrue("a legacy 關閉 migrates to configured", status.enabled)
        assertEquals("the stored list stays applied, ready for the switch", 1, status.effectiveRuleCount)
        assertTrue("the migration must not download anything on its own", source.requests.isEmpty())
    }

    @Test
    fun choosingAStrengthDownloadsThatTiersListAndPublishesTheRules() {
        source.body = "||ads.example^\n||tracker.example^\n"

        val seen = mutableListOf<FilterStatus>()
        coordinator().selectStrength(FilterProfile.STANDARD, onStatus = { seen.add(it) })

        assertEquals(1, source.requests.size)
        assertEquals(FilterProfile.STANDARD.primaryUrl, source.requests.single().url)

        val settled = seen.last()
        assertTrue(settled.enabled)
        assertEquals(2, settled.effectiveRuleCount)
        assertEquals(2, repository.snapshot().subscriptionRuleCount)
    }

    @Test
    fun theChoiceSurvivesARestartWithoutDownloadingAgain() {
        source.body = "||ads.example^\n"
        coordinator().selectStrength(FilterProfile.STRICT)

        val status = coordinator().publishCurrent()

        assertTrue(status.enabled)
        assertEquals(FilterProfile.STRICT, status.profile)
        assertEquals("the stored list must be applied without a second download", 1, status.effectiveRuleCount)
        assertEquals(1, source.requests.size)
    }

    /**
     * Turning protection off and on again is the home switch's business and never touches these
     * settings: it stops and starts the tunnel, and nothing in the filter path is keyed on that. So
     * the tier the user chose, and the list already downloaded for it, must still be there afterwards
     * — with no second download.
     */
    @Test
    fun theChosenStrengthSurvivesProtectionBeingSwitchedOffAndOnAgain() {
        source.body = "||ads.example^\n"
        coordinator().selectStrength(FilterProfile.STRICT)

        // A second coordinator reading the store is what the app does after the switch went off and
        // on again: nothing about the protection state is stored here.
        val status = coordinator().publishCurrent()

        assertEquals("the chosen tier must not change", FilterProfile.STRICT, status.profile)
        assertTrue("the filter stays configured; the home switch owns protection", status.enabled)
        assertEquals(
            "the stored list must be applied again without a download",
            1,
            status.effectiveRuleCount
        )
        assertTrue(
            "the downloaded list stays on disk",
            contentStore.exists(FilterSubscription.PROFILE_SUBSCRIPTION_ID)
        )
        assertEquals("switching protection off and on must not download again", 1, source.requests.size)
    }

    @Test
    fun aFailedFirstDownloadStillRecordsTheChoiceButPublishesNoRules() {
        source.failure = SubscriptionFailure.CONNECT_FAILED

        val seen = mutableListOf<FilterStatus>()
        coordinator().selectStrength(FilterProfile.STANDARD, onStatus = { seen.add(it) })

        assertTrue("the user's choice must survive a failed download", seen.last().enabled)
        assertFalse(seen.last().hasUsableRules)
        assertTrue(repository.snapshot().isEmpty)
    }

    @Test
    fun aFailedUpdateKeepsTheRulesThatWereAlreadyStored() {
        source.body = "||ads.example^\n"
        val coordinator = coordinator()
        coordinator.selectStrength(FilterProfile.STANDARD)

        source.body = null
        source.failure = SubscriptionFailure.IO
        coordinator.refresh()

        assertEquals(
            "a failed update must not remove the protection the user already has",
            1,
            repository.snapshot().subscriptionRuleCount
        )
    }

    @Test
    fun theAssemblerPublishesNothingWhileTheMasterSwitchIsOff() {
        contentStore.write(FilterSubscription.PROFILE_SUBSCRIPTION_ID, "||ads.example^\n")
        val ready = FilterSettings.forProfile(FilterProfile.STANDARD).withSubscription(
            FilterSubscription.forProfile(FilterProfile.STANDARD).copy(
                state = SubscriptionState.READY,
                ruleCount = 1
            )
        )
        val assembler = FilterAssembler(repository, contentStore)

        // `forProfile` is the "user chose this tier" factory, so it is already switched on; the
        // contract under test is that the master switch alone decides whether rules are published.
        assertTrue(assembler.assemble(ready.withEnabled(false)).isEmpty)
        assertEquals(1, assembler.assemble(ready.withEnabled(true)).subscriptionRuleCount)
    }
}

/** A source that answers from a script and records every request it was asked to make. */
private class FakeSubscriptionSource : SubscriptionSource {

    var body: String? = null
    var failure: SubscriptionFailure? = null
    val requests = mutableListOf<SubscriptionFetchRequest>()

    override fun fetch(request: SubscriptionFetchRequest): SubscriptionFetchResult {
        requests.add(request)
        failure?.let { return SubscriptionFetchResult.Failure(it) }
        val text = body ?: return SubscriptionFetchResult.Failure(SubscriptionFailure.EMPTY_BODY)
        return SubscriptionFetchResult.Success(
            text = text,
            byteCount = text.length.toLong(),
            etag = "\"v1\""
        )
    }
}
