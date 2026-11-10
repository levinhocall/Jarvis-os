package com.jarvis.assistant.ui.directives

import android.Manifest
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.directives.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import java.io.File

class EnglishTeacherActivity : DirectiveActivity() {
    private val failures = kotlinx.coroutines.CoroutineExceptionHandler { _, _ ->
        if (::status.isInitialized) status.text = "The operation could not finish. Stored data has not been reset; please retry."
    }
    private val app get() = application as JarvisApp
    private val dao get() = app.memoryDatabase.directiveDao()
    private var profile = LearningProfileEntity()
    private lateinit var text: EditText
    private lateinit var status: TextView
    private lateinit var reply: TextView
    private lateinit var metrics: TextView
    private lateinit var recordButton: MaterialButton
    private lateinit var sendButton: MaterialButton
    private var recorder: MediaRecorder? = null
    private var microphoneLease: com.jarvis.assistant.audio.MicrophoneOwnership.Lease? = null
    private var audio: File? = null
    private var request: Job? = null
    private var lastReply = ""
    private var profileLoaded = false
    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startRecording() else status.text = "Microphone permission denied; text practice is available."
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        heading("English Teacher AI")
        label("Choose your trainer, level and goal. Submitted text or audio goes to your configured Gemini provider; progress is stored encrypted on this phone.")
        status = label("Loading local learning profile…")
        metrics = label("No assessed attempts yet.")
        text = input("Write a practice answer or ask for a lesson…").apply { isSaveEnabled = false }
        sendButton = action("Send text for coaching") { submit(null) }.apply { isEnabled = false }
        recordButton = action("Record & assess with Gemini (up to 30s)") {
            if (recorder == null) mic.launch(Manifest.permission.RECORD_AUDIO) else finishRecording()
        }.apply { isEnabled = false }
        reply = label("").apply { setTextIsSelectable(true) }
        action("Read reply with offline voice") {
            if (lastReply.isNotBlank()) {
                com.jarvis.assistant.audio.VoiceSynthesisRouter.init(applicationContext)
                if (!com.jarvis.assistant.audio.VoiceSynthesisRouter.speakPrivate(lastReply)) notice("Offline speech is unavailable or still initializing. The reply remains on screen.")
            }
        }
        action("Clear learning history") {
            MaterialAlertDialogBuilder(this).setTitle("Erase local learning history?")
                .setNegativeButton("Keep", null).setPositiveButton("Erase") { _, _ -> lifecycleScope.launch(failures) { request?.cancelAndJoin(); abortRecording(); dao.clearAttempts(); reply.text = ""; lastReply = "" } }.show()
        }
        lifecycleScope.launch(failures) {
            try {
                profile = dao.profile() ?: LearningProfileEntity()
                LearningPolicy.validate(profile)
                val selectorStart = page.childCount
                label("Trainer: Aisha · patience / Alex · grammar / Maya · conversation / Ryan · interviews", 12f)
                options(LearningPolicy.trainers.keys.toList(), profile.trainer) { profile = profile.copy(trainer = it); saveProfile() }
                options(LearningPolicy.levels, profile.level) { profile = profile.copy(level = it); saveProfile() }
                options(LearningPolicy.goals, profile.goal) { profile = profile.copy(goal = it); saveProfile() }
                val controls = (selectorStart until page.childCount).map { page.getChildAt(it) }
                var insertion = page.indexOfChild(text)
                controls.forEach { page.removeView(it); page.addView(it, insertion++) }
                profileLoaded = true
                sendButton.isEnabled = true; recordButton.isEnabled = true
                status.text = "Ready. Scores are AI estimates, not certified language-test results."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status.text = "Could not load the encrypted profile. Existing data has not been reset." }
        }
        lifecycleScope.launch(failures) { repeatOnLifecycle(Lifecycle.State.STARTED) {
            dao.observeMetrics().collect { attempts ->
                val m = withContext(Dispatchers.Default) { LearningPolicy.metrics(attempts) }
                fun value(n: Int?) = n?.let { "$it/100" } ?: "Not assessed"
                metrics.text = "${m.streak}-day streak • ${m.attempts} assessed samples\nOverall: ${value(m.overall)} • Fluency: ${value(m.fluency)}\nPronunciation: ${value(m.pronunciation)} (recorded audio only)\nAI-estimated scores; text practice never creates pronunciation scores."
            }
        } }
    }
    private fun saveProfile() {
        val selected = profile
        lifecycleScope.launch(failures) {
            try { dao.saveProfile(selected) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status.text = "Could not save trainer preferences. Existing progress is preserved." }
        }
    }
    private fun submit(file: File?) {
        if (request?.isActive == true) { file?.delete(); return }
        val input = text.text.toString().trim()
        if (file == null && input.isBlank()) { notice("Enter a practice message or record an answer."); return }
        if (input.length > 6000) { notice("Keep a practice sample under 6,000 characters."); file?.delete(); return }
        val selectedProfile = profile
        sendButton.isEnabled = false; recordButton.isEnabled = false
        request = lifecycleScope.launch(failures) {
            status.text = "Assessing your actual sample…"
            try {
                val assessment = DirectiveAiClient(app.preferences).assess(selectedProfile, input, file)
                dao.addAttempt(LearningAttemptEntity(trainer = selectedProfile.trainer, level = selectedProfile.level,
                    goal = selectedProfile.goal, userText = if (file == null) input else assessment.transcript,
                    reply = assessment.reply, correction = assessment.correction, grammar = assessment.grammar,
                    fluency = assessment.fluency, pronunciation = assessment.pronunciation, audioAssessed = file != null))
                lastReply = assessment.reply
                reply.text = buildString {
                    if (file != null) append("Heard: ${assessment.transcript}\n\n")
                    append(assessment.reply)
                    if (assessment.correction.isNotBlank()) append("\n\nCorrection: ${assessment.correction}")
                }
                status.text = "Assessment saved locally. Continue with your trainer's practice question."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status.text = "Assessment unavailable. Check provider access. No score or progress was invented." }
            finally { file?.delete(); sendButton.isEnabled = true; recordButton.isEnabled = true }
        }
    }
    @Suppress("DEPRECATION")
    private fun startRecording() {
        if (!profileLoaded || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || recorder != null || request?.isActive == true) return
        if (com.jarvis.assistant.background.JarvisConversationService.isSessionOn.value) {
            notice("End the live voice session before recording a learning sample."); return
        }
        val lease = com.jarvis.assistant.audio.MicrophoneOwnership.acquire(
            com.jarvis.assistant.audio.MicrophoneOwner.LEARNING_SAMPLE
        )
        if (lease == null) { notice("Microphone is busy. Stop wake listening, dictation or voice enrollment, then retry."); return }
        microphoneLease = lease
        try {
            val file = File.createTempFile("english-practice-", ".m4a", cacheDir); audio = file
            val recording = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            recorder = recording
            recording.setAudioSource(MediaRecorder.AudioSource.MIC)
            recording.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recording.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recording.setAudioSamplingRate(16000); recording.setAudioEncodingBitRate(64000)
            recording.setMaxDuration(30000); recording.setOutputFile(file.absolutePath)
            recording.setOnInfoListener { _, what, _ -> if (recorder === recording && what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finishRecording() }
            recording.setOnErrorListener { _, _, _ -> if (recorder === recording) {
                abortRecording(); status.text = "Recording interrupted. No assessment was saved."
            } }
            recording.prepare(); recording.start()
            recordButton.text = "Stop and assess recording"; sendButton.isEnabled = false
            status.text = "Recording. The stopped sample is sent to Gemini for an audio-based assessment."
        } catch (_: Exception) { abortRecording(); status.text = "Recording could not start. Text practice is still available." }
    }
    private fun finishRecording() {
        val current = recorder ?: return
        val file = audio; recorder = null; audio = null
        val lease = microphoneLease; microphoneLease = null
        try {
            check(file != null) { "Recording file unavailable" }
            current.stop()
            recordButton.text = "Record & assess with Gemini (up to 30s)"
        } catch (_: Exception) {
            file?.delete(); sendButton.isEnabled = true
            status.text = "Recording was too short or interrupted. No assessment was saved."
            return
        } finally { runCatching { current.release() }; lease?.close() }
        submit(file)
    }
    private fun abortRecording() {
        runCatching { recorder?.stop() }; runCatching { recorder?.release() }; recorder = null
        microphoneLease?.close(); microphoneLease = null
        audio?.delete(); audio = null
        if (::recordButton.isInitialized) { recordButton.text = "Record & assess with Gemini (up to 30s)"; sendButton.isEnabled = profileLoaded && request?.isActive != true; recordButton.isEnabled = profileLoaded && request?.isActive != true }
    }
    override fun onStop() { abortRecording(); super.onStop() }
    override fun onDestroy() { request?.cancel(); super.onDestroy() }
}
