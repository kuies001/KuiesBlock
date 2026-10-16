package tw.kuies.block.vpn

import android.app.Activity
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

    /**
     * Whether the consent dialog's result code is a grant.
     *
     * One definition, used by every caller: only an explicit OK is an authorization, while a refusal,
     * a dismissal and a cancelled dialog are all "not granted" and must never be recorded or shown as
     * one. Kept Android-UI-free (only the result code), so it is testable on the JVM.
     */
    fun consentGranted(resultCode: Int): Boolean = resultCode == Activity.RESULT_OK
}
