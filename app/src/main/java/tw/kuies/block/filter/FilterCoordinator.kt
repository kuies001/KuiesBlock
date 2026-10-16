package tw.kuies.block.filter

import android.content.Context
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The one place that turns the stored settings plus the downloaded lists into the snapshot the VPN
 * reads, and the only place the app downloads a list from.
 *
 * Why it exists: the engine, the settings model, the store, the fetcher and the assembler were all
 * in place, but nothing in the app ever assembled a snapshot — so the rules could never take effect
 * on a real install. This class closes that gap and nothing more.
 *
 * Rules it keeps:
 *  * **Nothing happens until the user chooses a strength.** [FilterSettings.enabled] is false until
 *    then, so a fresh install neither downloads anything nor publishes a single rule.
 *  * **Publishing is immediate and local.** Switching on (or off) republishes from what is already
 *    stored before any network work, so the packet path never waits for a download.
 *  * **A failed download never removes protection.** The updater keeps the previous content and the
 *    assembler keeps using it, exactly as [FilterSettings.contributesToSnapshot] describes.
 *  * **Parsing happens here, never on the packet path.** All of it runs on [dispatcher].
 */
class FilterCoordinator(
    private val store: FilterSettingsStore,
    private val updater: SubscriptionUpdater,
    private val assembler: FilterAssembler,
    private val dispatcher: Executor
) {

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
     * Applies the user's choice.
     *
     * [profile] = a tier: the filter switches on and that tier's list is downloaded. `null`: the
     * filter switches off, and the stored lists are **kept** so choosing a tier again applies them
     * without another download.
     *
     * [onStatus] may be called twice — once immediately (the state that needs no network) and once
     * when the download settles — so the screen can show progress honestly instead of freezing.
     * [onFinished] is called exactly once, after the last [onStatus].
     */
    fun selectStrength(
        profile: FilterProfile?,
        onStatus: (FilterStatus) -> Unit = {},
        onFinished: () -> Unit = {}
    ) {
        dispatcher.execute {
            try {
                val base = runCatching { store.load() }.getOrNull() ?: return@execute
                val chosen = if (profile == null) base.withEnabled(false) else base.withProfile(profile)
                runCatching { store.save(chosen) }

                // Publish before touching the network: switching on with content already stored takes
                // effect now, and switching off stops filtering now.
                onStatus(FilterStatus.of(chosen, assembler.assemble(chosen)))
                if (profile == null) return@execute

                val settled = downloadProfileList(chosen)
                onStatus(FilterStatus.of(settled, assembler.assemble(settled)))
            } finally {
                onFinished()
            }
        }
    }

    /** Re-downloads the enabled tier's list; a failure keeps whatever is stored. */
    fun refresh(onResult: (FilterStatus) -> Unit = {}) {
        dispatcher.execute {
            val settings = runCatching { store.load() }.getOrNull() ?: return@execute
            val refreshed = if (settings.enabled) downloadProfileList(settings) else settings
            onResult(FilterStatus.of(refreshed, assembler.assemble(refreshed)))
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

    /**
     * One update attempt for the active tier's list, with the result persisted.
     *
     * The bookkeeping the attempt produced is written back in every branch — a failure has to survive
     * a restart too, so the screen can keep telling the user the list is stale.
     */
    private fun downloadProfileList(settings: FilterSettings): FilterSettings {
        val subscription = settings.builtInSubscription ?: return settings
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
