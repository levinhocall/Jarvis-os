package com.jarvis.assistant.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.jarvis.assistant.audio.AudioRecorder
import com.jarvis.assistant.audio.MicrophoneOwner
import com.jarvis.assistant.audio.MicrophoneOwnership
import com.jarvis.assistant.audio.VoskModelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.vosk.Recognizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/** System recognition binding, backed by the existing Vosk model and AudioRecorder.
 * No cloud, playback, commands or assistant session are started here. English model only.
 */
class JarvisRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private class Request(val callback: Callback, parent: CoroutineScope, val partials: Boolean, val epoch: Long) {
        val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        val lock = Any()
        var recognizer: Recognizer? = null
        var recorder: AudioRecorder? = null
        var finalText = ""
        var lastPartial = ""
        var speechStarted = false
        var readyPosted = false
        var lastMeter = 0L
    }
    private val active = AtomicReference<Request?>(null)
    private val epochs = AtomicLong(0L)

    override fun onStartListening(intent: Intent, callback: Callback) {
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            callback.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); return
        }
        val language = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE).orEmpty()
        if (language.isNotBlank() && Locale.forLanguageTag(language).language != "en") {
            callback.error(SpeechRecognizer.ERROR_CLIENT); return
        }
        if(active.get() != null || MicrophoneOwnership.owner.value != null) {
            callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return
        }
        val request = Request(callback,scope,intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false),epochs.incrementAndGet())
        if (MicrophoneOwnership.owner.value != null || !active.compareAndSet(null,request)) {
            request.scope.cancel(); callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return
        }
        request.scope.launch {
            try {
                VoskModelManager.init(applicationContext,scope,includeSpeaker=false)
                withTimeout(20_000) { VoskModelManager.isModelReady.first { it } }
                ensureActive()
                synchronized(request.lock) {
                    if (active.get() !== request) return@synchronized
                    request.recognizer = Recognizer(VoskModelManager.model ?: error("Offline model unavailable"),16000f)
                }
                val recorder = AudioRecorder(MicrophoneOwner.LOCAL_DICTATION) { pcm, amplitude, _ ->
                    if (active.get() === request) {
                        try {
                            var complete = false
                            synchronized(request.lock) {
                                val recognizer = request.recognizer
                                if (recognizer != null && active.get() === request) {
                                    complete = recognizer.acceptWaveForm(pcm,pcm.size)
                                    val json = JSONObject(if(complete) recognizer.result else recognizer.partialResult)
                                    val text = json.optString(if(complete) "text" else "partial").take(4000)
                                    if (text.isNotBlank() && !request.speechStarted) {
                                        request.speechStarted=true
                                        emit(request) { beginningOfSpeech() }
                                    }
                                    if (complete) request.finalText=text
                                    else if(request.partials && text != request.lastPartial) {
                                        request.lastPartial=text
                                        emit(request) { partialResults(result(text)) }
                                    }
                                }
                            }
                            val now=android.os.SystemClock.elapsedRealtime()
                            if(now-request.lastMeter >= 200) {
                                request.lastMeter=now; emit(request) { rmsChanged(amplitude.coerceIn(0f,1f)*10f) }
                            }
                            if(complete) finish(request)
                        } catch (_: Exception) { finish(request,SpeechRecognizer.ERROR_AUDIO) }
                    }
                }
                synchronized(request.lock) {
                    if(active.get() === request) request.recorder=recorder
                }
                recorder.onCaptureFailure = { finish(request,SpeechRecognizer.ERROR_AUDIO) }
                ensureActive()
                if (!recorder.start(request.scope)) {
                    finish(request,SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return@launch
                }
                if(active.get() !== request) { recorder.stop(); return@launch }
                emitReady(request)
                delay(30_000)
                finish(request)
            } catch (cancelled: CancellationException) {
                if(active.get() === request) finish(request,SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                throw cancelled
            } catch (_: Exception) { finish(request,SpeechRecognizer.ERROR_AUDIO) }
        }
    }

    private fun result(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,arrayListOf(text))
    }
    private fun emitReady(request: Request) = synchronized(request.lock) {
        if(!request.readyPosted && active.get() === request) {
            request.readyPosted=true
            main.post { if(active.get() === request) runCatching { request.callback.readyForSpeech(Bundle()) } }
        }
    }
    private fun emit(request: Request, action: Callback.() -> Unit) = synchronized(request.lock) {
        emitReady(request)
        main.post { if(active.get() === request) runCatching { request.callback.action() } }
    }
    private fun finish(request: Request, error: Int? = null, deliver: Boolean = true) {
        if(!active.compareAndSet(request,null)) return
        request.scope.cancel()
        scope.launch(NonCancellable + Dispatchers.Default) {
            val text = synchronized(request.lock) {
                request.recorder?.stop(); request.recorder=null
                val recognizer=request.recognizer.also { request.recognizer=null }
                try {
                    request.finalText.ifBlank { recognizer?.finalResult?.let { JSONObject(it).optString("text") }.orEmpty() }.take(4000)
                } catch (_: Exception) { "" }
                finally { runCatching { recognizer?.close() } }
            }
            if(deliver) main.post {
                if(epochs.get() != request.epoch) return@post
                runCatching {
                    when {
                        error != null -> request.callback.error(error)
                        text.isBlank() -> request.callback.error(SpeechRecognizer.ERROR_NO_MATCH)
                        else -> { request.callback.endOfSpeech(); request.callback.results(result(text)) }
                    }
                }
            }
        }
    }
    override fun onStopListening(callback: Callback) { active.get()?.let { finish(it) } }
    override fun onCancel(callback: Callback) { epochs.incrementAndGet();active.get()?.let { finish(it,deliver=false) } }
    override fun onDestroy() {
        epochs.incrementAndGet()
        active.get()?.let { finish(it,deliver=false) }
        scope.cancel(); super.onDestroy()
    }
}
