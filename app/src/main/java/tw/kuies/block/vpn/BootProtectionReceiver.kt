package tw.kuies.block.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import tw.kuies.block.data.ProtectionIntentStore

/**
 * The best-effort path that restores protection after a restart.
 *
 * It stands **beside** the system's Always-on VPN, not instead of it: Always-on VPN is the reliable
 * mechanism, while this broadcast is not even delivered to an app that was force-stopped or has never
 * been launched, and the platform may delay or skip it. The settings page says so in as many words —
 * nothing here may claim a guarantee, and nothing here runs unless the user asked for it.
 *
 * What it deliberately never does: show the VPN consent dialog, stop or reconfigure another VPN, touch
 * Private DNS, or retry. It attempts once and leaves a failure to the app's ordinary error state.
 */
class BootProtectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Only the boot broadcast is handled: an unrelated — or spoofed — action can do nothing here.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val appContext = context.applicationContext
        try {
            val store = ProtectionIntentStore(appContext)
            // Read once and record what the always-on stage actually saw. That stage runs *before* the
            // serviceRunning stage, so a read that fails silently makes a platform-started tunnel look
            // like a duplicate this app avoided — which is what the test phone showed on 2026-10-17: the
            // setting really named this app while this gate reported otherwise. Category only, never a
            // package name or any other identifier.
            val configuredAlwaysOnApp = AlwaysOnVpnProbe.readConfiguredApp(appContext)
            val alwaysOnVpn = AlwaysOnVpnProbe.isEnabledFor(appContext.packageName, configuredAlwaysOnApp)
            if (store.bootAutostartEnabled()) {
                Log.i(
                    TAG,
                    "Boot autostart input: alwaysOn=" + when {
                        configuredAlwaysOnApp == null -> "unreadable"
                        alwaysOnVpn -> "this-app"
                        else -> "another-app"
                    }
                )
            }
            val decision = BootAutostartPolicy.decide(
                preferenceEnabled = store.bootAutostartEnabled(),
                authorizationGranted = VpnAuthorization.isGranted(appContext),
                userWantsProtection = store.protectionDesiredOn(),
                serviceRunning = KuiesVpnService.isRunning,
                alwaysOnVpn = alwaysOnVpn,
                otherVpn = OtherVpnProbe.state(appContext, KuiesVpnService.isRunning)
            )
            if (decision == BootAutostartPolicy.Decision.START) {
                KuiesVpnService.start(appContext)
            } else if (store.bootAutostartEnabled()) {
                // One category-only line, and only for a user who asked for this: a boot that did
                // nothing has to be explainable afterwards, and the reason is a decision name — never an
                // owner, an address or anything about the user's network.
                Log.i(TAG, "Boot autostart skipped: ${decision.name}")
            }
        } catch (exception: Exception) {
            // A receiver that throws would take the app process down at boot; a skipped start is
            // always the safer outcome, and the user can still open the app.
            Log.w(TAG, "Boot autostart skipped: ${exception.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "KuiesBlockBoot"
    }
}
