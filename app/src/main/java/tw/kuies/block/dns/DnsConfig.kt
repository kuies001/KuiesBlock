package tw.kuies.block.dns

/**
 * Transport candidates for the DNS endpoint.
 *
 * DoH is the transport KuiesBlock actually uses and the server is confirmed to support it, so it
 * is the default. The other values are still listed so the app can describe and refuse them
 * explicitly rather than silently picking one.
 */
enum class DnsProtocol(
    val storageKey: String,
    val displayName: String,
    val description: String,
    val defaultPort: Int,
    val encrypted: Boolean
) {
    UNCONFIRMED(
        storageKey = "unconfirmed",
        displayName = "尚未選擇",
        description = "尚未選擇傳輸方式；防護不會啟動。",
        defaultPort = 0,
        encrypted = false
    ),
    DOH(
        storageKey = "doh",
        displayName = "DNS over HTTPS (DoH)",
        description = "透過 HTTPS 加密查詢，預設連接埠 443。",
        defaultPort = 443,
        encrypted = true
    ),
    DOT(
        storageKey = "dot",
        displayName = "DNS over TLS (DoT)",
        description = "伺服器支援 DoT，但此版本尚未實作，防護不會啟動。",
        defaultPort = 853,
        encrypted = true
    ),
    PLAINTEXT(
        storageKey = "plain",
        displayName = "純文字 DNS",
        description = "未加密，容易被窺探或竄改。僅在明確選擇時使用。",
        defaultPort = 53,
        encrypted = false
    );

    companion object {
        /**
         * DoH is confirmed to work against dns.kuies.tw and is the only implemented transport, so it
         * is what an unknown or missing stored value falls back to. Falling back to
         * [UNCONFIRMED] instead would make a fresh install refuse to start protection.
         */
        val DEFAULT: DnsProtocol = DOH

        fun fromStorageKey(key: String?): DnsProtocol =
            entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}

/** Central DNS endpoint + protocol configuration. */
data class DnsConfig(
    val host: String = DEFAULT_HOST,
    val protocol: DnsProtocol = DnsProtocol.DEFAULT,
    val dohPath: String = DEFAULT_DOH_PATH
) {
    companion object {
        const val DEFAULT_HOST = "dns.kuies.tw"
        const val DEFAULT_DOH_PATH = "/dns-query"
    }
}
