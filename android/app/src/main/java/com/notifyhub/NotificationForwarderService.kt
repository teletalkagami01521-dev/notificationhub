package com.notifyhub

import android.app.Notification
import android.graphics.Bitmap
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import com.notifyhub.data.PendingNotification
import com.notifyhub.utils.DedupCache
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant

class NotificationForwarderService : NotificationListenerService() {

    private companion object {
        // Window for server-side dedup: identical content repeats are allowed once
        // this bucket changes (1 minute), so recurring notifications aren't blocked.
        const val DEDUP_BUCKET_MS = 60_000L
    }

    // Only true system noise. Call/Play-Services packages are intentionally NOT
    // blocked so missed-call and other real notifications still arrive.
    private val blocklist = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.miui.daemon",
        "com.miui.securitycenter"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName in blocklist) return
        // isOngoing check removed — banking/OTP apps (bKash, Nagad) mark their
        // OTP notifications as ongoing; skipping them would miss critical codes

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)
        val text  = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val imageData = extractImageData(extras)

        // Keep image-only notifications (no title/text but a picture).
        if (title.isNullOrBlank() && text.isNullOrBlank() && imageData == null) return

        // Time-bucketed dedup hash: identical content within the same minute is
        // suppressed, but the same content at a later time is allowed through
        // (the DB unique constraint would otherwise block it forever).
        val bucket = sbn.postTime / DEDUP_BUCKET_MS
        val hash = sha256("${sbn.packageName}:${title.orEmpty()}:${text.orEmpty()}:${imageData?.hashCode() ?: 0}:$bucket")
        if (DedupCache.isDuplicate(hash)) return

        val appLabel = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        val payload = PendingNotification(
            deviceId    = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID),
            packageName = sbn.packageName,
            appLabel    = appLabel,
            title       = title,
            textBody    = text,
            bigText     = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                            ?.toString()?.take(2000),
            subText     = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            postedAt    = Instant.ofEpochMilli(sbn.postTime).toString(),
            dedupHash   = hash
        )

        NotificationSender.sendOrQueue(payload, imageData)
    }

    private fun extractImageData(extras: android.os.Bundle): String? {
        return try {
            val bitmap = extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE) ?: return null
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
            val bytes = out.toByteArray()
            // Skip large images (>500 KB compressed) to avoid POST timeouts
            if (bytes.size > 512_000) return null
            "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
