package com.jarvis.assistant.forge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.ui.forge.ForgeActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide controller managing the lifecycle, streaming generation,
 * state machine, and notifications for Website Forge.
 */
object ForgeController {

    private const val NOTIFICATION_CHANNEL_ID = "jarvis_forge_channel"
    private const val NOTIFICATION_ID = 99201

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null

    private val _uiState = MutableStateFlow(ForgeUiState())
    val uiState: StateFlow<ForgeUiState> = _uiState.asStateFlow()

    private val _pendingOpen = AtomicBoolean(false)
    val pendingOpen: Boolean
        get() = _pendingOpen.get()

    private var appContext: Context? = null
    private var client: WebsiteForgeClient? = null
    private var clientFactory: ((Context) -> WebsiteForgeClient)? = null

    private var currentHtmlBuffer = StringBuilder()
    private var lastSavedFile: File? = null
    private var lastPrompt: String = ""
    private var isScreenVisible = false

    fun init(context: Context, customClient: WebsiteForgeClient? = null) {
        val app = context.applicationContext
        appContext = app
        client = customClient ?: WebsiteForgeClient(app)
        ensureNotificationChannel(app)
    }

    fun setClientFactory(factory: ((Context) -> WebsiteForgeClient)?) {
        clientFactory = factory
    }

    private fun getClient(): WebsiteForgeClient {
        val ctx = appContext ?: throw IllegalStateException("ForgeController not initialized with Context")
        val custom = clientFactory?.invoke(ctx)
        if (custom != null) return custom
        if (client == null) {
            client = WebsiteForgeClient(ctx)
        }
        return client!!
    }

    fun setScreenVisible(visible: Boolean) {
        isScreenVisible = visible
        if (visible) {
            _pendingOpen.set(false)
        }
    }

    fun markPendingOpen() {
        _pendingOpen.set(true)
    }

    fun consumePendingOpen(): Boolean {
        return _pendingOpen.getAndSet(false)
    }

    fun partialHtml(): String = synchronized(currentHtmlBuffer) {
        currentHtmlBuffer.toString()
    }

    /**
     * Starts a website build session. Returns an immediate, spoken-friendly result.
     */
    fun start(prompt: String, isEdit: Boolean = false): ForgeStartResult {
        val context = appContext ?: return ForgeStartResult.MissingKey
        val prefs = AppPreferences(context)
        val apiKey = prefs.getNextActiveKey("gemini") ?: prefs.apiKey

        if (apiKey.isBlank()) {
            return ForgeStartResult.MissingKey
        }

        if (!isNetworkAvailable(context)) {
            return ForgeStartResult.Offline
        }

        if (_uiState.value.phase == ForgePhase.BUILDING) {
            return ForgeStartResult.Busy
        }

        if (isEdit && currentHtmlBuffer.isEmpty() && lastSavedFile == null) {
            return ForgeStartResult.NothingToEdit
        }

        lastPrompt = prompt
        val buildId = UUID.randomUUID().toString()
        _pendingOpen.set(true)

        launchBuild(prompt, isEdit, buildId)
        return ForgeStartResult.Started(buildId)
    }

    fun edit(change: String): ForgeStartResult {
        return start(change, isEdit = true)
    }

    fun retry(): ForgeStartResult {
        if (lastPrompt.isBlank()) {
            return ForgeStartResult.NothingToEdit
        }
        return start(lastPrompt, isEdit = false)
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        _uiState.value = _uiState.value.copy(
            phase = ForgePhase.CANCELLED,
            error = "Build cancelled by user."
        )
    }

    fun reset() {
        cancel()
        synchronized(currentHtmlBuffer) {
            currentHtmlBuffer.setLength(0)
        }
        _uiState.value = ForgeUiState()
    }

    fun openSaved(file: File) {
        if (!file.exists()) return
        val content = file.readText(Charsets.UTF_8)
        synchronized(currentHtmlBuffer) {
            currentHtmlBuffer.setLength(0)
            currentHtmlBuffer.append(content)
        }
        lastSavedFile = file
        val sections = ForgeStageDetector.extractSections(content)
        _uiState.value = ForgeUiState(
            phase = ForgePhase.DONE,
            prompt = file.nameWithoutExtension,
            stage = ForgeStage.FINALIZING,
            progress = 1.0f,
            metrics = ForgeMetrics(content.length, sections.size, 0L, 0.0),
            sections = sections,
            file = file
        )
    }

