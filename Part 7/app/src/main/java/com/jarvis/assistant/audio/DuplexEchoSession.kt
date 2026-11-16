package com.jarvis.assistant.audio

/** Session-scoped DSP ownership, never a microphone/player owner. */
interface PcmSpeechDetector : AutoCloseable { fun accept(pcm: ByteArray): Float }

class DuplexEchoSession(val enabled: Boolean, private val factory: () -> EchoBackend = { Aec3Native() },
    private val speechFactory: (() -> PcmSpeechDetector)? = null) : AutoCloseable {
    @Volatile private var processor: FramedDuplexProcessor?=null
    private var closed=false
    private var speech: PcmSpeechDetector?=null
    @Volatile var speechProbability=0f
        private set
    val hasSpeechDetector: Boolean get() = speechFactory != null
    private val playbackEpoch=java.util.concurrent.atomic.AtomicLong(0L)
    @Synchronized fun initialize(): Boolean {
        if(closed) return false
        if(enabled && processor == null) processor=FramedDuplexProcessor(factory())
        try { if(speech == null) speech=speechFactory?.invoke() }
        catch(error: Throwable) { processor?.close();processor=null;throw error }
        return true
    }
    @Synchronized fun capture(pcm: ByteArray): ByteArray {
        if(closed) return byteArrayOf()
        val cleaned=if(enabled) processor?.capture(pcm) ?: byteArrayOf() else pcm
        if(cleaned.isNotEmpty()) speechProbability=speech?.accept(cleaned)?.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0f
        return cleaned
    }
    @Synchronized fun render(pcm: ByteArray, offset: Int, count: Int, delayMs: Int, epoch: Long) {
        if(epoch == playbackEpoch.get()) processor?.renderAccepted(pcm,offset,count,delayMs)
    }
    fun reset(epoch: Long) { playbackEpoch.set(epoch);processor?.requestReset() }
    @Synchronized override fun close() { if(!closed) {
        closed=true;processor?.close();processor=null;speech?.close();speech=null;speechProbability=0f
    } }
}
