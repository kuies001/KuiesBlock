package tw.kuies.block

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kuies.block.update.AppVersion
import tw.kuies.block.update.ReleaseAsset
import tw.kuies.block.update.ReleaseInfo
import tw.kuies.block.update.UpdateAssetPolicy

/**
 * The asset-selection rules that stand between a GitHub response and a downloaded APK.
 *
 * Only one exact, official APK address may be used; a duplicate, a look-alike host, another tag, or a
 * checksum naming another file all fail closed.
 */
class UpdateAssetPolicyTest {

    private val version = requireNotNull(AppVersion.parse("0.4.41"))
    private val sha = "ce804b50826187cd9c7cc0044e42549cae11dad6c4f88783ec1d14c9d9f68974"
    private val apkUrl =
        "https://github.com/kuies001/KuiesBlock/releases/download/v0.4.41/KuiesBlock-v0.4.41.apk"
    private val checksumUrl =
        "https://github.com/kuies001/KuiesBlock/releases/download/v0.4.41/KuiesBlock-v0.4.41.sha256"

    private fun release(vararg assets: ReleaseAsset) = ReleaseInfo(
        version = version,
        tagName = "v0.4.41",
        notes = "",
        pageUrl = null,
        assets = assets.toList()
    )

    private fun apk(
        name: String = "KuiesBlock-v0.4.41.apk",
        url: String = apkUrl,
        size: Long = 123L,
        digest: String? = "sha256:$sha"
    ) = ReleaseAsset(name, url, size, digest)

    @Test
    fun namesFollowTheThreePartVersion() {
        assertEquals("KuiesBlock-v0.4.41.apk", UpdateAssetPolicy.apkName(version))
        assertEquals("KuiesBlock-v0.4.41.sha256", UpdateAssetPolicy.checksumName(version))
    }

    @Test
    fun selectsOnlyTheExactOfficialApkAndChecksum() {
        val release = release(
            apk(),
            ReleaseAsset("KuiesBlock-v0.4.41.sha256", checksumUrl, 89L)
        )

        assertEquals("KuiesBlock-v0.4.41.apk", UpdateAssetPolicy.apkAsset(release)?.name)
        assertEquals("KuiesBlock-v0.4.41.sha256", UpdateAssetPolicy.checksumAsset(release)?.name)
        assertEquals(sha, UpdateAssetPolicy.sha256FromDigest("sha256:$sha"))
    }

    @Test
    fun aLookAlikeHostOrAnotherTagIsRefused() {
        val lookAlike = apk(url = apkUrl.replace("github.com", "github.com.evil.example"))
        val anotherTag = apk(url = apkUrl.replace("v0.4.41", "v0.4.40"))

        assertNull(UpdateAssetPolicy.apkAsset(release(lookAlike)))
        assertNull(UpdateAssetPolicy.apkAsset(release(anotherTag)))
    }

    @Test
    fun aDuplicateApkNameIsAmbiguousAndRefused() {
        assertNull(UpdateAssetPolicy.apkAsset(release(apk(), apk(size = 456L))))
    }

    @Test
    fun aMissingOrWrongDigestIsNotAValidChecksum() {
        assertNull(UpdateAssetPolicy.sha256FromDigest(null))
        assertNull(UpdateAssetPolicy.sha256FromDigest("md5:$sha"))
        assertNull(UpdateAssetPolicy.sha256FromDigest("sha256:1234"))
        assertEquals(sha, UpdateAssetPolicy.sha256FromDigest("SHA256:${sha.uppercase()}"))
    }

    @Test
    fun aChecksumFileMustNameTheSameApk() {
        assertEquals(sha, UpdateAssetPolicy.sha256FromChecksumFile("$sha  KuiesBlock-v0.4.41.apk", "KuiesBlock-v0.4.41.apk"))
        assertEquals(sha, UpdateAssetPolicy.sha256FromChecksumFile("$sha *KuiesBlock-v0.4.41.apk", "KuiesBlock-v0.4.41.apk"))
        assertEquals(sha, UpdateAssetPolicy.sha256FromChecksumFile(sha, "KuiesBlock-v0.4.41.apk"))
        assertNull(
            UpdateAssetPolicy.sha256FromChecksumFile(
                "$sha  KuiesBlock-v0.4.40.apk",
                "KuiesBlock-v0.4.41.apk"
            )
        )
        assertNull(UpdateAssetPolicy.sha256FromChecksumFile("not a hash", "KuiesBlock-v0.4.41.apk"))
    }

    @Test
    fun onlyGitHubDownloadHostsAreAllowed() {
        assertTrue(UpdateAssetPolicy.isAllowedDownloadHost("https://github.com/x".toHttpUrl()))
        assertTrue(
            UpdateAssetPolicy.isAllowedDownloadHost(
                "https://release-assets.githubusercontent.com/x".toHttpUrl()
            )
        )
        assertFalse(
            UpdateAssetPolicy.isAllowedDownloadHost(
                "https://github.com.evil.example/x".toHttpUrl()
            )
        )
        assertFalse(
            UpdateAssetPolicy.isAllowedDownloadHost("http://github.com/x".toHttpUrl())
        )
    }

    @Test
    fun anOversizedApkIsRefusedBeforeDownload() {
        val oversized = apk(size = UpdateAssetPolicy.MAX_APK_BYTES + 1)

        assertNull(UpdateAssetPolicy.apkAsset(release(oversized)))
    }

    @Test
    fun aReleaseWithoutAssetsHasNoDownloadAddress() {
        assertNull(UpdateAssetPolicy.apkAsset(release()))
        assertNotNull(version)
    }
}
