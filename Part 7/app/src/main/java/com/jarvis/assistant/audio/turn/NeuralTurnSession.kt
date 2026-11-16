package com.jarvis.assistant.audio.turn

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One bounded analysis request per observed pause. It cannot capture audio or send a Live turn. */
class NeuralTurnSession(private val scope: CoroutineScope, private val factory: () -> TurnEndModel) : AutoCloseable {
    private val lock=Any()
    private val inference=Mutex()
    private var model: TurnEndModel?=null
    private var job: Job?=null
    private var generation=0L
    private var requested=-1L
    private var result: Pair<Long,Float>?=null
    private var closed=false
    private val ring=ByteArray(256000)
    private var writeAt=0
    private var audioSize=0
    fun accept(pcm: ByteArray) = synchronized(lock) {
        require(pcm.size <= 4096 && pcm.size%2 == 0)
        if(!closed) append(pcm)
    }
    private fun append(pcm: ByteArray) {
        val first=minOf(pcm.size,ring.size-writeAt)
        System.arraycopy(pcm,0,ring,writeAt,first)
        if(first < pcm.size) System.arraycopy(pcm,first,ring,0,pcm.size-first)
        writeAt=(writeAt+pcm.size)%ring.size;audioSize=minOf(ring.size,audioSize+pcm.size)
    }
    fun beginUtterance(preroll: Iterable<ByteArray>) = synchronized(lock) {
        if(!closed) { discard();preroll.forEach(::append) }
    }
    fun discard() = synchronized(lock) {
        generation++;result=null;job?.cancel();writeAt=0;audioSize=0
    }
    private fun snapshot(): ByteArray {
        val output=ByteArray(audioSize)
        val start=(writeAt-audioSize+ring.size)%ring.size
        val first=minOf(audioSize,ring.size-start)
        System.arraycopy(ring,start,output,0,first)
        if(first<audioSize) System.arraycopy(ring,0,output,first,audioSize-first)
        return output
    }
    @Volatile var lastError: String?=null
        private set
    fun initialize() = synchronized(lock) { if(!closed && model == null) model=factory() }
    fun speech() = synchronized(lock) { generation++;result=null;job?.cancel();Unit }
    fun request() = synchronized(lock) {
        if(closed || model == null || requested == generation || audioSize < 3200) return@synchronized
        val token=generation;requested=token;val engine=model ?: return@synchronized
        job?.cancel()
        job=scope.launch(Dispatchers.Default) {
            try {
                val probability=inference.withLock {
                    ensureActive()
                    val pcm=synchronized(lock) {
                        if(closed || token != generation) throw CancellationException("Stale voice pause")
                        snapshot()
                    }
                    engine.probability(pcm)
                }
                ensureActive()
                synchronized(lock) { if(!closed && token == generation) result=token to probability }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { lastError=error.javaClass.simpleName }
        }
    }
    /** Unknown/late results retain existing VAD. Strong incomplete evidence only adds bounded patience. */
    fun silenceTimeout(base: Long): Long = synchronized(lock) {
        val probability=result?.takeIf { it.first == generation }?.second ?: return@synchronized base
        when {
            probability >= .9f -> minOf(base,180L)
            probability <= .1f -> maxOf(base,700L)
            else -> base
        }
    }
    override fun close() {
        val retiring=synchronized(lock) {
            if(closed) return
            closed=true;generation++;result=null;job?.cancel();writeAt=0;audioSize=0;ring.fill(0);model.also { model=null }
        }
        retiring?.close() // Called by existing service's background resource cleanup.
    }
}
