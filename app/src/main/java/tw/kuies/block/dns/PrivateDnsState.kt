package tw.kuies.block.dns

import android.content.Context
import android.net.Uri
import android.provider.Settings

/** Android's Private DNS (DoT) mode. */
enum class PrivateDnsMode { OFF, OPPORTUNISTIC, HOSTNAME, UNKNOWN }

/**
 * Android's Private DNS setting, as far as it can be read reliably.
 *
 * [hostname] is only populated when the mode is [PrivateDnsMode.HOSTNAME] and the system actually
 * reported a specifier. Android keeps the last specifier around even in "automatic" mode, so
 * reporting it in any other mode would be a guess — and this class never guesses.
 */
data class PrivateDnsState(
    val mode: PrivateDnsMode,
    val hostname: String?
) {
    /** True whenever interception cannot be assumed: anything except an explicit "off". */
    val mayBypassInterception: Boolean get() = mode != PrivateDnsMode.OFF

    /** True when strict Private DNS is pinned to a specific hostname. */
    val isStrictHostname: Boolean get() = mode == PrivateDnsMode.HOSTNAME

    /**
     * True only when a hostname was read *and* it is the same host KuiesBlock uses.
     *
     * The hostname is compared, never displayed: an arbitrary internal private-DNS host is the
     * user's own information and has no reason to appear in the UI.
     */
    fun pinsHostname(host: String): Boolean {
        val pinned = hostname?.trim()?.trimEnd('.') ?: return false
        val candidate = host.trim().trimEnd('.')
        return pinned.isNotEmpty() && candidate.isNotEmpty() && pinned.equals(candidate, ignoreCase = true)
    }

    companion object {
        val UNKNOWN = PrivateDnsState(PrivateDnsMode.UNKNOWN, hostname = null)
    }
}

/**
 * Reads and interprets Android's Private DNS setting.
 *
 * When Private DNS is opportunistic or pinned to a hostname, Android may resolve names over its own
 * encrypted channel instead of the DNS server this VPN provides. Interception can then be bypassed
 * (DNS still works normally). KuiesBlock reports that plainly instead of claiming it filtered
 * queries it never saw, and never changes the user's setting.
 */
object PrivateDnsSettings {

    private const val MODE_OFF = "off"
    private const val MODE_OPPORTUNISTIC = "opportunistic"
    private const val MODE_HOSTNAME = "hostname"

    /**
     * `Settings.Global.PRIVATE_DNS_MODE` is hidden API, so the documented settings keys are used
     * directly. Reading them needs no permission and neither value is ever modified by the app.
     */
    private const val PRIVATE_DNS_MODE_KEY = "private_dns_mode"
    private const val PRIVATE_DNS_SPECIFIER_KEY = "private_dns_specifier"

    /**
     * The global keys whose change can move the effective DNS path. Exposed so the service and the
     * UI observe exactly the same two keys instead of each hard-coding them.
     *
     * Observing them is how AOSP itself tracks this setting (DnsManager, owned by
     * ConnectivityService). Two caveats shape the design: an observer only fires while the process
     * is alive and registered, and a multi-user change needs `UserHandle.USER_ALL`; both are
     * handled by re-reading the setting on every start as well.
     */
    val OBSERVED_GLOBAL_KEYS: List<String> = listOf(PRIVATE_DNS_MODE_KEY, PRIVATE_DNS_SPECIFIER_KEY)

    /** `content://settings/global/<key>` for every observed key. */
    fun observedUris(): List<Uri> = OBSERVED_GLOBAL_KEYS.map { Settings.Global.getUriFor(it) }

    /** Pure mapping so the interpretation is testable without a device. */
    fun modeOf(rawMode: String?): PrivateDnsMode = when (rawMode?.trim()?.lowercase()) {
        MODE_OFF -> PrivateDnsMode.OFF
        MODE_OPPORTUNISTIC -> PrivateDnsMode.OPPORTUNISTIC
        MODE_HOSTNAME -> PrivateDnsMode.HOSTNAME
        else -> PrivateDnsMode.UNKNOWN
    }

    /**
     * Pure mapping from the two raw settings. A specifier is only kept for the strict hostname
     * mode, so a stale value left behind by Android can never be presented as the active setting.
     */
    fun stateOf(rawMode: String?, rawSpecifier: String?): PrivateDnsState {
        val mode = modeOf(rawMode)
        val hostname = if (mode == PrivateDnsMode.HOSTNAME) {
            rawSpecifier?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        return PrivateDnsState(mode, hostname)
    }

    /**
     * True whenever interception cannot be assumed. Only an explicit "off" lets KuiesBlock claim
     * the system resolver is using the DNS server this VPN provides.
     */
    fun mayBypassInterception(mode: PrivateDnsMode): Boolean = mode != PrivateDnsMode.OFF

    fun read(context: Context): PrivateDnsState = stateOf(
        rawMode = readGlobal(context, PRIVATE_DNS_MODE_KEY),
        rawSpecifier = readGlobal(context, PRIVATE_DNS_SPECIFIER_KEY)
    )

    private fun readGlobal(context: Context, key: String): String? =
        runCatching { Settings.Global.getString(context.contentResolver, key) }.getOrNull()
}
