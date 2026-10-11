package tw.kuies.block.doh

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.random.Random
import tw.kuies.block.dns.message.DnsMessageReader
import tw.kuies.block.dns.message.DnsMessageWriter
import tw.kuies.block.dns.message.DnsRecordReader
import tw.kuies.block.dns.message.DnsResponseOutcome

/**
 * Resolves the DoH hostname through the *underlying* network's own DNS servers over plain UDP.
 *
 * This exists only to bootstrap the DoH endpoint. Without it the app would have to resolve
 * `dns.kuies.tw` through the system resolver, which — once the tunnel is up — is our own VPN DNS,
 * and the handshake would recurse forever.
 *
 * Scope and safety:
 *  * The socket is protected, so the lookup cannot be routed back into the tunnel.
 *  * Only the DoH hostname is looked up here. User queries are never sent in cleartext and are
 *    never silently downgraded to plain DNS.
 *  * A spoofed reply is rejected by checking that the source (via `connect`), transaction id and
 *    response bit all match.
 *  * Failures simply return an empty list; the caller treats that as "DoH unavailable" and applies
 *    the fail-open policy.
 */
class PlainDnsBootstrap(
    private val protector: VpnSocketProtector,
    private val servers: List<InetSocketAddress>,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MS,
    private val queryIdSource: () -> Int = { Random.nextInt(0, 0x10000) }
) : HostnameResolver {

    override fun resolve(host: String): List<InetAddress> {
        if (servers.isEmpty()) return emptyList()
        val addresses = LinkedHashSet<InetAddress>()
        for (type in TYPES_IN_ORDER) {
            for (server in servers) {
                addresses += query(server, host, type)
                if (addresses.isNotEmpty()) return addresses.toList()
            }
        }
        return addresses.toList()
    }

    private fun query(server: InetSocketAddress, host: String, type: Int): List<InetAddress> {
        val transactionId = queryIdSource() and 0xFFFF
        val query = DnsMessageWriter.buildQuery(transactionId, host, type, ednsUdpPayloadSize = null)
        return try {
            DatagramSocket().use { socket ->
                protector.protect(socket)
                socket.soTimeout = timeoutMillis
                socket.connect(server)
                socket.send(DatagramPacket(query, query.size))

                val buffer = ByteArray(MAX_RESPONSE_BYTES)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)

                val length = response.length
                if (length < DnsMessageReader.HEADER_SIZE) return emptyList()
                val parsed = DnsMessageReader.readResponse(buffer, length)
                if (parsed !is DnsResponseOutcome.Ok) return emptyList()
                if (parsed.response.header.id != transactionId) return emptyList()
                DnsRecordReader.readAddressRecords(buffer, length)
            }
        } catch (timeout: SocketTimeoutException) {
            emptyList()
        } catch (io: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 3_000
        private const val MAX_RESPONSE_BYTES = 4_096
        private val TYPES_IN_ORDER = intArrayOf(DnsMessageReader.TYPE_A, DnsMessageReader.TYPE_AAAA)
    }
}
