package tw.kuies.block.update

/**
 * What one GitHub release says, reduced to the facts this app uses.
 *
 * Everything else in the response — asset URLs, authors, digests, download counts — is discarded, so
 * nothing that arrives over the network can be acted on beyond what the update dialog shows.
 *
 * [notes] and [pageUrl] are untrusted data: [notes] is only ever displayed as text, and [pageUrl] must
 * pass [ReleasePagePolicy] before it is ever opened.
 */
data class ReleaseInfo(
    /** The release's version, parsed from `tag_name`. */
    val version: AppVersion,
    /** The tag exactly as GitHub wrote it, so nothing is lost to the normalising parser. */
    val tagName: String,
    /** The release notes (`body`); empty when the release carries none. */
    val notes: String,
    /** The published page (`html_url`), to be validated by [ReleasePagePolicy] before any use. */
    val pageUrl: String?
)
