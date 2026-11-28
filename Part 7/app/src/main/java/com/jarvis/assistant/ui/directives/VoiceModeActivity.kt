package com.jarvis.assistant.ui.directives

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.VoiceConnection
import com.jarvis.assistant.ui.compose.VoiceModeComposeScreen
import com.jarvis.assistant.ui.settings.ApiConnectionActivity
import com.jarvis.assistant.ui.settings.AuraControlActivity
import com.jarvis.assistant.util.DirectivePowerPolicy
import com.jarvis.assistant.util.PermissionHelper

/** Distraction-free Compose Voice Mode backed by JarvisConversationService and OpenGL ES 3.0 orb. */
class VoiceModeActivity : AppCompatActivity() {
    private var attemptedStart = false
    private var transcriptExpanded by mutableStateOf(false)
    private var localExplanation by mutableIntStateOf(0)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (PermissionHelper.hasAllRequiredPermissions(this)) {
            startVoice()
        } else {
            localExplanation = R.string.plasma_permission
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_JarvisAssistant_Voice)
        super.onCreate(savedInstanceState)
        attemptedStart = savedInstanceState?.getBoolean("attempted") ?: false
        transcriptExpanded = savedInstanceState?.getBoolean("transcript") ?: false
        localExplanation = savedInstanceState?.getInt("explanation") ?: 0

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val prefs = (application as JarvisApp).preferences
        if (DirectivePowerPolicy.backgroundAllowed(
                prefs.powerProfile,
                getSystemService(PowerManager::class.java).isPowerSaveMode
            )
        ) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val activeFacts by JarvisConversationService.voicePresentation.collectAsStateWithLifecycle()
                val explanationString = if (localExplanation != 0 && !activeFacts.active) {
                    getString(localExplanation)
                } else {
                    ""
                }
                VoiceModeComposeScreen(
                    preferences = prefs,
                    localExplanation = explanationString,
                    showPermissionSettings = localExplanation == R.string.plasma_permission && !activeFacts.active,
                    transcriptExpanded = transcriptExpanded,
                    onToggleTranscript = { transcriptExpanded = !transcriptExpanded },
                    onBack = { finish() },
                    onOpenSettings = {
                        startActivity(Intent(this@VoiceModeActivity, ApiConnectionActivity::class.java))
                    },
                    onOpenAppearance = {
                        startActivity(Intent(this@VoiceModeActivity, AuraControlActivity::class.java))
                    },
                    onOpenPermissionSettings = {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                "package:$packageName".toUri()
                            )
                        )
                    },
                    onStartVoice = { startVoice() },
                    onInterruptVoice = { JarvisConversationService.interruptPlayback() },
                    onToggleMute = {
                        prefs.isMicMuted = !prefs.isMicMuted
                        JarvisConversationService.setMicMuted(prefs.isMicMuted)
                    },
                    onEndVoice = {
                        if (JarvisConversationService.isSessionOn.value) {
                            send(JarvisConversationService.ACTION_STOP_ALL)
                        }
                    },
                    onSendTextMessage = { text ->
                        if (text.isNotBlank() && JarvisConversationService.voicePresentation.value.connection == VoiceConnection.LIVE) {
                            JarvisConversationService.sendTextMessage(text)
                        }
                    }
                )
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (!attemptedStart) {
            attemptedStart = true
            startVoice()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("explanation", localExplanation)
        outState.putBoolean("attempted", attemptedStart)
        outState.putBoolean("transcript", transcriptExpanded)
        super.onSaveInstanceState(outState)
    }

    private fun startVoice() {
        if (JarvisConversationService.isSessionOn.value) return
        val missing = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            permissions.launch(missing.toTypedArray())
            return
        }
        val prefs = (application as JarvisApp).preferences
        if ((prefs.getNextActiveKey("gemini") ?: prefs.apiKey).isBlank()) {
            localExplanation = R.string.plasma_key_required
            return
        }
        try {
            localExplanation = 0
            send(JarvisConversationService.ACTION_START_CONVERSATION)
        } catch (_: Exception) {
            localExplanation = R.string.plasma_start_failed
        }
    }

    private fun send(action: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, JarvisConversationService::class.java).setAction(action)
        )
    }
}
