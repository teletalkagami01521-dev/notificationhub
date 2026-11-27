package com.notifyhub.utils

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max

object NotificationImageExtractor {

    private const val SOURCE_EXTRA_PICTURE = "extra_picture"
    private const val SOURCE_PICTURE_ICON = "picture_icon"
    private const val SOURCE_MESSAGE_URI = "message_uri"
    private const val SOURCE_EXTRA_STREAM = "extra_stream"
    private const val SOURCE_LARGE_ICON = "large_icon_big"
    private const val SOURCE_OEM_BUNDLE = "oem_bundle"
    private const val EXTRA_STREAM = "android.stream"
    private const val EXTRA_PICTURE_ICON = "android.pictureIcon"

    private const val MAX_IMAGE_DATA_URL = 1_500_000
    private const val MAX_RAW_BYTES = 12_000_000
    private val MAX_DIMENSIONS = intArrayOf(1600, 1280, 1024, 768, 512)
    private val JPEG_QUALITIES = intArrayOf(80, 70, 60, 50)
    private val PHOTO_MARKERS = listOf(
        "sent a photo", "sent photos", "sent photo", "photo", "image",
        "ছবি", "ফটো", "ফোটো"
    )
    private val AVATAR_MARKERS = listOf("avatar", "profile", "contact", "profile picture", "user")

    data class Messaging(val senderName: String?, val chatBody: String?)
    data class Result(val dataUrl: String?, val source: String?, val photoIntent: Boolean)

    fun extractMessaging(extras: Bundle): Messaging {
        val messages = messageList(extras)
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()?.trim()?.takeUnless { it.isNullOrBlank() }
        val messageSender = messages.firstNotNullOfOrNull { message ->
            personName(message.get("sender_person"))
                ?: personName(message.get("person"))
                ?: message.getCharSequence("sender")?.toString()?.trim()?.takeUnless { it.isNullOrBlank() }
        }
        val notificationTitle = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim()?.takeUnless { it.isNullOrBlank() }
        val senderName = conversationTitle ?: messageSender ?: notificationTitle

        val realLines = messages.mapNotNull { message ->
            message.getCharSequence("text")?.toString()?.trim()
        }.filter { line ->
            line.isNotBlank() && !line.equals("message", ignoreCase = true)
        }
        val chatBody = realLines.take(3).joinToString(" | ").takeIf { it.isNotBlank() }
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?.toString()?.take(4000)?.trim()?.takeUnless { it.isNullOrBlank() }
        val visibleText = extras.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()?.trim()?.takeUnless { it.isNullOrBlank() }
        val body = bigText ?: chatBody ?: visibleText

        return Messaging(senderName = senderName, chatBody = chatBody ?: body)
    }

    fun hasPhotoIntent(extras: Bundle): Boolean {
        val texts = mutableListOf<String>()
        extras.getCharSequence(Notification.EXTRA_TITLE)?.let { texts.add(it.toString()) }
        extras.getCharSequence(Notification.EXTRA_TEXT)?.let { texts.add(it.toString()) }
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { texts.add(it.toString()) }
        messageList(extras).forEach { message ->
            message.getCharSequence("text")?.let { texts.add(it.toString()) }
            val type = message.getString("type") ?: message.get("type")?.toString()
            if (mimeLooksImage(type)) return true
        }
        return texts.any { text ->
            val lower = text.lowercase()
            PHOTO_MARKERS.any { marker -> lower.contains(marker) }
        }
    }

