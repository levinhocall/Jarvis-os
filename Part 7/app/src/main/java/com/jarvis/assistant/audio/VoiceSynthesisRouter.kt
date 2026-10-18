package com.jarvis.assistant.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object VoiceSynthesisRouter {
    private const val TAG = "VoiceSynthesisRouter"

    const val CINEMATIC_PITCH = 0.92f
    const val CINEMATIC_RATE = 1.08f

    private var localTts: TextToSpeech? = null
    private var isInitialized = false

    fun init(context: Context) {
        if (localTts != null) return
        localTts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                configureVoiceProfile()
                Log.i(TAG, "Cinematic Voice Profile configured (Pitch=$CINEMATIC_PITCH, Rate=$CINEMATIC_RATE)")
            }
        }
    }

    private fun configureVoiceProfile() {
        localTts?.apply {
            setPitch(CINEMATIC_PITCH)
            setSpeechRate(CINEMATIC_RATE)
            language = Locale("hi", "IN").takeIf { isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE } ?: Locale.US
        }
    }

    /**
     * Normalizes currency, numbers, and Hinglish phrases for natural conversational TTS.
     */
    fun normalizeForSpeech(text: String): String {
        var clean = text.trim()

        // Replace currency symbols
        clean = clean.replace(Regex("₹\\s*([\\d,]+)")) { match ->
            val num = match.groupValues[1].replace(",", "")
            "$num rupaye"
        }
        clean = clean.replace(Regex("Rs\\.?\\s*([\\d,]+)", RegexOption.IGNORE_CASE)) { match ->
            val num = match.groupValues[1].replace(",", "")
            "$num rupees"
        }

        // Replace percentages
        clean = clean.replace(Regex("(\\d+)%"), "$1 percent")

        // Clean markdown bold, asterisks, brackets
        clean = clean.replace(Regex("[*#_`~\\[\\]]"), "")

        // Polite honorific normalization
        if (!clean.contains("Sir", ignoreCase = true) && clean.length > 10) {
            clean = clean.trimEnd('.') + ", Sir."
        }

        return clean
    }

    /**
     * Speaks text using the Cinematic Voice Profile with normalized phonetics.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val tts = localTts ?: return
        val normalized = normalizeForSpeech(text)

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onDone?.invoke() }
            override fun onError(utteranceId: String?) { onDone?.invoke() }
        })

        tts.speak(normalized, TextToSpeech.QUEUE_FLUSH, null, "utterance_${System.currentTimeMillis()}")
    }

    fun stop() {
        try {
            localTts?.stop()
        } catch (_: Exception) {}
    }

    fun release() {
        try {
            localTts?.stop()
            localTts?.shutdown()
        } catch (_: Exception) {}
        localTts = null
        isInitialized = false
    }
}
