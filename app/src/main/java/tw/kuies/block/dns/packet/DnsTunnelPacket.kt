package tw.kuies.block.dns.packet

import tw.kuies.block.dns.message.DnsMessageError
import tw.kuies.block.dns.message.DnsMessageReader
import tw.kuies.block.dns.message.DnsQueryOutcome

const val IP_PROTOCOL_UDP = 17

/** The virtual DNS endpoint the tunnel intercepts: an address plus the port we answer on. */
data class DnsInterceptTarget(val address: Int, val port: Int)

/**
 * A DNS query taken from the tunnel, with everything needed to build the reply.
 *
 * [domain] is the question name exactly as it arrived. Canonicalising it (case, trailing dot, IDN)
 * belongs to whatever consumes it, so the packet layer stays a faithful decoder.
 */
class TunnelDnsQuery(
    val transactionId: Int,
    val clientAddress: Int,
    val clientPort: Int,
    val queryBytes: ByteArray,
    val queryLength: Int,
    val ednsUdpPayloadSize: Int?,
    val domain: String
)

sealed interface DnsTunnelOutcome {
    data class Query(val query: TunnelDnsQuery) : DnsTunnelOutcome
    data class Drop(val reason: PacketDropReason) : DnsTunnelOutcome
}

/**
 * Turns a raw tunnel read into either an intercepted DNS query or a non-sensitive drop reason.
 *
 * Everything that cannot be fully validated is dropped: a malformed or unexpected packet is never
 * treated as a query, and is certainly never reported as a blocked ad.
 */
object DnsTunnelPacketParser {

    fun parse(bytes: ByteArray, length: Int, target: DnsInterceptTarget): DnsTunnelOutcome {
        val ip = when (val outcome = Ipv4Parser.parse(bytes, length)) {
            is Ipv4ParseOutcome.Drop -> return DnsTunnelOutcome.Drop(outcome.reason)
            is Ipv4ParseOutcome.Ok -> outcome.packet
        }
        if (ip.protocol != IP_PROTOCOL_UDP) {
            // TCP DNS is intentionally not proxied in this version.
            return DnsTunnelOutcome.Drop(PacketDropReason.NOT_UDP)
        }
        if (ip.destinationAddress != target.address) {
            return DnsTunnelOutcome.Drop(PacketDropReason.NOT_TARGET_ADDRESS)
        }
        val udp = when (val outcome = UdpParser.parse(ip)) {
            is UdpParseOutcome.Drop -> return DnsTunnelOutcome.Drop(outcome.reason)
            is UdpParseOutcome.Ok -> outcome.datagram
        }
        if (udp.destinationPort != target.port) {
            return DnsTunnelOutcome.Drop(PacketDropReason.NOT_DNS_PORT)
        }

        val payload = udp.copyPayload()
        return when (val outcome = DnsMessageReader.readQuery(payload, payload.size)) {
            is DnsQueryOutcome.Invalid -> DnsTunnelOutcome.Drop(dropReasonFor(outcome.error))
            is DnsQueryOutcome.Ok -> DnsTunnelOutcome.Query(
                TunnelDnsQuery(
                    transactionId = outcome.query.header.id,
                    clientAddress = ip.sourceAddress,
                    clientPort = udp.sourcePort,
                    queryBytes = payload,
                    queryLength = payload.size,
                    ednsUdpPayloadSize = outcome.query.ednsUdpPayloadSize,
                    domain = outcome.query.question.name
                )
            )
        }
    }

    private fun dropReasonFor(error: DnsMessageError): PacketDropReason = when (error) {
        DnsMessageError.NOT_A_QUERY -> PacketDropReason.NOT_A_QUERY
        DnsMessageError.NO_QUESTION -> PacketDropReason.NO_QUESTION
        else -> PacketDropReason.MALFORMED_DNS
    }
}

/** Rebuilds an IPv4 + UDP datagram carrying a DNS response back to the client. */
object UdpDnsReplyWriter {

    fun buildReply(
        query: TunnelDnsQuery,
        sourceAddress: Int,
        sourcePort: Int,
        dnsPayload: ByteArray,
        identification: Int
    ): ByteArray {
        val segment = UdpParser.buildSegment(
            sourcePort = sourcePort,
            destinationPort = query.clientPort,
            payload = dnsPayload,
            sourceAddress = sourceAddress,
            destinationAddress = query.clientAddress
        )
        val header = Ipv4Parser.buildHeader(
            sourceAddress = sourceAddress,
            destinationAddress = query.clientAddress,
            protocol = IP_PROTOCOL_UDP,
            payloadLength = segment.size,
            identification = identification
        )
        val packet = ByteArray(header.size + segment.size)
        System.arraycopy(header, 0, packet, 0, header.size)
        System.arraycopy(segment, 0, packet, header.size, segment.size)
        return packet
    }
}

/**
 * Decides how large a UDP reply may be.
 *
 * When the client sent no EDNS(0) OPT it asks for the RFC 1035 limit of 512 bytes. Honouring that
 * strictly would force a truncated reply for many ordinary answers, and a truncated reply makes the
 * resolver retry over TCP DNS, which this version deliberately does not proxy — the lookup would
 * simply fail. So a missing OPT falls back to the largest datagram the link can carry, which real
 * clients accept, and the answer resolves.
 */
object DnsReplyPolicy {

    const val MIN_UDP_PAYLOAD = 512
    private const val IPV4_HEADER = 20
    private const val UDP_HEADER = 8

    /** How many bytes of DNS payload fit in one UDP datagram on a link with [mtu] bytes. */
    fun ipv4UdpBudget(mtu: Int): Int = mtu - IPV4_HEADER - UDP_HEADER

    fun udpPayloadBudget(clientEdnsSize: Int?, ipv4Budget: Int): Int {
        if (ipv4Budget <= MIN_UDP_PAYLOAD) return ipv4Budget
        val requested = clientEdnsSize ?: ipv4Budget
        return requested.coerceIn(MIN_UDP_PAYLOAD, ipv4Budget)
    }

    fun fitsInUdp(responseLength: Int, budget: Int): Boolean = responseLength <= budget
}
