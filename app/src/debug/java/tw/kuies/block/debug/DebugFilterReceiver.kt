package tw.kuies.block.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import tw.kuies.block.dns.message.DnsMessageReader
import tw.kuies.block.dns.message.DnsMessageWriter
import tw.kuies.block.dns.message.DnsResponseOutcome
import tw.kuies.block.filter.DomainName
import tw.kuies.block.filter.DomainSet
import tw.kuies.block.filter.FilterSnapshotHolder
import tw.kuies.block.filter.LocalFilterStats
import tw.kuies.block.vpn.VpnRoutePlan

/**
 * Debug-build only verification hook for the local filter. A release APK has no such component.
 *
 * Two things here cannot be observed from `adb shell`: shell name resolution does **not** traverse
 * the app's VPN, and a locally blocked name looks identical to an upstream block. So this receiver
 * lets a test (1) install a known rule set, and (2) ask the app itself to send a DNS query to the
 * virtual DNS address — the app process is inside the tunnel, so that query takes the real path
 * (reader → parse → local rules → reply) and the answered rcode is what the test asserts on.
 *
 * Usage:
 * ```
 * adb shell am broadcast -n tw.kuies.block/.debug.DebugFilterReceiver \
 *     -a tw.kuies.block.debug.SET_FILTER_RULES --es domains example.com,ads.test
 * adb shell am broadcast -n tw.kuies.block/.debug.DebugFilterReceiver \
 *     -a tw.kuies.block.debug.PROBE_DNS --es domain example.com
 * adb shell am broadcast -n tw.kuies.block/.debug.DebugFilterReceiver \
 *     -a tw.kuies.block.debug.CLEAR_FILTER_RULES
 * logcat -s KuiesFilterProbe
 * ```
 * Nothing is persisted, so a forgotten seed cannot outlive the process.
 */
class DebugFilterReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SET_RULES -> setRules(intent.getStringExtra(EXTRA_DOMAINS))
            ACTION_CLEAR_RULES -> clearRules()
            ACTION_PROBE -> {
                // Off the main thread: a socket wait must never be able to ANR the app.
                Thread({ probe(intent.getStringExtra(EXTRA_DOMAIN)) }, "kuies-filter-probe").start()
            }
            ACTION_RESOLVE_SYSTEM -> {
                Thread(
                    { resolveThroughSystemResolver(intent.getStringExtra(EXTRA_DOMAIN)) },
                    "kuies-sysresolve"
                ).start()
            }
            ACTION_RESET_COUNTER -> {
                LocalFilterStats.reset()
                Log.i(TAG, "probe counter reset")
            }
        }
    }

    private fun setRules(raw: String?) {
        val requested = raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val accepted = requested.mapNotNull { DomainName.normalise(it) }.distinct()
        if (accepted.size > MAX_SEEDED_RULES || accepted.isEmpty()) {
            Log.w(TAG, "seed rejected: ${accepted.size} usable domain(s) of ${requested.size}")
            return
        }
        LocalFilterStats.reset()
        FilterSnapshotHolder.repository.publish(subscriptionBlock = DomainSet.of(accepted))
        Log.i(TAG, "seeded ${accepted.size} rule(s); snapshot version=${FilterSnapshotHolder.repository.version}")
    }

    private fun clearRules() {
        LocalFilterStats.reset()
        FilterSnapshotHolder.repository.clear()
        Log.i(TAG, "rules cleared; snapshot version=${FilterSnapshotHolder.repository.version}")
    }

    /**
     * Sends one real UDP DNS query to the virtual DNS address and reports what came back.
     *
     * This socket is deliberately **not** protected: only the upstream DoH sockets are, so this
     * query is routed into our own tunnel like any other app's would be.
     */
    private fun probe(domain: String?) {
        val name = domain?.trim().orEmpty()
        if (name.isEmpty()) {
            Log.w(TAG, "probe needs --es domain")
            return
        }
        val plan = VpnRoutePlan.DEFAULT
        val query = DnsMessageWriter.buildQuery(
            transactionId = PROBE_TRANSACTION_ID,
            name = name,
            type = DnsMessageReader.TYPE_A
        )
        val startedAt = System.currentTimeMillis()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = PROBE_TIMEOUT_MILLIS
                socket.send(
                    DatagramPacket(
                        query,
                        query.size,
                        InetAddress.getByName(plan.dnsServerAddress),
                        plan.dnsPort
                    )
                )
                val buffer = ByteArray(PROBE_BUFFER_BYTES)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                val elapsed = System.currentTimeMillis() - startedAt
                val parsed = DnsMessageReader.readResponse(buffer, response.length)
                val rcode = (parsed as? DnsResponseOutcome.Ok)?.response?.rcode
                val answers = (parsed as? DnsResponseOutcome.Ok)?.response?.answerCount
                Log.i(
                    TAG,
                    "probe domain=$name rcode=$rcode answers=$answers bytes=${response.length} " +
                        "elapsedMs=$elapsed locallyBlocked=${LocalFilterStats.blocked}"
                )
            }
        } catch (timeout: java.net.SocketTimeoutException) {
            Log.i(
                TAG,
                "probe domain=$name rcode=TIMEOUT answers=- locallyBlocked=${LocalFilterStats.blocked}"
            )
        } catch (error: Exception) {
            Log.i(
                TAG,
                "probe domain=$name rcode=ERROR(${error.javaClass.simpleName}) answers=- " +
                    "locallyBlocked=${LocalFilterStats.blocked}"
            )
        }
    }

    /**
     * Resolves [domain] with Android's **system** resolver and reports what happened.
     *
     * This is the probe that answers the round's real question: whether this app's rules can influence
     * what an ordinary app resolves. Unlike [probe], which sends a packet straight to the virtual DNS,
     * this one takes the same path any other app's lookup takes — whatever DNS the default network has
     * configured — so it works exactly when the system is still handing DNS to the tunnel.
     *
     * The caller must use a name that cannot be in the resolver cache (a fresh subdomain), otherwise no
     * query is sent and the result proves nothing. The resolved addresses are counted, never logged:
     * the log stays a test artefact, not a record of what any name resolved to.
     */
    private fun resolveThroughSystemResolver(domain: String?) {
        val name = domain?.trim().orEmpty()
        if (name.isEmpty()) {
            Log.w(TAG, "resolve needs --es domain")
            return
        }
        val blockedBefore = LocalFilterStats.blocked
        val startedAt = System.currentTimeMillis()
        val elapsed = { System.currentTimeMillis() - startedAt }
        try {
            val addresses = InetAddress.getAllByName(name)
            Log.i(
                TAG,
                "systemLookup domain=$name result=OK addressCount=${addresses.size} " +
                    "elapsedMs=${elapsed()} locallyBlockedBefore=$blockedBefore " +
                    "locallyBlockedAfter=${LocalFilterStats.blocked}"
            )
        } catch (unknown: java.net.UnknownHostException) {
            Log.i(
                TAG,
                "systemLookup domain=$name result=UNKNOWN_HOST elapsedMs=${elapsed()} " +
                    "locallyBlockedBefore=$blockedBefore locallyBlockedAfter=${LocalFilterStats.blocked}"
            )
        } catch (error: Exception) {
            Log.i(
                TAG,
                "systemLookup domain=$name result=ERROR(${error.javaClass.simpleName}) " +
                    "elapsedMs=${elapsed()} locallyBlockedBefore=$blockedBefore " +
                    "locallyBlockedAfter=${LocalFilterStats.blocked}"
            )
        }
    }

    companion object {
        const val TAG = "KuiesFilterProbe"
        const val ACTION_SET_RULES = "tw.kuies.block.debug.SET_FILTER_RULES"
        const val ACTION_CLEAR_RULES = "tw.kuies.block.debug.CLEAR_FILTER_RULES"
        const val ACTION_PROBE = "tw.kuies.block.debug.PROBE_DNS"
        const val ACTION_RESOLVE_SYSTEM = "tw.kuies.block.debug.RESOLVE_SYSTEM"
        const val ACTION_RESET_COUNTER = "tw.kuies.block.debug.RESET_FILTER_COUNTER"
        const val EXTRA_DOMAINS = "domains"
        const val EXTRA_DOMAIN = "domain"

        private const val MAX_SEEDED_RULES = 200
        private const val PROBE_TRANSACTION_ID = 0x4242
        private const val PROBE_TIMEOUT_MILLIS = 4_000
        private const val PROBE_BUFFER_BYTES = 4_096
    }
}
