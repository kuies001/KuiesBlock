package tw.kuies.block.filter

import android.content.Context
import java.util.concurrent.Executor
import java.util.concurrent.Executors
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
     * Applies the user's choice of [profile]: that tier's list is downloaded and the snapshot is
     * republished from the stored settings.
     *
     * There is no "switch the filter off" call any more. 關閉 was removed, so the only on/off is the
     * home screen's protection switch, which this class never touches: choosing a strength picks a
     * list, it never starts the tunnel.
     *
     * [onStatus] may be called twice — once immediately (the state that needs no network) and once
     * when the download settles — so the screen can show progress honestly instead of freezing.
     * [onFinished] is called exactly once, after the last [onStatus].
     */
    fun selectStrength(
        profile: FilterProfile,
        onStatus: (FilterStatus) -> Unit = {},
        onFinished: () -> Unit = {}
    ) {
        dispatcher.execute {
            try {
                val base = runCatching { store.load() }.getOrNull() ?: return@execute
                val chosen = base.withProfile(profile)
                runCatching { store.save(chosen) }

                // Publish before touching the network: choosing a tier whose content is already
                // stored takes effect now.
                onStatus(FilterStatus.of(chosen, assembler.assemble(chosen)))

                val settled = downloadProfileList(chosen)
                onStatus(FilterStatus.of(settled, assembler.assemble(settled)))
            } finally {
                onFinished()
            }
        }
    }

    /** Re-downloads the configured tier's list; a failure keeps whatever is stored. */
    fun refresh(onResult: (FilterStatus) -> Unit = {}) {
        dispatcher.execute {
            val settings = runCatching { store.load() }.getOrNull() ?: return@execute
            // No enabled gate: 關閉 is gone, so every stored settings object is configured and there
            // is nothing left to switch off. Whether the tunnel runs is the home switch's business and
            // does not stop the stored list from being kept up to date.
            val refreshed = downloadProfileList(settings)
            onResult(FilterStatus.of(refreshed, assembler.assemble(refreshed)))
        }
    }

    /**
     * Refreshes every source that already has downloaded content, and reports what it found.
     *
     * This is the automatic sweep. Two rules make it safe to run without the user watching:
     *  * **A source with no stored content is never downloaded here.** Automatic updates default to
     *    enabled, so downloading on first run would contradict the rule that nothing is fetched until
     *    the user picks a strength; a fresh install therefore checks nothing and downloads nothing.
     *  * **A failure keeps the previous list.** [SubscriptionUpdater] already guarantees that, so this
     *    only has to record the outcome.
     *
     * Runs on [dispatcher], which is what serialises it against a manual 立即更新 — the second one to
     * arrive waits rather than downloading the same list twice.
     */
    fun refreshAll(onFinished: (FilterRefreshSummary) -> Unit = {}) {
        dispatcher.execute {
            if (!refreshAllInFlight.compareAndSet(false, true)) {
                onFinished(FilterRefreshSummary.NOT_RUN)
                return@execute
            }
            try {
                // Elvis rather than a separate null check: it keeps `settings` non-null for everything
                // below — including the bookkeeping variable — without depending on a smart cast.
                val settings = runCatching { store.load() }.getOrNull() ?: run {
                    onFinished(FilterRefreshSummary.NOT_RUN)
                    return@execute
                }

                val targets = settings.subscriptions.filter { it.enabled && it.ruleCount > 0 }
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

                // Written once for the whole sweep, then republished once: a partial settings file
                // between two sources would describe a state the user never had.
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
