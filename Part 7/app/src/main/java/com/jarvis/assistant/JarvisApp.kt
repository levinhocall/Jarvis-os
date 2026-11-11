package com.jarvis.assistant

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.jarvis.assistant.security.SecurityManager
import com.jarvis.assistant.audio.VoskModelManager
import com.jarvis.assistant.automation.command.CommandExecutor
import com.jarvis.assistant.automation.agent.GeminiFunctionCallProvider
import com.jarvis.assistant.automation.agent.LlmPlanner
import com.jarvis.assistant.automation.mission.MissionEngine
import com.jarvis.assistant.automation.mission.LongHorizonMissionCoordinator
import com.jarvis.assistant.automation.mission.PersistentMissionCheckpointStorage
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.automation.contacts.ContactResolver
import com.jarvis.assistant.automation.phone.PhoneControlManager
import com.jarvis.assistant.automation.whatsapp.WhatsAppAssistant
import com.jarvis.assistant.automation.youtube.YouTubeAutomationManager
import com.jarvis.assistant.automation.proactive.TriggerRepository
import com.jarvis.assistant.automation.proactive.ProactiveAssistantCoordinator
import com.jarvis.assistant.data.memory.MemoryDatabase
import com.jarvis.assistant.data.memory.MemoryManager
import com.jarvis.assistant.data.memory.MemoryRepository
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.data.preferences.YouTubePreferences
import com.jarvis.assistant.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class JarvisApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + com.jarvis.assistant.core.diagnostics.StorageHealth.handler)
    private var batteryLowReceiver: BroadcastReceiver? = null

    lateinit var preferences: AppPreferences
        private set

    lateinit var chatRepository: ChatRepository
        private set

    lateinit var conversationRepository: com.jarvis.assistant.data.chat.ConversationRepository
        private set

    lateinit var memoryDatabase: MemoryDatabase
        private set

    lateinit var memoryRepository: MemoryRepository
        private set

    lateinit var memoryManager: MemoryManager
        private set

    lateinit var youTubePreferences: YouTubePreferences
        private set

    lateinit var youTubeAutomationManager: YouTubeAutomationManager
        private set

    lateinit var contactResolver: ContactResolver
        private set

    lateinit var phoneControlManager: PhoneControlManager
        private set

    lateinit var whatsAppAssistant: WhatsAppAssistant
        private set

    lateinit var commandExecutor: CommandExecutor
        private set

    lateinit var triggerRepository: TriggerRepository
        private set

    lateinit var unifiedRuntime: com.jarvis.assistant.core.runtime.JarvisUnifiedRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        SecurityManager.verify(this)
        instance = this
        preferences = AppPreferences(this)
        com.jarvis.assistant.util.ThemeManager.applyNightPreference(preferences.appTheme)
        com.jarvis.assistant.data.directives.IdeaReviewWorker.schedule(this, preferences.ideasCloudReviewEnabled)
        com.jarvis.assistant.ui.aura.AutonomyWorker.schedule(this)
        chatRepository = ChatRepository(preferences)
        triggerRepository = TriggerRepository(this)
        registerBatteryLowReceiver()

        // Cancel uploads persisted by older versions before initializing local memory.
        com.jarvis.assistant.data.memory.sync.MemorySyncScheduler.cancelLegacyWork(this)

        // Memory System
        memoryDatabase = MemoryDatabase.getInstance(this)
        // Strictly on-device memory: Room + SQLCipher stores memories locally.
        // Embeddings use the deterministic local provider; no remote endpoint is configured.
        memoryRepository = MemoryRepository(memoryDatabase.memoryDao(), awaitReady = memoryDatabase::awaitReady)
        conversationRepository = com.jarvis.assistant.data.chat.ConversationRepository(memoryDatabase.chatDao(), awaitReady = memoryDatabase::awaitReady)
        memoryManager = MemoryManager(memoryRepository, preferences, appScope)

        // Automation Systems
        youTubePreferences = YouTubePreferences(this)
        youTubeAutomationManager = YouTubeAutomationManager(this, youTubePreferences)
        contactResolver = ContactResolver(this)
        phoneControlManager = PhoneControlManager(this, contactResolver)
        whatsAppAssistant = WhatsAppAssistant(this, contactResolver)

        // Command Engine
        commandExecutor = CommandExecutor(
            phoneManager = phoneControlManager,
            youtubeManager = youTubeAutomationManager,
            whatsappAssistant = whatsAppAssistant,
            memoryManager = memoryManager
        )

        // Unified Runtime & Self-Diagnostics
        unifiedRuntime = com.jarvis.assistant.core.runtime.JarvisUnifiedRuntime(
            missionCoordinator = LongHorizonMissionCoordinator(
                PersistentMissionCheckpointStorage(applicationContext)
            )
        )
        MissionEngine.missionDao = memoryDatabase.missionDao()
        MissionEngine.llmPlanner = LlmPlanner(
            GeminiFunctionCallProvider(
                apiKeyProvider = {
                    preferences.getNextActiveKey("gemini") ?: preferences.apiKey
                },
                modelProvider = { GeminiConstants.MODEL_3_8_FLASH },
                onApiKeyRateLimited = { key -> preferences.reportKeyRateLimit("gemini", key) },
                onApiKeyInvalid = { key -> preferences.reportKeyInvalid("gemini", key) },
                onApiKeySuccess = { key -> preferences.reportKeySuccess("gemini", key) }
            )
        )
        appScope.launch(Dispatchers.IO) {
            memoryDatabase.awaitReady()
            // Also migrate headless service starts; do not depend on opening a screen first.
            try {
                conversationRepository.migrateLegacyHistory(
                    chatRepository::readLegacyJson, chatRepository::clearMigratedJson
                )
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                com.jarvis.assistant.core.diagnostics.StorageHealth.record(error)
            }
            unifiedRuntime.missionCoordinator.restorePersistedState()
            unifiedRuntime.bootSelfTest(context = this@JarvisApp)
            com.jarvis.assistant.automation.mission.MissionEngine.resumeNonTerminalMissions(this@JarvisApp)
        }

        // Cloud memory sync is intentionally not scheduled. Memory remains on this device.
        // Load Vosk on explicit voice/wake activation, not on every application launch.
        com.jarvis.assistant.audio.HeadsetWhisperManager.init(this, appScope)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                com.jarvis.assistant.util.ThemeManager.applyTheme(activity)
            }
            override fun onActivityStarted(activity: android.app.Activity) {
                startedActivities++
                if (startedActivities == 1) _isAppForeground.value = true
            }
            override fun onActivityResumed(activity: android.app.Activity) {
                currentResumedActivity = activity
                com.jarvis.assistant.util.ThemeManager.applyTheme(activity)
            }
            override fun onActivityPaused(activity: android.app.Activity) {
                if (currentResumedActivity === activity) {
                    currentResumedActivity = null
                }
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) _isAppForeground.value = false
            }
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {
                if (currentResumedActivity === activity) {
                    currentResumedActivity = null
                }
            }
        })
    }

    private fun registerBatteryLowReceiver() {
        if (batteryLowReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_BATTERY_LOW) return
                val batteryState = this@JarvisApp.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = batteryState?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryState?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val status = batteryState?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                    ?: BatteryManager.BATTERY_STATUS_UNKNOWN
                if (level < 0 || scale <= 0) return
                val batteryPct = ((level * 100f) / scale).toInt().coerceIn(0, 100)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                ProactiveAssistantCoordinator.onLowBatteryDetected(batteryPct, isCharging)
            }
        }
        batteryLowReceiver = receiver
        val filter = IntentFilter(Intent.ACTION_BATTERY_LOW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    var currentResumedActivity: android.app.Activity? = null
        private set

    fun moveAppToBack(): Boolean {
        val act = currentResumedActivity ?: return false
        return try {
            act.moveTaskToBack(true)
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private val _isAppForeground = MutableStateFlow(false)
        val isAppForeground: StateFlow<Boolean> = _isAppForeground.asStateFlow()

        lateinit var instance: JarvisApp
            private set
    }
}

