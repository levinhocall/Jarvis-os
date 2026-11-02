package com.jarvis.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.jarvis.assistant.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Call
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
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
    @Volatile
    private var isInitialized = false
    private var pendingSpeech: Pair<String, (() -> Unit)?>? = null
    private var pendingSpeechIsPrivate = false
    @Volatile private var appContext: Context? = null
    private val externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var externalTtsJob: Job? = null
    private var externalPlayer: MediaPlayer? = null
    @Volatile private var externalCall: Call? = null
    private var externalAudioFile: File? = null
    private val externalHttpClient = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        appContext = context.applicationContext
        if (localTts != null) return
        localTts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                configureVoiceProfile()
                Log.i(TAG, "Cinematic Voice Profile configured (Pitch=$CINEMATIC_PITCH, Rate=$CINEMATIC_RATE)")
                val queued = pendingSpeech
                if (queued != null) {
                    pendingSpeech = null
                    if (pendingSpeechIsPrivate) speakPrivate(queued.first, queued.second)
                    else speak(queued.first, queued.second)
                }
            } else {
                isInitialized = false
                pendingSpeech = null
                Log.e(TAG, "Android TextToSpeech initialization failed (status=$status).")
            }
        }
    }

    private fun configureVoiceProfile() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        localTts?.apply {
            setAudioAttributes(attributes)
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
        val tts = localTts
        if (tts == null || !isInitialized) {
            Log.w(TAG, "TTS not ready yet; queuing one pending utterance (content omitted).")
            pendingSpeechIsPrivate = false
            pendingSpeech = Pair(text, onDone)
            return
        }

        val preferences = (appContext as? JarvisApp)?.preferences
        val elevenLabsKey = preferences?.getNextActiveKey("elevenlabs")
        if (!elevenLabsKey.isNullOrBlank() && BuildConfig.ELEVENLABS_VOICE_ID.isNotBlank()) {
            speakWithElevenLabs(text, elevenLabsKey, onDone)
        } else {
            stopExternalPlayback()
            speakLocal(text, onDone)
        }
    }

    /** Local memory/reflex results must never reach an external TTS provider or network voice. */
    fun speakPrivate(text: String, onDone: (() -> Unit)? = null): Boolean {
        stopExternalPlayback()
        val tts = localTts
        if (tts == null || !isInitialized) {
            pendingSpeechIsPrivate = true
            pendingSpeech = Pair(text, onDone)
            return true
        }
        val voices = tts.voices.orEmpty().filter { !it.isNetworkConnectionRequired }
        val offlineVoice = voices.firstOrNull { it.locale.language == Locale.getDefault().language }
            ?: voices.firstOrNull()
        if (offlineVoice == null || tts.setVoice(offlineVoice) != TextToSpeech.SUCCESS) {
            Log.w(TAG, "No offline TTS voice is available for private speech; text remains on screen.")
            return false
        }
        speakLocal(text, onDone)
        return true
    }

    private fun speakLocal(text: String, onDone: (() -> Unit)?) {
        val tts = localTts ?: return
        val normalized = normalizeForSpeech(text)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onDone?.invoke() }
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "Local TTS utterance failed; completion callback suppressed.")
            }
        })
        tts.speak(normalized, TextToSpeech.QUEUE_FLUSH, null, "utterance_${System.currentTimeMillis()}")
    }

    private fun speakWithElevenLabs(text: String, apiKey: String, onDone: (() -> Unit)?) {
        val context = appContext ?: run { speakLocal(text, onDone); return }
        externalTtsJob?.cancel()
        stopExternalPlayback()
        runCatching { localTts?.stop() }
        externalTtsJob = externalScope.launch {
            val audio = withContext(Dispatchers.IO) {
                runCatching {
                    val body = JSONObject()
                        .put("text", normalizeForSpeech(text))
                        .put("model_id", "eleven_multilingual_v2")
                        .put("voice_settings", JSONObject()
                            .put("stability", 0.45)
                            .put("similarity_boost", 0.78)
                            .put("style", 0.15)
                            .put("use_speaker_boost", true))
                        .toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = Request.Builder()
                        .url("https://api.elevenlabs.io/v1/text-to-speech/${BuildConfig.ELEVENLABS_VOICE_ID}")
                        .header("xi-api-key", apiKey)
                        .header("Accept", "audio/mpeg")
                        .post(body)
                        .build()
                    val call = externalHttpClient.newCall(request)
                    externalCall = call
                    call.execute().use { response ->
                        externalCall = null
                        if (!response.isSuccessful) {
                            Log.w(TAG, "ElevenLabs TTS returned HTTP ${response.code}; using Android TTS fallback.")
                            null
                        } else {
                            response.body?.bytes()?.takeIf { it.isNotEmpty() && it.size <= 12_000_000 }
                        }
                    }
                }.getOrNull()
            }
            if (audio == null) {
                speakLocal(text, onDone)
                return@launch
            }
            val file = File(context.cacheDir, "jarvis_tts_${System.currentTimeMillis()}.mp3")
            try {
                file.writeBytes(audio)
                externalAudioFile = file
                val player = MediaPlayer()
                externalPlayer = player
                player.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                player.setDataSource(file.absolutePath)
                player.setOnPreparedListener { prepared ->
                    runCatching { prepared.start() }.onFailure {
                        releaseExternalPlayer()
                        speakLocal(text, onDone)
                    }
                }
                player.setOnCompletionListener {
                    releaseExternalPlayer()
                    onDone?.invoke()
                }
                player.setOnErrorListener { _, _, _ ->
                    releaseExternalPlayer()
                    speakLocal(text, onDone)
                    true
                }
                player.prepareAsync()
            } catch (error: Exception) {
                Log.w(TAG, "ElevenLabs audio playback failed; using Android TTS fallback.", error)
                releaseExternalPlayer()
                speakLocal(text, onDone)
            }
        }
    }

    private fun stopExternalPlayback() {
        externalTtsJob?.cancel()
        externalTtsJob = null
        externalCall?.cancel()
        externalCall = null
        releaseExternalPlayer()
    }

    private fun releaseExternalPlayer() {
        runCatching { externalPlayer?.stop() }
        runCatching { externalPlayer?.release() }
        externalPlayer = null
        runCatching { externalAudioFile?.delete() }
        externalAudioFile = null
    }

    fun isSpeaking(): Boolean {
        return try {
            externalPlayer != null || localTts?.isSpeaking == true
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        pendingSpeech = null
        pendingSpeechIsPrivate = false
        stopExternalPlayback()
        try {
            localTts?.stop()
        } catch (_: Exception) {}
    }

    fun release() {
        stopExternalPlayback()
        try {
            localTts?.stop()
            localTts?.shutdown()
        } catch (_: Exception) {}
        localTts = null
        isInitialized = false
        pendingSpeech = null
    }
}
