package tw.kuies.block.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetSocketAddress

/** The non-VPN network our own upstream traffic should leave through, and what it offers us. */
data class UnderlyingNetworkInfo(
    val network: Network,
    val candidate: UnderlyingCandidate,
    val dnsServers: List<InetSocketAddress>
)

/**
 * Finds the non-VPN network the tunnel's upstream traffic should leave through.
 *
 * The VPN transport is always skipped, so a caller can never end up pointed at our own virtual DNS
 * address (which would recurse). A network whose capabilities cannot be read is treated as a VPN
 * and skipped rather than guessed at.
 *
 * Which candidate wins is decided by [UnderlyingNetworkPolicy], never by enumeration order: the
 * platform lists cellular before Wi-Fi in `allNetworks`, so "the first network that qualifies" is
 * cellular even when Wi-Fi is the network Android is actually using.
 *
 * Shared by the VPN service (which needs the network itself for `setUnderlyingNetworks`) and by the
 * in-app DNS diagnostic (which needs the same upstream servers the DoH bootstrap uses, instead of
 * the system resolver that Android's Private DNS takes over).
 */
object UnderlyingNetworkFinder {

    const val PLAIN_DNS_PORT = 53

    fun connectivity(context: Context): ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    /**
     * The network to start the tunnel on.
     *
     * The system's active network is used when it is a usable non-VPN network, because that is the
     * network it routes traffic over today. Otherwise every candidate is ranked, so a start while
     * another VPN holds the default slot (or while our own tunnel is still tearing down) still picks
     * the network the system would use instead of the first one that happens to qualify.
     *
     * The DoH bootstrap needs a resolver, so a network that declares no DNS servers cannot start the
     * tunnel and is not a candidate here.
     */
    fun find(context: Context): UnderlyingNetworkInfo? = find(connectivity(context))

    fun find(manager: ConnectivityManager?): UnderlyingNetworkInfo? {
        if (manager == null) return null
        val candidates = allDetected(manager).filter { it.dnsServers.isNotEmpty() }
        if (candidates.isEmpty()) return null
        val decision = UnderlyingNetworkPolicy.decide(
            candidates = candidates.map { it.candidate },
            current = null,
            systemDefault = activeDetected(manager)?.candidate
        )
        val chosen = decision.candidate ?: return null
        return candidates.firstOrNull { it.candidate.handle == chosen.handle }
    }

    /**
     * The system's active network, when it is a network the tunnel can ride on. This is the
     * system's own answer to "which network carries this traffic", so the policy treats it as
     * authoritative; it is null while our own tunnel is the active network.
     */
    fun activeDetected(manager: ConnectivityManager): UnderlyingNetworkInfo? =
        manager.activeNetwork?.let { detect(manager, it) }

    /**
     * Describes [network] as a candidate for the tunnel's upstream. Returns null for a VPN
     * (including this app's own tunnel) and for any network without real internet access.
     *
     * The DNS servers may be empty: a network can still be the right answer for
     * `setUnderlyingNetworks` while offering nothing to bootstrap DoH with.
     */
    fun detect(manager: ConnectivityManager, network: Network): UnderlyingNetworkInfo? {
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        if (!isUsableUpstream(capabilities)) return null
        return UnderlyingNetworkInfo(
            network = network,
            candidate = candidateOf(network, capabilities),
            dnsServers = dnsServersOf(manager.getLinkProperties(network))
        )
    }

    fun dnsServersOf(linkProperties: LinkProperties?): List<InetSocketAddress> =
        linkProperties?.dnsServers.orEmpty().map { InetSocketAddress(it, PLAIN_DNS_PORT) }

    /** Every non-VPN network with internet access, in whatever order the platform reports them. */
    @Suppress("DEPRECATION")
    private fun allDetected(manager: ConnectivityManager): List<UnderlyingNetworkInfo> =
        manager.allNetworks.mapNotNull { detect(manager, it) }

    private fun isUsableUpstream(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    private fun candidateOf(network: Network, capabilities: NetworkCapabilities) = UnderlyingCandidate(
        handle = network.networkHandle,
        transport = transportOf(capabilities),
        validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    )

    private fun transportOf(capabilities: NetworkCapabilities): UnderlyingTransport = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> UnderlyingTransport.CELLULAR
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> UnderlyingTransport.LOCAL
        else -> UnderlyingTransport.OTHER
    }
}
