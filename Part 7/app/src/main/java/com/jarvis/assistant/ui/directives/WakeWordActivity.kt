package com.jarvis.assistant.ui.directives

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.audio.VoskModelManager
import com.jarvis.assistant.background.JarvisConversationService
import kotlinx.coroutines.launch

class WakeWordActivity : DirectiveActivity() {
    private lateinit var status: android.widget.TextView
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) enable()
        else status.text = "Microphone permission is needed. Wake listening remains off."
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        heading("Offline Wake Word")
        label("Say ‘Hey Jarvis’. Vosk recognizes the wake phrase on this phone. The bundled English model is about 68 MiB; no extra model download is needed. Live replies still use your configured AI provider.")
        label("Listening uses a visible microphone foreground service. Start it here while the app is open. Android/HyperOS can stop background listening; it is never silently enabled at boot.")
        status = label("Wake listening is off until you start it.")
        action("Enable Hey Jarvis listening") {
            val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33) required += Manifest.permission.POST_NOTIFICATIONS
            permissions.launch(required.toTypedArray())
        }
        action("Stop voice and wake listening") {
            if (JarvisConversationService.isSessionOn.value || JarvisConversationService.isStandby.value) {
                startService(Intent(this, JarvisConversationService::class.java).setAction(JarvisConversationService.ACTION_STOP_ALL))
            }
            status.text = "Stop requested. Microphone indicator should turn off."
        }
        action("Prepare / retry offline model") {
            VoskModelManager.init(applicationContext, lifecycleScope, com.jarvis.assistant.JarvisApp.instance.preferences.isOwnerVoiceEnabled)
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { VoskModelManager.status.collect { status.text = it } }
            launch { JarvisConversationService.conversationState.collect { status.text = "Voice state: ${it.name}\n${VoskModelManager.status.value}" } }
        } }
    }
    private fun enable() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, JarvisConversationService::class.java).setAction(JarvisConversationService.ACTION_ENTER_STANDBY))
            status.text = "Starting offline wake listener…"
        } catch (_: Exception) { status.text = "Android blocked microphone startup. Keep Jarvis open and check permissions." }
    }
}
