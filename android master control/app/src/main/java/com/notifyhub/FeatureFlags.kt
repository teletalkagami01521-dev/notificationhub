package com.notifyhub

import android.content.Context

object FeatureFlags {
    const val NOTIFICATION_IMAGE_V2 = "notification_image_v2"
    const val NOTIFICATION_PHOTO_INTENT = "notification_photo_intent"
    const val TYPED_TEXT_V2 = "typed_text_v2"
    const val DIAGNOSTICS = "diagnostics"
    const val MEDIA_OWNER_V2 = "media_owner_v2"

    private const val PREFS = "notifyhub_feature_flags"

    private val defaults = mapOf(
        NOTIFICATION_IMAGE_V2 to true,
        NOTIFICATION_PHOTO_INTENT to true,
        TYPED_TEXT_V2 to true,
        DIAGNOSTICS to true,
        MEDIA_OWNER_V2 to true
    )

    fun isEnabled(context: Context, key: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key, defaults[key] ?: false)

    fun setEnabled(context: Context, key: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, enabled)
            .apply()
    }

    fun snapshot(context: Context): Map<String, Boolean> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return defaults.mapValues { (key, defaultValue) -> prefs.getBoolean(key, defaultValue) }
    }
}
