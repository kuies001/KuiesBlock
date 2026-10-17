package tw.kuies.block.filter

import android.content.Context
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

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
 */
class FilterCoordinator(
    private val store: FilterSettingsStore,
    private val updater: SubscriptionUpdater,
    private val assembler: FilterAssembler,
    private val contentStore: SubscriptionContentStore,
    private val dispatcher: Executor
) {

    /**
     * Guards the whole automatic sweep.
     *
     * Every update already runs on [dispatcher], so automatic and manual work cannot interleave; this
     * flag is what stops a second sweep from starting while one is still going, which is the case a
     * period firing just as the user taps 立即更新 would otherwise produce.
     */
    private val refreshAllInFlight = AtomicBoolean(false)

    /** Publishes what the stored settings and stored list text imply right now, with no network. */
    fun publishCurrent(): FilterStatus {
        val settings = store.load()
        return FilterStatus.of(settings, assembler.assemble(settings))
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
     * Preset modes download their one fixed tier list. Custom mode publishes the selected sources
     * immediately and downloads only the selected sources that have no stored rules yet, so entering
     * custom mode does not turn into a burst of downloads for everything the user ever ticked.
     *
     * [onStatus] may be called twice — once immediately (the state that needs no network) and once
     * when the download settles — so the screen can show progress honestly instead of freezing.
     * [onFinished] is called exactly once, after the last [onStatus].
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
                runCatching { store.save(chosen) }

                // Publish before touching the network: a source whose content is already stored takes
                // effect now, and a mode with nothing downloaded reports exactly that.
                onStatus(FilterStatus.of(chosen, assembler.assemble(chosen)))

                val settled = when (mode) {
                    FilterMode.CUSTOM -> downloadMissingSelected(chosen)
                    else -> downloadProfileList(chosen)
                }
                onStatus(FilterStatus.of(settled, assembler.assemble(settled)))
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
     * The new selection is published before any network work. Selecting a source that has never been
     * downloaded fetches it once; deselecting never deletes its stored content, so turning it back on
     * can reuse the existing list.
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
                runCatching { store.save(chosen) }
                onStatus(FilterStatus.of(chosen, assembler.assemble(chosen)))

                val settled = if (selected) downloadIfMissing(chosen, source) else chosen
                onStatus(FilterStatus.of(settled, assembler.assemble(settled)))
            } finally {
                onFinished()
            }
        }
    }

    /** Re-downloads every source the active mode is using; a failure keeps whatever is stored. */
    fun refresh(onResult: (FilterStatus) -> Unit = {}) {
        dispatcher.execute {
            val settings = runCatching { store.load() }.getOrNull() ?: return@execute
            // No enabled gate: 關閉 is gone, so every stored settings object is configured and there
            // is nothing left to switch off. Whether the tunnel runs is the home switch's business and
            // does not stop the stored lists from being kept up to date.
            val refreshed = downloadAll(settings, settings.effectiveSubscriptions.filter { it.enabled })
            onResult(FilterStatus.of(refreshed, assembler.assemble(refreshed)))
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
     * Serialisation: the download itself runs on [dispatcher], which is a single thread, so a sweep and
     * a manual 立即更新 can never be *inside* [SubscriptionUpdater] at the same time. That is why the
     * second of two consecutive updates of the same list is a conditional request carrying the ETag the
     * first one just stored, rather than a second download of the same content.
     *
     * The [refreshAllInFlight] claim is taken **before** the work is queued, on the caller's thread. A
     * claim taken inside the queued task could never fire: the second sweep would simply wait its turn
     * behind the first and then find the flag free again, so the guard would read as protection while
     * doing nothing at all.
     */
    fun refreshAll(onFinished: (FilterRefreshSummary) -> Unit = {}) {
        if (!refreshAllInFlight.compareAndSet(false, true)) {
            onFinished(FilterRefreshSummary.NOT_RUN)
            return
        }
        try {
            dispatcher.execute { sweep(onFinished) }
        } catch (_: RejectedExecutionException) {
            // A shut-down dispatcher must not strand the claim for the life of the process.
            refreshAllInFlight.set(false)
            onFinished(FilterRefreshSummary.NOT_RUN)
        }
    }

    /** The sweep itself; runs on [dispatcher] and releases the claim on every path. */
    private fun sweep(onFinished: (FilterRefreshSummary) -> Unit) {
        try {
            // Elvis rather than a separate null check: it keeps `settings` non-null for everything
            // below — including the bookkeeping variable — without depending on a smart cast.
            val settings = runCatching { store.load() }.getOrNull() ?: run {
                onFinished(FilterRefreshSummary.NOT_RUN)
                return
            }

            val targets = FilterAutoUpdateSelection.select(settings)
            var current = settings
            var updated = 0
            var unchanged = 0
            var failed = 0
            var skipped = 0

            for (target in targets) {
                val before = current.subscriptions.firstOrNull { it.id == target.id } ?: continue
                val result = updater.update(
                    SubscriptionUpdateRequest(
                        subscription = before,
                        profile = current.profile,
                        etag = current.etagFor(before.id)
                    )
                )
                when (result) {
                    is SubscriptionUpdateResult.Updated -> {
                        current = current.withSubscription(result.subscription)
                            .withEtag(before.id, result.etag)
                        updated++
                    }
                    is SubscriptionUpdateResult.Unchanged -> {
                        current = current.withSubscription(result.subscription)
                        unchanged++
                    }
                    is SubscriptionUpdateResult.Failed -> {
                        current = current.withSubscription(result.subscription)
                        failed++
                    }
                    // Another update for this list held the single flight; that one persists itself.
                    SubscriptionUpdateResult.SkippedInFlight -> skipped++
                }
            }

            // Written once for the whole sweep, then republished once: a partial settings file between
            // two sources would describe a state the user never had.
            runCatching { store.save(current) }
            if (targets.isNotEmpty()) {
                runCatching { assembler.assemble(current) }
            }
            onFinished(
                FilterRefreshSummary(
                    considered = targets.size,
                    updated = updated,
                    unchanged = unchanged,
                    failed = failed,
                    skipped = skipped
                )
            )
        } finally {
            refreshAllInFlight.set(false)
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
            onResult(FilterStatus.of(updated, assembler.assemble(updated)))
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
            onResult(FilterStatus.of(updated, assembler.assemble(updated)))
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
            val updated = download(subscription, settings)
            onResult(FilterStatus.of(updated, assembler.assemble(updated)))
        }
    }

    // ---- downloads --------------------------------------------------------------------------

    /** The active tier's list, expressed as a download of a known subscription. */
    private fun downloadProfileList(settings: FilterSettings): FilterSettings {
        val subscription = settings.builtInSubscription ?: return settings
        return download(subscription, settings)
    }

    /** Downloads only custom-mode sources whose content has never been stored. */
    private fun downloadMissingSelected(settings: FilterSettings): FilterSettings =
        downloadAll(settings, settings.effectiveSubscriptions.filter { it.ruleCount <= 0 })

    /** Downloads [source] when it has no usable stored copy yet. */
    private fun downloadIfMissing(settings: FilterSettings, source: FilterSource): FilterSettings {
        val subscription = settings.subscriptions.firstOrNull { source.matches(it) }
            ?: return settings
        return if (subscription.ruleCount > 0) settings else download(subscription, settings)
    }

    /**
     * Downloads each target in order, persisting after every attempt.
     *
     * A failure still records the source's new bookkeeping, so the screen can report the real state.
     * The updater keeps the previously stored content, so a failed attempt never empties a list.
     */
    private fun downloadAll(
        settings: FilterSettings,
        targets: List<FilterSubscription>
    ): FilterSettings {
        var current = settings
        for (target in targets) {
            val subscription = current.subscriptions.firstOrNull { it.id == target.id } ?: continue
            current = download(subscription, current)
        }
        return current
    }

    /**
     * One update attempt for [subscription], with the result persisted.
     *
     * The bookkeeping the attempt produced is written back in every branch — a failure has to survive
     * a restart too, so the screen can keep telling the user the list is stale. The updater keeps the
     * previously downloaded text when an attempt fails, so a failure never empties a good list.
     */
    private fun download(subscription: FilterSubscription, settings: FilterSettings): FilterSettings {
        val request = SubscriptionUpdateRequest(
            subscription = subscription,
            profile = settings.profile,
            etag = settings.etagFor(subscription.id)
        )

        val updated = when (val result = updater.update(request)) {
            is SubscriptionUpdateResult.Updated ->
                settings.withSubscription(result.subscription)
                    .withEtag(subscription.id, result.etag)

            is SubscriptionUpdateResult.Unchanged -> settings.withSubscription(result.subscription)

            is SubscriptionUpdateResult.Failed -> settings.withSubscription(result.subscription)

            // Another update for this list is already running; its own result will be persisted.
            SubscriptionUpdateResult.SkippedInFlight -> return settings
        }
        runCatching { store.save(updated) }
        return updated
    }

    companion object {

        /** A coordinator on [context]'s process-wide snapshot and a private preference file. */
        fun create(context: Context): FilterCoordinator {
            val appContext = context.applicationContext
            val contentStore = SubscriptionContentStore.forFilesDir(appContext.filesDir)
            val dispatcher = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "kuies-filter").apply { isDaemon = true }
            }
            val updater = SubscriptionUpdater(
                source = SubscriptionFetcher.create(),
                contentStore = contentStore,
                dispatcher = dispatcher
            )
            return FilterCoordinator(
                store = FilterSettingsStore(appContext),
                updater = updater,
                assembler = FilterAssembler(FilterSnapshotHolder.repository, contentStore),
                contentStore = contentStore,
                dispatcher = dispatcher
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
