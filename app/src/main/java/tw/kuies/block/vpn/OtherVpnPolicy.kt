package tw.kuies.block.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process

/**
 * Whether a VPN other than KuiesBlock currently owns the system's default network.
 *
 * The guide only ever uses this to show a plain notice: it never stops, disables or reconfigures
 * anything, because another app's VPN is the user's own arrangement.
 *
 * The judgement is split from the platform read so it can be unit tested; [OtherVpnProbe] is the thin
 * part that talks to `ConnectivityManager`.
 */
object OtherVpnPolicy {

    /**
     * [defaultNetworkHasVpnTransport] is the system's default (active) network carrying the VPN
     * transport; [defaultNetworkOwnerUid] is that network's owner when the platform reports one.
     *
     * Our own tunnel is excluded twice over: by ownership where the platform tells us the owner, and
     * by [ourTunnelRunning] otherwise. The second test also covers the moment just after our tunnel
     * stops, when the platform can still report it as the active network for a frame.
     */
    fun anotherVpnHoldsTheSlot(
        defaultNetworkHasVpnTransport: Boolean,
        defaultNetworkOwnerUid: Int?,
        ourUid: Int,
        ourTunnelRunning: Boolean
    ): Boolean {
        if (!defaultNetworkHasVpnTransport) return false
        if (defaultNetworkOwnerUid == ourUid) return false
        return !ourTunnelRunning
    }
}

/** Reads the platform state [OtherVpnPolicy] judges. Read-only: it never touches any VPN. */
object OtherVpnProbe {

    fun anotherVpnHoldsTheSlot(context: Context, ourTunnelRunning: Boolean): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val active = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(active) ?: return false
        // Owner UID is only reported from Android 10 on; below that the "our own tunnel" question is
        // answered by `ourTunnelRunning` alone.
        val ownerUid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            capabilities.ownerUid.takeIf { it != Process.INVALID_UID }
        } else {
            null
        }
        return OtherVpnPolicy.anotherVpnHoldsTheSlot(
            defaultNetworkHasVpnTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            defaultNetworkOwnerUid = ownerUid,
            ourUid = Process.myUid(),
            ourTunnelRunning = ourTunnelRunning
        )
    }
}
