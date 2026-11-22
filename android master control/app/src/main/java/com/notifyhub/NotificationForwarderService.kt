package com.notifyhub

import android.app.Notification
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import com.notifyhub.data.PendingNotification
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

class NotificationForwarderService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        ServiceHealth.markAlive(this)
        AppStatusReporter.report(this, force = true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            requestRebind(ComponentName(this, NotificationForwarderService::class.java))
        } catch (_: Exception) {}
        try {
            val pm = packageManager
            val cn = ComponentName(this, NotificationForwarderService::class.java)
            pm.setComponentEnabledSetting(
                cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        ServiceHealth.markAlive(this)

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.take(4000)
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val imageData = extractImageData(extras)
        val appLabel = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        val payload = PendingNotification(
            deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID),
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title,
            textBody = text,
            bigText = bigText,
            subText = subText,
            postedAt = Instant.ofEpochMilli(sbn.postTime).toString(),
            dedupHash = UUID.randomUUID().toString()
        )

        NotificationSender.sendOrQueue(payload, imageData)
    }

    private fun extractImageData(extras: android.os.Bundle): String? {
        return try {
            val bitmap = extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE) ?: return null
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
            val bytes = out.toByteArray()
            if (bytes.size > 512_000) return null
            "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        } catch (_: Exception) {
            null
        }
    }
}
