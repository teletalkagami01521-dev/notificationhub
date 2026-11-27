package com.notifyhub

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Reports the health of the notification-listener pipeline. The app never posts
 * a visible "access lost" alert; status is surfaced on the dashboard instead.
 */
object ServiceHealth {

    private const val PREFS = "notifyhub_state"
    private const val KEY_LAST_ALIVE = "last_listener_alive"
    private const val KEY_WATCHDOG_SCHEDULED = "watchdog_scheduled_at"
    private const val WATCHDOG_WINDOW_MS = 6 * 60 * 60 * 1000L

    @Volatile private var connected = false
    @Volatile private var typedConnected = false
    val notificationListenerConnected: Boolean get() = connected
    val typedTextConnected: Boolean get() = typedConnected

    fun markAlive(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_ALIVE, System.currentTimeMillis()).apply()
    }

    fun markListenerConnected(context: Context) {
        connected = true
        markAlive(context)
    }

    fun markListenerDisconnected() {
        connected = false
    }

    fun lastAlive(context: Context): Long =
        prefs(context).getLong(KEY_LAST_ALIVE, 0L)

    fun markWatchdogScheduled(context: Context) {
        prefs(context).edit().putLong(KEY_WATCHDOG_SCHEDULED, System.currentTimeMillis()).apply()
    }

    fun isWatchdogEnabled(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(KEY_WATCHDOG_SCHEDULED, 0L) <= WATCHDOG_WINDOW_MS

    fun isListenerEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(context.packageName, ignoreCase = true)
    }

    fun isBatteryExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    fun markTypedTextConnected(context: Context) {
        typedConnected = true
        AppDiagnostics.setNow(context, AppDiagnostics.TYPED_TEXT_ALIVE_AT)
        AppDiagnostics.increment(context, AppDiagnostics.TYPED_SERVICE_BIND_TOTAL)
        AppStatusReporter.report(context, force = true)
    }

    fun markTypedTextDisconnected(context: Context) {
        typedConnected = false
        AppStatusReporter.report(context, force = true)
    }

    fun markTypedEvent(context: Context) {
        AppDiagnostics.setNow(context, AppDiagnostics.TYPED_TEXT_ALIVE_AT)
        AppDiagnostics.setNow(context, AppDiagnostics.TYPED_TEXT_LAST_EVENT_AT)
        AppDiagnostics.increment(context, AppDiagnostics.TYPED_EVENTS_TOTAL)
    }

    fun markTypedSent(context: Context) {
        AppDiagnostics.increment(context, AppDiagnostics.TYPED_TEXT_SENT_TOTAL)
    }

    fun markPlaceholderRejected(context: Context) {
        AppDiagnostics.increment(context, AppDiagnostics.TYPED_PLACEHOLDER_REJECTED_TOTAL)
    }

    fun markWatchdogFired(context: Context) {
        AppDiagnostics.setNow(context, AppDiagnostics.WATCHDOG_LAST_FIRED_AT)
    }

    fun markNotificationRebind(context: Context) {
        AppDiagnostics.increment(context, AppDiagnostics.NOTIFICATION_REBIND_TOTAL)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
