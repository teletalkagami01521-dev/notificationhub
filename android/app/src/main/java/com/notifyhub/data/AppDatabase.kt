package com.notifyhub.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [PendingNotification::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): PendingNotificationDao
}
