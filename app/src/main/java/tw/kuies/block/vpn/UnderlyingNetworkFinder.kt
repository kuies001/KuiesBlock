package tw.kuies.block.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetSocketAddress

/** The non-VPN network our own upstream traffic should leave through. */
data class UnderlyingNetworkInfo(val network: Network, val dnsServers: List<InetSocketAddress>)

/**
 * Finds the non-VPN network and the DNS servers that belong to it.
 *
 * The VPN transport is always skipped, so a caller can never end up pointed at our own virtual DNS
 * address (which would recurse). A network whose capabilities cannot be read is treated as a VPN
 * and skipped rather than guessed at.
 *
 * Shared by the VPN service (which needs the network itself for `setUnderlyingNetworks`) and by the
 * in-app DNS diagnostic (which needs the same upstream servers the DoH bootstrap uses, instead of
 * the system resolver that Android's Private DNS takes over).
 */
object UnderlyingNetworkFinder {

    const val PLAIN_DNS_PORT = 53

    fun connectivity(context: Context): ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    fun find(context: Context): UnderlyingNetworkInfo? = find(connectivity(context))

    fun find(manager: ConnectivityManager?): UnderlyingNetworkInfo? {
        if (manager == null) return null
        // `activeNetwork` is the non-VPN default until our own tunnel exists, which covers a fresh
        // start without enumerating anything.
        val active = manager.activeNetwork
        if (active != null && !isVpn(manager, active)) {
            withDnsServers(manager, active)?.let { return it }
        }
        return anyUnderlyingNetwork(manager)
    }

    fun dnsServersOf(linkProperties: LinkProperties?): List<InetSocketAddress> =
        linkProperties?.dnsServers.orEmpty().map { InetSocketAddress(it, PLAIN_DNS_PORT) }

    private fun isVpn(manager: ConnectivityManager, network: Network): Boolean =
        manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != false

    private fun withDnsServers(manager: ConnectivityManager, network: Network): UnderlyingNetworkInfo? {
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
        val servers = dnsServersOf(manager.getLinkProperties(network))
        if (servers.isEmpty()) return null
        return UnderlyingNetworkInfo(network, servers)
    }

    /**
     * Fallback for a sticky restart, where the tunnel may still be the active network.
     *
     * Enumerating networks is deprecated in favour of callbacks, but callbacks are asynchronous and
     * the DoH bootstrap has to be ready before the tunnel is created. Skipping the VPN transport
     * keeps this safe.
     */
    @Suppress("DEPRECATION")
    private fun anyUnderlyingNetwork(manager: ConnectivityManager): UnderlyingNetworkInfo? {
        for (network in manager.allNetworks) {
            if (isVpn(manager, network)) continue
            withDnsServers(manager, network)?.let { return it }
        }
        return null
    }
}
