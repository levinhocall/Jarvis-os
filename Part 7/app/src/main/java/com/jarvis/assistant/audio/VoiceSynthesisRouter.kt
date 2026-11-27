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
import com.jarvis.assistant.audio.model.DynamicModelCatalog
import com.jarvis.assistant.audio.tts.PocketTtsEngine
import com.jarvis.assistant.audio.tts.PocketTtsModelConfig
import com.jarvis.assistant.audio.tts.TtsBackendChoice
import com.jarvis.assistant.audio.tts.TtsLanguageRouter
import com.jarvis.assistant.core.runtime.JarvisEvent
import com.jarvis.assistant.core.runtime.JarvisEventBus
import com.jarvis.assistant.data.model.PlaybackSource
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.util.Log
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale

object VoiceSynthesisRouter {
    private const val TAG = "VoiceSynthesisRouter"
    val isLocalReady: Boolean get() = isInitialized && localTts != null

    const val CINEMATIC_PITCH = 0.92f
    const val CINEMATIC_RATE = 1.08f

    data class PlaybackSignal(val generation: Long = 0, val playing: Boolean = false, val external: Boolean = false)
    private val playbackLock = Any()
    private val playbackMutable = kotlinx.coroutines.flow.MutableStateFlow(PlaybackSignal())
    val playback: kotlinx.coroutines.flow.StateFlow<PlaybackSignal> = playbackMutable
    private fun nextPlayback(): Long = synchronized(playbackLock) {
        (playbackMutable.value.generation + 1).also { playbackMutable.value = PlaybackSignal(it, external = playbackMutable.value.external) }
    }
    private fun publishPlayback(generation: Long, playing: Boolean, external: Boolean = false) = synchronized(playbackLock) {
        if (playbackMutable.value.generation == generation) playbackMutable.value = PlaybackSignal(generation, playing, external)
    }

