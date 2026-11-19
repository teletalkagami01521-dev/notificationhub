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
import com.notifyhub.utils.DedupCache
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class NotificationForwarderService : NotificationListenerService() {

    private companion object {
        // Window for server-side dedup: identical content repeats are allowed once
        // this bucket changes (1 minute), so recurring notifications aren't blocked.
        const val DEDUP_BUCKET_MS = 60_000L
        const val STATE_WINDOW_MS = 5 * 60_000L

        // System security/permission nag notifications ("Review app with full device
        // access", Play Protect warnings from the permission component itself).
        // These are never forwarded AND are dismissed from the phone's shade, so the
        // accessibility toggle does not attract a trail of scary notifications.
        val SHADE_DISMISS = setOf(
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.lbe.security.miui",
            "com.miui.guardprovider"
        )
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

    private fun collapseSignature(title: String?, text: String?, subText: String?): String {
        fun norm(s: String?) = s.orEmpty()
            .lowercase()
            .replace(Regex("""\d+(?:[.,]\d+)?\s*(?:%|kb|mb|gb|tb|bytes?|items?|files?|photos?|videos?|messages?)"""), "#")
            .replace(Regex("""\d+"""), "#")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return "${norm(title)}|${norm(text)}|${norm(subText)}"
    }

    private fun shouldSuppressState(sbn: StatusBarNotification, signature: String): Boolean {
        val key = "${sbn.packageName}|${sbn.id}|${sbn.tag ?: ""}"
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

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // MIUI often kills the process without rebinding the listener afterwards.
        // Ask the system to rebind, then force a component toggle as a fallback
        // because requestRebind() alone is not always honoured on MIUI.
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

        if (sbn.packageName in SHADE_DISMISS) {
            try {
                cancelNotification(sbn.key)
            } catch (_: Exception) {}
            return
        }
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
        if (shouldSuppressState(sbn, collapseSignature(title, text, subText))) return

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
