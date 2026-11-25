package com.jarvis.assistant.audio

import android.util.Log

/** Session-scoped DSP ownership, never a microphone/player owner. */
interface PcmSpeechDetector : AutoCloseable { fun accept(pcm: ByteArray): Float }

class DuplexEchoSession(
    val enabled: Boolean,
    private val factory: () -> EchoBackend = { Aec3Native() },
    private val speechFactory: (() -> PcmSpeechDetector)? = null,
    private val allowAecFallback: Boolean = false
) : AutoCloseable {
    companion object {
        private const val TAG = "DuplexEchoSession"
    }

    @Volatile private var processor: FramedDuplexProcessor? = null
    private var closed = false
    private var speech: PcmSpeechDetector? = null
    private var speechInitFailed = false
    @Volatile var speechProbability = 0f
        private set
    @Volatile var isAecActive = false
        private set
    @Volatile var fallbackReason: String? = null
        private set
    val hasSpeechDetector: Boolean get() = !speechInitFailed && (speech != null || (!closed && speechFactory != null))
    private val playbackEpoch = java.util.concurrent.atomic.AtomicLong(0L)

    @Synchronized fun initialize(): Boolean {
        if (closed) return false
        if (enabled && processor == null && fallbackReason == null) {
            if (allowAecFallback) {
                try {
                    processor = FramedDuplexProcessor(factory())
                    isAecActive = true
                } catch (error: Throwable) {
                    if (error is VirtualMachineError || error is ThreadDeath) throw error
                    fallbackReason = error.message ?: error.javaClass.simpleName
                    isAecActive = false
                    Log.w(TAG, "Software AEC3 unavailable ($fallbackReason); falling back to hardware AEC.")
                }
            } else {
                processor = FramedDuplexProcessor(factory())
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
            isAecActive = false
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
        if (cleaned.isNotEmpty()) {
            speechProbability = speech?.accept(cleaned)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        }
        return cleaned
    }

    @Synchronized fun render(pcm: ByteArray, offset: Int, count: Int, delayMs: Int, epoch: Long) {
        if (epoch == playbackEpoch.get()) processor?.renderAccepted(pcm, offset, count, delayMs)
    }

    fun echoMetrics(): AecMetrics? = processor?.metrics()

    fun reset(epoch: Long) {
        playbackEpoch.set(epoch)
        processor?.requestReset()
    }

    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            isAecActive = false
            processor?.close()
            processor = null
            speech?.close()
            speech = null
            speechProbability = 0f
        }
    }
}
