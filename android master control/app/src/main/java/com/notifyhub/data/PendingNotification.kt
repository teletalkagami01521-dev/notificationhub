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
    val senderName: String? = null,
    val chatBody: String? = null,
    val imageData: String? = null,
    val imageSource: String? = null,
    val photoIntent: Boolean = false,
    val notificationKey: String? = null,
    val channelId: String? = null,
    val category: String? = null,
    val groupKey: String? = null,
    val sortKey: String? = null,
    val conversationTitle: String? = null,
    val flags: Int = 0,
    val extrasJson: String? = null,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)
