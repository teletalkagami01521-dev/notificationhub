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
            .fallbackToDestructiveMigration()
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "queue-drain",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DrainWorker>(15, TimeUnit.MINUTES).build()
        )
    }
}
