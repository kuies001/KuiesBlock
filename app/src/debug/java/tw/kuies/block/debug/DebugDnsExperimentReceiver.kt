package tw.kuies.block.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import tw.kuies.block.vpn.DnsExperiment
import tw.kuies.block.vpn.DnsExperimentMode
import tw.kuies.block.vpn.KuiesVpnService

/**
 * Debug-build only: this class lives in the `debug` source set, so a release APK has no such
 * component and cannot switch experiments.
 *
 * It stores the Private-DNS experiment mode and stops any running tunnel so the next start uses the
 * new declaration. It deliberately does not restart the tunnel by itself, so there can be no
 * uncontrolled rebuild loop.
 *
 * Usage (debug APK):
 * ```
 * adb shell am broadcast -n tw.kuies.block/.debug.DebugDnsExperimentReceiver \
 *     -a tw.kuies.block.debug.SET_DNS_EXPERIMENT --es mode e2
 * ```
 * `mode` accepts `baseline`, `e2` or `e1`.
 */
class DebugDnsExperimentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra(EXTRA_MODE)
        val mode = DnsExperimentMode.fromStorageKey(raw)
        DnsExperiment.setMode(context, mode)
        Log.i(TAG, "Debug DNS experiment mode -> ${mode.name} (requested=$raw)")
        // Stop the tunnel so the new declaration takes effect on the next start.
        runCatching { context.stopService(Intent(context, KuiesVpnService::class.java)) }
    }

    companion object {
        const val TAG = "KuiesDnsExperiment"
        const val EXTRA_MODE = "mode"
    }
}
