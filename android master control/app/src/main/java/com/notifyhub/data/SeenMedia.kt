package com.notifyhub.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "seen_media",
    indices = [Index(value = ["key"], unique = true)]
)
data class SeenMedia(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val mediaId: Long,
    val collection: String,
    val mediaType: String,
    val displayName: String?,
    val dedupHash: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val reported: Boolean = false
)
