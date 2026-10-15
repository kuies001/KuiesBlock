package tw.kuies.block.vpn

import tw.kuies.block.dns.message.DnsMessageWriter
import tw.kuies.block.dns.message.DnsRcode
import tw.kuies.block.dns.packet.DnsReplyPolicy
import tw.kuies.block.dns.packet.TunnelDnsQuery
import tw.kuies.block.filter.DomainName
import tw.kuies.block.filter.FilterDecision
import tw.kuies.block.filter.FilterSnapshot

/**
 * Applies the local rules to an intercepted query.
 *
 * Pure and socket-free: it either produces the DNS payload to send back or returns null so the query
 * continues to the upstream DoH transport exactly as it did before this feature existed. That
 * split keeps the VPN service's packet handling unchanged and makes the decision fully unit testable.
 *
 * A blocked query is answered with **NXDOMAIN**, the same meaning AdGuard Home already gives to a
 * blocked name, so the client sees one consistent "does not exist" answer whichever layer blocked it.
 * The reply echoes the query's transaction ID and question verbatim, which is what every stub
 * resolver requires.
 */
object LocalDnsFilterPolicy {

    /**
     * The reply for a query the local rules block, or null when it must go upstream.
     *
     * [udpBudget] is the same budget upstream replies obey; when a block reply would not fit, null is
     * returned so the upstream path handles truncation rather than this policy inventing a packet
     * that the client cannot accept.
     */
    fun blockReply(query: TunnelDnsQuery, snapshot: FilterSnapshot, udpBudget: Int): ByteArray? {
        if (snapshot.isEmpty) return null
        val domain = DomainName.normalise(query.domain) ?: return null
        if (snapshot.decide(domain) != FilterDecision.BLOCK) return null

        val reply = DnsMessageWriter.responseHeaderOnly(
            queryBytes = query.queryBytes,
            queryLength = query.queryLength,
            rcode = DnsRcode.NXDOMAIN,
            truncated = false
        )
        return if (DnsReplyPolicy.fitsInUdp(reply.size, udpBudget)) reply else null
    }
}
