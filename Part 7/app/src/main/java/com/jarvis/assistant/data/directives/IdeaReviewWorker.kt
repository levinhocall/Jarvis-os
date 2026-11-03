package com.jarvis.assistant.data.directives

import android.content.Context
import androidx.work.*
import com.jarvis.assistant.data.memory.MemoryDatabase
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Separate explicit opt-in for sharing ideas; ordinary personal memories are never read. */
class IdeaReviewWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val prefs = AppPreferences(applicationContext)
        if (!prefs.ideasCloudReviewEnabled) return Result.success()
        val saver = (applicationContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode
        if (!com.jarvis.assistant.util.DirectivePowerPolicy.backgroundAllowed(prefs.powerProfile, saver)) return Result.retry()
        return try {
            val dao = MemoryDatabase.getInstance(applicationContext).directiveDao()
            val client = DirectiveAiClient(prefs)
            for (idea in dao.unreviewedIdeas(3)) {
                if (!prefs.ideasCloudReviewEnabled || isStopped) break
                val result = client.refineIdea(idea.content)
                if (prefs.ideasCloudReviewEnabled) dao.saveRefinement(idea.id, idea.updatedAt, result)
            }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }
    }

    companion object {
        private const val NAME = "jarvis-idea-review"
        fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context.applicationContext)
            if (!enabled) { work.cancelUniqueWork(NAME); return }
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<IdeaReviewWorker>(12, TimeUnit.HOURS).setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build())
        }
    }
}
