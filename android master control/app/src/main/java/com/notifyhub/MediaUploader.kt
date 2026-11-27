package com.notifyhub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import com.notifyhub.data.PendingMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object MediaUploader {

    private const val BATCH_SIZE = 5
    private const val BATCH_DELAY_MS = 150L
    private const val BASE_RETRY_MS = 10_000L
    private const val MAX_RETRY_MS = 30 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val draining = AtomicBoolean(false)

    private data class PreparedMedia(
        val imageData: String,
        val frameData: List<String>,
        val hash: String,
        val width: Int,
        val height: Int,
        val durationMs: Long?
    )

    fun kick(context: Context) {
        val appContext = context.applicationContext
        scope.launch { drainQueue(appContext) }
    }

    suspend fun enqueueShared(
        context: Context,
        uri: Uri,
        mimeType: String,
        displayName: String,
        sizeBytes: Long,
        source: String = "share",
        sourceLabel: String = "Shared media",
        sourcePackage: String? = null
    ) {
        val appContext = context.applicationContext
        try {
            val safeName = displayName.ifBlank {
                if (mimeType.startsWith("video/")) "shared_video" else "shared_image"
            }.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val cached = File(appContext.cacheDir, "shared_${System.currentTimeMillis()}_$safeName")
            openStream(appContext, uri)?.use { input ->
                cached.outputStream().use { output -> input.copyTo(output) }
            } ?: return

            val capturedAt = java.time.Instant.now().toString()
            val match = NotificationMediaMatcher.find(
                context = appContext,
                capturedAt = capturedAt,
                packageName = sourcePackage ?: source,
                ownerPackageName = sourcePackage,
                source = source,
                path = cached.absolutePath,
                displayName = safeName
            )
            val item = PendingMedia(
                deviceId = deviceId(appContext),
                source = source,
                packageName = source,
                appLabel = sourceLabel,
                mediaType = if (mimeType.startsWith("video/")) "video_thumbnail" else "image",
                displayName = safeName,
                mimeType = mimeType,
                originalMimeType = mimeType,
                sizeBytes = if (sizeBytes > 0) sizeBytes else cached.length(),
                width = null,
                height = null,
                durationMs = null,
                capturedAt = capturedAt,
                localUri = Uri.fromFile(cached).toString(),
                dedupHash = sha256("${deviceId(appContext)}:${cached.absolutePath}:${cached.length()}"),
                relatedNotificationHash = match?.dedupHash
            )
            if (App.database.dao().insertMedia(item) > 0) {
                match?.let { NotificationMediaMatcher.mark(it) }
                drainQueue(appContext)
            } else {
                cached.delete()
            }
        } catch (_: Exception) {
        }
    }

    suspend fun drainQueue(context: Context) {
        if (!draining.compareAndSet(false, true)) return
        try {
            if (!NetworkState.canUpload(context)) return
            val dao = App.database.dao()
            val now = System.currentTimeMillis()
            for (item in dao.getPendingMedia(now, BATCH_SIZE)) {
                if (!NetworkState.canUpload(context)) break
                val ok = upload(context, item)
                if (ok) {
                    dao.deleteMedia(item.id)
                    deleteCachedShare(context, item.localUri)
                } else {
                    dao.markAttemptMedia(item.id, now + retryDelay(item.attempts))
                }
                delay(BATCH_DELAY_MS)
            }
        } finally {
            draining.set(false)
        }
    }

    private fun retryDelay(attempts: Int): Long {
        val shift = attempts.coerceIn(0, 12)
        return minOf(MAX_RETRY_MS, BASE_RETRY_MS shl shift)
    }

    fun labelFor(context: Context, packageName: String): String {
        if (packageName == "media_store") return "Media"
        if (packageName == "downloads") return "Downloads"
        if (packageName == "share") return "Shared media"
        if (packageName == "shared_app") return "Shared media"
        if (packageName == "screenshot") return "Screenshot"
        if (packageName == "trash") return "Trash"
        if (packageName == "camera") return "Camera"
        if (packageName == "filesystem") return "Files"
        if (packageName == "whatsapp") return "WhatsApp"
        if (packageName == "telegram") return "Telegram"
        if (packageName == "messenger") return "Messenger"
        if (packageName == "instagram") return "Instagram"
        if (packageName == "snapchat") return "Snapchat"
        if (packageName == "facebook") return "Facebook"
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun upload(context: Context, item: PendingMedia): Boolean {
        return try {
            val actionOnly = !item.action.isNullOrBlank()
            val prepared = if (actionOnly) null else prepare(context, item) ?: return false
            val json = JSONObject().apply {
                put("deviceId", item.deviceId)
                put("source", item.source)
                put("packageName", item.packageName)
                put("appLabel", item.appLabel)
                put("mediaType", item.mediaType)
                put("displayName", item.displayName)
                put("mimeType", prepared?.imageData?.substringAfter("data:")?.substringBefore(";") ?: item.mimeType)
                put("originalMimeType", item.originalMimeType ?: item.mimeType)
                put("sizeBytes", item.sizeBytes)
                put("width", prepared?.width ?: item.width)
                put("height", prepared?.height ?: item.height)
                val durationMs = prepared?.durationMs ?: item.durationMs
                if (durationMs != null) put("durationMs", durationMs)
                put("capturedAt", item.capturedAt)
                put("dedupHash", prepared?.hash ?: item.dedupHash)
                item.relatedNotificationHash?.let { put("relatedNotificationHash", it) }
                item.action?.let { put("action", it) }
                if (prepared != null) {
                    put("imageData", prepared.imageData)
                    val frames = JSONArray()
                    prepared.frameData.forEach { frames.put(it) }
                    put("thumbnailData", frames)
                }
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${BuildConfig.SERVER_URL}/api/media")
                .header("X-API-Key", BuildConfig.API_KEY)
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful || response.code == 409
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun prepare(context: Context, item: PendingMedia): PreparedMedia? {
        val uri = Uri.parse(item.localUri)
        return if (item.mediaType == "video_thumbnail") {
            prepareVideo(context, uri)
        } else {
            prepareImage(context, uri)
        }
    }

    private fun prepareImage(context: Context, uri: Uri): PreparedMedia? {
        val bytes = openStream(context, uri)?.use { readBytes(it) } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val profile = NetworkState.profile(context)
        var sample = 1
        while (bounds.outWidth / sample > profile.imageMax || bounds.outHeight / sample > profile.imageMax) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null

        val oriented = applyExifOrientation(decoded, bytes)
        val scaled = scaleDown(oriented, profile.imageMax)
        val width = scaled.width
        val height = scaled.height
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, profile.imageQuality, out)
        if (scaled !== oriented) scaled.recycle()
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()

        val jpeg = out.toByteArray()
        val data = "data:image/jpeg;base64,${Base64.encodeToString(jpeg, Base64.NO_WRAP)}"
        return PreparedMedia(
            imageData = data,
            frameData = listOf(data),
            hash = sha256Bytes(jpeg),
            width = width,
            height = height,
            durationMs = null
        )
    }

    private fun prepareVideo(context: Context, uri: Uri): PreparedMedia? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file") retriever.setDataSource(uri.path) else retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val count = when {
                duration <= 0L -> 1
                duration < 10_000L -> 1
                duration < 30_000L -> 3
                duration < 60_000L -> 6
                else -> 8
            }
            val times = if (duration > 0L) {
                (0 until count).map { i ->
                    val fraction = if (count == 1) {
                        0.5
                    } else {
                        (i + 1).toDouble() / (count + 1)
                    }
                    (duration * fraction * 1000).toLong().coerceAtLeast(0L)
                }
            } else listOf(0L)

            val profile = NetworkState.profile(context)
            val frames = mutableListOf<String>()
            var hashSource = ByteArray(0)
            var width = 0
            var height = 0
            for (timeUs in times) {
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                val scaled = scaleDown(frame, profile.videoMax)
                if (width == 0) {
                    width = scaled.width
                    height = scaled.height
                }
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, profile.videoQuality, out)
                if (scaled !== frame) scaled.recycle()
                frame.recycle()
                val jpeg = out.toByteArray()
                if (hashSource.isEmpty()) hashSource = jpeg
                frames.add("data:image/jpeg;base64,${Base64.encodeToString(jpeg, Base64.NO_WRAP)}")
            }
            if (frames.isEmpty()) return null
            PreparedMedia(
                imageData = frames.first(),
                frameData = frames,
                hash = sha256Bytes(hashSource),
                width = width,
                height = height,
                durationMs = duration
            )
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun openStream(context: Context, uri: Uri): InputStream? {
        return try {
            if (uri.scheme == "file") FileInputStream(uri.path!!) else context.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        }
    }

    private fun readBytes(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > 30 * 1024 * 1024) throw IOException("media too large")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun applyExifOrientation(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: Exception) {
            bitmap
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun sha256Bytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun deleteCachedShare(context: Context, localUri: String) {
        try {
            val uri = Uri.parse(localUri)
            if (uri.scheme != "file") return
            val file = File(uri.path ?: return)
            if (file.absolutePath.startsWith(context.cacheDir.absolutePath)) file.delete()
        } catch (_: Exception) {
        }
    }
}
