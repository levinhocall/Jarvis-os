package com.jarvis.assistant.audio

import android.util.Log
import com.jarvis.assistant.audio.dtln.DtlnDiagnostics
import com.jarvis.assistant.audio.dtln.DtlnEchoSuppressor

/** Session-scoped DSP ownership, never a microphone/player owner. */
interface PcmSpeechDetector : AutoCloseable {
    fun accept(pcm: ByteArray): Float
    fun accept(pcm: ByteArray, playbackActive: Boolean): Float = accept(pcm)
    fun isSpeechActive(): Boolean = false
    fun reset() {}
}

class DuplexEchoSession(
    val enabled: Boolean,
    private val factory: () -> EchoBackend = { Aec3Native() },
    private val speechFactory: (() -> PcmSpeechDetector)? = null,
    private val allowAecFallback: Boolean = false,
    private val dtlnFactory: (() -> DtlnEchoSuppressor)? = null
) : AutoCloseable {
    companion object {
        private const val TAG = "DuplexEchoSession"
    }

    @Volatile private var processor: FramedDuplexProcessor? = null
    private var dtln: DtlnEchoSuppressor? = null
    private var closed = false
    private var speech: PcmSpeechDetector? = null
    private var speechInitFailed = false

    @Volatile var speechProbability = 0f
        private set
    @Volatile var isSpeechConfirmed = false
        private set
    @Volatile var isPlaybackActiveForVad = false
    @Volatile private var lastRenderWallMs = 0L
    @Volatile var isAecActive = false
        private set
    @Volatile var isDtlnActive = false
        private set
    @Volatile var fallbackReason: String? = null
        private set
    @Volatile var dtlnFallbackReason: String? = null
        private set
    val hasSpeechDetector: Boolean get() = !speechInitFailed && (speech != null || (!closed && speechFactory != null))
    private val playbackEpoch = java.util.concurrent.atomic.AtomicLong(0L)

    @Synchronized fun initialize(): Boolean {
        if (closed) return false
        val df = dtlnFactory
        if (enabled && dtln == null && df != null && dtlnFallbackReason == null) {
            try {
                dtln = df.invoke()
                isDtlnActive = true
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is ThreadDeath) throw error
                dtlnFallbackReason = error.message ?: error.javaClass.simpleName
                isDtlnActive = false
                Log.w(TAG, "Dual DTLN suppressor unavailable ($dtlnFallbackReason); continuing with AEC3 only.")
            }
        }
        if (enabled && processor == null && fallbackReason == null) {
            if (allowAecFallback) {
                try {
                    processor = FramedDuplexProcessor(factory(), dtln)
                    isAecActive = true
                } catch (error: Throwable) {
                    if (error is VirtualMachineError || error is ThreadDeath) throw error
                    fallbackReason = error.message ?: error.javaClass.simpleName
                    isAecActive = false
                    Log.w(TAG, "Software AEC3 unavailable ($fallbackReason); falling back to hardware AEC.")
                }
            } else {
                processor = FramedDuplexProcessor(factory(), dtln)
                isAecActive = true
            }
        }
        try {
            val sf = speechFactory
            if (speech == null && sf != null && !speechInitFailed) {
                if (allowAecFallback) {
                    speech = try {
                        sf.invoke()
                    } catch (error: Throwable) {
                        if (error is VirtualMachineError || error is ThreadDeath) throw error
                        speechInitFailed = true
                        Log.w(TAG, "Optional Silero speech gate init failed (${error.message}); continuing without neural VAD.")
                        null
                    }
                } else {
                    speech = sf.invoke()
                }
            }
        } catch (error: Throwable) {
            processor?.close()
            processor = null
            dtln = null
            isAecActive = false
            isDtlnActive = false
            throw error
        }
        return true
    }

    @Synchronized fun capture(pcm: ByteArray): ByteArray {
        if (closed) return byteArrayOf()
        val activeProcessor = processor
        val cleaned = when {
            activeProcessor != null -> activeProcessor.capture(pcm)
            !enabled || fallbackReason != null -> pcm
            else -> byteArrayOf()
        }
        val activeSpeech = speech
        if (cleaned.isNotEmpty() && activeSpeech != null) {
            val playbackWindowActive = isPlaybackActiveForVad || (System.currentTimeMillis() - lastRenderWallMs in 0..220L)
            speechProbability = activeSpeech.accept(cleaned, playbackWindowActive).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            isSpeechConfirmed = activeSpeech.isSpeechActive()
        } else if (cleaned.isNotEmpty()) {
            speechProbability = 0f
            isSpeechConfirmed = false
        }
        return cleaned
    }

    @Synchronized fun render(pcm: ByteArray, offset: Int, count: Int, delayMs: Int, epoch: Long) {
        if (epoch == playbackEpoch.get()) {
            lastRenderWallMs = System.currentTimeMillis()
            processor?.renderAccepted(pcm, offset, count, delayMs)
        }
    }

    @Synchronized fun resetSpeechDetector() {
        speech?.reset()
        speechProbability = 0f
        isSpeechConfirmed = false
    }

    fun echoMetrics(): AecMetrics? = processor?.metrics()

    fun dtlnDiagnostics(): DtlnDiagnostics? = dtln?.diagnostics()

    fun reset(epoch: Long) {
        playbackEpoch.set(epoch)
        processor?.requestReset()
    }

    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            isAecActive = false
            isDtlnActive = false
            val ownsProcessor = processor != null
            processor?.close()
            processor = null
            if (!ownsProcessor) {
                runCatching { dtln?.close() }
            }
            dtln = null
            speech?.close()
            speech = null
            speechProbability = 0f
            isSpeechConfirmed = false
            isPlaybackActiveForVad = false
        }
    }
}
