package com.jarvis.assistant.ui.directives

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.*

/** No silent cloud fallback for the private idea vault. Called only after microphone consent. */
class LocalDictation(private val context: Context, private val result: (String) -> Unit, private val status: (String) -> Unit) {
    private var recognizer: SpeechRecognizer? = null
    private var microphoneLease: com.jarvis.assistant.audio.MicrophoneOwnership.Lease? = null
    fun start() {
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            status("On-device dictation is unavailable. Type your idea; no audio was sent to a cloud recognizer.")
            return
        }
        close()
        val lease = com.jarvis.assistant.audio.MicrophoneOwnership.acquire(
            com.jarvis.assistant.audio.MicrophoneOwner.LOCAL_DICTATION
        )
        if (lease == null) {
            status("Microphone is busy. Stop voice/wake listening or recording, then retry dictation.")
            return
        }
        microphoneLease = lease
        try {
            val speech = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = speech
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (recognizer === speech) status("Listening on device…") }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { if (recognizer === speech) status("Transcribing on device…") }
                override fun onError(error: Int) {
                    if (recognizer !== speech) return
                    close()
                    status("Dictation stopped. Check the offline speech model or try typing.")
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onResults(results: Bundle?) {
                    if (recognizer !== speech) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    close()
                    if (text.isNotBlank()) { result(text); status("Dictation ready. Review and save your idea.") }
                    else status("No speech was recognized.")
                }
            })
            speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
        } catch (_: Exception) { close(); status("On-device dictation could not start. Your typed draft is unchanged.") }
    }
    fun close() {
        val previous = recognizer; recognizer = null
        val lease = microphoneLease; microphoneLease = null
        runCatching { previous?.cancel() }
        runCatching { previous?.destroy() }
        lease?.close()
    }
}
