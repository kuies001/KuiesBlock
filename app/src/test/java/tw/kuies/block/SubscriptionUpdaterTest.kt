package tw.kuies.block

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tw.kuies.block.filter.FilterAssembler
import tw.kuies.block.filter.FilterDecision
import tw.kuies.block.filter.FilterMode
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterRepository
import tw.kuies.block.filter.FilterSettings
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.SubscriptionContentStore
import tw.kuies.block.filter.SubscriptionFailure
import tw.kuies.block.filter.SubscriptionFetchRequest
import tw.kuies.block.filter.SubscriptionFetchResult
import tw.kuies.block.filter.SubscriptionOrigin
import tw.kuies.block.filter.SubscriptionSource
import tw.kuies.block.filter.SubscriptionState
import tw.kuies.block.filter.SubscriptionStateWriter
import tw.kuies.block.filter.SubscriptionUpdateRequest
import tw.kuies.block.filter.SubscriptionUpdateResult
import tw.kuies.block.filter.SubscriptionUpdater
import tw.kuies.block.filter.UpdateLimits

class SubscriptionUpdaterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val listText = "||ads.example^\n||tracker.example^\n"

    private val subscription = FilterSubscription(
        id = "mine",
        displayName = "My list",
        url = "https://lists.example.com/ads.txt",
        origin = SubscriptionOrigin.USER
    )

    private lateinit var contentStore: SubscriptionContentStore
    private lateinit var repository: FilterRepository
    private lateinit var assembler: FilterAssembler

    @Before
    fun setUp() {
        contentStore = SubscriptionContentStore(
            File(temporaryFolder.root, SubscriptionContentStore.DIRECTORY_NAME)
        )
        repository = FilterRepository()
        assembler = FilterAssembler(repository, contentStore)
    }

    private fun updater(
        source: SubscriptionSource,
        dispatcher: Executor = Executor { it.run() },
        onState: (String, FilterSubscription, String?) -> Unit = { _, _, _ -> },
        limits: UpdateLimits = UpdateLimits.DEFAULT,
        clock: () -> Long = System::currentTimeMillis
    ): SubscriptionUpdater = SubscriptionUpdater(
        source = source,
        contentStore = contentStore,
        dispatcher = dispatcher,
        stateWriter = SubscriptionStateWriter(onState),
        limits = limits,
        clock = clock
    )

    private fun sourceOf(text: String, etag: String? = null) = SubscriptionSource {
        SubscriptionFetchResult.Success(text, text.toByteArray().size.toLong(), etag)
    }

    private fun request(etag: String? = null) = SubscriptionUpdateRequest(
        subscription = subscription,
        profile = FilterProfile.STANDARD,
        etag = etag
    )

    /** The settings as the app would hold them after an update result. */
    private fun stored(subscription: FilterSubscription): FilterSettings =
        FilterSettings.forProfile(FilterProfile.STANDARD)
            .withMode(FilterMode.CUSTOM)
            .withSubscription(subscription)

    @Test
    fun aSuccessfulUpdateStoresTheListAndItsBookkeeping() {
        val recorded = mutableListOf<Triple<String, FilterSubscription, String?>>()
        val update = updater(
            sourceOf(listText, "\"v1\""),
            onState = { id, value, etag -> recorded.add(Triple(id, value, etag)) },
            clock = { 1_700_000_000_000L }
        )

        val result = update.update(request())

        assertTrue(result is SubscriptionUpdateResult.Updated)
        val updated = (result as SubscriptionUpdateResult.Updated).subscription
        assertEquals(SubscriptionState.READY, updated.state)
        assertEquals(2, updated.ruleCount)
        assertEquals(1_700_000_000_000L, updated.lastSuccessAtMillis ?: 0L)
        assertNull(updated.lastError)
        assertEquals("\"v1\"", (result as SubscriptionUpdateResult.Updated).etag)
        assertEquals(listText, contentStore.read(subscription.id))
        assertEquals(1, recorded.size)
        assertEquals("mine", recorded.single().first)
        assertEquals(updated, recorded.single().second)
        assertEquals("\"v1\"", recorded.single().third)
    }

    @Test
    fun aFailedUpdateKeepsThePreviousContentAndThePreviousSnapshot() {
        val source = MutableSource(SubscriptionFetchResult.Success(listText, 10L, "\"v1\""))
        val recordedEtags = mutableListOf<String?>()
        val update = updater(
            source,
            onState = { _, _, etag -> recordedEtags.add(etag) },
            clock = { 1_700_000_000_000L }
        )

        val first = update.update(request()) as SubscriptionUpdateResult.Updated
        val afterSuccess = request(etag = first.etag).copy(subscription = first.subscription)
        val before = assembler.assemble(stored(first.subscription).withEtag(subscription.id, first.etag))
        assertEquals(FilterDecision.BLOCK, before.decide("ads.example"))

        source.next = SubscriptionFetchResult.Failure(SubscriptionFailure.HTTP_STATUS)
        val failed = update.update(afterSuccess) as SubscriptionUpdateResult.Failed

        assertEquals(SubscriptionFailure.HTTP_STATUS, failed.reason)
        assertEquals(SubscriptionState.FAILED, failed.subscription.state)
        assertEquals(SubscriptionFailure.HTTP_STATUS.label, failed.subscription.lastError)
        assertEquals("the downloaded rules stay", 2, failed.subscription.ruleCount)
        assertEquals(1_700_000_000_000L, failed.subscription.lastSuccessAtMillis ?: 0L)
        assertEquals("the stored list is untouched", listText, contentStore.read(subscription.id))
        assertEquals("the ETag of the stored copy is kept", first.etag, recordedEtags.last())

        val afterFailure = assembler.assemble(stored(failed.subscription))
        assertTrue(
            "the rules are republished when the settings change",
            before.version < afterFailure.version
        )
        assertEquals(
            "a failed update must not remove protection the user already has",
            FilterDecision.BLOCK,
            afterFailure.decide("ads.example")
        )
    }

    @Test
    fun aTruncatedListIsRejectedAndThePreviousCopySurvives() {
        val update = updater(sourceOf(listText))
        val first = update.update(request()) as SubscriptionUpdateResult.Updated
        assertEquals(listText, contentStore.read(subscription.id))

        val strictLimits = updater(sourceOf(listText), limits = UpdateLimits(maxRules = 1))
        val failed = strictLimits.update(
            request().copy(subscription = first.subscription)
        ) as SubscriptionUpdateResult.Failed

        assertEquals(SubscriptionFailure.TRUNCATED_LIST, failed.reason)
        assertEquals("the previous copy is kept", listText, contentStore.read(subscription.id))
        assertEquals(SubscriptionFailure.TRUNCATED_LIST.label, failed.subscription.lastError)
        assertEquals(2, failed.subscription.ruleCount)
    }

    @Test
    fun aFailureWithNothingStoredContributesNoRules() {
        val update = updater(SubscriptionSource { SubscriptionFetchResult.Failure(SubscriptionFailure.IO) })

        val failure = update.update(request()) as SubscriptionUpdateResult.Failed

        assertEquals(0, failure.subscription.ruleCount)
        assertFalse(FilterSettings.DEFAULTS.contributesToSnapshot(failure.subscription))
        assertTrue(assembler.assemble(stored(failure.subscription)).isEmpty)
    }

    @Test
    fun anOversizedListIsRejectedBeforeItIsParsed() {
        val big = "||ads.example^\n".repeat(100)
        val update = updater(sourceOf(big), limits = UpdateLimits(maxContentBytes = 16))

        val result = update.update(request()) as SubscriptionUpdateResult.Failed

        assertEquals(SubscriptionFailure.TOO_LARGE, result.reason)
        assertFalse(contentStore.exists(subscription.id))
    }

    @Test
    fun aListThatTakesTooLongToParseIsRejected() {
        var calls = 0
        // The first read of the clock starts the parse, the second ends it.
        val clock = { if (calls++ == 0) 0L else 60_000L }
        val update = updater(
            sourceOf(listText),
            limits = UpdateLimits(parseTimeoutMillis = 5_000),
            clock = clock
        )

        val result = update.update(request()) as SubscriptionUpdateResult.Failed

        assertEquals(SubscriptionFailure.PARSE_TIMEOUT, result.reason)
        assertFalse(contentStore.exists(subscription.id))
    }

    @Test
    fun aListWithNoRulesAtAllIsRejected() {
        val commentsOnly = "! nothing to see here\n[Adblock Plus]\n"
        val update = updater(sourceOf(commentsOnly))

        val result = update.update(request()) as SubscriptionUpdateResult.Failed

        assertEquals(SubscriptionFailure.EMPTY_LIST, result.reason)
        assertFalse(contentStore.exists(subscription.id))
    }

    @Test
    fun notModifiedKeepsTheStoredCopyAndClearsTheLastError() {
        val source = MutableSource(SubscriptionFetchResult.Success(listText, 10L, "\"v1\""))
        var now = 1_000L
        val update = updater(source, clock = { now })

        val first = update.update(request()) as SubscriptionUpdateResult.Updated
        source.next = SubscriptionFetchResult.Failure(SubscriptionFailure.IO)
        val failure = update.update(
            request().copy(subscription = first.subscription)
        ) as SubscriptionUpdateResult.Failed
        source.next = SubscriptionFetchResult.NotModified
        now = 2_000L

        val result = update.update(
            SubscriptionUpdateRequest(
                subscription = failure.subscription,
                profile = FilterProfile.STANDARD,
                etag = "\"v1\""
            )
        )

        assertTrue(result is SubscriptionUpdateResult.Unchanged)
        val confirmed = (result as SubscriptionUpdateResult.Unchanged).subscription
        assertEquals(SubscriptionState.READY, confirmed.state)
        assertNull(confirmed.lastError)
        assertEquals(2_000L, confirmed.lastSuccessAtMillis ?: 0L)
        assertEquals(2, confirmed.ruleCount)
        assertEquals("nothing was re-written", listText, contentStore.read(subscription.id))
    }

    @Test
    fun twoConcurrentUpdatesForTheSameSubscriptionFetchOnlyOnce() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val source = SubscriptionSource {
            calls.incrementAndGet()
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            SubscriptionFetchResult.Failure(SubscriptionFailure.IO)
        }
        val update = updater(source)

        val first = AtomicInteger()
        val thread = Thread { first.set(if (update.update(request()) is SubscriptionUpdateResult.Failed) 1 else 0) }
        thread.start()
        assertTrue("the first update should be in flight", entered.await(10, TimeUnit.SECONDS))

        val second = update.update(request())
        release.countDown()
        thread.join(10_000)

        assertEquals(SubscriptionUpdateResult.SkippedInFlight, second)
        assertEquals("exactly one fetch", 1, calls.get())
        assertEquals("the first update finished normally", 1, first.get())
    }

    @Test
    fun schedulingRefusesASecondUpdateInsteadOfQueueingIt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val source = SubscriptionSource {
            calls.incrementAndGet()
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            SubscriptionFetchResult.Failure(SubscriptionFailure.IO)
        }
        val delivered = CopyOnWriteArrayList<SubscriptionUpdateResult>()
        val update = updater(source)

        val thread = Thread { update.schedule(request()) { delivered.add(it) } }
        thread.start()
        assertTrue(entered.await(10, TimeUnit.SECONDS))

        val accepted = update.schedule(request()) { error("a skipped update must not report a result") }
        release.countDown()
        thread.join(10_000)

        assertFalse("the second request is refused, not queued", accepted)
        assertEquals(1, calls.get())
        assertTrue(delivered.single() is SubscriptionUpdateResult.Failed)
    }

    @Test
    fun schedulingRunsTheUpdateOnTheSuppliedDispatcher() {
        val done = CountDownLatch(1)
        val threadName = CopyOnWriteArrayList<String>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "filter-updater-test")
        }
        val update = updater(sourceOf(listText), dispatcher = executor)

        val accepted = update.schedule(request()) {
            threadName.add(Thread.currentThread().name)
            done.countDown()
        }
        assertTrue(accepted)
        assertTrue("the result should arrive on the dispatcher", done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertEquals(listOf("filter-updater-test"), threadName)
    }

    @Test
    fun aSameThreadDispatcherIsEnoughToRunAnUpdate() {
        val update = updater(sourceOf(listText), dispatcher = Executor { it.run() })
        val results = CopyOnWriteArrayList<SubscriptionUpdateResult>()

        assertTrue(update.schedule(request()) { results.add(it) })

        assertTrue(results.single() is SubscriptionUpdateResult.Updated)
        assertEquals(listText, contentStore.read(subscription.id))
    }

    @Test
    fun anIdThatIsNotUsableAsAFileNameCannotBeStored() {
        val update = updater(sourceOf(listText))

        val result = update.update(
            SubscriptionUpdateRequest(
                subscription = subscription.copy(id = "../escape"),
                profile = FilterProfile.STANDARD
            )
        ) as SubscriptionUpdateResult.Failed

        assertEquals(SubscriptionFailure.STORE_WRITE_FAILED, result.reason)
        assertFalse(File(temporaryFolder.root, "escape.list").exists())
        assertFalse(File(temporaryFolder.root.parentFile, "escape.list").exists())
    }

    @Test
    fun theFetchRequestCarriesTheTierAllowListForABuiltInSource() {
        val seen = CopyOnWriteArrayList<SubscriptionFetchRequest>()
        val update = updater(
            SubscriptionSource { request ->
                seen.add(request)
                SubscriptionFetchResult.Failure(SubscriptionFailure.IO)
            }
        )
        val builtIn = FilterSubscription.forProfile(FilterProfile.STANDARD)

        update.update(SubscriptionUpdateRequest(builtIn, FilterProfile.STANDARD, etag = "\"v9\""))

        assertEquals(1, seen.size)
        assertEquals(FilterProfile.STANDARD.primaryUrl, seen.single().url)
        assertEquals("\"v9\"", seen.single().etag)
        assertEquals(
            setOf("cdn.jsdelivr.net"),
            seen.single().allowedHosts
        )
    }

    @Test
    fun aUserSourceGetsNoAllowListSoItMayUseAnyPublicHost() {
        val seen = CopyOnWriteArrayList<SubscriptionFetchRequest>()
        val update = updater(
            SubscriptionSource { request ->
                seen.add(request)
                SubscriptionFetchResult.Failure(SubscriptionFailure.IO)
            }
        )

        update.update(request())

        assertTrue(seen.single().allowedHosts.isEmpty())
    }

    private class MutableSource(var next: SubscriptionFetchResult) : SubscriptionSource {
        override fun fetch(request: SubscriptionFetchRequest): SubscriptionFetchResult = next
    }
}
