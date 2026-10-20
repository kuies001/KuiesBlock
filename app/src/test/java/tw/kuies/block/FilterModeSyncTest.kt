package tw.kuies.block

import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tw.kuies.block.filter.FilterAssembler
import tw.kuies.block.filter.FilterCoordinator
import tw.kuies.block.filter.FilterDebouncer
import tw.kuies.block.filter.FilterMode
import tw.kuies.block.filter.FilterProfile
import tw.kuies.block.filter.FilterRepository
import tw.kuies.block.filter.FilterSettings
import tw.kuies.block.filter.FilterSettingsStore
import tw.kuies.block.filter.FilterSource
import tw.kuies.block.filter.FilterStatus
import tw.kuies.block.filter.FilterSubscription
import tw.kuies.block.filter.SubscriptionContentStore
import tw.kuies.block.filter.SubscriptionFailure
import tw.kuies.block.filter.SubscriptionFetchRequest
import tw.kuies.block.filter.SubscriptionFetchResult
import tw.kuies.block.filter.SubscriptionSource
import tw.kuies.block.filter.SubscriptionState
import tw.kuies.block.filter.SubscriptionUpdater
import tw.kuies.block.filter.syncKey

/**
 * The automatic work a mode or source change starts.
 *
 * The promises under test are the ones the user can see: choosing a strength is enough (no 立即更新
 * tap), a cached tier is in effect before the network answers, a missing tier reports that it is not
 * ready, a failure keeps the previous rules, and a late download can never publish itself as the
 * mode that is now selected.
 */
class FilterModeSyncTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val source = ScriptedSource()
    private val store = FilterSettingsStore(FakeSharedPreferences())
    private val sameThread = Executor { it.run() }
    private lateinit var contentStore: SubscriptionContentStore
    private lateinit var repository: FilterRepository

    @Before
    fun setUp() {
        contentStore = SubscriptionContentStore.forFilesDir(folder.newFolder("files"))
        repository = FilterRepository()
    }

    private fun coordinator(
        apply: Executor = sameThread,
        download: Executor = sameThread,
        debouncer: FilterDebouncer = FilterDebouncer.IMMEDIATE
    ) = FilterCoordinator(
        store = store,
        updater = SubscriptionUpdater(
            source = source,
            contentStore = contentStore,
            dispatcher = download
        ),
        assembler = FilterAssembler(repository, contentStore),
        contentStore = contentStore,
        dispatcher = apply,
        downloadDispatcher = download,
        debouncer = debouncer
    )

    /** A STANDARD main list that is already on disk, as a previous download would leave it. */
    private fun storeReadyStandardInCustomMode() {
        contentStore.write(FilterSubscription.PROFILE_SUBSCRIPTION_ID, "||ads.example^\n")
        store.save(
            FilterSettings.forProfile(FilterProfile.STANDARD)
                .withMode(FilterMode.CUSTOM)
                .withEtag(FilterSubscription.PROFILE_SUBSCRIPTION_ID, "\"v1\"")
                .withSubscription(
                    FilterSubscription.forProfile(FilterProfile.STANDARD).copy(
                        state = SubscriptionState.READY,
                        ruleCount = 1,
                        lastSuccessAtMillis = 1_000L
                    )
                )
        )
    }

    @Test
    fun choosingAStrengthDownloadsItsListWithoutAManualUpdate() {
        source.body = "||ads.example^\n||tracker.example^\n"

        val statuses = mutableListOf<FilterStatus>()
        coordinator().selectMode(FilterMode.RELAXED, onStatus = { statuses += it })

        assertEquals(listOf(FilterProfile.RELAXED.primaryUrl), source.requests.map { it.url })
        assertEquals(FilterMode.RELAXED, store.load().mode)
        assertEquals(2, repository.snapshot().subscriptionRuleCount)
        assertEquals(2, statuses.last().effectiveRuleCount)
    }

    /**
     * The point of keeping a tier's bookkeeping when the same list is selected again: the rules are
     * published from disk first, so the count never dips while the background check runs.
     */
    @Test
    fun aStoredTierIsPublishedBeforeTheBackgroundCheck() {
        storeReadyStandardInCustomMode()
        source.notModified = true

        val statuses = mutableListOf<FilterStatus>()
        coordinator().selectMode(FilterMode.STANDARD, onStatus = { statuses += it })

        assertEquals("the cached list must be in effect at once", 1, statuses.first().effectiveRuleCount)
        assertEquals(1, statuses.last().effectiveRuleCount)
        assertEquals(1, repository.snapshot().subscriptionRuleCount)
        assertEquals(
            "the check must be conditional, not a second full download",
            "\"v1\"",
            source.requests.single().etag
        )
    }

    /**
     * A tier that has never been downloaded must say so until its list arrives: the old tier's rules
     * are not renamed into the new mode.
     */
    @Test
    fun aTierWithNoCacheReportsNoRulesUntilTheDownloadSettles() {
        source.body = "||ads.example^\n"

        val statuses = mutableListOf<FilterStatus>()
        coordinator().selectMode(FilterMode.STRICT, onStatus = { statuses += it })

        assertFalse("nothing may be claimed before the list exists", statuses.first().hasUsableRules)
        assertEquals(0, statuses.first().effectiveRuleCount)
        assertTrue(statuses.last().hasUsableRules)
        assertEquals(1, statuses.last().effectiveRuleCount)
    }

    @Test
    fun aFailedDownloadLeavesAnAlreadyStoredTierInEffect() {
        storeReadyStandardInCustomMode()
        source.failure = SubscriptionFailure.CONNECT_FAILED

        val statuses = mutableListOf<FilterStatus>()
        coordinator().selectMode(FilterMode.STANDARD, onStatus = { statuses += it })

        assertEquals(1, statuses.last().effectiveRuleCount)
        assertEquals(1, repository.snapshot().subscriptionRuleCount)
        assertEquals(
            SubscriptionState.FAILED,
            statuses.last().sourceStatuses.single().state
        )
        assertEquals(
            "the failure must not clear the list it could not refresh",
            1,
            statuses.last().sourceStatuses.single().ruleCount
        )
    }

    /**
     * The race the brief calls out: 嚴格 starts downloading, the user picks 寬鬆 before it finishes.
     * The late 嚴格 result may be stored in its own cache, but it must not publish itself as current.
     */
    @Test
    fun aLateTierDownloadCannotOverwriteTheNewestChoice() {
        source.bodyFor = { url ->
            when (url) {
                FilterProfile.STRICT.primaryUrl -> "||strict.example^\n"
                else -> "||relaxed.example^\n||relaxed2.example^\n"
            }
        }
        val run = ArrayDeque<Runnable>()
        val apply = Executor { run.addLast(it) }
        val download = Executor { run.addLast(it) }
        lateinit var coordinator: FilterCoordinator
        coordinator = coordinator(apply = apply, download = download)
        source.onFetch = { request ->
            // The switch happens while 嚴格's fetch is in flight, which is the real-world ordering.
            if (request.url == FilterProfile.STRICT.primaryUrl) {
                coordinator.selectMode(FilterMode.RELAXED)
            }
        }

        coordinator.selectMode(FilterMode.STRICT)
        while (run.isNotEmpty()) run.removeFirst().run()

        assertEquals(FilterMode.RELAXED, store.load().mode)
        assertEquals(FilterProfile.RELAXED.primaryUrl, store.load().builtInSubscription?.url)
        assertEquals(2, repository.snapshot().subscriptionRuleCount)
        assertEquals(
            "寬鬆's list must be the one published, not 嚴格's late result",
            setOf("relaxed.example", "relaxed2.example").size,
            repository.snapshot().subscriptionRuleCount
        )
        assertEquals(
            "the stale tier must not be fetched a second time",
            listOf(FilterProfile.STRICT.primaryUrl, FilterProfile.RELAXED.primaryUrl),
            source.requests.map { it.url }
        )
    }

    /**
     * Ticking several custom sources must produce one download pass for the final selection, not one
     * download per tap.
     */
    @Test
    fun rapidCustomSourceChangesAreMergedIntoOnePass() {
        storeReadyStandardInCustomMode()
        val debouncer = ManualDebouncer()
        val coordinator = coordinator(debouncer = debouncer)

        coordinator.selectMode(FilterMode.CUSTOM)
        coordinator.selectSource(FilterSource.HAGEZI_TIF, true)
        coordinator.selectSource(FilterSource.OISD, true)
        coordinator.selectSource(FilterSource.ADGUARD_DNS, true)

        assertEquals("one pending pass, not three", 1, debouncer.pendingCount)
        assertTrue("nothing may be fetched before the quiet period ends", source.requests.isEmpty())

        debouncer.runPending()

        assertEquals(
            setOf(
                FilterSource.HAGEZI_TIF.primaryUrl,
                FilterSource.OISD.primaryUrl,
                FilterSource.ADGUARD_DNS.primaryUrl
            ),
            source.requests.map { it.url }.toSet()
        )
        assertEquals("each source exactly once", 3, source.requests.size)
        assertEquals(
            "the selection alone decides which sources contribute",
            setOf(
                FilterSource.HAGEZI_NORMAL.id,
                FilterSource.HAGEZI_TIF.id,
                FilterSource.OISD.id,
                FilterSource.ADGUARD_DNS.id
            ),
            store.load().selectedSources
        )
    }

    /** Deselecting must remove that source from the very next snapshot, before any download. */
    @Test
    fun deselectingASourceRemovesItsRulesImmediately() {
        storeReadyStandardInCustomMode()
        val coordinator = coordinator()
        source.body = "||oisd.example^\n"

        coordinator.selectMode(FilterMode.CUSTOM)
        coordinator.selectSource(FilterSource.OISD, true)
        val withOisd = repository.snapshot().subscriptionRuleCount
        assertTrue(withOisd > 1)

        val statuses = mutableListOf<FilterStatus>()
        coordinator.selectSource(FilterSource.OISD, false, onStatus = { statuses += it })

        assertEquals("the cached main list stays", 1, statuses.first().effectiveRuleCount)
        assertEquals(1, repository.snapshot().subscriptionRuleCount)
        assertFalse(FilterSource.OISD.id in store.load().selectedSources)
    }

    /**
     * 立即更新 followed at once by a strength change must not download the old tier: the explicit
     * refresh is superseded before it starts, and only the new mode's list is fetched.
     */
    @Test
    fun aManualUpdateSupersededByAModeChangeDoesNotDownloadTwice() {
        storeReadyStandardInCustomMode()
        val run = ArrayDeque<Runnable>()
        val apply = Executor { run.addLast(it) }
        val download = Executor { run.addLast(it) }
        val coordinator = coordinator(apply = apply, download = download)

        coordinator.refresh()
        coordinator.selectMode(FilterMode.STRICT)
        while (run.isNotEmpty()) run.removeFirst().run()

        assertEquals(
            "only the newly chosen tier may be fetched",
            listOf(FilterProfile.STRICT.primaryUrl),
            source.requests.map { it.url }
        )
    }

    /**
     * A mode-triggered sync is an ordinary one-shot download: it must not create or touch the periodic
     * WorkManager job. The coordinator has no scheduler at all, and this guard keeps it that way.
     */
    @Test
    fun theAutomaticSyncAddsNoPeriodicWork() {
        val sourceFile = locate("src/main/java/tw/kuies/block/filter/FilterCoordinator.kt")
        val code = sourceFile.readText(Charsets.UTF_8)
        assertFalse(code.contains("WorkManager"))
        assertFalse(code.contains("PeriodicUpdateScheduler"))
        assertFalse(code.contains("FilterUpdateWorker"))
    }

    @Test
    fun aFreshInstallStillDownloadsNothingUntilAChoiceIsMade() {
        val status = coordinator().publishCurrent()

        assertTrue(status.enabled)
        assertTrue("no rules may be published before the user chooses", repository.snapshot().isEmpty)
        assertTrue("nothing may be downloaded before the user chooses", source.requests.isEmpty())
        assertNull(store.load().etagFor(FilterSubscription.PROFILE_SUBSCRIPTION_ID))
    }

    private fun locate(relative: String): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            for (candidate in listOf(File(directory, relative), File(directory, "app/$relative"))) {
                if (candidate.isFile) return candidate
            }
            directory = directory.parentFile
        }
        throw AssertionError("could not locate $relative from ${File("").absolutePath}")
    }
}

