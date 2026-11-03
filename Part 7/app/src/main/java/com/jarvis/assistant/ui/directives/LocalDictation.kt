package com.jarvis.assistant.ui.directives

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.*

/** No silent cloud fallback for the private idea vault. Called only after microphone consent. */
class LocalDictation(private val context: Context, private val result: (String) -> Unit, private val status: (String) -> Unit) {
    private var recognizer: SpeechRecognizer? = null
    fun start() {
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            status("On-device dictation is unavailable. Type your idea; no audio was sent to a cloud recognizer.")
            return
        }
        close()
        try {
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status("Listening on device…") }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { status("Transcribing on device…") }
                override fun onError(error: Int) { status("Dictation stopped. Check the offline speech model or try typing.") }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) { result(text); status("Dictation ready. Review and save your idea.") }
                    else status("No speech was recognized.")
                }
            })
            speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
        }
        } catch (_: Exception) { close(); status("On-device dictation could not start. Your typed draft is unchanged.") }
    }
    fun close() { runCatching { recognizer?.cancel() }; runCatching { recognizer?.destroy() }; recognizer = null }
}
