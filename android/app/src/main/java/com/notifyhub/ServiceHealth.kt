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

    fun markAlive(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_ALIVE, System.currentTimeMillis()).apply()
    }

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

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
