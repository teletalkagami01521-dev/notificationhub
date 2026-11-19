package com.notifyhub

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import com.notifyhub.data.PendingMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

object DownloadFolderCapture {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanning = AtomicBoolean(false)
    private var observer: ContentObserver? = null
    private var observedTree: Uri? = null
    private var scanJob: Job? = null

    fun start(context: Context) {
        val tree = MediaSettings.downloadsTree(context) ?: return
        if (MediaSettings.lastDownloadsScan(context) == 0L) {
            MediaSettings.setLastDownloadsScan(context, System.currentTimeMillis())
        }
        if (observer == null || observedTree != tree) {
            observer?.let {
                try {
                    context.contentResolver.unregisterContentObserver(it)
                } catch (_: Exception) {
                }
            }
            observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    schedule(context)
                }
            }
            try {
                context.contentResolver.registerContentObserver(tree, true, observer!!)
                observedTree = tree
            } catch (_: Exception) {
                observer = null
                observedTree = null
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
        observedTree = null
        scanJob?.cancel()
        scanJob = null
    }

    fun scanRecent(context: Context, hours: Long) {
        val appContext = context.applicationContext
        scope.launch {
            scan(appContext, System.currentTimeMillis() - hours * 60 * 60 * 1000L)
        }
    }

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(1_500)
            scan(appContext, MediaSettings.lastDownloadsScan(appContext))
        }
    }

    private suspend fun scan(context: Context, sinceMs: Long) {
        if (!scanning.compareAndSet(false, true)) return
        val tree = MediaSettings.downloadsTree(context) ?: run {
            scanning.set(false)
            return
        }
        try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val queue = ArrayDeque<String>()
            queue.add(rootId)
            var scanned = 0
            while (queue.isNotEmpty() && scanned < 500) {
                val parentId = queue.removeFirst()
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        DocumentsContract.Document.COLUMN_SIZE
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext() && scanned < 500) {
                        scanned++
                        val documentId = cursor.getString(0) ?: continue
                        val name = cursor.getString(1) ?: "download"
                        val mime = cursor.getString(2) ?: ""
                        val modified = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                        val size = if (cursor.isNull(4)) 0L else cursor.getLong(4)

                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            queue.add(documentId)
                            continue
                        }
                        if (!mime.startsWith("image/") && !mime.startsWith("video/")) continue
                        if (modified <= sinceMs) continue

                        enqueue(context, tree, documentId, name, mime, modified, size)
                    }
                }
            }
            MediaSettings.setLastDownloadsScan(context, System.currentTimeMillis())
        } catch (_: Exception) {
        } finally {
            scanning.set(false)
        }
    }

    private suspend fun enqueue(
        context: Context,
        tree: Uri,
        documentId: String,
        displayName: String,
        mimeType: String,
        modified: Long,
        sizeBytes: Long
    ) {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        val deviceId = MediaUploader.deviceId(context)
        val capturedAt = Instant.ofEpochMilli(if (modified > 0) modified else System.currentTimeMillis()).toString()
        val item = PendingMedia(
            deviceId = deviceId,
            source = "downloads",
            packageName = "downloads",
            appLabel = "Downloads",
            mediaType = if (mimeType.startsWith("video/")) "video_thumbnail" else "image",
            displayName = displayName,
            mimeType = mimeType,
            originalMimeType = mimeType,
            sizeBytes = sizeBytes,
            width = null,
            height = null,
            durationMs = null,
            capturedAt = capturedAt,
            localUri = uri.toString(),
            dedupHash = MediaUploader.sha256("$deviceId:$uri:$modified:$sizeBytes")
        )
        if (App.database.dao().insertMedia(item) > 0) {
            MediaUploader.kick(context)
        }
    }
}
