package com.jarvis.assistant.audio

data class AecMetrics(
    val echoReturnLossDb: Double = 0.0,
    val echoReturnLossEnhancementDb: Double = 0.0,
    val delayMs: Int = 0,
    val activeProcessing: Boolean = false,
    val renderFrames: Long = 0L,
    val captureFrames: Long = 0L,
    val saturatedCaptureFrames: Long = 0L
)

interface EchoBackend : AutoCloseable {
    fun render24k(samples: ShortArray, delayMs: Int)
    fun capture16k(input: ShortArray, output: ShortArray)
    fun reset()
    fun metrics(): AecMetrics? = null
}

/** PCM16 framing only. Preserves split samples and feeds accepted speaker writes, never queued packets. */
class FramedDuplexProcessor(private val backend: EchoBackend) : AutoCloseable {
    private val render = ShortArray(240)
    private val capture = ShortArray(160)
    private val cleaned = ShortArray(160)
    private var renderCount=0
    private var captureCount=0
    private var renderByte=-1
    private var captureByte=-1
    private var closed=false
    private val resetPending=java.util.concurrent.atomic.AtomicBoolean(false)
    private fun applyReset() { if(resetPending.getAndSet(false)) {
        renderCount=0;captureCount=0;renderByte=-1;captureByte=-1;backend.reset()
    } }
    @Synchronized fun renderAccepted(pcm: ByteArray, offset: Int, length: Int, delayMs: Int) {
        if(closed) return
        require(offset >= 0 && length >= 0 && offset <= pcm.size-length)
        applyReset()
        for(i in offset until offset+length) {
            if(renderByte < 0) renderByte=pcm[i].toInt() and 255
            else {
                render[renderCount++]=(renderByte or (pcm[i].toInt() shl 8)).toShort();renderByte=-1
                if(renderCount==240) { backend.render24k(render,delayMs.coerceIn(0,500));renderCount=0 }
            }
        }
    }
    @Synchronized fun capture(pcm: ByteArray): ByteArray {
        if(closed) return byteArrayOf()
        require(pcm.size <= 4096)
        applyReset()
        val samples=(pcm.size + if(captureByte >= 0) 1 else 0)/2
        val output=ByteArray(((captureCount+samples)/160)*320)
        var written=0
        for(b in pcm) {
            if(captureByte < 0) captureByte=b.toInt() and 255
            else {
                capture[captureCount++]=(captureByte or (b.toInt() shl 8)).toShort();captureByte=-1
                if(captureCount==160) {
                    backend.capture16k(capture,cleaned)
                    for(v in cleaned) { output[written++]=v.toByte();output[written++]=(v.toInt() shr 8).toByte() }
                    captureCount=0
                }
            }
        }
        check(written == output.size)
        return output
    }
    @Synchronized fun metrics(): AecMetrics? = if (closed) null else backend.metrics()
    /** Invalidate immediately, rebuild native state on the next audio worker call, not the UI thread. */
    fun requestReset() { resetPending.set(true) }
    @Synchronized override fun close() { if(!closed) { closed=true;backend.close() } }
}
