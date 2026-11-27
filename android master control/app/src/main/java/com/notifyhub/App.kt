package com.notifyhub

import android.app.Application
import androidx.room.Room
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.notifyhub.data.AppDatabase
import java.util.concurrent.TimeUnit

class App : Application() {

    companion object {
        lateinit var database: AppDatabase
    }

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(this, AppDatabase::class.java, "notifyhub.db")
            .addMigrations(
                AppDatabase.MIGRATION_3_5,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "queue-drain",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DrainWorker>(15, TimeUnit.MINUTES).build()
        )
        ServiceRebindHelper.schedule(this)
        ServiceRebindHelper.rebindIfNeeded(this)
    }
}
