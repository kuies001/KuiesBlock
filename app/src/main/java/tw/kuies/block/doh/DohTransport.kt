package tw.kuies.block.doh

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

/**
 * Excludes a socket from the VPN tunnel so our own upstream traffic leaves through the
 * underlying network instead of looping back into the tunnel. Implemented by the VPN service.
 */
interface VpnSocketProtector {

    fun protect(socket: Socket): Boolean

    fun protect(socket: DatagramSocket): Boolean

    companion object {
        /** Used outside a tunnel (unit tests, diagnostics) where there is nothing to protect. */
        val NONE: VpnSocketProtector = object : VpnSocketProtector {
            override fun protect(socket: Socket): Boolean = false
            override fun protect(socket: DatagramSocket): Boolean = false
        }
    }
}

/** Resolves a hostname without relying on the system resolver (which would recurse into the VPN). */
fun interface HostnameResolver {
    fun resolve(host: String): List<InetAddress>
}

/** Sends one DNS wire-format query and returns the response bytes, or a classified failure. */
interface DohTransport : AutoCloseable {

    fun exchange(queryBytes: ByteArray): DohResult

    override fun close() {}
}