/** A debouncer whose quiet period the test ends explicitly. */
private class ManualDebouncer : FilterDebouncer {

    private var pending: Runnable? = null

    val pendingCount: Int get() = if (pending == null) 0 else 1

    override fun post(task: Runnable) {
        pending = task
    }

    fun runPending() {
        val task = pending ?: return
        pending = null
        task.run()
    }
}

/** A source that answers from a script and records every request it was asked to make. */
private class ScriptedSource : SubscriptionSource {

    var body: String? = "||ads.example^\n"
    var bodyFor: ((String) -> String?)? = null
    var failure: SubscriptionFailure? = null
    var notModified = false
    var onFetch: ((SubscriptionFetchRequest) -> Unit)? = null
    val requests = mutableListOf<SubscriptionFetchRequest>()

    override fun fetch(request: SubscriptionFetchRequest): SubscriptionFetchResult {
        requests.add(request)
        onFetch?.invoke(request)
        failure?.let { return SubscriptionFetchResult.Failure(it) }
        if (notModified) return SubscriptionFetchResult.NotModified
        val text = bodyFor?.invoke(request.url) ?: body
            ?: return SubscriptionFetchResult.Failure(SubscriptionFailure.EMPTY_BODY)
        return SubscriptionFetchResult.Success(
            text = text,
            byteCount = text.length.toLong(),
            etag = "\"v1\""
        )
    }
}
