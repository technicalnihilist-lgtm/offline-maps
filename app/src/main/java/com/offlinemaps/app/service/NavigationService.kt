package com.offlinemaps.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.offlinemaps.app.MainActivity
import com.offlinemaps.app.OfflineMapsApp
import com.offlinemaps.app.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps turn-by-turn guidance alive while the screen is off.
 *
 * It does no navigation maths itself - [com.offlinemaps.app.nav.NavigationManager] owns the loop.
 * This service exists so Android keeps the process running and the user can see, at a glance, that
 * navigation is active. It stops itself the moment the session ends.
 */
class NavigationService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val manager = (application as OfflineMapsApp).locator.navigationManager
        lifecycleScope.launch {
            manager.session.collectLatest { session ->
                if (session == null) {
                    stopSelf()
                } else {
                    val notification = buildNotification(session.nextInstruction, session.remainingDistance)
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            (application as OfflineMapsApp).locator.navigationManager.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        // Start immediately so the notification appears even before the first GPS fix.
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.navigating), ""))
        return START_STICKY
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.end_navigation), stopIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.nav_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.nav_notification_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "navigation"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.offlinemaps.app.STOP_NAVIGATION"

        fun start(context: Context) {
            val intent = Intent(context, NavigationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NavigationService::class.java).setAction(ACTION_STOP))
        }
    }
}
