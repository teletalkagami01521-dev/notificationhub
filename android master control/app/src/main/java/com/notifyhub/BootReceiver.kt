package com.notifyhub

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                ServiceRebindHelper.schedule(context)
                ServiceRebindHelper.startSyncService(context)
                ServiceRebindHelper.rebindIfNeeded(context, force = true)
            }
        }
    }
}

class ServiceWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ServiceRebindHelper.ACTION_WATCHDOG) return
        ServiceHealth.markWatchdogFired(context.applicationContext)
        ServiceRebindHelper.schedule(context)
        ServiceRebindHelper.rebindIfNeeded(context)
        ServiceRebindHelper.startSyncService(context)
        AppStatusReporter.report(context)
    }
}

object ServiceRebindHelper {

    const val ACTION_WATCHDOG = "com.notifyhub.SERVICE_WATCHDOG"

    private const val REQUEST_CODE = 9001
    private const val WATCHDOG_INTERVAL_MS = 10 * 60 * 1000L
    private const val STALE_AFTER_MS = 30 * 60 * 1000L
    private const val REBIND_COOLDOWN_MS = 10 * 60 * 1000L

    @Volatile private var lastRebindAt = 0L

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(appContext, ServiceWatchdogReceiver::class.java)
            .setAction(ACTION_WATCHDOG)
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setInexactRepeating(
            AlarmManager.RTC,
            System.currentTimeMillis() + WATCHDOG_INTERVAL_MS,
            WATCHDOG_INTERVAL_MS,
            pendingIntent
        )
        ServiceHealth.markWatchdogScheduled(appContext)
    }

    fun startSyncService(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, SyncForegroundService::class.java)
        try {
            ContextCompat.startForegroundService(appContext, intent)
        } catch (_: Exception) {
            try {
                appContext.startService(intent)
            } catch (_: Exception) {
            }
        }
    }

    fun rebindIfNeeded(context: Context, force: Boolean = false) {
        if (!ServiceHealth.isListenerEnabled(context)) return
        val stale = System.currentTimeMillis() - ServiceHealth.lastAlive(context) > STALE_AFTER_MS
        if (force || stale || !ServiceHealth.notificationListenerConnected) {
            requestNotificationRebind(context, force)
        }
    }

    fun requestNotificationRebind(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        if (!ServiceHealth.isListenerEnabled(appContext)) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRebindAt < REBIND_COOLDOWN_MS) return
        lastRebindAt = now
        ServiceHealth.markNotificationRebind(appContext)
        try {
            NotificationListenerService.requestRebind(
                ComponentName(appContext, NotificationForwarderService::class.java)
            )
        } catch (_: Exception) {
        }
    }
}
