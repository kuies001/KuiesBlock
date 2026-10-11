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
 * It arms the Private-DNS experiment mode **for the current app process only** — nothing is stored,
 * so a leftover can never quietly replace the shipped rule in a later session (the earlier build
 * persisted it, which is why a stale `e2` silently overrode the production policy). It also stops
 * any running tunnel so the next start uses the new declaration, and it deliberately does not
 * restart the tunnel by itself, so there can be no uncontrolled rebuild loop.
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
        DnsExperiment.arm(context, mode)
        Log.i(TAG, "Debug DNS experiment armed -> ${mode.name} (requested=$raw, this process only)")
        // Stop the tunnel so the new declaration takes effect on the next start.
        runCatching { context.stopService(Intent(context, KuiesVpnService::class.java)) }
    }

    companion object {
        const val TAG = "KuiesDnsExperiment"
        const val EXTRA_MODE = "mode"
    }
}
