package tw.kuies.block.dns

import android.content.Context
import android.provider.Settings

/** Android's Private DNS (DoT) mode. */
enum class PrivateDnsMode { OFF, OPPORTUNISTIC, HOSTNAME, UNKNOWN }

/**
 * Reads and interprets Android's Private DNS setting.
 *
 * When Private DNS is opportunistic or set to a hostname, Android may resolve names over its own
 * encrypted channel instead of the DNS server this VPN provides. Interception can then be bypassed
 * (DNS still works normally). KuiesBlock reports that plainly instead of claiming it filtered
 * queries it never saw, and never changes the user's setting.
 */
object PrivateDnsSettings {

    private const val MODE_OFF = "off"
    private const val MODE_OPPORTUNISTIC = "opportunistic"
    private const val MODE_HOSTNAME = "hostname"

    /**
     * `Settings.Global.PRIVATE_DNS_MODE` is hidden API, so the documented settings key is used
     * directly. Reading it needs no permission and the value is never modified by the app.
     */
    private const val PRIVATE_DNS_MODE_KEY = "private_dns_mode"

    /** Pure mapping so the interpretation is testable without a device. */
    fun modeOf(rawMode: String?): PrivateDnsMode = when (rawMode?.trim()?.lowercase()) {
        MODE_OFF -> PrivateDnsMode.OFF
        MODE_OPPORTUNISTIC -> PrivateDnsMode.OPPORTUNISTIC
        MODE_HOSTNAME -> PrivateDnsMode.HOSTNAME
        else -> PrivateDnsMode.UNKNOWN
    }

    /**
     * True whenever interception cannot be assumed: Private DNS is opportunistic or pinned to a
     * hostname, or the setting could not be read at all. Only an explicit "off" lets KuiesBlock
     * claim the system resolver is using the DNS server this VPN provides.
     */
    fun mayBypassInterception(mode: PrivateDnsMode): Boolean = mode != PrivateDnsMode.OFF

    fun read(context: Context): PrivateDnsMode = modeOf(
        runCatching {
            Settings.Global.getString(context.contentResolver, PRIVATE_DNS_MODE_KEY)
        }.getOrNull()
    )
}
