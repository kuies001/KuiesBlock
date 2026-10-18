package tw.kuies.block.update

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Which release asset may be downloaded, and which addresses may serve it.
 *
 * The release JSON is untrusted. An asset is usable only when its name is exactly the one this version
 * publishes, there is exactly one such asset, and its `browser_download_url` is this project's own
 * `https://github.com/kuies001/KuiesBlock/releases/download/<tag>/<name>` address. Redirects are
 * allowed only to GitHub's release CDN hosts.
 */
object UpdateAssetPolicy {

    const val PACKAGE_NAME = "tw.kuies.block"

    /** The certificate SHA-256 of the production signing key, from the published v0.4.4 APK. */
    const val EXPECTED_SIGNING_CERT_SHA256 =
        "b6f5ccd5b3c8497e8b095db2c653c0dfe5b1c6e9b84b0f67307aad501af3235c"

    /** A ceiling for the downloaded APK, so a hostile or broken response cannot fill the cache. */
    const val MAX_APK_BYTES = 100L * 1024 * 1024

    private const val OWNER = "kuies001"
    private const val REPO = "KuiesBlock"
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

    fun apkName(version: AppVersion): String = "KuiesBlock-${version.tagText()}.apk"

    fun checksumName(version: AppVersion): String = "KuiesBlock-${version.tagText()}.sha256"

    /** Exactly one official APK asset for [release], or null when the response is ambiguous. */
    fun apkAsset(release: ReleaseInfo): ReleaseAsset? =
        singleOfficialAsset(release, apkName(release.version))

    /** The release's checksum asset, if it publishes one. */
    fun checksumAsset(release: ReleaseInfo): ReleaseAsset? =
        singleOfficialAsset(release, checksumName(release.version))

    /** A GitHub `sha256:<hex>` digest as lowercase hex, or null when it is not that shape. */
    fun sha256FromDigest(digest: String?): String? {
        val text = digest?.trim() ?: return null
        if (!text.startsWith("sha256:", ignoreCase = true)) return null
        val value = text.substringAfter(':').trim().lowercase()
        return value.takeIf { SHA256.matches(it) }
    }

    /**
     * The hash from a `.sha256` file.
     *
     * Accepts the common `hash  filename` form and a bare hash. When a filename is present it must be
     * the APK this release names, so a checksum copied from another asset cannot validate this one.
     */
    fun sha256FromChecksumFile(text: String, expectedApkName: String): String? {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val parts = first.split(Regex("\\s+"))
        val hash = parts.firstOrNull()?.lowercase()?.takeIf { SHA256.matches(it) } ?: return null
        val named = parts.drop(1).joinToString(" ").trim().removePrefix("*")
        if (named.isNotEmpty() && named != expectedApkName) return null
        return hash
    }

    /** True only for this project's exact release-download address for [expectedName]. */
    fun isOfficialAssetUrl(tagName: String, expectedName: String, raw: String): Boolean {
        val url = raw.toHttpUrlOrNull() ?: return false
        if (!url.isHttps || url.host != "github.com") return false
        if (url.query != null || url.fragment != null) return false
        return url.pathSegments == listOf(OWNER, REPO, "releases", "download", tagName, expectedName)
    }

    /** Redirect hops may go to GitHub itself or to its release CDN, and nowhere else. */
    fun isAllowedDownloadHost(url: HttpUrl): Boolean = url.isHttps && url.host in ALLOWED_HOSTS

    private fun singleOfficialAsset(release: ReleaseInfo, name: String): ReleaseAsset? {
        val matches = release.assets.filter { it.name == name }
        if (matches.size != 1) return null
        val asset = matches.single()
        if (!isOfficialAssetUrl(release.tagName, name, asset.downloadUrl)) return null
        if (asset.sizeBytes > MAX_APK_BYTES) return null
        return asset
    }

    private val ALLOWED_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com"
    )
}
