package com.notifyhub

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            finish()
            return
        }
        val (source, sourceLabel) = referrerInfo(intent)
        Toast.makeText(this, "Saving to Storage Sync…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            var saved = 0
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    val mime = contentResolver.getType(uri) ?: intent.type ?: continue
                    if (!mime.startsWith("image/") && !mime.startsWith("video/")) continue
                    val (name, size) = metadata(uri)
                    MediaUploader.enqueueShared(
                        this@ShareReceiverActivity, uri, mime, name, size, source, sourceLabel
                    )
                    saved++
                }
            }
            Toast.makeText(
                this@ShareReceiverActivity,
                if (saved > 0) "Sent to Storage Sync" else "No media found",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }

    private fun referrerInfo(intent: Intent): Pair<String, String> {
        val referrer: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_REFERRER, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_REFERRER)
        }
        val raw = intent.getStringExtra(Intent.EXTRA_REFERRER_NAME) ?: referrer?.toString()
            ?: return "share" to "Shared media"
        val host = runCatching { Uri.parse(raw).host }.getOrNull()
            ?: raw.substringAfter("//").substringBefore("/").substringBefore("?")
        val pkg = host.substringBefore(":").ifBlank { return "share" to "Shared media" }
        val source = when {
            pkg.contains("whatsapp") -> "whatsapp"
            pkg.contains("telegram") -> "telegram"
            pkg.contains("snapchat") -> "snapchat"
            pkg.contains("instagram") -> "instagram"
            pkg.contains("facebook.orca") || pkg.contains("messenger") -> "messenger"
            pkg.contains("facebook") -> "facebook"
            else -> "shared_app"
        }
        val label = MediaUploader.labelFor(this, pkg).takeIf { it != pkg } ?: pkg
        return source to label
    }

    private fun sharedUris(intent: Intent): List<Uri> {
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                list.orEmpty()
            }
            else -> emptyList()
        }
    }

    private fun metadata(uri: Uri): Pair<String, Long> {
        var name = "shared_media"
        var size = 0L
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) {
        }
        return name to size
    }
}
