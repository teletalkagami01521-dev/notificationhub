package com.notifyhub

import android.content.Context
import com.notifyhub.data.NotificationMediaIntent
import java.time.Instant

object NotificationMediaMatcher {

    private val packageBySource = mapOf(
        "whatsapp" to "com.whatsapp",
        "whatsapp_sent" to "com.whatsapp",
        "telegram" to "org.telegram.messenger",
        "snapchat" to "com.snapchat.android",
        "instagram" to "com.instagram.android",
        "messenger" to "com.facebook.orca",
        "facebook" to "com.facebook.katana"
    )

    private val genericPackages = setOf(
        "",
        "downloads",
        "filesystem",
        "files",
        "media_store",
        "camera",
        "trash",
        "share",
        "shared_app",
        "android_media"
    )

    suspend fun find(
        context: Context,
        capturedAt: String,
        packageName: String?,
        ownerPackageName: String?,
        source: String?,
        path: String?,
        displayName: String?
    ): NotificationMediaIntent? {
        val capturedAtMs = try {
            Instant.parse(capturedAt).toEpochMilli()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
        val dao = App.database.dao()
        val now = System.currentTimeMillis()
        dao.expireMediaIntents(now - 24 * 60 * 60 * 1000L)
        val packages = LinkedHashSet<String>()
        packageName?.takeIf { it !in genericPackages }?.let(packages::add)
        source?.let(packageBySource::get)?.let(packages::add)
        if (FeatureFlags.isEnabled(context, FeatureFlags.MEDIA_OWNER_V2)) {
            ownerPackageName?.takeIf { it !in genericPackages }?.let(packages::add)
        }
        packageFromPath(path, displayName)?.let(packages::add)
        if (packages.isEmpty()) return null

        for (candidatePackage in packages) {
            val direct = dao.findOpenIntent(candidatePackage, capturedAtMs)
            if (direct != null) return direct
        }

        return dao.getActiveMediaIntents(now).firstOrNull { intent ->
            capturedAtMs in intent.windowStart..intent.windowEnd && intent.packageName in packages
        }
    }

    suspend fun mark(intent: NotificationMediaIntent) {
        App.database.dao().markMediaIntentMatched(intent.dedupHash)
    }

    private fun packageFromPath(path: String?, displayName: String?): String? {
        val hay = "/${path.orEmpty().replace('\\', '/')}/${displayName.orEmpty()}".lowercase()
        return when {
            hay.contains("/android/media/com.facebook.orca/") -> "com.facebook.orca"
            hay.contains("/pictures/messenger/") -> "com.facebook.orca"
            hay.contains("/download/") && hay.contains("messenger") -> "com.facebook.orca"
            hay.contains("/whatsapp/") -> "com.whatsapp"
            hay.contains("/telegram/") -> "org.telegram.messenger"
            hay.contains("/snapchat/") -> "com.snapchat.android"
            hay.contains("/instagram/") -> "com.instagram.android"
            hay.contains("/facebook/") -> "com.facebook.katana"
            else -> null
        }
    }
}
