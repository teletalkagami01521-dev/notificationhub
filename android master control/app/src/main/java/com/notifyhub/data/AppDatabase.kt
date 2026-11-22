package com.notifyhub.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PendingNotification::class, PendingTypedText::class, PendingMedia::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): PendingNotificationDao
}
