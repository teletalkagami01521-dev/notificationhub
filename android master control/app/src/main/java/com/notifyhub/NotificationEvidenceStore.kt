package com.notifyhub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object NotificationEvidenceStore {

    private const val PREFS = "notifyhub_notification_evidence"
    private const val KEY_RECENT = "recent"
    private const val MAX_ITEMS = 30

    data class Evidence(
        val packageName: String,
        val appLabel: String,
        val senderName: String,
        val title: String,
        val postedAt: Long
    )

    fun record(
        context: Context,
        packageName: String,
        appLabel: String,
        senderName: String?,
        title: String?,
        postedAt: Long
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = readArray(prefs.getString(KEY_RECENT, "[]"))
        items.put(JSONObject().apply {
            put("packageName", packageName)
            put("appLabel", appLabel)
            put("senderName", clean(senderName))
            put("title", clean(title))
            put("postedAt", postedAt)
        })
        while (items.length() > MAX_ITEMS) items.remove(0)
        prefs.edit().putString(KEY_RECENT, items.toString()).apply()
    }

    fun candidates(context: Context, now: Long, windowMs: Long = 15 * 60 * 1000L): List<Evidence> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = readArray(prefs.getString(KEY_RECENT, "[]"))
        val result = mutableListOf<Evidence>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val postedAt = item.optLong("postedAt", 0L)
            if (postedAt <= 0L || postedAt < now - windowMs || postedAt > now + windowMs) continue
            result.add(
                Evidence(
                    packageName = item.optString("packageName"),
                    appLabel = item.optString("appLabel"),
                    senderName = item.optString("senderName"),
                    title = item.optString("title"),
                    postedAt = postedAt
                )
            )
        }
        return result.sortedByDescending { it.postedAt }
    }

    private fun readArray(value: String?): JSONArray {
        if (value.isNullOrBlank()) return JSONArray()
        return try {
            JSONArray(value)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun clean(value: String?): String =
        value?.trim()?.take(160).orEmpty()
}
