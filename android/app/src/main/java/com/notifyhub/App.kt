package com.notifyhub

import android.app.Application
import androidx.room.Room
import com.notifyhub.data.AppDatabase

class App : Application() {

    companion object {
        lateinit var database: AppDatabase
    }

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(this, AppDatabase::class.java, "notifyhub.db")
            .fallbackToDestructiveMigration()
            .build()
    }
}
