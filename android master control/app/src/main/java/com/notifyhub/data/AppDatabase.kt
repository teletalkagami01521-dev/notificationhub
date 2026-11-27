package com.notifyhub.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PendingNotification::class,
        PendingTypedText::class,
        TypedTextDraft::class,
        PendingMedia::class,
        NotificationMediaIntent::class,
        SeenMedia::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): PendingNotificationDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateTo5(db)
            }
        }

        val MIGRATION_3_5 = object : Migration(3, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_typed_text ADD COLUMN targetName TEXT NOT NULL DEFAULT ''")
                migrateTo5(db)
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_notifications ADD COLUMN notificationKey TEXT")
                db.execSQL("ALTER TABLE pending_notifications ADD COLUMN channelId TEXT")
                db.execSQL("ALTER TABLE pending_notifications ADD COLUMN category TEXT")
                db.execSQL("ALTER TABLE pending_notifications ADD COLUMN groupKey TEXT")
                db.execSQL("ALTER TABLE pending_notifications ADD COLUMN sortKey TEXT")
                db.execSQL("ALTER TABLE pending_notifications ADD COLUMN conversationTitle TEXT")
                db.execSQL("ALTER TABLE pending_notifications ADD COLUMN flags INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pending_notifications ADD COLUMN extrasJson TEXT")
                db.execSQL("ALTER TABLE pending_notifications ADD COLUMN nextAttemptAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pending_typed_text ADD COLUMN className TEXT")
                db.execSQL("ALTER TABLE pending_typed_text ADD COLUMN viewId TEXT")
                db.execSQL("ALTER TABLE pending_typed_text ADD COLUMN hintText TEXT")
                db.execSQL("ALTER TABLE pending_typed_text ADD COLUMN contentDescription TEXT")
                db.execSQL("ALTER TABLE pending_typed_text ADD COLUMN inputType INTEGER")
                db.execSQL("ALTER TABLE pending_typed_text ADD COLUMN eventType INTEGER")
                db.execSQL("ALTER TABLE pending_typed_text ADD COLUMN nextAttemptAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pending_media ADD COLUMN nextAttemptAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS typed_text_drafts (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "sessionKey TEXT NOT NULL, " +
                        "deviceId TEXT NOT NULL, " +
                        "packageName TEXT NOT NULL, " +
                        "appLabel TEXT NOT NULL, " +
                        "text TEXT NOT NULL, " +
                        "targetName TEXT NOT NULL, " +
                        "className TEXT, " +
                        "viewId TEXT, " +
                        "hintText TEXT, " +
                        "contentDescription TEXT, " +
                        "inputType INTEGER, " +
                        "eventType INTEGER, " +
                        "firstSeenAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_typed_text_drafts_sessionKey ON typed_text_drafts(sessionKey)")
            }
        }

        private fun migrateTo5(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE pending_notifications ADD COLUMN senderName TEXT")
            db.execSQL("ALTER TABLE pending_notifications ADD COLUMN chatBody TEXT")
            db.execSQL("ALTER TABLE pending_notifications ADD COLUMN imageData TEXT")
            db.execSQL("ALTER TABLE pending_notifications ADD COLUMN imageSource TEXT")
            db.execSQL("ALTER TABLE pending_notifications ADD COLUMN photoIntent INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE pending_typed_text ADD COLUMN rawPackage TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE pending_typed_text ADD COLUMN rawAppLabel TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE pending_typed_text ADD COLUMN resolvedPackage TEXT")
            db.execSQL("ALTER TABLE pending_typed_text ADD COLUMN resolvedAppLabel TEXT")
            db.execSQL("ALTER TABLE pending_typed_text ADD COLUMN attributionSource TEXT")
            db.execSQL("ALTER TABLE pending_typed_text ADD COLUMN attributionConfidence REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE pending_typed_text ADD COLUMN targetSource TEXT")
            db.execSQL("ALTER TABLE pending_media ADD COLUMN relatedNotificationHash TEXT")
            db.execSQL("ALTER TABLE pending_media ADD COLUMN action TEXT")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS notification_media_intents (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "packageName TEXT NOT NULL, " +
                    "senderName TEXT, " +
                    "dedupHash TEXT NOT NULL, " +
                    "windowStart INTEGER NOT NULL, " +
                    "windowEnd INTEGER NOT NULL, " +
                    "matched INTEGER NOT NULL DEFAULT 0, " +
                    "expired INTEGER NOT NULL DEFAULT 0, " +
                    "createdAt INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS seen_media (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "key TEXT NOT NULL, " +
                    "mediaId INTEGER NOT NULL, " +
                    "collection TEXT NOT NULL, " +
                    "mediaType TEXT NOT NULL, " +
                    "displayName TEXT, " +
                    "dedupHash TEXT NOT NULL, " +
                    "firstSeenAt INTEGER NOT NULL, " +
                    "lastSeenAt INTEGER NOT NULL, " +
                    "reported INTEGER NOT NULL DEFAULT 0)"
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_seen_media_key ON seen_media(key)")
        }
    }
}
