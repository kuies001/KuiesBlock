package tw.kuies.block.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/**
 * Checks a downloaded APK before Android's installer is allowed to see it.
 *
 * [PackageManager.getPackageArchiveInfo] makes the platform parse the archive and verify its signature;
 * the signing info is therefore produced by Android, not read from an attacker-controlled field. The
 * package name, version and signing certificate are then compared with the release and with the
 * installed app, so a debug-signed build is refused with an explanation instead of a failed install.
 */
object ApkVerifier {

    fun verify(context: Context, apk: File, release: ReleaseInfo): UpdateInstallFailure? {
        val packageManager = context.packageManager
        val archive = runCatching {
            packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        }.getOrNull() ?: return UpdateInstallFailure.APK_UNREADABLE

        archive.applicationInfo?.let { application ->
            application.sourceDir = apk.absolutePath
            application.publicSourceDir = apk.absolutePath
        }

        if (archive.packageName != UpdateAssetPolicy.PACKAGE_NAME) {
            return UpdateInstallFailure.PACKAGE_MISMATCH
        }
        val versionName = archive.versionName?.trim()?.removePrefix("v")?.removePrefix("V")
        if (versionName != release.version.text()) {
            return UpdateInstallFailure.VERSION_MISMATCH
        }

        val installed = runCatching { packageManager.getPackageInfo(context.packageName, 0) }
            .getOrNull() ?: return UpdateInstallFailure.INSTALLER_UNAVAILABLE
        if (archive.longVersionCode <= installed.longVersionCode) {
            return UpdateInstallFailure.VERSION_NOT_NEWER
        }

        val archiveCertificates = signerDigests(archive) ?: return UpdateInstallFailure.SIGNATURE_MISMATCH
        if (archiveCertificates.size != 1 ||
            archiveCertificates.single() != UpdateAssetPolicy.EXPECTED_SIGNING_CERT_SHA256
        ) {
            return UpdateInstallFailure.SIGNATURE_MISMATCH
        }

        val installedWithSignatures = runCatching {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        }.getOrNull() ?: return UpdateInstallFailure.INSTALLED_SIGNATURE_MISMATCH
        val installedCertificates = signerDigests(installedWithSignatures)
            ?: return UpdateInstallFailure.INSTALLED_SIGNATURE_MISMATCH
        if (installedCertificates.size != 1 ||
            installedCertificates.single() != UpdateAssetPolicy.EXPECTED_SIGNING_CERT_SHA256
        ) {
            return UpdateInstallFailure.INSTALLED_SIGNATURE_MISMATCH
        }
        return null
    }

    /** The certificate digests Android verified for [info]; null when there is no valid signer. */
    private fun signerDigests(info: PackageInfo): List<String>? {
        val signingInfo = info.signingInfo ?: return null
        if (signingInfo.hasMultipleSigners()) return null
        val signers = signingInfo.apkContentsSigners ?: return null
        if (signers.isEmpty()) return null
        return signers.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }
        }
    }
}
