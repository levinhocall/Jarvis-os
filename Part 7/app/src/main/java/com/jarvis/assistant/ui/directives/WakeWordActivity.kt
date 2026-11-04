package com.jarvis.assistant.ui.directives

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.jarvis.assistant.R
import com.jarvis.assistant.audio.VoskModelManager
import com.jarvis.assistant.audio.wake.*
import com.jarvis.assistant.background.JarvisConversationService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Reference-inspired setup; all indicators reflect the real service/model state. */
class WakeWordActivity : DirectiveActivity() {
    private lateinit var status: TextView
    private lateinit var wakeSwitch: SwitchCompat
    private var rendering = false
    private var starting = false
    private var requestedTest = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) enable()
        else { status.text = "Microphone permission needed. Listening was not started."; renderSwitch() }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        requestedTest = state?.getBoolean("test_requested") ?: false
        val settings = WakeSettings(this)
        heading("Wake Word")
        label("Your voice. Your call sign. Recognized on this phone.")
        lateinit var engine: TextView
        lateinit var testButton: com.google.android.material.button.MaterialButton
        section("Microphone") {
            engine = label("Vosk · Offline",18f)
            status = label(WakeDiagnostics.status.value)
            wakeSwitch = SwitchCompat(this).apply {
                text = "Background wake listening"; minHeight=dp(48)
                setTextColor(themeColor(R.attr.themeTextPrimary))
                setOnCheckedChangeListener { _, value ->
                    if (!rendering) {
                        if(value) { renderSwitch(); requestListening(false) } else stopListening()
                    }
                }
            }
            page.addView(wakeSwitch)
            testButton = action("Test microphone") {
                if (WakeDiagnostics.testing.value && JarvisConversationService.isStandby.value) stopListening()
                else requestListening(true)
            }
            action("Stop all voice listening") { stopListening() }
            label("Test mode stays local and shows what was heard. A normal wake opens your configured assistant.")
        }
        section("Sensitivity") {
            val percent=label("${settings.sensitivity}%",24f)
            label("Higher accepts weaker matches and may wake by mistake. Lower is stricter; it does not change microphone gain.")
            page.addView(SeekBar(this).apply {
                max=100; progress=settings.sensitivity; contentDescription="Wake sensitivity percentage"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                    override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                        if(user) { settings.sensitivity=value; percent.text="$value%" }
                    }
                })
            })
        }
        section("Custom Wake Word") {
            label("Enter only the name. ‘Hey’ and ‘Wake up’ are added automatically.")
            val name=input("Just the name, e.g. Jarvis or Myra",1).apply { setText(settings.name) }
            val current=label("",18f)
            fun showCurrent() { current.text="Current wake word: ${settings.name}\nSay ‘Hey ${settings.name}’ or ‘Wake up ${settings.name}’" }
            showCurrent()
            val alias=input("Optional phonetic alias, e.g. Mira",1).apply { setText(settings.alias) }
            action("Save wake word") {
                val value=name.text.toString()
                if(!WakeMatcher.validName(value)) {
                    name.error="Enter only a name: one or two English words, 2–20 letters each"
                } else if(alias.text.isNotBlank() && !WakeMatcher.validName(alias.text.toString())) {
                    alias.error="Use one or two English words"
                } else {
                    settings.name=value; settings.alias=alias.text.toString()
                    showCurrent(); notice("Wake word saved. Try Test microphone.")
                }
            }
            label("The English model may need a phonetic alias for unfamiliar names. Say the wake phrase separately from your command.")
        }
        lateinit var modelStatus: TextView
        section("Offline Model · Vosk") {
            modelStatus=label(VoskModelManager.status.value)
            label("Wake recognition is always offline. The ~68 MiB model is already bundled; no download is needed for normal setup.")
            action("Prepare / repair bundled model") {
                VoskModelManager.init(applicationContext,lifecycleScope,com.jarvis.assistant.JarvisApp.instance.preferences.isOwnerVoiceEnabled)
            }
            val progress=LinearProgressIndicator(this).apply { max=100; visibility=View.GONE }
            page.addView(progress,LinearLayout.LayoutParams(-1,dp(6)))
            val downloadStatus=label("Optional official download: ~40 MB compressed / ~70 MB extracted. Requires internet and 350 MiB free storage.")
            val download=action("Download / retry official model") { ModelDownloadWorker.enqueue(applicationContext) }
            val cancel=action("Cancel download") { WorkManager.getInstance(applicationContext).cancelUniqueWork(ModelDownloadWorker.WORK_NAME) }.apply { isEnabled=false }
            WorkManager.getInstance(applicationContext).getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.WORK_NAME).observe(this) { jobs ->
                val tracking=getSharedPreferences("offline_wake",MODE_PRIVATE)
                val active=jobs.firstOrNull { !it.state.isFinished }
                if(active!=null) tracking.edit().putString("download_id",active.id.toString()).apply()
                val job=active ?: jobs.firstOrNull { it.id.toString()==tracking.getString("download_id",null) }
                download.isEnabled=active==null; cancel.isEnabled=active!=null
                progress.visibility=if(active!=null) View.VISIBLE else View.GONE
                if(job!=null) {
                    val data=if(job.state.isFinished) job.outputData else job.progress
                    val percent=data.getInt("percent",-1)
                    progress.isIndeterminate=percent<0
                    if(percent>=0) progress.setProgressCompat(percent,false)
                    val phase=data.getString("phase") ?: when(job.state) {
                        androidx.work.WorkInfo.State.ENQUEUED -> "Waiting for network and sufficient battery"
                        androidx.work.WorkInfo.State.CANCELLED -> "Download cancelled; existing model preserved"
                        else -> job.state.name
                    }
                    downloadStatus.text="$phase${if(percent>=0) " · $percent%" else ""}"
                }
            }
        }
        section("Background behavior") {
            fun toggle(title:String, checked:Boolean, save:(Boolean)->Unit) {
                page.addView(SwitchCompat(this).apply { text=title; isChecked=checked; minHeight=dp(48); setTextColor(themeColor(R.attr.themeTextPrimary)); setOnCheckedChangeListener {_,value->save(value)} })
            }
            toggle("Allow wake while in pocket",settings.allowPocket) {settings.allowPocket=it}
            toggle("Silent wake transitions · no app chime",settings.silentWake) {settings.silentWake=it}
            label("A colored border acknowledges wake. Android microphone indicators remain visible. Pocket fabric and battery restrictions can affect detection.")
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { WakeDiagnostics.status.collect {status.text=it} }
            launch { VoskModelManager.status.collect {modelStatus.text=it} }
            launch { combine(JarvisConversationService.isStandby,WakeDiagnostics.testing,WakeDiagnostics.listening) { standby,test,capture -> Triple(standby,test,capture) }.collect { (standby,test,capture) ->
                if (standby || capture) starting=false
                renderSwitch()
                engine.text=when { capture && test -> "● Microphone test · Local only"; capture -> "● Listening · Vosk offline"; standby -> "Preparing microphone…"; else -> "○ Wake microphone off" }
                testButton.text=if(standby && test) "Stop microphone test" else "Test microphone"
            } }
        } }
        renderSwitch()
    }
    private fun section(title:String, content:()->Unit) {
        val parent=page
        val body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(16))}
        parent.addView(MaterialCardView(this).apply {
            radius=dp(20).toFloat(); strokeWidth=dp(1); strokeColor=themeColor(R.attr.themeCardBorder)
            setCardBackgroundColor(themeColor(R.attr.themeCardBg)); addView(body)
        },LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12)})
        page=body
        try {label(title,18f);content()} finally {page=parent}
    }
    private fun renderSwitch() {
        rendering=true
        wakeSwitch.isChecked=JarvisConversationService.isStandby.value && !WakeDiagnostics.testing.value
        rendering=false
    }
    private fun requestListening(test:Boolean) {
        requestedTest=test
        val required=mutableListOf(Manifest.permission.RECORD_AUDIO)
        if(android.os.Build.VERSION.SDK_INT>=33) required+=Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(required.toTypedArray())
    }
    private fun stopListening() {
        if(starting || JarvisConversationService.isSessionOn.value || JarvisConversationService.isStandby.value) {
            starting=false
            startService(Intent(this,JarvisConversationService::class.java).setAction(JarvisConversationService.ACTION_STOP_ALL))
            status.text="Stopping microphone…"
        } else status.text="Microphone is already stopped"
        renderSwitch()
    }
    override fun onSaveInstanceState(outState:Bundle) {outState.putBoolean("test_requested",requestedTest);super.onSaveInstanceState(outState)}
    private fun enable() {
        try {
            starting=true
            ContextCompat.startForegroundService(this,Intent(this,JarvisConversationService::class.java).setAction(if(requestedTest) JarvisConversationService.ACTION_TEST_WAKE else JarvisConversationService.ACTION_ENTER_STANDBY))
            status.text="Starting offline microphone…"
        } catch(_:Exception) {starting=false;status.text="Android blocked startup. Keep Jarvis open and check microphone permissions.";renderSwitch()}
    }
}
