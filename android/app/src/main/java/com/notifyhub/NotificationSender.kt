package com.notifyhub

import com.notifyhub.data.PendingNotification
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

    suspend fun drainQueue() {
        val dao = App.database.dao()
        for (item in dao.getPending()) {
            if (post(item)) {
                dao.delete(item.id)
            } else {
                dao.incrementAttempts(item.id)
            }
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
