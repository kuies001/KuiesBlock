package tw.kuies.block.filter

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The one place that turns the stored settings plus the downloaded lists into the snapshot the VPN
 * reads, and the only place the app downloads a list from.
 *
 * Why it exists: the engine, the settings model, the store, the fetcher and the assembler were all
 * in place, but nothing in the app ever assembled a snapshot — so the rules could never take effect
 * on a real install. This class closes that gap and nothing more.
 *
 * Rules it keeps:
 *  * **Nothing is downloaded until the user chooses a strength.** A fresh install has no stored list,
 *    so it neither downloads anything nor publishes a single rule — and nothing here starts
 *    protection, which is the home screen's switch to throw.
 *  * **Publishing is immediate and local.** Choosing a tier republishes from what is already stored
 *    before any network work, so the packet path never waits for a download.
 *  * **A failed download never removes protection.** The updater keeps the previous content and the
 *    assembler keeps using it, exactly as [FilterSettings.contributesToSnapshot] describes.
 *  * **Parsing happens here, never on the packet path.** All of it runs on [dispatcher].
 *
 * Two executors, one direction of data flow:
 *  * [dispatcher] owns every read-modify-write of the stored settings and every publish. It is the
 *    only thread that saves, so two changes can never build one settings object each and overwrite
 *    one another.
 *  * [downloadDispatcher] owns the network and the parsing. It never saves, and its results are
 *    handed back to [dispatcher] to be merged into whatever the settings are *then*.
 *
 * That split is what lets a mode change publish from cache while a previous update is still in
 * flight, and it is why a slow download never blocks the screen.
 */
