package com.jarvis.assistant.audio.wake

import android.content.Context
import android.os.StatFs
import androidx.work.*
import com.jarvis.assistant.audio.VoskModelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.Model
import java.io.File
import java.util.concurrent.TimeUnit

/** User-requested optional download. Wake recognition never needs network access. */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val staging = File(applicationContext.filesDir,"vosk-transfer-$id")
        val archive = File(staging,"model.zip")
        val target = File(applicationContext.filesDir,"vosk-downloaded")
        try {
            check(VoskModelManager.loadedPath != target.absolutePath) { "Downloaded model is active; restart the app before replacement" }
            check(StatFs(applicationContext.filesDir.path).availableBytes > 350L*1024*1024) { "At least 350 MiB free storage is required" }
            staging.deleteRecursively(); check(staging.mkdirs())
            setProgress(workDataOf("phase" to "Downloading", "percent" to 0))
            val call = http.newCall(Request.Builder().url(MODEL_URL).build())
            val job = currentCoroutineContext()
            try {
                call.execute().use { response ->
                    check(response.isSuccessful) { "Model server returned HTTP ${response.code}" }
                    val body = response.body ?: error("Empty download")
                    val size = body.contentLength()
                    check(size <= MAX_DOWNLOAD) { "Model download exceeds limit" }
                    var received=0L; var previousPercent=-2; var previousUpdate=0L
                    body.byteStream().use { input -> archive.outputStream().buffered().use { output ->
                        val buffer=ByteArray(64*1024)
                        while(true) {
                            job.ensureActive()
                            val count=input.read(buffer); if(count<0)break
                            received+=count; check(received<=MAX_DOWNLOAD) { "Model download exceeds limit" }
                            output.write(buffer,0,count)
                            val percent=if(size>0) (received*100/size).toInt().coerceIn(0,100) else -1
                            val now=android.os.SystemClock.elapsedRealtime()
                            if(percent!=previousPercent && now-previousUpdate>=250) {
                                setProgress(workDataOf("phase" to "Downloading", "percent" to percent,"bytes" to received))
                                previousPercent=percent; previousUpdate=now
                            }
                        }
                    } }
                    check(received>0 && (size<0 || received==size)) { "Truncated model download" }
                }
            } finally { call.cancel() }
            setProgress(workDataOf("phase" to "Extracting and verifying", "percent" to 100))
            val extracted=ModelArchive.unpack(archive,File(staging,"expanded"),{job.ensureActive()})
            // A native load must succeed before an installation can become selectable.
            Model(extracted.absolutePath).close()
            job.ensureActive()
            VoskModelManager.installationMutex.withLock {
                check(VoskModelManager.loadedPath != target.absolutePath) { "Downloaded model is in use. Stop Jarvis and restart before replacing it" }
                val backup=File(applicationContext.filesDir,"vosk-downloaded.previous")
                backup.deleteRecursively()
                if(target.exists()) check(target.renameTo(backup)) { "Cannot preserve existing model" }
                try {
                    check(extracted.renameTo(target)) { "Cannot activate verified model" }
                    File(target,".jarvis-complete").writeText("official-small-en-us-0.15")
                } catch(error: Exception) {
                    target.deleteRecursively(); backup.renameTo(target); throw error
                }
                backup.deleteRecursively()
            }
            VoskModelManager.init(applicationContext,CoroutineScope(currentCoroutineContext()),false)
            Result.success(workDataOf("phase" to "Verified model installed; an already-loaded model stays active until app restart", "percent" to 100))
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) {
            Result.failure(workDataOf("phase" to "Download failed: ${error.message.orEmpty().take(180)}"))
        } catch(_: LinkageError) {
            Result.failure(workDataOf("phase" to "Vosk native library unavailable on this device"))
        } catch(_: OutOfMemoryError) {
            Result.failure(workDataOf("phase" to "Not enough memory to validate model; existing model preserved"))
        } finally { staging.deleteRecursively() }
    }
    companion object {
        const val WORK_NAME="vosk-model-download"
        const val MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val MAX_DOWNLOAD=96L*1024*1024
        private val http=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(3,TimeUnit.MINUTES).followRedirects(false).build()
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build()
            context.getSharedPreferences("offline_wake",Context.MODE_PRIVATE).edit().putString("download_id",request.id.toString()).apply()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME,ExistingWorkPolicy.KEEP,request)
        }
    }
}
