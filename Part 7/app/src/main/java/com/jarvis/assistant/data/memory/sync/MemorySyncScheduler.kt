package com.jarvis.assistant.data.memory.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object MemorySyncScheduler {
    private const val UNIQUE_WORK_NAME = "jarvis-personal-memory-sync"

    fun schedule(context: Context) {
        if (com.jarvis.assistant.BuildConfig.MEMORY_SYNC_ENDPOINT.isBlank()) return
        val request = PeriodicWorkRequestBuilder<MemorySyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }
}
