package tw.kuies.block.update

/**
 * The one GitHub response field set the update checker reads.
 *
 * `GET /repos/{owner}/{repo}/releases/latest` already answers with the newest release that is neither
 * a draft nor a pre-release, so that endpoint is the primary guard; the `draft` and `prerelease` flags
 * are still checked here, so a response that contradicts the endpoint — from a proxy, a mirror or a
 * replay — is refused rather than trusted.
 *
 * The parsed values are data. Nothing here is executed, evaluated or followed as an address;
 * [ReleaseInfo.pageUrl] is only ever opened after [ReleasePagePolicy] has approved it.
 */
object ReleaseJson {

    /**
     * Reads [body] into a [ReleaseInfo], or returns null when the document is not a usable, stable
     * release description: not a JSON object, missing or unparsable `tag_name`, or marked as a draft
     * or a pre-release.
     */
    fun parse(body: String): ReleaseInfo? {
        val fields = JsonReader.readObject(body) ?: return null

        // Absent is fine — the endpoint promises a stable release — but present-and-not-false is not:
        // an explicit `true`, or a value that is not a boolean at all, refuses the document.
        if (flag(fields, "draft") != false) return null
        if (flag(fields, "prerelease") != false) return null

        val tagName = fields["tag_name"]?.asText()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val version = AppVersion.parse(tagName) ?: return null

        return ReleaseInfo(
            version = version,
            tagName = tagName,
            notes = fields["body"]?.asText().orEmpty(),
            pageUrl = fields["html_url"]?.asText()?.takeIf { it.isNotBlank() },
            assets = (fields["assets"] as? JsonValue.Items)
                ?.values
                ?.mapNotNull(::parseAsset)
                .orEmpty()
        )
    }

    /** One asset, or null when it does not carry the two fields a download needs. */
    private fun parseAsset(value: JsonValue): ReleaseAsset? {
        val fields = (value as? JsonValue.Fields)?.values ?: return null
        val name = fields["name"]?.asText()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val url = fields["browser_download_url"]?.asText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val size = (fields["size"] as? JsonValue.Number)?.raw?.toLongOrNull()?.takeIf { it >= 0 } ?: 0L
        val digest = fields["digest"]?.asText()?.trim()?.takeIf { it.isNotEmpty() }
        return ReleaseAsset(
            name = name,
            downloadUrl = url,
            sizeBytes = size,
            digest = digest
        )
    }

    /** False when [key] is absent, its value when it is a boolean, and null when it is neither. */
    private fun flag(fields: Map<String, JsonValue>, key: String): Boolean? =
        fields[key]?.asFlag() ?: if (fields.containsKey(key)) null else false
}
