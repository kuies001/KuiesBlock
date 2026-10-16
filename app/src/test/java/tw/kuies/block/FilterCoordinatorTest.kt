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
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterRepository
import tw.kuies.block.filter.FilterSettings
import tw.kuies.block.filter.FilterSettingsStore
import tw.kuies.block.filter.FilterStatus
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.SubscriptionContentStore
import tw.kuies.block.filter.SubscriptionFailure
import tw.kuies.block.filter.SubscriptionFetchRequest
import tw.kuies.block.filter.SubscriptionFetchResult
import tw.kuies.block.filter.SubscriptionSource
import tw.kuies.block.filter.SubscriptionState
import tw.kuies.block.filter.SubscriptionUpdater

/**
 * The wiring that makes the local filter actually take effect: nothing at all until the user chooses
 * a strength, one download when they do, and the stored list kept afterwards.
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
        dispatcher = sameThread
    )

    @Test
    fun aFreshInstallPublishesNothingAndDownloadsNothing() {
        val status = coordinator().publishCurrent()

        assertFalse(status.enabled)
        assertTrue("no rules may be published before the user chooses", repository.snapshot().isEmpty)
        assertTrue("nothing may be downloaded before the user chooses", source.requests.isEmpty())
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

    @Test
    fun switchingOffStopsPublishingAndKeepsTheStoredList() {
        source.body = "||ads.example^\n"
        val coordinator = coordinator()
        coordinator.selectStrength(FilterProfile.STANDARD)

        val seen = mutableListOf<FilterStatus>()
        coordinator.selectStrength(null, onStatus = { seen.add(it) })

        assertFalse(seen.last().enabled)
        assertTrue("switching off must publish no rules", repository.snapshot().isEmpty)
        assertTrue(
            "the downloaded list stays on disk so switching back on needs no download",
            contentStore.exists(FilterSubscription.PROFILE_SUBSCRIPTION_ID)
        )
        assertFalse(store.load().enabled)
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
