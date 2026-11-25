package com.jarvis.assistant.audio

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.jarvis.assistant.data.model.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes Android [TextToSpeech] utterances into the active [AudioPlayer] PCM pipeline via
 * [TextToSpeech.synthesizeToFile] whenever a unified PCM sink is running, giving WebRTC AEC3
 * and DTLN an exact far-end reference instead of blind-muting the microphone. Falls back cleanly
 * to [TextToSpeech.speak] whenever no session sink is active or synthesis fails/times out.
 */
object AndroidTtsPcmBridge {
    private const val TAG = "AndroidTtsPcmBridge"
    private const val SYNTHESIS_TIMEOUT_MS = 1_500L

    interface PcmSink {
        fun isActive(): Boolean
        fun enqueueTtsPcm(pcm24kMono: ByteArray, source: PlaybackSource, onDrained: (() -> Unit)? = null): Boolean
    }

    private data class PendingEntry(
        val wavFile: File?,
        val sink: PcmSink?,
        val isCurrent: () -> Boolean,
        val onUnifiedStart: () -> Unit,
        val onDone: () -> Unit,
        val onFallbackSpeak: () -> Unit,
        val onDirectStart: (() -> Unit)? = null,
        val onDirectStop: ((interrupted: Boolean) -> Unit)? = null,
        val isDirectFallback: Boolean = false
    ) {
        @Volatile var settled = false
        @Volatile var cancelled = false
    }

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, PendingEntry>()
    @Volatile private var activeSink: PcmSink? = null

    fun registerSink(sink: PcmSink?) {
        activeSink = sink
    }

    fun unregisterSink(sink: PcmSink) {
        if (activeSink === sink) activeSink = null
    }

    fun hasActiveSink(): Boolean = activeSink?.isActive() == true

    fun cancelAll() {
        val entries = pending.values.toList()
        pending.clear()
        entries.forEach { entry ->
            entry.cancelled = true
            entry.settled = true
            runCatching { entry.wavFile?.delete() }
        }
    }

    fun createProgressListener(): UtteranceProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val id = utteranceId ?: return
            val entry = pending[id] ?: return
            if (entry.isDirectFallback && entry.isCurrent()) {
                runCatching { entry.onDirectStart?.invoke() }
            }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            val id = utteranceId ?: return
            val entry = pending.remove(id) ?: return
            entry.settled = true
            runCatching { entry.wavFile?.delete() }
            if (entry.isDirectFallback) {
                runCatching { entry.onDirectStop?.invoke(interrupted) }
            }
        }

        override fun onDone(utteranceId: String?) {
            val id = utteranceId ?: return
            val entry = pending.remove(id) ?: return
            if (entry.settled) {
                runCatching { entry.wavFile?.delete() }
                return
            }
            entry.settled = true
            if (entry.isDirectFallback) {
                runCatching { entry.wavFile?.delete() }
                if (entry.isCurrent()) runCatching { entry.onDone() }
                return
            }

            bridgeScope.launch {
                val wavFile = entry.wavFile
                val pcm24k = try {
                    if (!entry.cancelled && wavFile != null && wavFile.exists()) {
                        PcmAudioConverter.decodeWavToMono24kPcm16(wavFile.readBytes())
                    } else null
                } catch (e: Exception) {
                    Log.w(TAG, "WAV decode failed for TTS utterance ($id): ${e.message}")
                    null
                } finally {
                    runCatching { wavFile?.delete() }
                }

                if (entry.cancelled || !entry.isCurrent()) return@launch
                val sink = entry.sink
                if (pcm24k != null && pcm24k.size >= 16 && sink != null && sink.isActive()) {
                    runCatching { entry.onUnifiedStart() }
                    val queued = sink.enqueueTtsPcm(pcm24k, PlaybackSource.ANDROID_TTS) {
                        if (!entry.cancelled && entry.isCurrent()) runCatching { entry.onDone() }
                    }
                    if (queued) return@launch
                }
                if (!entry.cancelled && entry.isCurrent()) {
                    entry.onFallbackSpeak()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            handleError(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            handleError(utteranceId)
        }

        private fun handleError(utteranceId: String?) {
            val id = utteranceId ?: return
            val entry = pending.remove(id) ?: return
            if (entry.settled) {
                runCatching { entry.wavFile?.delete() }
                return
            }
            entry.settled = true
            runCatching { entry.wavFile?.delete() }
            if (entry.isDirectFallback) {
                runCatching { entry.onDirectStop?.invoke(false) }
                if (entry.isCurrent()) runCatching { entry.onDone() }
            } else if (entry.isCurrent()) {
                entry.onFallbackSpeak()
            }
        }
    }

    /**
     * Synthesizes [text] into the unified [AudioPlayer] sink when available; otherwise invokes
     * [TextToSpeech.speak] directly so standalone standby/browser TTS stays responsive.
     */
    fun speak(
        tts: TextToSpeech,
        cacheDir: File?,
        text: String,
        utteranceId: String,
        isCurrent: () -> Boolean,
        onUnifiedStart: () -> Unit,
        onDirectStart: () -> Unit,
        onDirectStop: (interrupted: Boolean) -> Unit,
        onDone: () -> Unit
    ) {
        val sink = activeSink?.takeIf { it.isActive() }
        val fallbackDirect = {
            if (isCurrent()) {
                val directEntry = PendingEntry(
                    wavFile = null,
                    sink = null,
                    isCurrent = isCurrent,
                    onUnifiedStart = onUnifiedStart,
                    onDone = onDone,
                    onFallbackSpeak = {},
                    onDirectStart = onDirectStart,
                    onDirectStop = onDirectStop,
                    isDirectFallback = true
                )
                pending[utteranceId] = directEntry
                val rc = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (rc != TextToSpeech.SUCCESS) {
                    pending.remove(utteranceId)
                    directEntry.settled = true
                    onDirectStop(false)
                }
            }
        }

        if (sink == null || cacheDir == null || !cacheDir.exists()) {
            fallbackDirect()
            return
        }

        val synthId = "${utteranceId}_pcm_${System.nanoTime()}"
        val wavFile = File(cacheDir, "jarvis_tts_${System.nanoTime()}.wav")
        val entry = PendingEntry(
            wavFile = wavFile,
            sink = sink,
            isCurrent = isCurrent,
            onUnifiedStart = onUnifiedStart,
            onDone = onDone,
            onFallbackSpeak = fallbackDirect,
            onDirectStart = onDirectStart,
            onDirectStop = onDirectStop,
            isDirectFallback = false
        )
        pending[synthId] = entry

        val res = runCatching {
            tts.synthesizeToFile(text, Bundle(), wavFile, synthId)
        }.getOrElse { TextToSpeech.ERROR }

        if (res != TextToSpeech.SUCCESS) {
            pending.remove(synthId)
            entry.settled = true
            runCatching { wavFile.delete() }
            fallbackDirect()
            return
        }

        bridgeScope.launch {
            delay(SYNTHESIS_TIMEOUT_MS)
            val timedOut = pending.remove(synthId)
            if (timedOut != null && !timedOut.settled && !timedOut.cancelled) {
                timedOut.settled = true
                runCatching { wavFile.delete() }
                if (timedOut.isCurrent()) {
                    Log.w(TAG, "TTS file synthesis timed out after ${SYNTHESIS_TIMEOUT_MS}ms; falling back to direct speech.")
                    runCatching { tts.stop() }
                    timedOut.onFallbackSpeak()
                }
            }
        }
    }
}