    private var localTts: TextToSpeech? = null
    private val localEngineEpoch=java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile
    private var isInitialized = false
    private var pendingSpeech: Pair<String, (() -> Unit)?>? = null
    private var pendingSpeechIsPrivate = false
    @Volatile private var appContext: Context? = null
    private val externalScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var externalTtsJob: Job? = null
    private var pocketTtsJob: Job? = null
    private val pocketTtsEngine = PocketTtsEngine()
    @Volatile private var standalonePocketTrack: AudioTrack? = null
    private var externalPlayer: MediaPlayer? = null
    private val externalCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
    private var externalAudioFile: File? = null
    private val externalHttpClient = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        appContext = context.applicationContext
        externalScope.launch(Dispatchers.IO) {
            initPocketTtsIfInstalled(context.applicationContext)
        }
        if (localTts != null) return
        val epoch=localEngineEpoch.incrementAndGet()
        localTts = TextToSpeech(context.applicationContext, { status ->
            if(epoch != localEngineEpoch.get()) return@TextToSpeech
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
        }, SystemTtsEngineSelection.requested())
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
            setOnUtteranceProgressListener(AndroidTtsPcmBridge.createProgressListener())
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
        if (pocketTtsEngine.isReady() &&
            TtsLanguageRouter.selectBackend(text, true) == TtsBackendChoice.POCKET_TTS_24K_PCM
        ) {
            speakLocal(text, onDone)
            return true
        }
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
        val normalized = normalizeForSpeech(text)
        val generation = nextPlayback()
        if (TtsLanguageRouter.selectBackend(normalized, pocketTtsEngine.isReady()) ==
            TtsBackendChoice.POCKET_TTS_24K_PCM
        ) {
            speakWithPocketTts(normalized, generation, onDone)
            return
        }
        speakWithAndroidTts(normalized, generation, onDone)
    }

    private fun speakWithAndroidTts(normalized: String, generation: Long, onDone: (() -> Unit)?) {
        val tts = localTts ?: return
        val expectedId = "utterance_$generation"
        AndroidTtsPcmBridge.speak(
            tts = tts,
            cacheDir = appContext?.cacheDir,
            text = normalized,
            utteranceId = expectedId,
            isCurrent = { playback.value.generation == generation },
            onUnifiedStart = { publishPlayback(generation, playing = false, external = false) },
            onDirectStart = { publishPlayback(generation, playing = true, external = false) },
            onDirectStop = { _ -> publishPlayback(generation, playing = false, external = false) },
            onDone = {
                if (playback.value.generation == generation) {
                    publishPlayback(generation, playing = false, external = false)
                    onDone?.invoke()
                }
            }
        )
    }

    private fun speakWithPocketTts(normalized: String, generation: Long, onDone: (() -> Unit)?) {
        pocketTtsJob?.cancel()
        pocketTtsEngine.reset()
        pocketTtsJob = externalScope.launch(Dispatchers.IO) {
            val useUnifiedSink = AndroidTtsPcmBridge.hasActiveSink()
            val standaloneTrack = if (!useUnifiedSink) createStandaloneTrack24k() else null
            standalonePocketTrack = standaloneTrack
            standaloneTrack?.play()
            var streamedAnyChunk = false
            val ok = try {
                if (!useUnifiedSink) publishPlayback(generation, playing = true, external = false)
                else publishPlayback(generation, playing = false, external = false)
                JarvisEventBus.tryPublish(
                    JarvisEvent.AssistantResponseStarted(
                        generation = generation,
                        backend = "PocketTTS",
                        eventId = JarvisEventBus.nextId(),
                        timestampMs = System.currentTimeMillis(),
                        sessionId = 0L
                    )
                )
                pocketTtsEngine.synthesizeStreaming(normalized) { pcm24k ->
                    if (playback.value.generation != generation) {
                        pocketTtsEngine.cancelSynthesis()
                        return@synthesizeStreaming
                    }
                    if (useUnifiedSink) {
                        if (AndroidTtsPcmBridge.submitPcm24kMono(pcm24k, PlaybackSource.POCKET_TTS)) {
                            streamedAnyChunk = true
                        }
                    } else if (standaloneTrack != null) {
                        val written = standaloneTrack.write(pcm24k, 0, pcm24k.size, AudioTrack.WRITE_BLOCKING)
                        if (written > 0) streamedAnyChunk = true
                    }
                }
            } finally {
                if (standalonePocketTrack === standaloneTrack) standalonePocketTrack = null
                runCatching { standaloneTrack?.pause() }
                runCatching { standaloneTrack?.flush() }
                runCatching { standaloneTrack?.release() }
            }
            if (playback.value.generation != generation) return@launch
            publishPlayback(generation, playing = false, external = false)
            if (!ok || !streamedAnyChunk) {
                speakWithAndroidTts(normalized, generation, onDone)
                return@launch
            }
            JarvisEventBus.tryPublish(
                JarvisEvent.AssistantResponseCompleted(
                    generation = generation,
                    backend = "PocketTTS",
                    eventId = JarvisEventBus.nextId(),
                    timestampMs = System.currentTimeMillis(),
                    sessionId = 0L
                )
            )
            withContext(Dispatchers.Main.immediate) { onDone?.invoke() }
        }
    }

    private fun createStandaloneTrack24k(): AudioTrack? {
        return runCatching {
            val minBytes = AudioTrack.getMinBufferSize(
                24_000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3_840)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(24_000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBytes * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()
    }

    fun isPocketTtsReady(): Boolean = pocketTtsEngine.isReady()

    fun reloadPocketTts(context: Context): Boolean = initPocketTtsIfInstalled(context.applicationContext)

    private fun initPocketTtsIfInstalled(context: Context): Boolean {
        val dir = File(context.filesDir, DynamicModelCatalog.POCKET_TTS_EN_DIR)
        if (!dir.isDirectory) return false
        return pocketTtsEngine.loadModel(PocketTtsModelConfig(dir.absolutePath))
    }

    private fun speakWithElevenLabs(text: String, apiKey: String, onDone: (() -> Unit)?) {
        val context = appContext ?: run { speakLocal(text, onDone); return }
        externalTtsJob?.cancel()
        stopExternalPlayback()
        runCatching { localTts?.stop() }
        val generation = playback.value.generation
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
                    externalCalls.add(call)
                    try {
                        currentCoroutineContext().ensureActive()
                        call.execute().use { response ->
                            if (!response.isSuccessful) {
                                Log.w(TAG, "ElevenLabs TTS returned HTTP ${response.code}; using Android TTS fallback.")
                                null
                            } else {
                                readBoundedAudio(response.body)
                            }
                        }
                    } finally {
                        // Keep calls cancellable through body download, including overlapping old jobs.
                        call.cancel()
                        externalCalls.remove(call)
                    }
                }.getOrNull()
            }
            if (playback.value.generation != generation) return@launch
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
                    if (externalPlayer !== prepared || playback.value.generation != generation) return@setOnPreparedListener
                    runCatching { prepared.start(); publishPlayback(generation, true, true) }.onFailure {
                        releaseExternalPlayer()
                        speakLocal(text, onDone)
                    }
                }
                player.setOnCompletionListener { completed ->
                    if (externalPlayer !== completed || playback.value.generation != generation) return@setOnCompletionListener
                    releaseExternalPlayer()
                    onDone?.invoke()
                }
                player.setOnErrorListener { failed, _, _ ->
                    if (externalPlayer !== failed || playback.value.generation != generation) return@setOnErrorListener true
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
        nextPlayback()
        externalTtsJob?.cancel()
        externalTtsJob = null
        externalCalls.forEach { it.cancel() }
        externalCalls.clear()
        releaseExternalPlayer()
    }

    private fun releaseExternalPlayer() {
        if (playback.value.external) publishPlayback(playback.value.generation, false, true)
        runCatching { externalPlayer?.stop() }
        runCatching { externalPlayer?.release() }
        externalPlayer = null
        runCatching { externalAudioFile?.delete() }
        externalAudioFile = null
    }

    fun isSpeaking(): Boolean {
        return try {
            externalPlayer != null || pocketTtsJob?.isActive == true || localTts?.isSpeaking == true
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        pendingSpeech = null
        pendingSpeechIsPrivate = false
        stopExternalPlayback()
        stopPocketTtsPlayback()
        try {
            localTts?.stop()
        } catch (_: Exception) {}
    }

    fun release() {
        localEngineEpoch.incrementAndGet()
        stopExternalPlayback()
        stopPocketTtsPlayback()
        pocketTtsEngine.unloadModel()
        try {
            localTts?.stop()
            localTts?.shutdown()
        } catch (_: Exception) {}
        localTts = null
        isInitialized = false
        pendingSpeech = null
    }

    private fun stopPocketTtsPlayback() {
        nextPlayback()
        pocketTtsEngine.cancelSynthesis()
        pocketTtsJob?.cancel()
        pocketTtsJob = null
        runCatching { standalonePocketTrack?.pause() }
        runCatching { standalonePocketTrack?.flush() }
    }
}
