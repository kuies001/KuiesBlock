package tw.kuies.block.update

/**
 * One downloadable file attached to a release.
 *
 * The address and digest are untrusted network data. [UpdateAssetPolicy] decides whether an asset is
 * the one official APK this app may fetch; nothing else consumes [downloadUrl] directly.
 */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long = 0L,
    /** GitHub's own digest, normally `sha256:<64 hex>`; null when the release predates it. */
    val digest: String? = null
)

/**
 * What one GitHub release says, reduced to the facts this app uses.
 *
 * [notes] and [pageUrl] are untrusted data: [notes] is only ever displayed as text, and [pageUrl] must
 * pass [ReleasePagePolicy] before it is ever opened. [assets] is parsed only so the update download
 * can select the expected APK and its checksum; every address is validated again before use.
 */
data class ReleaseInfo(
    /** The release's version, parsed from `tag_name`. */
    val version: AppVersion,
    /** The tag exactly as GitHub wrote it, so nothing is lost to the normalising parser. */
    val tagName: String,
    /** The release notes (`body`); empty when the release carries none. */
    val notes: String,
    /** The published page (`html_url`), to be validated by [ReleasePagePolicy] before any use. */
    val pageUrl: String?,
    /** The release's assets, in the order GitHub returned them. */
    val assets: List<ReleaseAsset> = emptyList()
)
