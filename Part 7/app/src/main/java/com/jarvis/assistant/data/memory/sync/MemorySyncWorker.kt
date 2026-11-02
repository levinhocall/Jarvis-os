package com.jarvis.assistant.data.memory.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Retained so persisted WorkManager requests from older versions can finish without uploading. */
class MemorySyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = Result.success()
}
