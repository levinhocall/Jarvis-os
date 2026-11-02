package com.jarvis.assistant.data.memory.sync

import android.content.Context
import androidx.work.WorkManager

/** Local-only memory: cancel legacy persisted work, including the initial one-shot upload. */
object MemorySyncScheduler {
    private const val UNIQUE_WORK_NAME = "jarvis-personal-memory-sync"

    fun cancelLegacyWork(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.cancelUniqueWork(UNIQUE_WORK_NAME)
        manager.cancelUniqueWork("$UNIQUE_WORK_NAME-initial")
    }

    /** Compatibility entry point; scheduling remote memory work is permanently disabled. */
    fun schedule(context: Context) = cancelLegacyWork(context)
}
