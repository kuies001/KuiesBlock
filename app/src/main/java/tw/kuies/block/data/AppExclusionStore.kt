package tw.kuies.block.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Persists the packages that must bypass KuiesBlock's VPN.
 *
 * Kept separate from [FilterSettings] on purpose: this is a VPN-scope choice, not a DNS rule, and a
 * later filter migration must never be able to drop it. Package names are the identity; labels are
 * only presentation and may change after an app update.
 */
class AppExclusionStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    /**
     * The stored exclusions. Never cleared because a package query failed: an uninstalled app keeps
     * its entry, so reinstalling the same package restores the user's previous choice.
     */
    fun load(): Set<String> =
        prefs.getStringSet(KEY_EXCLUDED, emptySet())?.toSet().orEmpty()

    fun save(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_EXCLUDED, packages.toSet()).apply()
    }

    fun clear() = save(emptySet())

    companion object {
        const val PREFS_NAME = "kuies_block_app_exclusions"
        private const val KEY_EXCLUDED = "excluded_packages"
    }
}

/** The result of the last attempt to rebuild the tunnel with a new exclusion set. */
sealed interface AppExclusionApplyResult {
    /** The tunnel is running with exactly these exclusions. */
    data class Applied(val packages: Set<String>) : AppExclusionApplyResult

    /**
     * The new set could not be established. [applied] is the previous set the service restored, or
     * whatever it could establish; it is never presented as if the new set had taken effect.
     */
    data class Failed(val desired: Set<String>, val applied: Set<String>) : AppExclusionApplyResult
}

/**
 * In-process hand-off from the VPN service to the settings screen.
 *
 * A broadcast would need an exported/permission surface for a same-process result, and a persisted
 * "applied" value could go stale after a failed rebuild. This holder only reports what the service
 * actually established. Listeners are not replayed, so reopening the screen does not resurrect an
 * old "applied" message.
 */
object AppExclusionApplyStatus {
    @Volatile
    var lastResult: AppExclusionApplyResult? = null
        private set

    private val listeners = CopyOnWriteArrayList<(AppExclusionApplyResult) -> Unit>()

    fun addListener(listener: (AppExclusionApplyResult) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun applied(packages: Set<String>) {
        publish(AppExclusionApplyResult.Applied(packages.toSet()))
    }

    fun failed(desired: Set<String>, applied: Set<String>) {
        publish(AppExclusionApplyResult.Failed(desired.toSet(), applied.toSet()))
    }

    fun reset() {
        lastResult = null
    }

    private fun publish(result: AppExclusionApplyResult) {
        lastResult = result
        listeners.forEach { runCatching { it(result) } }
    }
}
