package tw.kuies.block.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * Thin wrapper around [VpnService.prepare] so the authorization flow is easy to reason about.
 *
 * [consentIntent] never shows UI by itself: it returns the Intent that the caller must launch
 * from a foreground Activity, or `null` when KuiesBlock already holds the authorization.
 */
object VpnAuthorization {

    fun consentIntent(context: Context): Intent? = VpnService.prepare(context)

    fun isGranted(context: Context): Boolean = VpnService.prepare(context) == null
}
