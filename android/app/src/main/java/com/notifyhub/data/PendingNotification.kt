package com.notifyhub.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_notifications")
data class PendingNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val textBody: String?,
    val bigText: String?,
    val subText: String?,
    val postedAt: String,
    val dedupHash: String,
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
