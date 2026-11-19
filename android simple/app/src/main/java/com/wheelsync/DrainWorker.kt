package com.wheelsync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic fallback drain. Runs even when MIUI has killed the foreground
 * service: wakes the process and flushes the Room queue to the server.
 */
class DrainWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            NotificationSender.drainQueue()
            AppStatusReporter.report(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
