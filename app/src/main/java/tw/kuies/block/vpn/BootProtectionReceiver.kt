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
            val decision = BootAutostartPolicy.decide(
                preferenceEnabled = store.bootAutostartEnabled(),
                authorizationGranted = VpnAuthorization.isGranted(appContext),
                userWantsProtection = store.protectionDesiredOn(),
                serviceRunning = KuiesVpnService.isRunning,
                alwaysOnVpn = AlwaysOnVpnProbe.isEnabledFor(
                    appContext.packageName,
                    AlwaysOnVpnProbe.readConfiguredApp(appContext)
                ),
                otherVpnActive = OtherVpnProbe.anotherVpnHoldsTheSlot(appContext, KuiesVpnService.isRunning)
            )
            if (decision == BootAutostartPolicy.Decision.START) {
                KuiesVpnService.start(appContext)
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
