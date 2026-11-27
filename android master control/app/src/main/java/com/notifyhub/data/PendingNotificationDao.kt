package com.notifyhub.data

import androidx.room.*

@Dao
interface PendingNotificationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(n: PendingNotification): Long

    @Query("SELECT * FROM pending_notifications WHERE nextAttemptAt <= :now ORDER BY createdAt ASC, id ASC LIMIT :limit")
    suspend fun getPending(now: Long, limit: Int): List<PendingNotification>

    @Query("DELETE FROM pending_notifications WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE pending_notifications SET attempts = attempts + 1, nextAttemptAt = :nextAttemptAt WHERE id = :id")
    suspend fun markAttempt(id: Long, nextAttemptAt: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertText(t: PendingTypedText): Long

    @Query("SELECT * FROM pending_typed_text WHERE nextAttemptAt <= :now ORDER BY createdAt ASC, id ASC LIMIT :limit")
    suspend fun getPendingText(now: Long, limit: Int): List<PendingTypedText>

    @Query("DELETE FROM pending_typed_text WHERE id = :id")
    suspend fun deleteText(id: Long)

    @Query("UPDATE pending_typed_text SET attempts = attempts + 1, nextAttemptAt = :nextAttemptAt WHERE id = :id")
    suspend fun markAttemptText(id: Long, nextAttemptAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTypedDraft(draft: TypedTextDraft)

    @Query("SELECT * FROM typed_text_drafts ORDER BY firstSeenAt ASC, id ASC LIMIT 200")
    suspend fun getTypedDrafts(): List<TypedTextDraft>

    @Query("DELETE FROM typed_text_drafts WHERE sessionKey = :sessionKey")
    suspend fun deleteTypedDraft(sessionKey: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMedia(m: PendingMedia): Long

    @Query("SELECT * FROM pending_media WHERE nextAttemptAt <= :now ORDER BY createdAt ASC, id ASC LIMIT :limit")
    suspend fun getPendingMedia(now: Long, limit: Int): List<PendingMedia>

    @Query("DELETE FROM pending_media WHERE id = :id")
    suspend fun deleteMedia(id: Long)

    @Query("UPDATE pending_media SET attempts = attempts + 1, nextAttemptAt = :nextAttemptAt WHERE id = :id")
    suspend fun markAttemptMedia(id: Long, nextAttemptAt: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMediaIntent(intent: NotificationMediaIntent): Long

    @Query("SELECT * FROM notification_media_intents WHERE matched = 0 AND expired = 0 AND windowEnd >= :now ORDER BY windowStart DESC LIMIT 50")
    suspend fun getActiveMediaIntents(now: Long): List<NotificationMediaIntent>

    @Query("UPDATE notification_media_intents SET matched = 1 WHERE dedupHash = :dedupHash")
    suspend fun markMediaIntentMatched(dedupHash: String)

    @Query("UPDATE notification_media_intents SET expired = 1 WHERE expired = 0 AND matched = 0 AND createdAt < :now")
    suspend fun expireMediaIntents(now: Long)

    @Query(
        "SELECT * FROM notification_media_intents " +
            "WHERE matched = 0 AND expired = 0 AND packageName = :packageName " +
            "AND windowStart <= :capturedAt AND windowEnd >= :capturedAt " +
            "ORDER BY windowStart DESC LIMIT 1"
    )
    suspend fun findOpenIntent(packageName: String, capturedAt: Long): NotificationMediaIntent?

    @Query("SELECT * FROM seen_media WHERE key = :key LIMIT 1")
    suspend fun getSeenMedia(key: String): SeenMedia?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeenMedia(item: SeenMedia)

    @Query("UPDATE seen_media SET lastSeenAt = :lastSeenAt, reported = 0 WHERE key = :key")
    suspend fun touchSeenMedia(key: String, lastSeenAt: Long)

    @Query("UPDATE seen_media SET reported = 1 WHERE id = :id")
    suspend fun markSeenMediaReported(id: Long)

    @Query(
        "SELECT * FROM seen_media WHERE reported = 0 AND lastSeenAt <= :before " +
            "ORDER BY lastSeenAt ASC LIMIT :limit"
    )
    suspend fun getDisappearanceCandidates(before: Long, limit: Int): List<SeenMedia>

    @Query(
        "SELECT * FROM seen_media WHERE collection = :collection AND reported = 0 " +
            "AND lastSeenAt >= :since ORDER BY lastSeenAt ASC LIMIT 500"
    )
    suspend fun getSeenSince(collection: String, since: Long): List<SeenMedia>
}
