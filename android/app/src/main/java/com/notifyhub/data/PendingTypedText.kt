package com.notifyhub.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_typed_text")
data class PendingTypedText(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val packageName: String,
    val appLabel: String,
    val text: String,
    val capturedAt: String,
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
