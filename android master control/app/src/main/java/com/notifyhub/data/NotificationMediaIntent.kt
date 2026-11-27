package com.notifyhub.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notification_media_intents")
data class NotificationMediaIntent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val senderName: String?,
    val dedupHash: String,
    val windowStart: Long,
    val windowEnd: Long,
    val matched: Boolean = false,
    val expired: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
