package tw.kuies.block.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * Receives the PackageInstaller session result and opens the system's own confirmation UI.
 *
 * The system installer — not an implicit APK view intent — owns the final confirmation and any
 * unknown-sources prompt. This is why the update flow cannot fall through to APKPure or another
 * third-party installer. The receiver is explicit in the PendingIntent and is not exported.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = readConfirmationIntent(intent)
                if (confirmation == null) {
                    UpdateInstallStatus.report(UpdateInstallFailure.INSTALLER_UNAVAILABLE)
                    UpdateInstaller.clearCache(context)
                } else {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (runCatching { context.startActivity(confirmation) }.isFailure) {
                        UpdateInstallStatus.report(UpdateInstallFailure.INSTALLER_UNAVAILABLE)
                        UpdateInstaller.clearCache(context)
                    }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                UpdateInstallStatus.clear()
                UpdateInstaller.clearCache(context)
            }

            else -> {
                UpdateInstallStatus.report(failureFor(status))
                UpdateInstaller.clearCache(context)
            }
        }
    }

    private fun readConfirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun failureFor(status: Int): UpdateInstallFailure = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateInstallFailure.INSTALL_CANCELLED
        PackageInstaller.STATUS_FAILURE_BLOCKED -> UpdateInstallFailure.INSTALL_BLOCKED
        PackageInstaller.STATUS_FAILURE_CONFLICT -> UpdateInstallFailure.INSTALL_CONFLICT
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> UpdateInstallFailure.INSTALL_INCOMPATIBLE
        PackageInstaller.STATUS_FAILURE_INVALID -> UpdateInstallFailure.INSTALL_INVALID
        PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateInstallFailure.INSTALL_STORAGE
        PackageInstaller.STATUS_FAILURE_TIMEOUT -> UpdateInstallFailure.INSTALL_TIMEOUT
        else -> UpdateInstallFailure.INSTALL_FAILED
    }

    companion object {
        const val ACTION_STATUS = "tw.kuies.block.action.UPDATE_INSTALL_STATUS"
    }
}
