package com.notifyhub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class SyncForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "notifyhub_sync"
        const val NOTIF_ID = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createSilentChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(NOTIF_ID, notification)
        startRetryLoop()
    }

    private fun createSilentChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Background Sync",
            NotificationManager.IMPORTANCE_MIN).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startRetryLoop() {
        scope.launch {
            while (isActive) {
                try {
                    NotificationSender.drainQueue()
                } catch (_: Exception) {}
                delay(60_000L)
            }
        }
    }

    // START_STICKY: Android restarts this service if it's killed
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