    private fun launchBuild(prompt: String, isEdit: Boolean, buildId: String) {
        activeJob?.cancel()
        activeJob = scope.launch {
            synchronized(currentHtmlBuffer) {
                if (!isEdit) {
                    currentHtmlBuffer.setLength(0)
                }
            }

            val startTime = System.currentTimeMillis()
            var lastEmitTime = 0L
            var last10KbCheckpoint = 0
            val existingBaseHtml = if (isEdit) partialHtml() else ""

            _uiState.value = ForgeUiState(
                phase = ForgePhase.BUILDING,
                prompt = prompt,
                isEdit = isEdit,
                stage = ForgeStage.BLUEPRINT,
                progress = 0.05f,
                buildId = buildId,
                log = listOf("Initializing Website Forge reactor core...", "Stage 1: BLUEPRINT")
            )

            try {
                val forgeClient = getClient()
                var currentStage = ForgeStage.BLUEPRINT
                var currentProgress = 0.05f
                val activeSections = mutableListOf<String>()
                val activeLogs = mutableListOf<String>("Initializing Website Forge reactor core...", "Stage 1: BLUEPRINT")

                forgeClient.streamGenerateSite(prompt, isEdit, existingBaseHtml).collect { chunk ->
                    synchronized(currentHtmlBuffer) {
                        currentHtmlBuffer.append(chunk)
                    }

                    val currentLength = synchronized(currentHtmlBuffer) { currentHtmlBuffer.length }
                    val now = System.currentTimeMillis()

                    // Checkpoint every 10 KB
                    if (currentLength / 10240 > last10KbCheckpoint) {
                        last10KbCheckpoint = currentLength / 10240
                        activeLogs.add("Compiling bundle: ${last10KbCheckpoint * 10} KB streamed...")
                    }

                    // Throttle UI state emission to at most every 120 ms
                    if (now - lastEmitTime >= 120) {
                        lastEmitTime = now
                        val snapshot = synchronized(currentHtmlBuffer) { currentHtmlBuffer.toString() }

                        // Detect stage (guaranteed monotonic)
                        val newStage = ForgeStageDetector.detect(snapshot, previousStage = currentStage)
                        if (newStage.index > currentStage.index) {
                            currentStage = newStage
                            activeLogs.add("Stage ${newStage.index}: ${newStage.label} — ${newStage.description}")
                        }

                        // Extract section names
                        val detectedSections = ForgeStageDetector.extractSections(snapshot)
                        if (detectedSections.size > activeSections.size) {
                            val newlyAdded = detectedSections.drop(activeSections.size)
                            for (s in newlyAdded) {
                                activeLogs.add("Laying out $s")
                            }
                            activeSections.clear()
                            activeSections.addAll(detectedSections)
                        }

                        currentProgress = ForgeStageDetector.calculateProgress(currentStage, currentLength, currentProgress)

                        val elapsedSec = (now - startTime) / 1000
                        val tokensPerSec = if (elapsedSec > 0) (currentLength / 4.0) / elapsedSec else 0.0

                        _uiState.value = _uiState.value.copy(
                            stage = currentStage,
                            progress = currentProgress,
                            metrics = ForgeMetrics(currentLength, activeSections.size, elapsedSec, tokensPerSec),
                            sections = activeSections.toList(),
                            log = activeLogs.takeLast(10)
                        )
                    }
                }

                // Processing complete: perform fence stripping, truncation repair, and safety-net check
                var rawHtml = synchronized(currentHtmlBuffer) { currentHtmlBuffer.toString() }
                rawHtml = ForgeHtml.stripFences(rawHtml)

                // Validation & truncation repair
                val finalHtml: String
                if (rawHtml.contains("</html>", ignoreCase = true)) {
                    finalHtml = ForgeHtml.withSafetyNet(rawHtml)
                } else if (rawHtml.length >= 14000) {
                    finalHtml = ForgeHtml.withSafetyNet(ForgeHtml.repairTruncated(rawHtml))
                } else {
                    throw IllegalStateException("Gemini stopped before finishing the complete website markup.")
                }

                synchronized(currentHtmlBuffer) {
                    currentHtmlBuffer.setLength(0)
                    currentHtmlBuffer.append(finalHtml)
                }

                val savedFile = forgeClient.saveWebsite(finalHtml)
                lastSavedFile = savedFile

                val totalElapsed = (System.currentTimeMillis() - startTime) / 1000
                val totalSections = ForgeStageDetector.extractSections(finalHtml)

                _uiState.value = _uiState.value.copy(
                    phase = ForgePhase.DONE,
                    stage = ForgeStage.FINALIZING,
                    progress = 1.0f,
                    metrics = ForgeMetrics(finalHtml.length, totalSections.size, totalElapsed, (finalHtml.length / 4.0) / totalElapsed.coerceAtLeast(1)),
                    sections = totalSections,
                    file = savedFile,
                    error = null
                )

                if (!isScreenVisible) {
                    postReadyNotification(appContext ?: return@launch, savedFile)
                }

            } catch (ce: CancellationException) {
                _uiState.value = _uiState.value.copy(
                    phase = ForgePhase.CANCELLED,
                    error = "Build cancelled."
                )
            } catch (e: Exception) {
                val mapped = getClient().mapError(e)
                _uiState.value = _uiState.value.copy(
                    phase = ForgePhase.FAILED,
                    error = mapped.message ?: "Website build failed."
                )
            }
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Website Forge Notifications"
            val desc = "Alerts when website builds are ready"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = desc
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun postReadyNotification(context: Context, file: File) {
        val intent = Intent(context, ForgeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ForgeActivity.EXTRA_SAVED_FILE, file.absolutePath)
            putExtra(ForgeActivity.EXTRA_OPEN_FORGE, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Your website is ready")
            .setContentText("Tap to view your 3D generated website in Website Forge.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, notif)
    }
}
