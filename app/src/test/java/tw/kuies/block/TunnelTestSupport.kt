package tw.kuies.block

import tw.kuies.block.dns.packet.Bytes
import tw.kuies.block.dns.packet.Checksums
import tw.kuies.block.dns.packet.IP_PROTOCOL_UDP
import tw.kuies.block.dns.packet.Ipv4Parser
import tw.kuies.block.dns.packet.DnsInterceptTarget
import tw.kuies.block.dns.packet.UdpParser

/** Addresses used across the tunnel tests. */
object TestNet {
    const val CLIENT = "10.20.30.40"
    const val DNS_SERVER = "10.111.222.2"
    const val OTHER = "10.111.222.9"
    const val DNS_PORT = 53

    val dnsTarget: DnsInterceptTarget = DnsInterceptTarget(
        address = requireNotNull(Bytes.addressFromText(DNS_SERVER)),
        port = DNS_PORT
    )
}

fun ipAddress(text: String): Int = requireNotNull(Bytes.addressFromText(text)) { "bad address $text" }

/** Builds a well-formed IPv4 + UDP datagram carrying [payload]. */
fun ipv4UdpPacket(
    sourceAddress: String,
    destinationAddress: String,
    sourcePort: Int,
    destinationPort: Int,
    payload: ByteArray,
    protocol: Int = IP_PROTOCOL_UDP,
    identification: Int = 0x1234
): ByteArray {
    val source = ipAddress(sourceAddress)
    val destination = ipAddress(destinationAddress)
    val segment = UdpParser.buildSegment(sourcePort, destinationPort, payload, source, destination)
    val header = Ipv4Parser.buildHeader(source, destination, protocol, segment.size, identification)
    return header + segment
}

/** Rewrites the IPv4 fragmentation field and refreshes the header checksum. */
fun withFragmentationField(packet: ByteArray, value: Int): ByteArray {
    Bytes.writeUInt16(packet, 6, value)
    Bytes.writeUInt16(packet, 10, 0)
    Bytes.writeUInt16(packet, 10, Checksums.computeIpv4HeaderChecksum(packet, 0, 20))
    return packet
}

/** Rewrites the IPv4 protocol field and refreshes the header checksum. */
fun withProtocol(packet: ByteArray, protocol: Int): ByteArray {
    packet[9] = protocol.toByte()
    Bytes.writeUInt16(packet, 10, 0)
    Bytes.writeUInt16(packet, 10, Checksums.computeIpv4HeaderChecksum(packet, 0, 20))
    return packet
}
