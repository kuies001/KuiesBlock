package tw.kuies.block.vpn

import tw.kuies.block.dns.packet.Bytes
import tw.kuies.block.dns.packet.DnsInterceptTarget

/**
 * The exact routing plan for the DNS-only tunnel.
 *
 * Only [dnsServerAddress] is routed into the tunnel, and only as a /32 host route. There is
 * deliberately no default route: a full-tunnel VPN without a working forwarding path would cut the
 * user's network off, which this version must never do. IPv6 is not configured at all — no address,
 * no DNS server, no route — so IPv6 traffic stays entirely on the underlying network.
 */
data class VpnRoutePlan(
    val interfaceAddress: String,
    val interfacePrefixLength: Int,
    val dnsServerAddress: String,
    val dnsPrefixLength: Int,
    val dnsPort: Int,
    val mtu: Int
) {
    val dnsServerAddressInt: Int
        get() = requireNotNull(Bytes.addressFromText(dnsServerAddress)) { "DNS address must be IPv4" }

    val interceptTarget: DnsInterceptTarget
        get() = DnsInterceptTarget(dnsServerAddressInt, dnsPort)

    companion object {
        val DEFAULT = VpnRoutePlan(
            interfaceAddress = "10.111.222.1",
            interfacePrefixLength = 32,
            dnsServerAddress = "10.111.222.2",
            dnsPrefixLength = 32,
            dnsPort = 53,
            mtu = 1500
        )
    }
}

/** Guards the invariant that the tunnel can only ever capture DNS traffic. */
object VpnRoutePlanPolicy {

    private const val HOST_ROUTE_PREFIX = 32
    private const val MIN_MTU = 576

    /** A zero-length prefix would capture everything. */
    fun isCatchAllRoute(prefixLength: Int): Boolean = prefixLength == 0

    /** Human-readable problems, empty when the plan is safe to apply. */
    fun problemsWith(plan: VpnRoutePlan): List<String> = buildList {
        if (isCatchAllRoute(plan.interfacePrefixLength)) {
            add("interface address must not be a catch-all route")
        }
        if (isCatchAllRoute(plan.dnsPrefixLength)) {
            add("the DNS route must never be a default route")
        }
        if (plan.dnsPrefixLength != HOST_ROUTE_PREFIX) {
            add("the DNS route must be a /32 host route")
        }
        if (Bytes.addressFromText(plan.interfaceAddress) == null) {
            add("the interface address must be a valid IPv4 address")
        }
        if (Bytes.addressFromText(plan.dnsServerAddress) == null) {
            add("the DNS server must be a valid IPv4 address")
        }
        if (plan.mtu < MIN_MTU) {
            add("MTU ${plan.mtu} is below the usable minimum")
        }
        if (plan.dnsPort !in 1..65535) {
            add("DNS port out of range")
        }
    }

    fun isValid(plan: VpnRoutePlan): Boolean = problemsWith(plan).isEmpty()
}
