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
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rohanc.navgate.MainActivity
import com.rohanc.navgate.R

/**
 * Keeps guidance alive while the screen is off or another app is in front. It owns no logic: it holds the process in the
 * foreground (with a notification showing the next turn) so location updates and spoken directions keep arriving.
 * Started when a trip begins and stopped the moment it ends.
 */
class NavigationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopForegroundCompat(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_EXIT) { NavigationEvents.requestExit(); stopForegroundCompat(); stopSelf(); return START_NOT_STICKY }
        val note = GuidanceNote(intent?.getStringExtra(EXTRA_TITLE) ?: "NavGate", intent?.getStringExtra(EXTRA_TEXT) ?: "Navigation is running",
            intent?.getStringExtra(EXTRA_CHIP).orEmpty(), intent?.getStringExtra(EXTRA_TRIP).orEmpty(), intent?.getIntExtra(EXTRA_DONE, 0) ?: 0, intent?.getIntExtra(EXTRA_TOTAL, 0) ?: 0)
        val notification = build(note)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // The system refused (for example the app was not visible): guidance simply continues in the foreground only.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun build(note: GuidanceNote): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Navigation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Next turn while NavGate guides you"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC   // the turn shows on the lock screen, like a navigation app should
            })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val exit = PendingIntent.getService(this, 1, Intent(this, NavigationService::class.java).setAction(ACTION_EXIT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= 36) return buildLiveUpdate(note, open, exit)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_navigate).setContentTitle(note.title).setContentText(note.instruction)
            .setSubText(note.trip.takeIf { it.isNotBlank() })
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_NAVIGATION).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(NotificationCompat.Action.Builder(R.drawable.ic_stat_navigate, EXIT_LABEL, exit).build())
        // Android 16 "live update": the same notification becomes a chip in the status bar and sits on top of the shade. Older versions ignore these extras.
        if (note.chip.isNotBlank()) builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED, true); putString(EXTRA_SHORT_CRITICAL_TEXT, note.chip) })
        return builder.build()
    }

    /** Android 16+: a progress-style, promoted notification. It is what the system turns into the status-bar chip, the top-of-shade card and the lock-screen face. */
    @androidx.annotation.RequiresApi(36)
    private fun buildLiveUpdate(note: GuidanceNote, open: PendingIntent, exit: PendingIntent): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(LIVE_CHANNEL_ID) == null) {
            // Promoted notifications want a normal-importance channel; it stays silent, the turns are spoken by the app itself.
            manager.createNotificationChannel(NotificationChannel(LIVE_CHANNEL_ID, "Navigation live view", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Next turn in the status bar and on the lock screen while NavGate guides you"
                setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        val icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_navigate)
        val builder = Notification.Builder(this, LIVE_CHANNEL_ID).setSmallIcon(icon).setContentTitle(note.title).setContentText(note.instruction)
            .setSubText(note.trip.takeIf { it.isNotBlank() }).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_NAVIGATION).setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE).setShowWhen(false)
            .addAction(Notification.Action.Builder(icon, EXIT_LABEL, exit).build())
        if (note.chip.isNotBlank()) builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED, true); putString(EXTRA_SHORT_CRITICAL_TEXT, note.chip) })
        // Samsung One UI draws its own status-bar pill and lock-screen "Now bar" from these keys (the same ones its own apps use), so a Samsung phone shows the trip there too.
        builder.addExtras(Bundle().apply {
            putInt("android.ongoingActivityNoti.style", 1)
            putString("android.ongoingActivityNoti.primaryInfo", note.chip.ifBlank { note.title })
            putString("android.ongoingActivityNoti.secondaryInfo", note.instruction)
            putString("android.ongoingActivityNoti.chipExpandedText", note.chip.ifBlank { note.title })
            putInt("android.ongoingActivityNoti.chipBgColor", 0xFF2F6BFF.toInt())
            putString("android.ongoingActivityNoti.nowbarPrimaryInfo", note.title)
            putString("android.ongoingActivityNoti.nowbarSecondaryInfo", note.instruction)
        })
        if (note.progressTotal > 0) builder.setStyle(Notification.ProgressStyle().setStyledByProgress(false).setProgress(note.progressDone)
            .setProgressTrackerIcon(icon).setProgressSegments(listOf(Notification.ProgressStyle.Segment(note.progressTotal))))
        else builder.setStyle(Notification.BigTextStyle().bigText(note.instruction))
        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true) }

    companion object {
        private const val CHANNEL_ID = "navgate_guidance"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "com.rohanc.navgate.nav.STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_CHIP = "chip"
        private const val EXTRA_DONE = "done"
        private const val EXTRA_TOTAL = "total"
        private const val LIVE_CHANNEL_ID = "navgate_guidance_live"
        private const val EXTRA_TRIP = "trip"
        private const val ACTION_EXIT = "com.rohanc.navgate.nav.EXIT"
        const val EXIT_LABEL = "Exit navigation"
        /** The platform's keys for a promoted ongoing notification and its status-bar text (Android 16+). */
        const val EXTRA_REQUEST_PROMOTED = "android.requestPromotedOngoing"
        const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"
        const val NOTIFICATION_ID_PUBLIC = NOTIFICATION_ID

        /** Starts the service, or refreshes the notification text if it is already running. Call while the app is visible. */
        fun show(context: Context, title: String, text: String) = show(context, GuidanceNote(title, text, "", ""))

        fun show(context: Context, note: GuidanceNote) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java)
                .putExtra(EXTRA_TITLE, note.title).putExtra(EXTRA_TEXT, note.instruction).putExtra(EXTRA_CHIP, note.chip).putExtra(EXTRA_TRIP, note.trip).putExtra(EXTRA_DONE, note.progressDone).putExtra(EXTRA_TOTAL, note.progressTotal)) }
        }

        fun stop(context: Context) { runCatching { context.startService(Intent(context, NavigationService::class.java).setAction(ACTION_STOP)) } }
    }
}
