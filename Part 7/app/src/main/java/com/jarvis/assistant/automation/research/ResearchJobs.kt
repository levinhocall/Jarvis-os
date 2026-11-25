package com.jarvis.assistant.automation.research

import android.content.Context
import android.content.SharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ui.aura.AuraCloudClient
import com.jarvis.assistant.ui.aura.AuraOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class ResearchJob(
    val id: UUID,
    val query: String,
    val state: WorkInfo.State?,
    val report: String?
)

internal class ResearchJobs(private val preferences: SharedPreferences?) {
    companion object {
        private const val JOB_IDS = "research_job_ids"
        private const val QUERY_PREFIX = "research_query_"
        private const val REPORT_PREFIX = "research_report_"
        private const val MAX_JOBS = 20
    }

    fun enqueue(context: Context, query: String, id: UUID = UUID.randomUUID()): UUID {
        require(query.length in 1..2000)
        check(AuraOptions.enabled("backgroundSearch")) { "Enable Background Web Search in Aura first." }
        val storage = storage()
        val queryKey = QUERY_PREFIX + id
        val manager = WorkManager.getInstance(context)
        synchronized(this) {
            val previous = storage.getString(queryKey, null)
            if (previous == query && (report(id) != null || manager.getWorkInfoById(id).get(8, TimeUnit.SECONDS) != null)) {
                return id
            }
            check(previous == null || previous == query) { "Research request conflicts with an existing task." }
            val knownIds = jobIds(storage)
            check(id.toString() in knownIds || knownIds.size < MAX_JOBS) {
                "Remove an old research task before adding another."
            }
            persist(storage, "Could not store the research query.") {
                putString(queryKey, query)
                putStringSet(JOB_IDS, knownIds + id.toString())
            }
            val request = OneTimeWorkRequestBuilder<BackgroundResearchWorker>()
                .setId(id)
                .setInputData(workDataOf("id" to id.toString()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .keepResultsForAtLeast(30, TimeUnit.DAYS)
                .build()
            try {
                manager.enqueueUniqueWork(queryKey, ExistingWorkPolicy.KEEP, request).result.get(8, TimeUnit.SECONDS)
            } catch (error: Exception) {
                try {
                    persist(storage, "Could not roll back the unscheduled research query.") {
                        remove(queryKey)
                        putStringSet(JOB_IDS, knownIds - id.toString())
                    }
                } catch (rollbackError: Exception) {
                    error.addSuppressed(rollbackError)
                }
                throw error
            }
        }
        return id
    }

    fun list(context: Context): List<ResearchJob> {
        val storage = storage()
        val manager = WorkManager.getInstance(context)
        return jobIds(storage).mapNotNull { storedId ->
            val id = UUID.fromString(storedId)
            val query = query(id) ?: return@mapNotNull null
            val report = report(id)
            ResearchJob(id, query, manager.getWorkInfoById(id).get(8, TimeUnit.SECONDS)?.state, report)
        }.sortedWith(compareBy<ResearchJob> { it.state?.isFinished == true || it.report != null }.thenBy { it.query })
    }

    fun remove(context: Context, id: UUID) {
        val storage = storage()
        synchronized(this) {
            val manager = WorkManager.getInstance(context)
            manager.cancelWorkById(id).result.get(8, TimeUnit.SECONDS)
            val remainingIds = jobIds(storage) - id.toString()
            persist(storage, "Could not remove the research task.") {
                remove(QUERY_PREFIX + id)
                remove(REPORT_PREFIX + id)
                putStringSet(JOB_IDS, remainingIds)
            }
        }
    }

    fun query(id: UUID): String? = storage().getString(QUERY_PREFIX + id, null)

    fun report(id: UUID): String? = storage().getString(REPORT_PREFIX + id, null)

    @Synchronized
    fun saveReport(id: UUID, report: String) {
        val storage = storage()
        if (!storage.contains(QUERY_PREFIX + id)) return
        persist(storage, "Could not save the research report.") {
            putString(REPORT_PREFIX + id, report)
        }
    }

    private inline fun persist(
        storage: SharedPreferences,
        errorMessage: String,
        change: SharedPreferences.Editor.() -> Unit
    ) {
        val editor = storage.edit()
        editor.change()
        // Do not report success before the data is committed on the background dispatcher.
        check(editor.commit()) { errorMessage }
    }

    private fun storage(): SharedPreferences = checkNotNull(preferences) { "Encrypted storage unavailable." }

    private fun jobIds(storage: SharedPreferences): Set<String> =
        storage.getStringSet(JOB_IDS, emptySet()).orEmpty()
}

class BackgroundResearchWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? JarvisApp ?: return@withContext Result.failure()
        val id = inputData.getString("id")?.let(UUID::fromString) ?: return@withContext Result.failure()
        val jobs = app.preferences.researchJobs
        val query = jobs.query(id) ?: return@withContext Result.failure()
        if (jobs.report(id) != null) return@withContext Result.success()
        if (!AuraOptions.enabled("backgroundSearch")) return@withContext Result.failure()
        try {
            jobs.saveReport(id, AuraCloudClient.research(query))
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
