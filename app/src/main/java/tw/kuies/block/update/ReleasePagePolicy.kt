package tw.kuies.block.update

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The only addresses the update dialog is allowed to open.
 *
 * A release's `html_url` is data from the network, so it is treated as untrusted: only an `https`
 * address on `github.com` itself, under this project's own release path, is returned for opening.
 * Anything else — another host, a look-alike such as `github.com.example.com`, a plain `http`
 * address, or a missing field — yields null, and the dialog then offers no download button at all.
 */
object ReleasePagePolicy {

    private const val OFFICIAL_HOST = "github.com"
    private const val RELEASE_PATH_PREFIX = "/kuies001/KuiesBlock/releases/"

    /** [url] when it is this project's own GitHub release page, otherwise null. */
    fun officialUrlOrNull(url: String?): String? {
        val text = url?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parsed = text.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https") return null
        if (parsed.host != OFFICIAL_HOST) return null
        if (!parsed.encodedPath.startsWith(RELEASE_PATH_PREFIX)) return null
        return parsed.toString()
    }
}
