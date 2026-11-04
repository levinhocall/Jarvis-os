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
    private var requestedTest = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) enable()
        else status.text = "Microphone permission is needed. Wake listening remains off."
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        heading("Offline Wake Word")
        requestedTest = state?.getBoolean("test_requested") ?: false
        val settings = com.jarvis.assistant.audio.wake.WakeSettings(this)
        label("English offline model: enter a call sign and optional phonetic alias. Unfamiliar names may not be in the model vocabulary; test locally before relying on them. Say the wake phrase separately from your command.")
        val name = input("Call sign (for example Myra)",1).apply { setText(settings.name) }
        val alias = input("Optional phonetic alias (for example Mira)",1).apply { setText(settings.alias) }
        action("Save call sign") {
            val valid = com.jarvis.assistant.audio.wake.WakeMatcher.validName(name.text.toString()) && (alias.text.isBlank() || com.jarvis.assistant.audio.wake.WakeMatcher.validName(alias.text.toString()))
            if (valid) { settings.name=name.text.toString(); settings.alias=alias.text.toString(); notice("Saved. Applied to the next recognition result.") }
            else notice("Use one or two English words, 2–20 letters each.")
        }
        val sensitivityLabel = label("Sensitivity: ${settings.sensitivity}% · higher accepts weaker/fuzzier matches")
        page.addView(android.widget.SeekBar(this).apply {
            max=100; progress=settings.sensitivity; contentDescription="Wake word sensitivity"
            setOnSeekBarChangeListener(object: android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: android.widget.SeekBar?) = Unit
                override fun onProgressChanged(bar: android.widget.SeekBar?, value:Int, user:Boolean) { if(user) {settings.sensitivity=value; sensitivityLabel.text="Sensitivity: $value% · higher may cause more false wakes"} }
            })
        })
        page.addView(androidx.appcompat.widget.SwitchCompat(this).apply { text="Allow wake while in pocket (more accidental wakes possible)"; isChecked=settings.allowPocket; setOnCheckedChangeListener {_,value -> settings.allowPocket=value} })
        label("Say ‘Hey’ followed by your saved call sign. Vosk recognizes the wake phrase on this phone. The bundled English model is about 68 MiB; no extra model download is needed. Live replies still use your configured AI provider.")
        label("Listening uses a visible microphone foreground service. Start it here while the app is open. Android/HyperOS can stop background listening; it is never silently enabled at boot.")
        status = label("Wake listening is off until you start it.")
        val modelStatus = label(VoskModelManager.status.value)
        val serviceStatus = label("Voice state: ${JarvisConversationService.conversationState.value.name}")
        action("Enable background wake listening") {
            requestedTest = false
            val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33) required += Manifest.permission.POST_NOTIFICATIONS
            permissions.launch(required.toTypedArray())
        }
        action("Live microphone test · local only") {
            requestedTest = true
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
        label("Optional model re-download: official Vosk small English archive (~40 MB download, ~70 MB extracted). Uses internet only during setup; requires 350 MiB free space. Queued while battery is low. Cancellation keeps the working model.")
        val downloadStatus = label("No model download requested")
        action("Download / retry official offline model") { com.jarvis.assistant.audio.wake.ModelDownloadWorker.enqueue(applicationContext) }
        action("Cancel model download") { androidx.work.WorkManager.getInstance(applicationContext).cancelUniqueWork(com.jarvis.assistant.audio.wake.ModelDownloadWorker.WORK_NAME) }
        androidx.work.WorkManager.getInstance(applicationContext).getWorkInfosForUniqueWorkLiveData(com.jarvis.assistant.audio.wake.ModelDownloadWorker.WORK_NAME).observe(this) { jobs ->
            val tracking=getSharedPreferences("offline_wake",MODE_PRIVATE)
            val active=jobs.firstOrNull { !it.state.isFinished }
            if(active!=null) tracking.edit().putString("download_id",active.id.toString()).apply()
            val job=active ?: jobs.firstOrNull { it.id.toString()==tracking.getString("download_id",null) }
            if(job!=null) {
                val data=if(job.state.isFinished) job.outputData else job.progress
                val percent=data.getInt("percent",-1)
                downloadStatus.text="${job.state}: ${data.getString("phase").orEmpty()} ${if(percent>=0) "$percent%" else ""}"
            }
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { com.jarvis.assistant.audio.wake.WakeDiagnostics.status.collect { status.text = it } }
            launch { VoskModelManager.status.collect { modelStatus.text = it } }
            launch { JarvisConversationService.conversationState.collect { serviceStatus.text = "Voice state: ${it.name}" } }
        } }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("test_requested",requestedTest); super.onSaveInstanceState(outState) }
    private fun enable() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, JarvisConversationService::class.java).setAction(if(requestedTest) JarvisConversationService.ACTION_TEST_WAKE else JarvisConversationService.ACTION_ENTER_STANDBY))
            status.text = "Starting offline wake listener…"
        } catch (_: Exception) { status.text = "Android blocked microphone startup. Keep Jarvis open and check permissions." }
    }
}
