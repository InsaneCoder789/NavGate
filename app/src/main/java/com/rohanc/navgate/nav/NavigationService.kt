package com.rohanc.navgate.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rohanc.navgate.MainActivity

/**
 * Keeps guidance alive while the screen is off or another app is in front. It owns no logic: it holds the process in the
 * foreground (with a notification showing the next turn) so location updates and spoken directions keep arriving.
 * Started when a trip begins and stopped the moment it ends.
 */
class NavigationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopForegroundCompat(); stopSelf(); return START_NOT_STICKY }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "NavGate"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Navigation is running"
        val notification = build(title, text)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // The system refused (for example the app was not visible): guidance simply continues in the foreground only.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun build(title: String, text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Navigation", NotificationManager.IMPORTANCE_LOW).apply { description = "Next turn while NavGate guides you" })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_directions).setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_NAVIGATION).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true) }

    companion object {
        private const val CHANNEL_ID = "navgate_guidance"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "com.rohanc.navgate.nav.STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"

        /** Starts the service, or refreshes the notification text if it is already running. Call while the app is visible. */
        fun show(context: Context, title: String, text: String) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text)) }
        }

        fun stop(context: Context) { runCatching { context.startService(Intent(context, NavigationService::class.java).setAction(ACTION_STOP)) } }
    }
}