    fun extract(
        context: Context,
        sbn: StatusBarNotification,
        allowPhotoIntentFallbacks: Boolean = true
    ): Result {
        val extras = sbn.notification.extras
        val photoIntent = allowPhotoIntentFallbacks && hasPhotoIntent(extras)

        extraPicture(extras)?.let { return Result(it, SOURCE_EXTRA_PICTURE, photoIntent) }
        pictureIcon(context, extras)?.let { return Result(it, SOURCE_PICTURE_ICON, photoIntent) }
        messageUri(context, extras)?.let { return Result(it, SOURCE_MESSAGE_URI, true) }
        extraStream(context, extras)?.let { return Result(it, SOURCE_EXTRA_STREAM, true) }
        if (photoIntent) {
            largeIcon(context, sbn.notification)?.let { return Result(it, SOURCE_LARGE_ICON, true) }
            oemBundle(context, extras)?.let { return Result(it, SOURCE_OEM_BUNDLE, true) }
        }
        return Result(null, null, photoIntent)
    }

    private fun extraPicture(extras: Bundle): String? {
        return try {
            val bitmap = extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
            if (bitmap == null || bitmap.isRecycled || looksLikeAvatar(bitmap)) null else encodeBitmap(bitmap)
        } catch (_: Exception) {
            null
        }
    }

