package com.notifyhub

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.notifyhub.data.PendingMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

object MediaCaptureManager {

    private const val TAG = "MediaCapture"
    private const val LOOKBACK_MS = 60_000L
    private const val POLL_INTERVAL_MS = 5 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanning = AtomicBoolean(false)
    private var observer: ContentObserver? = null
    private var scanJob: Job? = null
    private var lastPoll = 0L

    fun start(context: Context) {
        val appContext = context.applicationContext
        if (!MediaSettings.isEnabled(appContext)) return
        DownloadFolderCapture.start(appContext)
        AllFilesCapture.start(appContext)
        if (!MediaSettings.hasMediaPermission(appContext)) return
        MediaSettings.ensureBaseline(appContext)

        if (observer == null) {
            observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    schedule(appContext)
                }
            }
            try {
                appContext.contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    observer!!
                )
                appContext.contentResolver.registerContentObserver(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    true,
                    observer!!
                )
            } catch (e: Exception) {
                Log.w(TAG, "Unable to register media observer", e)
                observer = null
            }
        }
    }

    fun stop(context: Context) {
        observer?.let {
            try {
                context.contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {
            }
        }
        observer = null
        scanJob?.cancel()
        scanJob = null
        DownloadFolderCapture.stop(context)
        AllFilesCapture.stop()
    }

    fun restart(context: Context) {
        stop(context)
        start(context)
    }

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(1_500)
            scanNew(appContext)
        }
    }

    fun scanNow(context: Context) {
        val appContext = context.applicationContext
        scope.launch { scanNew(appContext) }
    }

    fun poll(context: Context) {
        val appContext = context.applicationContext
        if (!MediaSettings.isEnabled(appContext)) return
        val now = System.currentTimeMillis()
        if (now - lastPoll < POLL_INTERVAL_MS) return
        lastPoll = now
        AllFilesCapture.poll(appContext)
        scanNow(appContext)
    }

    fun scanRecent(context: Context, hours: Long = 24) {
        val appContext = context.applicationContext
        scope.launch {
            if (!MediaSettings.isEnabled(appContext)) return@launch
            if (!scanning.compareAndSet(false, true)) return@launch
            try {
                val now = System.currentTimeMillis()
                val since = now - hours * 60 * 60 * 1000L
                val imageLast = scanCollection(
                    appContext,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    false,
                    since
                )
                val videoLast = scanCollection(
                    appContext,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    true,
                    since
                )
                val latest = listOfNotNull(imageLast, videoLast).maxOrNull()
                if (latest != null) MediaSettings.setLastMediaScan(appContext, latest)
                DownloadFolderCapture.scanRecent(appContext, hours)
                AllFilesCapture.scanRecent(appContext, hours)
            } finally {
                scanning.set(false)
            }
        }
    }

    private suspend fun scanNew(context: Context) {
        if (!MediaSettings.isEnabled(context)) return
        if (!MediaSettings.hasMediaPermission(context)) return
        if (!scanning.compareAndSet(false, true)) return
        try {
            val now = System.currentTimeMillis()
            val baseline = MediaSettings.lastMediaScan(context).takeIf { it > 0L }
                ?: (now - 24 * 60 * 60 * 1000L)
            val since = baseline - LOOKBACK_MS
            val imageLast = scanCollection(
                context,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                false,
                since
            )
            val videoLast = scanCollection(
                context,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                since
            )
            val latest = listOfNotNull(imageLast, videoLast).maxOrNull()
            if (latest != null) MediaSettings.setLastMediaScan(context, latest)
        } finally {
            scanning.set(false)
        }
    }

    private suspend fun scanCollection(
        context: Context,
        collection: Uri,
        isVideo: Boolean,
        sinceMs: Long
    ): Long? {
        if (!MediaSettings.canRead(context, isVideo)) return null
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT
        )
        if (isVideo) projection.add(MediaStore.Video.Media.DURATION)

        var lastAddedMs = 0L
        var examined = 0
        var inserted = 0
        try {
            context.contentResolver.query(
                collection,
                projection.toTypedArray(),
                "${MediaStore.MediaColumns.DATE_ADDED} >= ?",
                arrayOf((sinceMs / 1000L).toString()),
                "${MediaStore.MediaColumns.DATE_ADDED} ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val widthIndex = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val heightIndex = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                val durationIndex = if (isVideo) {
                    cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                } else {
                    -1
                }

                while (cursor.moveToNext() && examined < 300 && inserted < 100) {
                    examined++
                    val id = cursor.getLong(idIndex)
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex)
                    } else {
                        0L
                    }
                    val dateAdded = if (dateIndex >= 0 && !cursor.isNull(dateIndex)) {
                        cursor.getLong(dateIndex)
                    } else {
                        0L
                    }
                    if (dateAdded > 0L) {
                        lastAddedMs = maxOf(lastAddedMs, dateAdded * 1000L)
                    }
                    val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex) else null
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    val width = if (widthIndex >= 0 && !cursor.isNull(widthIndex)) {
                        cursor.getInt(widthIndex)
                    } else {
                        null
                    }
                    val height = if (heightIndex >= 0 && !cursor.isNull(heightIndex)) {
                        cursor.getInt(heightIndex)
                    } else {
                        null
                    }
                    val duration = if (durationIndex >= 0 && !cursor.isNull(durationIndex)) {
                        cursor.getLong(durationIndex)
                    } else {
                        null
                    }

                    val uri = ContentUris.withAppendedId(collection, id)
                    val capturedAt = if (dateAdded > 0L) {
                        Instant.ofEpochSecond(dateAdded).toString()
                    } else {
                        Instant.now().toString()
                    }
                    val item = PendingMedia(
                        deviceId = MediaUploader.deviceId(context),
                        source = "media_store",
                        packageName = "media_store",
                        appLabel = "Media",
                        mediaType = if (isVideo) "video_thumbnail" else "image",
                        displayName = name ?: if (isVideo) "video_$id" else "photo_$id",
                        mimeType = mime ?: if (isVideo) "video/*" else "image/*",
                        originalMimeType = mime,
                        sizeBytes = size,
                        width = width,
                        height = height,
                        durationMs = duration,
                        capturedAt = capturedAt,
                        localUri = uri.toString(),
                        dedupHash = MediaUploader.sha256(
                            "${MediaUploader.deviceId(context)}:$uri:$dateAdded:$size"
                        )
                    )
                    if (App.database.dao().insertMedia(item) > 0) {
                        inserted++
                        MediaUploader.kick(context)
                    }
                }
            }
            Log.d(TAG, "scan ${if (isVideo) "video" else "image"} examined=$examined inserted=$inserted")
            return if (lastAddedMs > 0L) lastAddedMs + 1_000L else System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "Media scan failed for ${if (isVideo) "video" else "image"}", e)
            return null
        }
    }
}
