package com.notifyhub.data

import androidx.room.*

@Dao
interface PendingNotificationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(n: PendingNotification): Long

    @Query("SELECT * FROM pending_notifications ORDER BY createdAt ASC LIMIT 50")
    suspend fun getPending(): List<PendingNotification>

    @Query("DELETE FROM pending_notifications WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE pending_notifications SET attempts = attempts + 1 WHERE id = :id")
    suspend fun incrementAttempts(id: Long)

    @Query("SELECT COUNT(*) FROM pending_notifications")
    suspend fun count(): Int

    // Drop oldest entries when queue exceeds capacity
    @Query("DELETE FROM pending_notifications WHERE id IN (SELECT id FROM pending_notifications ORDER BY createdAt ASC LIMIT :n)")
    suspend fun dropOldest(n: Int)
}
