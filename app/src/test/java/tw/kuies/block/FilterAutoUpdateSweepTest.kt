package tw.kuies.block

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tw.kuies.block.filter.FilterAssembler
import tw.kuies.block.filter.FilterCoordinator
import tw.kuies.block.filter.FilterMode
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterRefreshSummary
import tw.kuies.block.filter.FilterSettings
import tw.kuies.block.filter.FilterSettingsStore
import tw.kuies.block.filter.FilterSnapshotHolder
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.SubscriptionContentStore
import tw.kuies.block.filter.SubscriptionFailure
import tw.kuies.block.filter.SubscriptionFetchRequest
import tw.kuies.block.filter.SubscriptionFetchResult
import tw.kuies.block.filter.SubscriptionOrigin
import tw.kuies.block.filter.SubscriptionSource
import tw.kuies.block.filter.SubscriptionState
import tw.kuies.block.filter.SubscriptionUpdater
import tw.kuies.block.filter.UpdateCheckOutcome
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The automatic sweep end to end, over the real coordinator and the real updater.
 *
 * Everything below the scheduler is exercised for real — settings store, updater, ETag bookkeeping,
 * content store, assembler — and only the network is replaced, by a scripted [SubscriptionSource]. That
 * is what makes the two claims the settings page rests on testable: a `304` leaves the stored list
 * exactly as it was, and a failure leaves the last valid list in place.
 *
 * The concurrency cases use a real single-thread executor and a source that blocks on a latch, because
 * the claim under test is about *time*: a second sweep must be refused while the first is still inside
 * the download, not merely detected once both have finished.
 */
class FilterAutoUpdateSweepTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val sameThread = Executor { it.run() }

    /** Answers what the test scripted, and records every request so "downloaded twice" is observable. */
    private class ScriptedSource(
        private val answer: (SubscriptionFetchRequest) -> SubscriptionFetchResult
    ) : SubscriptionSource {
        val requests: MutableList<SubscriptionFetchRequest> =
            Collections.synchronizedList(mutableListOf())

        override fun fetch(request: SubscriptionFetchRequest): SubscriptionFetchResult {
            requests += request
            return answer(request)
        }
    }

    private fun newContentStore() = SubscriptionContentStore.forFilesDir(folder.newFolder())

    private fun coordinator(
        prefs: SharedPreferences,
        source: SubscriptionSource,
        dispatcher: Executor,
        contentStore: SubscriptionContentStore = newContentStore()
    ): FilterCoordinator = FilterCoordinator(
        store = FilterSettingsStore(prefs),
        updater = SubscriptionUpdater(
            source = source,
            contentStore = contentStore,
            dispatcher = dispatcher
        ),
        // The same content store as the updater: the assembler has to read what the updater wrote.
        assembler = FilterAssembler(FilterSnapshotHolder.repository, contentStore),
        contentStore = contentStore,
        dispatcher = dispatcher
    )

    /** Preferences holding one built-in list, as the store would have persisted them. */
    private fun prefsWithBuiltIn(
        ruleCount: Int,
        state: SubscriptionState = SubscriptionState.READY
    ): SharedPreferences {
        val prefs = FakeSharedPreferences()
        FilterSettingsStore(prefs).save(
            FilterSettings(
                subscriptions = listOf(
                    FilterSubscription.forProfile(FilterProfile.STANDARD)
                        .copy(state = state, ruleCount = ruleCount)
                )
            )
        )
        return prefs
    }

    private fun builtInId() = FilterSubscription.PROFILE_SUBSCRIPTION_ID

    @Test
    fun `a fresh install issues no request at all`() {
        // The store's defaults: one built-in list, nothing downloaded, no attempt behind it.
        val source = ScriptedSource { SubscriptionFetchResult.NotModified }
        val coordinator = coordinator(FakeSharedPreferences(), source, sameThread)

        var summary: FilterRefreshSummary? = null
        coordinator.refreshAll { summary = it }

        assertEquals(0, source.requests.size)
        assertEquals(UpdateCheckOutcome.NothingToCheck, summary!!.outcome)
    }

    @Test
    fun `an enabled source with rules is updated and the outcome is reported`() {
        val source = ScriptedSource {
            SubscriptionFetchResult.Success("||ads.example^\n", 15L, "\"etag-1\"")
        }
        val coordinator = coordinator(prefsWithBuiltIn(ruleCount = 100), source, sameThread)

        var summary: FilterRefreshSummary? = null
        coordinator.refreshAll { summary = it }

        assertEquals(1, source.requests.size)
        assertEquals(builtInId(), source.requests.single().subscriptionId)
        assertEquals(1, summary!!.updated)
        assertEquals(UpdateCheckOutcome.Updated(1), summary!!.outcome)
    }

    @Test
    fun `a 304 keeps the stored list exactly as it was`() {
        val prefs = prefsWithBuiltIn(ruleCount = 100)
        val content = newContentStore()
        content.write(builtInId(), "||before.example^\n")
        val source = ScriptedSource { SubscriptionFetchResult.NotModified }

        var summary: FilterRefreshSummary? = null
        coordinator(prefs, source, sameThread, content).refreshAll { summary = it }

        assertEquals("||before.example^\n", content.read(builtInId()))
        assertEquals(UpdateCheckOutcome.AllCurrent, summary!!.outcome)
    }

    @Test
    fun `a failed update keeps the last valid list and says so`() {
        val prefs = prefsWithBuiltIn(ruleCount = 100)
        val content = newContentStore()
        content.write(builtInId(), "||before.example^\n")
        val source = ScriptedSource { SubscriptionFetchResult.Failure(SubscriptionFailure.IO) }

        var summary: FilterRefreshSummary? = null
        coordinator(prefs, source, sameThread, content).refreshAll { summary = it }

        // The rules the user already had are still there, and still counted.
        assertEquals("||before.example^\n", content.read(builtInId()))
        val after = FilterSettingsStore(prefs).load().builtInSubscription!!
        assertEquals(SubscriptionState.FAILED, after.state)
        assertEquals(100, after.ruleCount)
        assertEquals(UpdateCheckOutcome.Failed(1), summary!!.outcome)
    }

    @Test
    fun `a user source whose first download failed is retried`() {
        val prefs = FakeSharedPreferences()
        FilterSettingsStore(prefs).save(
            FilterSettings(
                mode = FilterMode.CUSTOM,
                subscriptions = listOf(
                    FilterSubscription.forProfile(FilterProfile.STANDARD),
                    FilterSubscription(
                        id = "user.abc",
                        displayName = "我的清單",
                        url = "https://example.invalid/x.txt",
                        origin = SubscriptionOrigin.USER,
                        state = SubscriptionState.FAILED,
                        ruleCount = 0
                    )
                )
            )
        )
        val source = ScriptedSource {
            SubscriptionFetchResult.Success("||a.example^\n", 14L, null)
        }

        var summary: FilterRefreshSummary? = null
        coordinator(prefs, source, sameThread).refreshAll { summary = it }

        // The recovery: exactly the source that had failed, and not the untouched built-in list.
        assertEquals(1, source.requests.size)
        assertEquals("user.abc", source.requests.single().subscriptionId)
        assertEquals(UpdateCheckOutcome.Updated(1), summary!!.outcome)
    }

    @Test
    fun `a sweep that arrives while one is running is refused, not queued`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val source = ScriptedSource {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            SubscriptionFetchResult.NotModified
        }
        val dispatcher = Executors.newSingleThreadExecutor()
        try {
            val coordinator = coordinator(prefsWithBuiltIn(ruleCount = 100), source, dispatcher)

            coordinator.refreshAll { finished.countDown() }
            assertTrue("the sweep never entered the download", entered.await(5, TimeUnit.SECONDS))

            // Arrives while the first is inside `fetch`. On a single-threaded dispatcher the claim taken
            // inside the task could never fire here — the second sweep would queue, run afterwards, and
            // find the flag free — so this asserts the claim is taken on the caller's thread.
            var second: FilterRefreshSummary? = null
            coordinator.refreshAll { second = it }
            assertEquals(FilterRefreshSummary.NOT_RUN, second)

            release.countDown()
            assertTrue("the first sweep never finished", finished.await(5, TimeUnit.SECONDS))

            // One list, one download: the refused sweep issued no request of its own.
            assertEquals(1, source.requests.size)
        } finally {
            dispatcher.shutdownNow()
        }
    }

    @Test
    fun `a coordinator rebuilt on the same files reads the stored settings back`() {
        // Stands in for the worker's process: a new coordinator, the same preference file and the same
        // content directory, nothing carried over in memory.
        val prefs = prefsWithBuiltIn(ruleCount = 100)
        val content = newContentStore()
        val first = ScriptedSource {
            SubscriptionFetchResult.Success("||ads.example^\n", 15L, "\"etag-1\"")
        }
        coordinator(prefs, first, sameThread, content).refreshAll { }

        val rebuilt = ScriptedSource { SubscriptionFetchResult.NotModified }
        var summary: FilterRefreshSummary? = null
        coordinator(prefs, rebuilt, sameThread, content).refreshAll { summary = it }

        // The ETag the first sweep stored is what the rebuilt one sends: proof the settings were read
        // back from disk rather than from memory, and that the second pass is a conditional request.
        assertEquals(1, rebuilt.requests.size)
        assertEquals("\"etag-1\"", rebuilt.requests.single().etag)
        assertEquals(UpdateCheckOutcome.AllCurrent, summary!!.outcome)
        assertEquals("||ads.example^\n", content.read(builtInId()))
    }

    @Test
    fun `a manual update during a sweep does not download the list a second time`() {
        val prefs = prefsWithBuiltIn(ruleCount = 100)
        val content = newContentStore()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Both calls run on the same dispatcher thread, so a plain counter is safe here and makes the
        // two answers unambiguous: the sweep stores new content, the manual update finds it current.
        var calls = 0
        val source = ScriptedSource {
            if (calls++ == 0) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                SubscriptionFetchResult.Success("||ads.example^\n", 15L, "\"etag-1\"")
            } else {
                SubscriptionFetchResult.NotModified
            }
        }
        val dispatcher = Executors.newSingleThreadExecutor()
        try {
            // The same content store the assertions read from: leaving the parameter out here made the
            // coordinator build its own directory, so `content.read` below saw an empty one.
            val coordinator = coordinator(prefs, source, dispatcher, content)
            val sweepFinished = CountDownLatch(1)
            coordinator.refreshAll { sweepFinished.countDown() }
            assertTrue("the sweep never entered the download", entered.await(5, TimeUnit.SECONDS))

            // The user taps 立即更新 while the sweep is still inside the download. It is deliberately not
            // refused — an explicit action always runs — it is serialised behind the sweep.
            val manualFinished = CountDownLatch(1)
            coordinator.refresh { manualFinished.countDown() }

            release.countDown()
            assertTrue("the sweep never finished", sweepFinished.await(5, TimeUnit.SECONDS))
            assertTrue("the manual update never finished", manualFinished.await(5, TimeUnit.SECONDS))

            assertEquals(2, source.requests.size)
            // The essence of "no second download": the manual request is conditional on the ETag the
            // sweep just stored, so the same list is not fetched again, and the rules that were already
            // valid are still there afterwards.
            assertNull(source.requests[0].etag)
            assertEquals("\"etag-1\"", source.requests[1].etag)
            assertEquals("||ads.example^\n", content.read(builtInId()))
            assertEquals(1, FilterSettingsStore(prefs).load().builtInSubscription!!.ruleCount)
        } finally {
            dispatcher.shutdownNow()
        }
    }
}
