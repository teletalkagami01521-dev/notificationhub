package com.wheelsync

import android.app.Notification
import android.graphics.Bitmap
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import com.wheelsync.data.PendingNotification
import com.wheelsync.utils.DedupCache
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class NotificationForwarderService : NotificationListenerService() {

    private companion object {
        // Dedup identity is the platform notification key, exact post time and
        // exact content. The short state window only drops repeated callbacks
        // for the same update, never a second distinct reply.
        const val STATE_WINDOW_MS = 1_500L
    }

    private data class NotifState(val signature: String, val at: Long)
    private val recentStates = ConcurrentHashMap<String, NotifState>()

    // Only true system noise + apps the user never wants to hear from.
    // Call/Play-Services packages are intentionally NOT blocked so missed-call
    // and other real notifications still arrive.
    private val blocklist = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.lbe.security.miui",
        "com.miui.guardprovider",
        "com.miui.daemon",
        "com.miui.securitycenter",
        "com.miui.powerkeeper",
        "com.miui.analytics",
        "com.miui.bugreport",
        "com.miui.player",
        "com.miui.video",
        "com.xiaomi.mipicks",
        "com.google.android.gms",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.pinterest"
    )

    // Promotional keywords. A notification whose title/text matches any of these
    // is treated as an ad and dropped. English terms use word boundaries so e.g.
    // "sale" doesn't match "wholesale"; Bangla terms are matched as substrings.
    // Edit this list freely — it is the single source of truth for promo filtering.
    private val promoRegex = Regex(
        "\\b(offer|offers|discount|cash ?back|promo|promotion|sale|coupon|voucher|deal|" +
        "subscribe|subscription|prize|winner|giveaway|lottery|" +
        "buy now|shop now|book now|apply now|register now|click here|free gift|" +
        "data pack|internet pack|recharge offer|special offer|exclusive offer)\\b",
        RegexOption.IGNORE_CASE
    )
    private val promoSubstrings = listOf(
        "% off", "%off", "%off)", "percent off",
        "অফার", "ছাড়", "ক্যাশব্যাক", "লটারি", "পুরস্কার", "ধামাকা"
    )

    private fun isPromo(title: String?, text: String?): Boolean {
        val hay = "${title.orEmpty()} ${text.orEmpty()}"
        if (hay.isBlank()) return false
        if (promoRegex.containsMatchIn(hay)) return true
        val lower = hay.lowercase()
        return promoSubstrings.any { lower.contains(it.lowercase()) }
    }

    private val progressRegex = Regex(
        """\b\d{1,3}%\b|\b\d+(?:[.,]\d+)?\s*(?:kb|mb|gb|tb)\s*(?:/|of)\s*\d+(?:[.,]\d+)?\s*(?:kb|mb|gb|tb)?\b""",
        RegexOption.IGNORE_CASE
    )
    private val progressPhraseRegex = Regex(
        """\b(backup in progress|uploading|downloading|syncing|updating|installing|processing|saving|compressing|optimizing|preparing|connecting|checking for|indexing|restoring|finishing|sending|pending)\b""",
        RegexOption.IGNORE_CASE
    )
    private val terminalPhraseRegex = Regex(
        """\b(completed?|finished|failed|error|couldn['’]?t|can't|unsuccessful|sent|delivered|downloaded|installed|backup complete)\b""",
        RegexOption.IGNORE_CASE
    )
    private val ephemeralExact = setOf(
        "checking for new calls",
        "updating messages…",
        "updating messages...",
        "your media is being saved…",
        "your media is being saved...",
        "processing service is on",
        "tap to return to call"
    )

    private fun combinedText(title: String?, text: String?, subText: String?, bigText: String?): String =
        listOfNotNull(title, text, subText, bigText).joinToString(" ").trim()

    private fun isNoisyProgressOrEphemeral(title: String?, text: String?, subText: String?, bigText: String?): Boolean {
        val hay = combinedText(title, text, subText, bigText)
        if (hay.isBlank()) return false
        val lower = hay.lowercase()
        if (ephemeralExact.any { lower == it || lower.contains(it) }) return true
        val hasProgress = progressRegex.containsMatchIn(hay) || progressPhraseRegex.containsMatchIn(hay)
        if (!hasProgress) return false
        return !terminalPhraseRegex.containsMatchIn(hay)
    }

    private fun exactSignature(
        title: String?,
        text: String?,
        subText: String?,
        bigText: String?,
        imageData: String?
    ): String = listOf(
        title.orEmpty(), text.orEmpty(), subText.orEmpty(), bigText.orEmpty(),
        (imageData?.hashCode() ?: 0).toString()
    ).joinToString("\u0001")

    private fun shouldSuppressState(sbn: StatusBarNotification, signature: String): Boolean {
        val key = sbn.key
        val now = System.currentTimeMillis()
        val prev = recentStates[key]
        recentStates[key] = NotifState(signature, now)
        if (recentStates.size > 300) {
            recentStates.entries.removeIf { now - it.value.at > STATE_WINDOW_MS }
        }
        return prev != null && prev.signature == signature && now - prev.at <= STATE_WINDOW_MS
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        ServiceHealth.markAlive(this)
        AppStatusReporter.report(this, force = true)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        ServiceHealth.markAlive(this)

        if (sbn.packageName in blocklist) return
        // isOngoing check removed — banking/OTP apps (bKash, Nagad) mark their
        // OTP notifications as ongoing; skipping them would miss critical codes

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)
        val text  = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.take(2000)
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        // Drop promotional/ad notifications before they ever reach the server.
        if (isPromo(title, text)) return

        // Drop in-progress/ephemeral status churn (backup %, syncing, saving, etc.)
        // while allowing likely terminal outcomes like completed/failed/sent.
        if (isNoisyProgressOrEphemeral(title, text, subText, bigText)) return

        val imageData = extractImageData(extras)

        // Keep image-only notifications (no title/text but a picture).
        if (title.isNullOrBlank() && text.isNullOrBlank() && imageData == null) return

        // Collapse repeated posts of the same notification slot where only
        // progress/count details changed.
        if (shouldSuppressState(sbn, exactSignature(title, text, subText, bigText, imageData))) return

        // Dedup by the platform notification key + exact post time + exact content.
        // Two replies posted at the same millisecond stay distinct when Android
        // gives them different keys or different text.
        val hash = sha256("${sbn.key}:${sbn.postTime}:${exactSignature(title, text, subText, bigText, imageData)}")
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
            bigText     = bigText,
            subText     = subText,
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
