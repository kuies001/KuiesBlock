package tw.kuies.block.doh

import tw.kuies.block.dns.DnsConfig
import tw.kuies.block.dns.DnsProtocol

/** A resolved DoH endpoint. */
data class DohEndpoint(val url: String, val host: String, val port: Int)

sealed interface DohEndpointOutcome {
    data class Ok(val endpoint: DohEndpoint) : DohEndpointOutcome
    data class Invalid(val failure: DohFailure) : DohEndpointOutcome
}

/**
 * Turns the central [DnsConfig] into a DoH URL.
 *
 * Only DoH is accepted: the endpoint is always `https://`, so a plaintext or unimplemented
 * transport can never quietly become the request path.
 */
object DohEndpointPolicy {

    const val TLS_PORT = 443

    /**
     * The encrypted backup upstream. Fixed on purpose: it is only used after the primary has
     * already failed several real round trips, and it is never configurable from the UI.
     */
    val CLOUDFLARE_FALLBACK = DohEndpoint(
        url = "https://cloudflare-dns.com/dns-query",
        host = "cloudflare-dns.com",
        port = TLS_PORT
    )


    fun build(config: DnsConfig): DohEndpointOutcome {
        if (config.protocol != DnsProtocol.DOH) {
            return DohEndpointOutcome.Invalid(DohFailure.PROTOCOL_NOT_DOH)
        }
        val host = config.host.trim()
        if (host.isEmpty() || host.any { it.isWhitespace() } || host.contains('/')) {
            return DohEndpointOutcome.Invalid(DohFailure.INVALID_ENDPOINT)
        }
        val path = when {
            config.dohPath.startsWith("/") -> config.dohPath
            else -> "/" + config.dohPath
        }
        val url = "https://$host$path"
        if (!isHttps(url)) {
            return DohEndpointOutcome.Invalid(DohFailure.INVALID_ENDPOINT)
        }
        return DohEndpointOutcome.Ok(DohEndpoint(url = url, host = host, port = TLS_PORT))
    }

    fun isHttps(url: String): Boolean = url.startsWith("https://", ignoreCase = true)
}
