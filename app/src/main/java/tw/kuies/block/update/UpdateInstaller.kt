package tw.kuies.block.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import okhttp3.OkHttpClient
import okhttp3.Request

/** The one file a completed download produced, or the reason it did not. */
sealed interface UpdateInstallResult {
    data class Verified(val file: File) : UpdateInstallResult
    data class Failed(val reason: UpdateInstallFailure) : UpdateInstallResult
}

/**
 * Downloads the official APK into the app's private cache and verifies it before it can be installed.
 *
 * Nothing here opens the system installer; the caller does that only after [UpdateInstallResult.Verified].
 * The download is streamed to a `.part` file, hashed while it is written, and only renamed after both
 * the size and SHA-256 match. Cancellation closes the HTTP call and removes the partial file.
 */
object UpdateInstaller {

    private const val UPDATE_DIR = "updates"
    private const val APK_FILE = "update.apk"
    private const val PART_FILE = "update.apk.part"
    private const val CHECKSUM_MAX_BYTES = 4096

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .callTimeout(2, TimeUnit.MINUTES)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Redirects are followed by OkHttp, but every hop must still be a GitHub release host.
            .addNetworkInterceptor { chain ->
                if (!UpdateAssetPolicy.isAllowedDownloadHost(chain.request().url)) {
                    throw IOException("Blocked update host")
                }
                chain.proceed(chain.request())
            }
            .build()
    }

    suspend fun downloadAndVerify(
        context: Context,
        release: ReleaseInfo,
        onProgress: (Int?) -> Unit,
        onVerifying: () -> Unit
    ): UpdateInstallResult {
        val apk = UpdateAssetPolicy.apkAsset(release)
            ?: return UpdateInstallResult.Failed(UpdateInstallFailure.APK_ASSET_MISSING)
        if (apk.sizeBytes > UpdateAssetPolicy.MAX_APK_BYTES) {
            return UpdateInstallResult.Failed(UpdateInstallFailure.SIZE_MISMATCH)
        }

        val expectedSha256 = UpdateAssetPolicy.sha256FromDigest(apk.digest)
            ?: UpdateAssetPolicy.checksumAsset(release)?.let { asset ->
                val text = try {
                    downloadText(asset.downloadUrl)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                text?.let { UpdateAssetPolicy.sha256FromChecksumFile(it, apk.name) }
            }
            ?: return UpdateInstallResult.Failed(UpdateInstallFailure.CHECKSUM_MISSING)

        val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
        val finalFile = File(dir, APK_FILE)
        val partFile = File(dir, PART_FILE)
        finalFile.delete()
        partFile.delete()

        val download = try {
            downloadToFile(apk.downloadUrl, partFile, apk.sizeBytes, onProgress)
        } catch (cancelled: CancellationException) {
            partFile.delete()
            throw cancelled
        } catch (_: Exception) {
            partFile.delete()
            currentCoroutineContext().ensureActive()
            return UpdateInstallResult.Failed(UpdateInstallFailure.DOWNLOAD_FAILED)
        }

        currentCoroutineContext().ensureActive()
        if (download is DownloadResult.Failed) {
            partFile.delete()
            return UpdateInstallResult.Failed(download.reason)
        }
        val success = download as DownloadResult.Success

        if (success.bytes == 0L) {
            partFile.delete()
            return UpdateInstallResult.Failed(UpdateInstallFailure.EMPTY_FILE)
        }
        if (apk.sizeBytes > 0 && success.bytes != apk.sizeBytes) {
            partFile.delete()
            return UpdateInstallResult.Failed(UpdateInstallFailure.SIZE_MISMATCH)
        }
        if (!success.sha256.equals(expectedSha256, ignoreCase = true)) {
            partFile.delete()
            return UpdateInstallResult.Failed(UpdateInstallFailure.CHECKSUM_MISMATCH)
        }
        if (!partFile.renameTo(finalFile)) {
            partFile.delete()
            return UpdateInstallResult.Failed(UpdateInstallFailure.DOWNLOAD_FAILED)
        }

        onVerifying()
        val failure = ApkVerifier.verify(context, finalFile, release)
        if (failure != null) {
            finalFile.delete()
            return UpdateInstallResult.Failed(failure)
        }
        return UpdateInstallResult.Verified(finalFile)
    }

    /**
     * Writes a verified APK into a PackageInstaller session and commits it.
     *
     * Android's own PackageInstaller owns the final confirmation UI, so no implicit APK view intent
     * can fall through to a third-party installer. The session is abandoned if writing or committing
     * fails; the receiver handles the asynchronous result.
     */
    fun commitSession(context: Context, file: File): UpdateInstallFailure? {
        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        var failure: UpdateInstallFailure? = null
        try {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply { setSize(file.length()) }
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val callback = Intent(context, UpdateInstallReceiver::class.java)
                    .setAction(UpdateInstallReceiver.ACTION_STATUS)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    flags
                )
                session.commit(pendingIntent.intentSender)
            }
        } catch (_: SecurityException) {
            failure = UpdateInstallFailure.INSTALL_BLOCKED
        } catch (_: IOException) {
            failure = UpdateInstallFailure.INSTALL_FAILED
        } catch (_: Exception) {
            failure = UpdateInstallFailure.INSTALL_FAILED
        } finally {
            if (failure != null && sessionId != -1) {
                runCatching { installer.abandonSession(sessionId) }
            }
        }
        return failure
    }

    /** Deletes both the finished APK and any partial download. Safe to call from any thread. */
    fun clearCache(context: Context) {
        File(context.cacheDir, UPDATE_DIR).listFiles()?.forEach { it.delete() }
    }

    private suspend fun downloadToFile(
        url: String,
        destination: File,
        expectedSize: Long,
        onProgress: (Int?) -> Unit
    ): DownloadResult {
        val request = Request.Builder().url(url).get().build()
        val call = client.newCall(request)
        val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    return DownloadResult.Failed(UpdateInstallFailure.DOWNLOAD_FAILED)
                }
                if (!UpdateAssetPolicy.isAllowedDownloadHost(response.request.url)) {
                    return DownloadResult.Failed(UpdateInstallFailure.DOWNLOAD_FAILED)
                }
                val body = response.body
                val total = if (expectedSize > 0) {
                    expectedSize
                } else {
                    body.contentLength().takeIf { it > 0 }
                }
                if (total != null && total > UpdateAssetPolicy.MAX_APK_BYTES) {
                    return DownloadResult.Failed(UpdateInstallFailure.SIZE_MISMATCH)
                }

                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                var lastPercent: Int? = null
                destination.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            bytes += read
                            if (bytes > UpdateAssetPolicy.MAX_APK_BYTES) {
                                return DownloadResult.Failed(UpdateInstallFailure.SIZE_MISMATCH)
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            val percent = total?.let {
                                ((bytes * 100) / it).toInt().coerceIn(0, 100)
                            }
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
                return DownloadResult.Success(bytes, digest.digest().toHex())
            }
        } catch (_: InterruptedIOException) {
            return DownloadResult.Failed(UpdateInstallFailure.DOWNLOAD_TIMEOUT)
        } catch (_: IOException) {
            return DownloadResult.Failed(UpdateInstallFailure.DOWNLOAD_FAILED)
        } finally {
            handle.dispose()
        }
    }

    private suspend fun downloadText(url: String): String? {
        val request = Request.Builder().url(url).get().build()
        val call = client.newCall(request)
        val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) return null
                if (!UpdateAssetPolicy.isAllowedDownloadHost(response.request.url)) return null
                val body = response.body
                if (body.contentLength() > CHECKSUM_MAX_BYTES) return null
                val bytes = body.byteStream().readAtMost(CHECKSUM_MAX_BYTES + 1)
                if (bytes.size > CHECKSUM_MAX_BYTES) return null
                return bytes.toString(Charsets.UTF_8)
            }
        } finally {
            handle.dispose()
        }
    }

    private suspend fun InputStream.readAtMost(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (out.size() < max) {
            currentCoroutineContext().ensureActive()
            val read = read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private sealed interface DownloadResult {
        data class Success(val bytes: Long, val sha256: String) : DownloadResult
        data class Failed(val reason: UpdateInstallFailure) : DownloadResult
    }
}
