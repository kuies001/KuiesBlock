package tw.kuies.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.update.ReleaseJson

/**
 * The release parser against the shapes GitHub really answers with, and against the shapes it must
 * never trust.
 *
 * Every case is hand-written text: nothing here needs GitHub to be reachable.
 */
class ReleaseJsonTest {

    /** A response shaped like the real one, with the nested objects and arrays a real one carries. */
    private fun releaseBody(
        tag: String = "v0.4.1",
        draft: String = "false",
        prerelease: String = "false",
        body: String = "修正了一些問題。",
        htmlUrl: String = "\"https://github.com/kuies001/KuiesBlock/releases/tag/v0.4.1\""
    ) = """
        {
          "url": "https://api.github.com/repos/kuies001/KuiesBlock/releases/1",
          "id": 415014795,
          "tag_name": "$tag",
          "draft": $draft,
          "prerelease": $prerelease,
          "body": "$body",
          "html_url": $htmlUrl,
          "author": { "login": "kuies001", "id": 1 },
          "assets": [
            { "name": "KuiesBlock.apk", "size": 2541857, "browser_download_url": "https://example.invalid/a.apk" }
          ]
        }
    """.trimIndent()

    @Test
    fun readsTheVersionTheNotesAndThePageFromAStableRelease() {
        val parsed = ReleaseJson.parse(releaseBody())

        assertTrue(parsed != null)
        assertEquals("0.4.1", parsed!!.version.text())
        assertEquals("v0.4.1", parsed.tagName)
        assertEquals("修正了一些問題。", parsed.notes)
        assertEquals(
            "https://github.com/kuies001/KuiesBlock/releases/tag/v0.4.1",
            parsed.pageUrl
        )
    }

    @Test
    fun readsTheOfficialApkAssetAndItsDigest() {
        val sha = "ce804b50826187cd9c7cc0044e42549cae11dad6c4f88783ec1d14c9d9f68974"
        val parsed = ReleaseJson.parse(
            """
            {
              "tag_name": "v0.4.41",
              "assets": [
                {
                  "name": "KuiesBlock-v0.4.41.apk",
                  "size": 123,
                  "browser_download_url": "https://github.com/kuies001/KuiesBlock/releases/download/v0.4.41/KuiesBlock-v0.4.41.apk",
                  "digest": "sha256:$sha"
                },
                {
                  "name": "KuiesBlock-v0.4.41.sha256",
                  "size": 89,
                  "browser_download_url": "https://github.com/kuies001/KuiesBlock/releases/download/v0.4.41/KuiesBlock-v0.4.41.sha256"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, parsed!!.assets.size)
        assertEquals("KuiesBlock-v0.4.41.apk", parsed.assets[0].name)
        assertEquals(123L, parsed.assets[0].sizeBytes)
        assertEquals("sha256:$sha", parsed.assets[0].digest)
        assertEquals(null, parsed.assets[1].digest)
    }

    @Test
    fun anAssetWithoutANameOrUrlIsIgnored() {
        val parsed = ReleaseJson.parse(
            """{"tag_name":"v0.4.41","assets":[{"size":1},{"name":"a.apk"}]}"""
        )

        assertEquals(0, parsed!!.assets.size)
    }

    @Test
    fun aTagWithoutTheGithubPrefixStillNamesTheSameVersion() {
        val parsed = ReleaseJson.parse(releaseBody(tag = "0.4.1"))

        assertEquals("0.4.1", parsed!!.version.text())
        assertEquals("0.4.1", parsed.tagName)
    }

    @Test
    fun aDraftIsNeverTheLatestStableRelease() {
        assertNull(ReleaseJson.parse(releaseBody(draft = "true")))
    }

    @Test
    fun aPreReleaseIsNeverTheLatestStableRelease() {
        assertNull(ReleaseJson.parse(releaseBody(prerelease = "true")))
    }

    @Test
    fun aFlagThatIsNotABooleanRefusesTheDocument() {
        assertNull(ReleaseJson.parse(releaseBody(draft = "\"no\"")))
        assertNull(ReleaseJson.parse(releaseBody(prerelease = "1")))
    }

    @Test
    fun aMissingOrUnparsableTagIsRefused() {
        assertNull(ReleaseJson.parse("""{"draft":false,"prerelease":false}"""))
        assertNull(ReleaseJson.parse("""{"tag_name":""}"""))
        assertNull(ReleaseJson.parse("""{"tag_name":"0.4"}"""))
        assertNull(ReleaseJson.parse("""{"tag_name":null}"""))
    }

    @Test
    fun missingOptionalFieldsAreToleratedRatherThanFatal() {
        val parsed = ReleaseJson.parse("""{"tag_name":"v0.4.1"}""")

        assertEquals("", parsed!!.notes)
        assertNull(parsed.pageUrl)
    }

    @Test
    fun aBodyThatIsNotAStringIsIgnoredRatherThanTrusted() {
        val parsed = ReleaseJson.parse("""{"tag_name":"v0.4.1","body":{"text":"nested"}}""")

        assertEquals("", parsed!!.notes)
    }

    @Test
    fun escapesInTheNotesAreDecodedExactlyOnce() {
        val json = """{"tag_name":"v0.4.1","body":"A\nB \"引號\" \u4e2d\u6587 \ud83d\ude00"}"""

        assertEquals("A\nB \"引號\" 中文 \uD83D\uDE00", ReleaseJson.parse(json)!!.notes)
    }

    @Test
    fun aRawControlCharacterInsideAStringRefusesTheDocument() {
        assertNull(ReleaseJson.parse("{\"tag_name\":\"v0.4.1\",\"body\":\"line\nbreak\"}"))
    }

    @Test
    fun anythingThatIsNotExactlyOneJsonObjectIsRefused() {
        val malformed = listOf(
            "",
            "   ",
            "not json at all",
            "[]",
            "[{\"tag_name\":\"v0.4.1\"}]",
            "\"v0.4.1\"",
            "{}",
            "{",
            "{\"tag_name\":\"v0.4.1\",}",
            "{\"tag_name\":\"v0.4.1\"} trailing",
            "{'tag_name':'v0.4.1'}",
            "{tag_name:\"v0.4.1\"}"
        )
        malformed.forEach { body ->
            assertNull("'$body' must not parse as a release", ReleaseJson.parse(body))
        }
    }

    @Test
    fun anAmbiguousDocumentWithARepeatedKeyIsRefused() {
        assertNull(ReleaseJson.parse("""{"tag_name":"v0.4.1","tag_name":"v9.9.9"}"""))
    }

    @Test
    fun theParserNeverThrowsOnTruncatedInput() {
        // Every prefix of a real response is fed in: a truncated read must end as "no answer", never as
        // a crash.
        val full = releaseBody()
        for (length in 0..full.length) {
            ReleaseJson.parse(full.substring(0, length))
        }
    }
}
