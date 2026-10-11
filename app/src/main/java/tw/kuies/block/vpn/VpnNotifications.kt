package tw.kuies.block.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import androidx.core.app.NotificationCompat
import tw.kuies.block.MainActivity
import tw.kuies.block.R

/** Builds the ongoing protection notification and the one-shot "protection stopped" notice. */
class VpnNotifications(private val service: Service) {

    fun ensureChannel() {
        val manager = service.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            service.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = service.getString(R.string.notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    fun buildOngoing(title: String, text: String): Notification =
        builder(title, text)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_shield_off,
                service.getString(R.string.action_stop_protection),
                stopPendingIntent()
            )
            .build()

    fun updateOngoing(title: String, text: String) {
        val manager = service.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(ONGOING_NOTIFICATION_ID, buildOngoing(title, text))
    }

    /** A dismissible notice so the user is told plainly when protection has stopped. */
    fun postStopped(title: String, text: String) {
        val manager = service.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(STOPPED_NOTIFICATION_ID, builder(title, text).setOngoing(false).build())
    }

    fun cancelStopped() {
        val manager = service.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(STOPPED_NOTIFICATION_ID)
    }

    private fun builder(title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_shield)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent())

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        service,
        0,
        Intent(service, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        service,
        1,
        Intent(service, KuiesVpnService::class.java).setAction(KuiesVpnService.ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        const val CHANNEL_ID = "kuies_block_vpn"
        const val ONGOING_NOTIFICATION_ID = 1001
        const val STOPPED_NOTIFICATION_ID = 1002
    }
}
