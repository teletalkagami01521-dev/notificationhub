package com.notifyhub

import android.content.Context

object AppDiagnostics {
    const val TYPED_SERVICE_BIND_TOTAL = "typed_service_bind_total"
    const val TYPED_EVENTS_TOTAL = "typed_events_total"
    const val TYPED_TEXT_SENT_TOTAL = "typed_text_sent_total"
    const val TYPED_PLACEHOLDER_REJECTED_TOTAL = "typed_placeholder_rejected_total"
    const val TYPED_SERVICE_RECONNECT_TOTAL = "typed_service_reconnect_total"
    const val WATCHDOG_LAST_FIRED_AT = "watchdog_last_fired_at"
    const val NOTIFICATION_REBIND_TOTAL = "notification_rebind_total"
    const val LISTENER_LAST_CONNECTED_AT = "listener_last_connected_at"
    const val LISTENER_LAST_DISCONNECTED_AT = "listener_last_disconnected_at"
    const val NOTIFICATION_IMAGES_TOTAL = "notification_images_total"
    const val NOTIFICATION_IMAGES_FAILED_TOTAL = "notification_images_failed_total"
    const val TYPED_TEXT_ALIVE_AT = "typed_text_alive_at"
    const val TYPED_TEXT_LAST_EVENT_AT = "typed_text_last_event_at"

    private const val PREFS = "notifyhub_diagnostics"

    fun increment(context: Context, key: String, amount: Long = 1L) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + amount).apply()
    }

    fun setNow(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(key, System.currentTimeMillis())
            .apply()
    }

    fun setTimestamp(context: Context, key: String, value: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(key, value)
            .apply()
    }

    fun value(context: Context, key: String): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key, 0L)
}
