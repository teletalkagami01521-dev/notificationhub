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
        val (source, sourceLabel, sourcePackage) = referrerInfo(intent)
        Toast.makeText(this, "Saving to Storage Sync…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            var saved = 0
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    val mime = contentResolver.getType(uri) ?: intent.type ?: continue
                    if (!mime.startsWith("image/") && !mime.startsWith("video/")) continue
                    val (name, size) = metadata(uri)
                    MediaUploader.enqueueShared(
                        this@ShareReceiverActivity,
                        uri,
                        mime,
                        name,
                        size,
                        source,
                        sourceLabel,
                        sourcePackage
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

    private fun referrerInfo(intent: Intent): Triple<String, String, String?> {
        val referrer: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_REFERRER, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_REFERRER)
        }
        val candidates = mutableListOf<String>()
        intent.getStringExtra("android.intent.extra.SOURCE_PACKAGE")?.let(candidates::add)
        intent.getStringExtra(Intent.EXTRA_REFERRER_NAME)?.let(candidates::add)
        referrer?.toString()?.let(candidates::add)
        val clipData = intent.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                val uri = clipData.getItemAt(i)?.uri ?: continue
                uri.host?.let(candidates::add)
                uri.authority?.let(candidates::add)
            }
        }
        val rawHost = referrer?.host
        if (!rawHost.isNullOrBlank()) candidates.add(rawHost)
        val pkg = candidates.asSequence()
            .map { it.substringAfter("//").substringBefore("/").substringBefore("?").substringBefore(":") }
            .map { it.lowercase() }
            .firstOrNull { it.contains("whatsapp") || it.contains("telegram") || it.contains("snapchat") ||
                it.contains("instagram") || it.contains("messenger") || it.contains("facebook") }
            ?: intent.getStringExtra("android.intent.extra.SOURCE_PACKAGE")?.trim()?.takeIf { it.isNotBlank() }
            ?: return Triple("share", "Shared media", null)
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
        return Triple(source, label, pkg)
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
