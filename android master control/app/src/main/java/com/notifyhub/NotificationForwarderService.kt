package com.notifyhub

import android.app.Notification
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import com.notifyhub.data.NotificationMediaIntent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.notifyhub.data.PendingNotification
import com.notifyhub.utils.NotificationImageExtractor
import com.notifyhub.utils.NotificationMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationForwarderService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    override fun onListenerConnected() {
        super.onListenerConnected()
        ServiceHealth.markListenerConnected(this)
        AppDiagnostics.setNow(this, AppDiagnostics.LISTENER_LAST_CONNECTED_AT)
        try {
            activeNotifications
        } catch (_: Exception) {
        }
        AppStatusReporter.report(this, force = true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        AppDiagnostics.setNow(this, AppDiagnostics.LISTENER_LAST_DISCONNECTED_AT)
        ServiceRebindHelper.requestNotificationRebind(this, force = true)
    }

    override fun onDestroy() {
        ServiceHealth.markListenerDisconnected()
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        ServiceHealth.markAlive(this)
        if (!ServiceHealth.notificationListenerConnected) {
            ServiceHealth.markListenerConnected(this)
            AppDiagnostics.setNow(this, AppDiagnostics.LISTENER_LAST_CONNECTED_AT)
        }
        scope.launch {
            try {
                processNotification(sbn)
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun processNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val extras = notification.extras
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.take(4000)
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val appLabel = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        val v2 = FeatureFlags.isEnabled(this, FeatureFlags.NOTIFICATION_IMAGE_V2)
        val messaging = if (v2) {
            NotificationImageExtractor.extractMessaging(extras)
        } else {
            NotificationImageExtractor.Messaging(null, null)
        }
        val imageResult = if (v2) {
            NotificationImageExtractor.extract(
                context = this,
                sbn = sbn,
                allowPhotoIntentFallbacks = FeatureFlags.isEnabled(this, FeatureFlags.NOTIFICATION_PHOTO_INTENT)
            )
        } else {
            NotificationImageExtractor.Result(legacyExtractImageData(extras), null, false)
        }

        val senderName = if (v2) messaging.senderName ?: title else null
        val chatBody = if (v2) messaging.chatBody else null
        val finalBody = listOf(bigText, chatBody, text).firstOrNull { !it.isNullOrBlank() }
        val imageSource = if (v2) imageResult.source else null
        val photoIntent = v2 && imageResult.photoIntent
        val dedupHash = UUID.randomUUID().toString()
        val notificationKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) sbn.key else null
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) notification.channelId else null
        val category = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) notification.category else null
        val groupKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) notification.group else null
        val sortKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) notification.sortKey else null
        val conversationTitle = NotificationMetadata.conversationTitle(extras)
        val extrasJson = NotificationMetadata.serialize(extras)

        val payload = PendingNotification(
            deviceId = deviceId,
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title,
            textBody = finalBody,
            bigText = bigText,
            subText = subText,
            postedAt = Instant.ofEpochMilli(sbn.postTime).toString(),
            dedupHash = dedupHash,
            senderName = senderName,
            chatBody = chatBody,
            imageData = imageResult.dataUrl,
            imageSource = imageSource,
            photoIntent = photoIntent,
            notificationKey = notificationKey,
            channelId = channelId,
            category = category,
            groupKey = groupKey,
            sortKey = sortKey,
            conversationTitle = conversationTitle,
            flags = notification.flags,
            extrasJson = extrasJson
        )

        if (v2) {
            if (FeatureFlags.isEnabled(this, FeatureFlags.DIAGNOSTICS)) {
                if (imageResult.dataUrl != null) {
                    AppDiagnostics.increment(this, AppDiagnostics.NOTIFICATION_IMAGES_TOTAL)
                } else if (photoIntent) {
                    AppDiagnostics.increment(this, AppDiagnostics.NOTIFICATION_IMAGES_FAILED_TOTAL)
                }
            }
            if (photoIntent && imageResult.dataUrl == null) {
                recordMediaIntent(sbn, senderName, dedupHash)
            }
        }

        if (v2) {
            NotificationEvidenceStore.record(
                context = this,
                packageName = sbn.packageName,
                appLabel = appLabel,
                senderName = senderName,
                title = title,
                postedAt = sbn.postTime
            )
        }

        NotificationSender.enqueueNotification(this, payload)
        AppStatusReporter.report(this)
    }

    private suspend fun recordMediaIntent(
        sbn: StatusBarNotification,
        senderName: String?,
        dedupHash: String
    ) {
        val intent = NotificationMediaIntent(
            packageName = sbn.packageName,
            senderName = senderName,
            dedupHash = dedupHash,
            windowStart = sbn.postTime - (5 * 60 * 1000L),
            windowEnd = sbn.postTime + (20 * 60 * 1000L)
        )
        App.database.dao().insertMediaIntent(intent)
    }

    private fun legacyExtractImageData(extras: Bundle): String? {
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