    private fun pictureIcon(context: Context, extras: Bundle): String? {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            val icon = extras.getParcelable<Icon>(EXTRA_PICTURE_ICON) ?: return null
            encodeIcon(context, icon, avatarCheck = false)
        } catch (_: Exception) {
            null
        }
    }

    private fun messageUri(context: Context, extras: Bundle): String? {
        for (message in messageList(extras)) {
            try {
                val type = message.getString("type") ?: message.get("type")?.toString()
                if (!mimeLooksImage(type)) continue
                val uri = message.get("uri") as? Uri ?: continue
                readImageUri(context, uri, type)?.let { return it }
                val nested = message.getBundle("extras")
                if (nested != null) {
                    val nestedType = nested.getString("type") ?: type
                    val nestedUri = nested.get("uri") as? Uri
                    if (nestedUri != null) {
                        readImageUri(context, nestedUri, nestedType)?.let { return it }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun extraStream(context: Context, extras: Bundle): String? {
        return try {
            val uri = extras.get(EXTRA_STREAM) as? Uri ?: return null
            val type = context.contentResolver.getType(uri)
            if (type != null && !mimeLooksImage(type)) return null
            readImageUri(context, uri, type)
        } catch (_: Exception) {
            null
        }
    }

    private fun largeIcon(context: Context, notification: Notification): String? {
        return try {
            val icon = notification.getLargeIcon() ?: return null
            val bitmap = drawableToBitmap(icon.loadDrawable(context)) ?: return null
            if (looksLikeAvatar(bitmap)) return null
            encodeBitmap(bitmap)
        } catch (_: Exception) {
            null
        }
    }

    private fun oemBundle(context: Context, extras: Bundle): String? {
        return try {
            val visited = IdentityHashMap<Any, Boolean>()
            scanBundle(context, extras, visited, 0, IntArray(1), 200)
        } catch (_: Exception) {
            null
        }
    }

    private fun scanBundle(
        context: Context,
        bundle: Bundle,
        visited: IdentityHashMap<Any, Boolean>,
        depth: Int,
        nodes: IntArray,
        maxNodes: Int
    ): String? {
        if (depth > 4 || visited.containsKey(bundle)) return null
        visited[bundle] = true
        for (key in bundle.keySet()) {
            if (nodes[0] >= maxNodes) return null
            nodes[0]++
            val value = try {
                bundle.get(key)
            } catch (_: Exception) {
                continue
            }
            when (value) {
                is Bundle -> {
                    scanBundle(context, value, visited, depth + 1, nodes, maxNodes)?.let { return it }
                }
                is Bitmap -> {
                    if (!value.isRecycled && !looksLikeAvatar(value)) {
                        encodeBitmap(value)?.let { return it }
                    }
                }
                is Icon -> {
                    encodeIcon(context, value, avatarCheck = true)?.let { return it }
                }
                is Uri -> {
                    val type = try {
                        context.contentResolver.getType(value)
                    } catch (_: Exception) {
                        null
                    }
                    if (type == null || mimeLooksImage(type)) {
                        readImageUri(context, value, type)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun encodeIcon(context: Context, icon: Icon, avatarCheck: Boolean): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && icon.type == Icon.TYPE_URI) {
                return readImageUri(context, icon.uri, null)
            }
            val bitmap = drawableToBitmap(icon.loadDrawable(context)) ?: return null
            if (avatarCheck && looksLikeAvatar(bitmap)) return null
            encodeBitmap(bitmap)
        } catch (_: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        return try {
            if (drawable == null) return null
            if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
                return drawable.bitmap
            }
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 512
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 512
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun readImageUri(context: Context, uri: Uri, mimeHint: String?): String? {
        return try {
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") return null
            val resolverType = try {
                context.contentResolver.getType(uri)
            } catch (_: Exception) {
                null
            }
            val type = resolverType ?: mimeHint
            if (type != null && !mimeLooksImage(type)) return null
            val bytes = readBytes(context, uri) ?: return null
            encodeBytes(bytes)
        } catch (_: Exception) {
            null
        }
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray? {
        return try {
            val output = ByteArrayOutputStream()
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(16_384)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_RAW_BYTES) return null
                    output.write(buffer, 0, read)
                }
            } ?: return null
            if (output.size() == 0) null else output.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeBytes(bytes: ByteArray): String? {
        val base = decodeBytes(bytes, MAX_DIMENSIONS[0]) ?: return null
        return encodeBitmap(base)
    }

    private fun decodeBytes(bytes: ByteArray, maxDimension: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sampleSize = 1
            while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > maxDimension) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeBitmap(source: Bitmap): String? {
        if (source.isRecycled) return null
        var smallest: String? = null
        var smallestLength = Int.MAX_VALUE
        for (maxDimension in MAX_DIMENSIONS) {
            val scaled = scaleDown(source, maxDimension)
            try {
                for (quality in JPEG_QUALITIES) {
                    val dataUrl = compressJpeg(scaled, quality) ?: continue
                    if (dataUrl.length <= MAX_IMAGE_DATA_URL) return dataUrl
                    if (dataUrl.length < smallestLength) {
                        smallest = dataUrl
                        smallestLength = dataUrl.length
                    }
                }
            } finally {
                if (scaled !== source && !scaled.isRecycled) scaled.recycle()
            }
        }
        return smallest?.takeIf { it.length <= MAX_IMAGE_DATA_URL }
    }

    private fun scaleDown(source: Bitmap, maxDimension: Int): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= maxDimension) return source
        val ratio = maxDimension.toFloat() / longest.toFloat()
        val width = max(1, (source.width * ratio).toInt())
        val height = max(1, (source.height * ratio).toInt())
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): String? {
        return try {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) return null
            val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            if (encoded.isEmpty()) null else "data:image/jpeg;base64,$encoded"
        } catch (_: Exception) {
            null
        }
    }

    private fun looksLikeAvatar(bitmap: Bitmap): Boolean {
        val square = abs(bitmap.width - bitmap.height) <= 8
        return square && bitmap.width <= 128 && bitmap.height <= 128
    }

    private fun mimeLooksImage(mime: String?): Boolean =
        !mime.isNullOrBlank() && mime.startsWith("image/", ignoreCase = true)

    private fun personName(value: Any?): String? = when (value) {
        is Bundle -> value.getCharSequence("name")?.toString()?.trim()
            ?: value.getString("name")?.trim()?.takeUnless { it.isNullOrBlank() }
        is CharSequence -> value.toString().trim().takeIf { it.isNotBlank() }
        is String -> value.trim().takeIf { it.isNotBlank() }
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun messageList(extras: Bundle): List<Bundle> {
        val current = extras.getParcelableArrayList<Bundle>(Notification.EXTRA_MESSAGES).orEmpty()
        val historic = extras.getParcelableArrayList<Bundle>(Notification.EXTRA_HISTORIC_MESSAGES).orEmpty()
        return current.asReversed() + historic.asReversed()
    }
}
