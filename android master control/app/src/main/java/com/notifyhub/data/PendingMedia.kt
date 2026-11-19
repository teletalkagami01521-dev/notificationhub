package com.notifyhub.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_media",
    indices = [Index(value = ["dedupHash"], unique = true)]
)
data class PendingMedia(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val source: String,
    val packageName: String,
    val appLabel: String,
    val mediaType: String,
    val displayName: String,
    val mimeType: String,
    val originalMimeType: String?,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val capturedAt: String,
    val localUri: String,
    val dedupHash: String,
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