class FilterCoordinator(
    private val store: FilterSettingsStore,
    private val updater: SubscriptionUpdater,
    private val assembler: FilterAssembler,
    private val contentStore: SubscriptionContentStore,
    private val dispatcher: Executor,
    private val downloadDispatcher: Executor = dispatcher,
    private val debouncer: FilterDebouncer = FilterDebouncer.IMMEDIATE
) {

    /**
     * Guards the whole automatic sweep.
     *
     * Every sweep runs on [downloadDispatcher], so automatic and manual work cannot interleave; this
     * flag is what stops a second sweep from starting while one is still going, which is the case a
     * period firing just as the user taps 立即更新 would otherwise produce.
     */
    private val refreshAllInFlight = AtomicBoolean(false)

    /**
     * Orders every request that may download.
     *
     * Only the newest request is allowed to publish its result or to clear the screen's progress
     * line. A job that has been superseded still finishes safely — the updater stores what it fetched
     * in that source's own file — but it cannot make an older mode look current.
     */
    private val syncRequests = AtomicLong(0L)

    /** What a change is doing, so the screen can say 準備中 rather than freeze. */
    private val syncListeners = CopyOnWriteArrayList<(FilterSyncPhase) -> Unit>()

    /** Every published status, so a background publish cannot leave the screen on an older count. */
    private val statusListeners = CopyOnWriteArrayList<(FilterStatus) -> Unit>()

    /** The last status published, used to word the progress line and as a fallback result. */
    @Volatile
    private var lastStatus: FilterStatus? = null

    /**
     * Which request currently owns the progress line.
     *
     * A request that never starts a download — every source is cached, for instance — must not leave
     * the screen saying 更新中 forever, so only the owning request is allowed to clear it. A newer
     * owner that does download therefore keeps the line up until it is really done.
     */
    @Volatile
    private var phaseOwner: Long? = null

    /** Observes the sync phase. The returned function removes the listener. */
    fun addSyncListener(listener: (FilterSyncPhase) -> Unit): () -> Unit {
        syncListeners.add(listener)
        return { syncListeners.remove(listener) }
    }

    /** Observes every published status. The returned function removes the listener. */
    fun addStatusListener(listener: (FilterStatus) -> Unit): () -> Unit {
        statusListeners.add(listener)
        return { statusListeners.remove(listener) }
    }

    /** Publishes what the stored settings and stored list text imply right now, with no network. */
    fun publishCurrent(): FilterStatus {
        val settings = store.load()
        return publish(settings)
    }

    /**
     * [publishCurrent] off the caller's thread.
     *
     * Used at start-up by both the UI and the VPN service. A failure here leaves the previous
     * snapshot untouched rather than publishing an empty one, so a storage problem can never silently
     * turn filtering off.
     */
    fun publishCurrentAsync(onResult: (FilterStatus) -> Unit = {}) {
        dispatcher.execute {
            val status = runCatching { publishCurrent() }.getOrNull() ?: return@execute
            onResult(status)
        }
    }

    /**
     * Applies the user's choice of [mode].
     *
     * The choice is saved and published **before** any network work: a tier whose list is already
     * stored takes effect now, and a tier with nothing stored reports exactly that. The list is then
     * checked in the background — a conditional request when it is already stored — so the user never
     * has to open 過濾器管理 and press 立即更新.
     *
     * [onStatus] may be called more than once: once with the state that needs no network and once
     * when the background check settles. [onFinished] is called once, after the immediate publish;
     * the background work is reported through [addSyncListener], not by blocking this call.
     */
    fun selectMode(
        mode: FilterMode,
        onStatus: (FilterStatus) -> Unit = {},
        onFinished: () -> Unit = {}
    ) {
        dispatcher.execute {
            try {
                val base = runCatching { store.load() }.getOrNull() ?: return@execute
                val chosen = base.withMode(mode)
                if (chosen != base) runCatching { store.save(chosen) }

                // Publish before touching the network: a source whose content is already stored takes
                // effect now, and a mode with nothing downloaded reports exactly that.
                val status = publish(chosen)
                onStatus(status)

                val kind = if (chosen.mode == FilterMode.CUSTOM) {
                    SyncKind.SELECTED_MISSING
                } else {
                    SyncKind.ACTIVE_LIST
                }
                // Re-tapping the current mode is a no-op when its list is usable; it only retries a
                // mode that has nothing published yet, or a custom selection with a source that never
                // arrived. A real switch always checks its list.
                val retries = chosen == base && when (kind) {
                    SyncKind.SELECTED_MISSING -> targetsFor(chosen, kind).isNotEmpty()
                    else -> !status.hasUsableRules
                }
                if (chosen != base || retries) {
                    // Only a request that really starts work takes a sequence number: a no-op tap must
                    // not cancel an update that is already running.
                    val request = syncRequests.incrementAndGet()
                    startSync(request, chosen, kind, status, onStatus)
                }
            } finally {
                onFinished()
            }
        }
    }

    /** Backwards-compatible entry point for the three preset strengths. */
    fun selectStrength(
        profile: FilterProfile,
        onStatus: (FilterStatus) -> Unit = {},
        onFinished: () -> Unit = {}
    ) = selectMode(FilterMode.forProfile(profile), onStatus, onFinished)

    /**
     * Turns one custom-mode source on or off.
     *
     * The new selection is published before any network work, so a source that is already downloaded
     * takes effect immediately and a deselected one leaves the snapshot immediately. Missing sources
     * are fetched in one debounced pass, so tapping a row of checkboxes does not start one download
     * per tap. Deselecting never deletes a source's stored content, so turning it back on can reuse
     * the existing list.
     */
    fun selectSource(
        source: FilterSource,
        selected: Boolean,
        onStatus: (FilterStatus) -> Unit = {},
        onFinished: () -> Unit = {}
    ) {
        dispatcher.execute {
            try {
                val base = runCatching { store.load() }.getOrNull() ?: return@execute
                val chosen = base.withSourceSelected(source, selected)
                if (chosen != base) runCatching { store.save(chosen) }
                val status = publish(chosen)
                onStatus(status)

                if (chosen != base) {
                    // A real change claims the newest sequence number, so the previous debounced pass
                    // is superseded rather than downloading a selection the user has already left. A
                    // refused change (the last source) claims nothing and cancels nothing.
                    val request = syncRequests.incrementAndGet()
                    scheduleDebouncedSync(request, chosen, status, onStatus)
                }
            } finally {
                onFinished()
            }
        }
    }

    /**
     * Re-downloads every source the active mode is using; a failure keeps whatever is stored.
     *
     * The work is serialised with the automatic updates, so pressing 立即更新 never starts a second
     * download of a list that is already being fetched.
     */
    fun refresh(onResult: (FilterStatus) -> Unit = {}) {
        dispatcher.execute {
            val settings = runCatching { store.load() }.getOrNull() ?: return@execute
            val request = syncRequests.incrementAndGet()
            startPhase(request)
            try {
                downloadDispatcher.execute {
                    runSync(request, settings.syncKey(), SyncKind.ALL_ACTIVE, onResult)
                }
            } catch (_: RejectedExecutionException) {
                endPhase(request)
            }
        }
    }

    /**
     * Refreshes the sources an automatic sweep is allowed to touch, and reports what it found.
     *
     * Two rules make it safe to run without the user watching:
     *  * **A source with nothing downloaded and no attempt behind it is never fetched here.**
     *    Automatic updates default to enabled, so downloading on first run would contradict the rule
     *    that nothing is fetched until the user picks a strength. [FilterAutoUpdateSelection] is the
     *    whole of that decision.
     *  * **A failure keeps the previous list.** [SubscriptionUpdater] already guarantees that, so this
     *    only has to record the outcome.
     *
     * The downloads run on [downloadDispatcher] and the merged result is written on [dispatcher]. A
     * source whose URL changed while the sweep was running is skipped rather than written back into
     * the new selection, so a sweep can never undo a mode change.
     */
    fun refreshAll(onFinished: (FilterRefreshSummary) -> Unit = {}) {
        if (!refreshAllInFlight.compareAndSet(false, true)) {
            onFinished(FilterRefreshSummary.NOT_RUN)
            return
        }
        try {
            downloadDispatcher.execute { sweep(onFinished) }
        } catch (_: RejectedExecutionException) {
            // A shut-down dispatcher must not strand the claim for the life of the process.
            refreshAllInFlight.set(false)
            onFinished(FilterRefreshSummary.NOT_RUN)
        }
    }

    /** The sweep itself; runs on [downloadDispatcher] and releases the claim on the apply path. */
    private fun sweep(onFinished: (FilterRefreshSummary) -> Unit) {
        val settings = runCatching { store.load() }.getOrNull()
        if (settings == null) {
            refreshAllInFlight.set(false)
            onFinished(FilterRefreshSummary.NOT_RUN)
            return
        }

        val targets = FilterAutoUpdateSelection.select(settings)
        if (targets.isEmpty()) {
            refreshAllInFlight.set(false)
            onFinished(FilterRefreshSummary.NOT_RUN)
            return
        }

        startPhase(SWEEP_OWNER)
        val results = ArrayList<SubscriptionUpdateResult>(targets.size)
        var updated = 0
        var unchanged = 0
        var failed = 0
        var skipped = 0

        for (target in targets) {
            val live = runCatching { store.load() }.getOrNull()
            val subscription = live?.subscriptions?.firstOrNull { it.id == target.id }
            if (live == null || subscription == null || subscription.url != target.url) {
                skipped++
                continue
            }
            when (
                val result = updater.update(
                    SubscriptionUpdateRequest(
                        subscription = subscription,
                        profile = live.profile,
                        etag = live.etagFor(subscription.id)
                    )
                )
            ) {
                is SubscriptionUpdateResult.Updated -> {
                    results.add(result)
                    updated++
                }
                is SubscriptionUpdateResult.Unchanged -> {
                    results.add(result)
                    unchanged++
                }
                is SubscriptionUpdateResult.Failed -> {
                    results.add(result)
                    failed++
                }
                // Another update for this list held the single flight; that one persists itself.
                SubscriptionUpdateResult.SkippedInFlight -> skipped++
            }
        }

        val summary = FilterRefreshSummary(
            considered = targets.size,
            updated = updated,
            unchanged = unchanged,
            failed = failed,
            skipped = skipped
        )
        applySweep(results, summary, onFinished)
    }

    /** Merges a sweep into the settings as they are now, publishes once, then reports. */
    private fun applySweep(
        results: List<SubscriptionUpdateResult>,
        summary: FilterRefreshSummary,
        onFinished: (FilterRefreshSummary) -> Unit
    ) {
        val task = Runnable {
            try {
                val latest = runCatching { store.load() }.getOrNull()
                if (latest != null) {
                    val merged = mergeResults(latest, results)
                    if (merged != latest) runCatching { store.save(merged) }
                    val snapshot = runCatching { assembler.assemble(merged) }.getOrNull()
                    if (snapshot != null) {
                        val status = FilterStatus.of(merged, snapshot)
                        lastStatus = status
                        notifyStatus(status)
                    }
                }
                onFinished(summary)
            } finally {
                refreshAllInFlight.set(false)
                endPhase(SWEEP_OWNER)
            }
        }
        try {
            dispatcher.execute(task)
        } catch (_: RejectedExecutionException) {
            refreshAllInFlight.set(false)
            onFinished(FilterRefreshSummary.NOT_RUN)
        }
    }

    /**
     * Adds a manual allow rule and republishes straight away.
     *
     * A manual rule needs no network, so the snapshot is replaced immediately and a running tunnel
     * picks it up on its next query — the user never restarts protection to make a rule count.
     */
    fun addManualAllow(rule: ManualRule, onResult: (FilterStatus) -> Unit = {}) =
        mutateSettings(onResult) { it.addManualAllow(rule) }

    fun addManualBlock(rule: ManualRule, onResult: (FilterStatus) -> Unit = {}) =
        mutateSettings(onResult) { it.addManualBlock(rule) }

    /** Removes a manual allow rule by its domain; [FilterSettings] matches it canonically. */
    fun removeManualAllow(domain: String, onResult: (FilterStatus) -> Unit = {}) =
        mutateSettings(onResult) { it.removeManualAllow(domain) }

    fun removeManualBlock(domain: String, onResult: (FilterStatus) -> Unit = {}) =
        mutateSettings(onResult) { it.removeManualBlock(domain) }

    /**
     * Applies a local-only change to the stored settings, republishes, and reports the new status.
     *
     * Everything here runs on [dispatcher], never on the caller's thread: the settings write and the
     * snapshot rebuild both touch disk, and the packet path must never wait for either.
     */
    private fun mutateSettings(
        onResult: (FilterStatus) -> Unit,
        change: (FilterSettings) -> FilterSettings
    ) {
        dispatcher.execute {
            val current = runCatching { store.load() }.getOrNull() ?: return@execute
            val updated = change(current)
            runCatching { store.save(updated) }
            onResult(publish(updated))
        }
    }

    // ---- user-added custom sources ----------------------------------------------------------

    /** Adds a custom source. It carries no rules until it has been downloaded. */
    fun addSubscription(subscription: FilterSubscription, onResult: (FilterStatus) -> Unit = {}) =
        mutateSettings(onResult) { it.withSubscription(subscription) }

    /**
     * Removes a custom source **and** the list text it downloaded.
     *
     * The stored file goes with the entry: leaving it behind would keep paying disk for rules nothing
     * can reference again. Only this subscription's own file is touched, and never the built-in
     * list's — its id is refused on purpose.
     */
    fun removeSubscription(subscriptionId: String, onResult: (FilterStatus) -> Unit = {}) {
        dispatcher.execute {
            val current = runCatching { store.load() }.getOrNull() ?: return@execute
            val updated = current.withoutSubscription(subscriptionId)
            runCatching { store.save(updated) }
            if (subscriptionId != FilterSubscription.PROFILE_SUBSCRIPTION_ID) {
                runCatching { contentStore.delete(subscriptionId) }
            }
            onResult(publish(updated))
        }
    }

    /**
     * Enables or disables one source.
     *
     * A disabled source keeps its downloaded text but stops contributing, so its rules leave the very
     * next snapshot — the switch is a real change, not a label on screen.
     */
    fun setSubscriptionEnabled(
        subscription: FilterSubscription,
        enabled: Boolean,
        onResult: (FilterStatus) -> Unit = {}
    ) = mutateSettings(onResult) { it.withSubscription(subscription.copy(enabled = enabled)) }

    /** Downloads one source on request; a failure keeps the rules that source already stored. */
    fun updateSubscription(subscription: FilterSubscription, onResult: (FilterStatus) -> Unit = {}) {
        dispatcher.execute {
            val settings = runCatching { store.load() }.getOrNull() ?: return@execute
            val live = settings.subscriptions.firstOrNull { it.id == subscription.id }
                ?: return@execute
            val request = syncRequests.incrementAndGet()
            startPhase(request)
            try {
                downloadDispatcher.execute {
                    runSync(request, settings.syncKey(), SyncKind.ONE(live.id), onResult)
                }
            } catch (_: RejectedExecutionException) {
                endPhase(request)
            }
        }
    }

    // ---- sync planning ----------------------------------------------------------------------

    /** What one background pass is allowed to download. */
    private sealed interface SyncKind {

        /** The active preset's own main list. */
        data object ACTIVE_LIST : SyncKind

        /** Selected custom sources that have no usable stored content yet. */
        data object SELECTED_MISSING : SyncKind

        /** Every source currently in effect, refreshed. */
        data object ALL_ACTIVE : SyncKind

        /** Exactly one source, by subscription id. */
        data class ONE(val subscriptionId: String) : SyncKind
    }

    /**
     * The sources [kind] implies for [settings].
     *
     * A preset never downloads anything but its own main list. Custom mode downloads only what has no
     * stored rules, so re-ticking a source that is still on disk costs nothing. A manual refresh asks
     * for everything in effect and relies on the updater's conditional requests to make it cheap.
     */
    private fun targetsFor(settings: FilterSettings, kind: SyncKind): List<FilterSubscription> =
        when (kind) {
            SyncKind.ACTIVE_LIST -> listOfNotNull(settings.builtInSubscription?.takeIf { it.enabled })
            SyncKind.SELECTED_MISSING ->
                settings.effectiveSubscriptions.filter { it.enabled && it.ruleCount <= 0 }
            SyncKind.ALL_ACTIVE -> settings.effectiveSubscriptions.filter { it.enabled }
            is SyncKind.ONE ->
                settings.subscriptions.filter { it.id == kind.subscriptionId }
        }

    /**
     * Starts a sync at once, unless there is nothing to do.
     *
     * The phase is reported before the job is queued so the screen says 準備中 immediately rather
     * than after the first network read.
     */
    private fun startSync(
        request: Long,
        settings: FilterSettings,
        kind: SyncKind,
        status: FilterStatus,
        onStatus: (FilterStatus) -> Unit
    ) {
        if (targetsFor(settings, kind).isEmpty()) return
        startPhase(request, status)
        val key = settings.syncKey()
        try {
            downloadDispatcher.execute { runSync(request, key, kind, onStatus) }
        } catch (_: RejectedExecutionException) {
            endPhase(request)
        }
    }

    /** Merges changes made in quick succession into one download pass for the final selection. */
    private fun scheduleDebouncedSync(
        request: Long,
        settings: FilterSettings,
        status: FilterStatus,
        onStatus: (FilterStatus) -> Unit
    ) {
        if (targetsFor(settings, SyncKind.SELECTED_MISSING).isEmpty()) return
        startPhase(request, status)
        val key = settings.syncKey()
        debouncer.post(
            Runnable {
                try {
                    downloadDispatcher.execute { runSync(request, key, SyncKind.SELECTED_MISSING, onStatus) }
                } catch (_: RejectedExecutionException) {
                    endPhase(request)
                }
            }
        )
    }

    /**
     * Downloads [kind]'s targets for [key], then hands the results back to [dispatcher].
     *
     * Before every request it reloads the settings: a newer choice makes the rest of this pass
     * pointless, so it stops instead of downloading a list the user has already left. The same check
     * runs again before anything is saved, which is what makes a late download harmless.
     */
    private fun runSync(
        request: Long,
        key: FilterSyncKey,
        kind: SyncKind,
        onStatus: (FilterStatus) -> Unit
    ) {
        val initial = runCatching { store.load() }.getOrNull()
        if (initial == null || initial.syncKey() != key) {
            settle(null, request, key, onStatus)
            return
        }

        val results = ArrayList<SubscriptionUpdateResult>()
        for (target in targetsFor(initial, kind)) {
            if (syncRequests.get() != request) break
            val live = runCatching { store.load() }.getOrNull() ?: break
            if (live.syncKey() != key) break
            val subscription = live.subscriptions.firstOrNull { it.id == target.id } ?: continue
            if (subscription.url != target.url) continue
            results.add(
                updater.update(
                    SubscriptionUpdateRequest(
                        subscription = subscription,
                        profile = live.profile,
                        etag = live.etagFor(subscription.id)
                    )
                )
            )
        }
        settle(results, request, key, onStatus)
    }

    /**
     * Applies [results] on [dispatcher] if the request is still current, then reports.
     *
     * A superseded job publishes nothing: its content may already be in that source's cache, which is
     * safe, but the snapshot stays the one the newest choice built.
     */
    private fun settle(
        results: List<SubscriptionUpdateResult>?,
        request: Long,
        key: FilterSyncKey,
        onStatus: (FilterStatus) -> Unit
    ) {
        val task = Runnable {
            val current = syncRequests.get() == request
            val applied = if (results != null) applyResults(request, key, results) else null
            // A superseded job reports nothing: its status belongs to a choice the user has already
            // left, and showing it — even briefly — would name the wrong mode.
            if (applied != null) {
                onStatus(applied)
            } else if (current) {
                lastStatus?.let(onStatus)
            }
            endPhase(request)
        }
        try {
            dispatcher.execute(task)
        } catch (_: RejectedExecutionException) {
            // Nothing to show and nowhere to show it; the next action publishes again.
        }
    }

    /** Merges download bookkeeping into the settings as they are now; null when superseded. */
    private fun applyResults(
        request: Long,
        key: FilterSyncKey,
        results: List<SubscriptionUpdateResult>
    ): FilterStatus? {
        if (syncRequests.get() != request) return null
        val latest = runCatching { store.load() }.getOrNull() ?: return null
        if (latest.syncKey() != key) return null
        if (results.isEmpty()) return lastStatus ?: publish(latest)

        val merged = mergeResults(latest, results)
        if (merged != latest) runCatching { store.save(merged) }
        return publish(merged)
    }

    /**
     * Writes each result into [settings], but only where it still describes the same list.
     *
     * The URL check is the last line of defence: it means a result can never attach one list's rule
     * count to another list that now occupies the same entry.
     */
    private fun mergeResults(
        settings: FilterSettings,
        results: List<SubscriptionUpdateResult>
    ): FilterSettings {
        var current = settings
        for (result in results) {
            val subscription = when (result) {
                is SubscriptionUpdateResult.Updated -> result.subscription
                is SubscriptionUpdateResult.Unchanged -> result.subscription
                is SubscriptionUpdateResult.Failed -> result.subscription
                SubscriptionUpdateResult.SkippedInFlight -> continue
            }
            val existing = current.subscriptions.firstOrNull { it.id == subscription.id } ?: continue
            if (existing.url != subscription.url) continue
            current = current.withSubscription(subscription)
            if (result is SubscriptionUpdateResult.Updated) {
                current = current.withEtag(subscription.id, result.etag)
            }
        }
        return current
    }

    /** Assembles and records the status, so progress wording and results agree. */
    private fun publish(settings: FilterSettings): FilterStatus {
        val status = FilterStatus.of(settings, assembler.assemble(settings))
        lastStatus = status
        notifyStatus(status)
        return status
    }

    private fun phaseFor(status: FilterStatus?): FilterSyncPhase =
        if ((status?.effectiveRuleCount ?: 0) > 0) {
            FilterSyncPhase.UPDATING
        } else {
            FilterSyncPhase.PREPARING
        }

    /** Claims the progress line for [request] and says what it is doing. */
    private fun startPhase(request: Long, status: FilterStatus? = lastStatus) {
        phaseOwner = request
        notifySync(phaseFor(status))
    }

    /**
     * Releases the progress line only if [request] still owns it.
     *
     * A request that has been replaced by a newer one must not switch the line off while that newer
     * request is still downloading.
     */
    private fun endPhase(request: Long) {
        if (phaseOwner != request) return
        phaseOwner = null
        notifySync(FilterSyncPhase.IDLE)
    }

    private fun notifySync(phase: FilterSyncPhase) {
        for (listener in syncListeners) {
            runCatching { listener(phase) }
        }
    }

    private fun notifyStatus(status: FilterStatus) {
        for (listener in statusListeners) {
            runCatching { listener(status) }
        }
    }

    companion object {

        /** Marks the periodic sweep as the owner of the progress line; it is not a user request. */
        private const val SWEEP_OWNER = Long.MIN_VALUE

        /** A coordinator on [context]'s process-wide snapshot and a private preference file. */
        fun create(context: Context): FilterCoordinator {
            val appContext = context.applicationContext
            val contentStore = SubscriptionContentStore.forFilesDir(appContext.filesDir)
            val applyDispatcher = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "kuies-filter").apply { isDaemon = true }
            }
            val downloadDispatcher = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "kuies-filter-download").apply { isDaemon = true }
            }
            val updater = SubscriptionUpdater(
                source = SubscriptionFetcher.create(),
                contentStore = contentStore,
                dispatcher = downloadDispatcher
            )
            return FilterCoordinator(
                store = FilterSettingsStore(appContext),
                updater = updater,
                assembler = FilterAssembler(FilterSnapshotHolder.repository, contentStore),
                contentStore = contentStore,
                dispatcher = applyDispatcher,
                downloadDispatcher = downloadDispatcher,
                debouncer = ScheduledFilterDebouncer()
            )
        }
    }
}

/**
 * The process-wide coordinator.
 *
 * One instance per process on purpose: [SubscriptionUpdater]'s single-flight guarantee and the
 * snapshot both live in instance state, so two coordinators would mean two downloads of the same list
 * and two writers of the same preference file.
 */
object FilterCoordinatorHolder {

    @Volatile
    private var instance: FilterCoordinator? = null

    fun get(context: Context): FilterCoordinator {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: FilterCoordinator.create(context).also { instance = it }
        }
    }
}
