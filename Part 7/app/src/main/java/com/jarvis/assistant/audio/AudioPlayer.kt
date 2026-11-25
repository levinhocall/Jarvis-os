package com.jarvis.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioTimestamp
import com.jarvis.assistant.data.model.AudioTimingConfidence
import com.jarvis.assistant.data.model.PlaybackSource
import android.os.Build
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlayer(
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit,
    private val onPlaybackAmplitude: (amplitude: Float) -> Unit,
    private val onPlaybackFailure: (category: String) -> Unit = {},
    private val onPlaybackObservation: (PlaybackObservation) -> Unit = {},
    private val onAcceptedRender: (ByteArray, Int, Int, Int, Long) -> Unit = { _,_,_,_,_ -> },
    private val onRenderReset: (Long) -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    data class PlaybackObservation(
        val epoch: Long,
        val revision: Long,
        val playing: Boolean,
        val level: Float,
        val timing: AudioTimingConfidence,
        val source: PlaybackSource = PlaybackSource.GEMINI_PCM
    )
    private val meterLock = Any()
    private val timeline = PcmEnvelopeTimeline()
    private val playbackClock = PlaybackFrameClock()
    private val timestamp = AudioTimestamp()
    private val meterRevision = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var activePlaybackSource: PlaybackSource = PlaybackSource.NONE
    private val completionLock = Any()
    private val turnCompletionCallbacks = mutableMapOf<Long, MutableList<() -> Unit>>()
    private var meterExecutor: java.util.concurrent.ScheduledExecutorService? = null
    private var meterTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var routeSettlesAt = 0L
    private var lastHead = -1L
    private var lastHeadChangedAt = 0L
    private val routeListener = android.media.AudioRouting.OnRoutingChangedListener {
        synchronized(meterLock) {
            meterRevision.incrementAndGet(); timeline.discardEnvelopes()
            routeSettlesAt = android.os.SystemClock.elapsedRealtime() + 300
            lastHead = -1
        }
        publishQuiet()
    }
    fun isObservationCurrent(observation: PlaybackObservation): Boolean =
        (!released.get() || !observation.playing) && observation.epoch == activeEpoch.get() && observation.revision == meterRevision.get()

    @Synchronized private fun startMeter() {
        if (released.get() || meterTask?.isDone == false) return
        val executor = meterExecutor ?: java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "JarvisPlaybackEnvelope").apply { isDaemon = true }
        }.also { meterExecutor = it }
        meterTask = executor.scheduleWithFixedDelay({ observePlayback() }, 0, 20, TimeUnit.MILLISECONDS)
    }
    @Synchronized private fun stopMeter() { meterTask?.cancel(false); meterTask = null; publishQuiet() }
    private fun publishQuiet() = publishObservation(
        PlaybackObservation(activeEpoch.get(), meterRevision.get(), false, 0f, AudioTimingConfidence.UNAVAILABLE, PlaybackSource.NONE)
    )
    private fun publishObservation(observation: PlaybackObservation) {
        if (!isObservationCurrent(observation)) return
        runCatching { onPlaybackObservation(observation); notifyAmplitude(observation.level) }
    }
    private fun resetMeter() = synchronized(meterLock) {
        meterRevision.incrementAndGet(); timeline.reset(activeEpoch.get()); playbackClock.reset(); lastHead = -1
        activePlaybackSource = PlaybackSource.NONE
        onRenderReset(activeEpoch.get())
    }
    private fun observePlayback() {
        if (released.get()) return
        val observation = runCatching {
            synchronized(trackLock) {
                val track = audioTrack ?: return
                synchronized(meterLock) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    var timing = AudioTimingConfidence.PLAYBACK_HEAD
                    val rawHead = playbackClock.head(track.playbackHeadPosition)
                    val hardware = if (now >= routeSettlesAt && track.getTimestamp(timestamp))
                        playbackClock.timestamp(timestamp.framePosition, timestamp.nanoTime, System.nanoTime(), timeline.writtenFrames, SAMPLE_RATE) else null
                    val head = hardware ?: rawHead
                    if (hardware != null) timing = AudioTimingConfidence.HARDWARE_TIMESTAMP
                    if (head != lastHead) { lastHeadChangedAt = now; lastHead = head }
                    val level = if (track.playState == AudioTrack.PLAYSTATE_PLAYING && now >= routeSettlesAt && now - lastHeadChangedAt < 150)
                        timeline.levelAt(head, activeEpoch.get()) else null
                    val observedSource = if (level != null) activePlaybackSource else PlaybackSource.NONE
                    PlaybackObservation(activeEpoch.get(), meterRevision.get(), level != null, level ?: 0f,
                        if (level == null) AudioTimingConfidence.UNAVAILABLE else timing, observedSource)
                }
            }
        }.getOrNull() ?: run { publishQuiet(); return }
        publishObservation(observation)
        if (!observation.playing && !isPlayingState.get()) stopMeter()
    }

    private var audioTrack: AudioTrack? = null
    private val trackLock = Any()
    private val audioQueue = PcmPlaybackQueue()
    private val released = AtomicBoolean(false)
    private val droppingTurn = AtomicBoolean(false)
    private val hardwareFailures = java.util.concurrent.atomic.AtomicInteger(0)
    private val isRunning = AtomicBoolean(false)
    private val isPlayingState = AtomicBoolean(false)
    private var playbackThread: Thread? = null

    private fun initAudioTrack() {
        if (released.get()) return
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        // 19,200 bytes = 400ms of 24kHz 16-bit mono PCM headroom.
        // Provides instant playback start while comfortably absorbing thread and network jitter.
        val bufferSize = maxOf(minBufferSize * 4, 19200)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        // Standard performance mode prevents MediaTek/Qualcomm FastMixer DMA ring buffer underrun loops ("aaaaaaaa")
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        resetMeter()
        runCatching { track.addOnRoutingChangedListener(routeListener, android.os.Handler(android.os.Looper.getMainLooper())) }
        audioTrack = track
    }

    private fun releaseTrack(track: AudioTrack) {
        try { track.removeOnRoutingChangedListener(routeListener) }
        finally { track.release() }
    }

    val audioSessionId: Int
        get() = audioTrack?.audioSessionId ?: 0

    fun pause() {
        synchronized(meterLock) { meterRevision.incrementAndGet() }
        onRenderReset(activeEpoch.get())
        stopMeter()
        synchronized(trackLock) {
            try {
                audioTrack?.let {
                    if (it.state == AudioTrack.STATE_INITIALIZED && it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        it.pause()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing AudioTrack: ${e.message}")
            }
        }
    }

    private val isTurnComplete = AtomicBoolean(false)

    fun notifyTurnComplete() {
        droppingTurn.set(false)
        isTurnComplete.set(true)
    }

    @Synchronized
    fun start() {
        if (released.get() || !isRunning.compareAndSet(false, true)) return

        playbackThread = Thread {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {}
            var silenceCounter = 0
            while (isRunning.get()) {
                try {
                    // If not currently playing, wait for chunks and start AudioTrack before writing
                    if (!isPlayingState.get()) {
                        val firstChunk = audioQueue.poll(1, TimeUnit.SECONDS)
                        if (firstChunk != null && audioQueue.isCurrent(firstChunk) && firstChunk.bytes.isNotEmpty()) {
                            if (!beginPlayback(firstChunk)) continue
                            silenceCounter = 0

                            // Start hardware track immediately so write() streams smoothly without blocking
                            synchronized(trackLock) {
                                audioTrack?.let { track ->
                                    if (track.state == AudioTrack.STATE_INITIALIZED && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                        try {
                                            track.play()
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error calling play(): ${e.message}")
                                        }
                                    }
                                }
                            }

                            VoiceFlightRecorder.recordFirstAudioFrame()
                            writeToTrack(firstChunk)

                        }
                        continue
                    }

                    // Currently playing: stream incoming chunks to the hardware AudioTrack
                    val chunk = audioQueue.poll(20, TimeUnit.MILLISECONDS)
                    if (chunk != null && audioQueue.isCurrent(chunk) && chunk.bytes.isNotEmpty()) {
                        silenceCounter = 0

                        writeToTrack(chunk)
                    } else {
                        silenceCounter++
                        // Clean turn completion:
                        // 1. If Gemini signaled turnComplete: wait 15 cycles (~300ms) of empty queue
                        //    so the hardware AudioTrack buffer has rendered out to the physical speaker completely.
                        // 2. If Gemini is still generating (turnComplete not yet received): wait 50 cycles (~1000ms)
                        //    to tolerate normal chunk gaps without stalling the mic for seconds.
                        val shouldFinish = (isTurnComplete.get() && silenceCounter >= 15) || (silenceCounter >= 50)

                        if (shouldFinish && isPlayingState.get()) {
                            isPlayingState.set(false)
                            synchronized(meterLock) { activePlaybackSource = PlaybackSource.NONE }
                            VoiceFlightRecorder.recordPlaybackComplete()
                            // The presentation clock drains remaining hardware frames independently.
                            notifyPlaybackState(false)
                            notifyAmplitude(0f)
                            dispatchTurnCompletionCallbacks(activeEpoch.get())
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Playback worker failed (${e.javaClass.simpleName})")
                    failHardware()
                }
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    val activeEpoch = java.util.concurrent.atomic.AtomicLong(0L)

    @Synchronized
    fun enqueueAudio(pcmData: ByteArray, epoch: Long? = null) {
        enqueuePcm(pcmData, SAMPLE_RATE, 1, epoch, PlaybackSource.GEMINI_PCM, null)
    }

    /**
     * Unified PCM playback ingestion path for Gemini Live, Android TTS WAV decode, and local
     * neural TTS engines. Resamples non-24 kHz or multi-channel input to 24 kHz mono PCM16,
     * chunks long utterances for <10 ms barge-in latency, and feeds the synchronized AEC/DTLN tap.
     */
    @Synchronized
    fun enqueuePcm(
        pcmData: ByteArray,
        sampleRateHz: Int = SAMPLE_RATE,
        channels: Int = 1,
        epoch: Long? = null,
        source: PlaybackSource = PlaybackSource.GEMINI_PCM,
        onDrained: (() -> Unit)? = null,
        markTurnCompleteAfterEnqueue: Boolean = (source != PlaybackSource.GEMINI_PCM)
    ): Boolean {
        if (released.get() || droppingTurn.get() || pcmData.size < 16) return false
        val normalized = if (sampleRateHz == SAMPLE_RATE && channels == 1) {
            pcmData
        } else {
            PcmAudioConverter.to24kMonoPcm16(pcmData, sampleRateHz, channels)
        }
        if (normalized.size < 16) return false

        val targetEpoch = epoch ?: activeEpoch.get()
        val maxSliceBytes = 9600 // 200ms slices at 24 kHz mono PCM16
        val neededFrames = (normalized.size + maxSliceBytes - 1) / maxSliceBytes
        // Avoid partial queueing when an oversized non-Gemini utterance exceeds queue headroom.
        if (source != PlaybackSource.GEMINI_PCM &&
            (normalized.size > (2 * 1024 * 1024 - audioQueue.queuedBytes) || neededFrames + audioQueue.size > 512)
        ) {
            return false
        }
        var offset = 0
        var acceptedAny = false
        while (offset < normalized.size) {
            val end = minOf(offset + maxSliceBytes, normalized.size)
            val slice = if (offset == 0 && end == normalized.size) normalized else normalized.copyOfRange(offset, end)
            when (audioQueue.offer(slice, targetEpoch, source)) {
                PcmPlaybackQueue.Offer.ACCEPTED -> acceptedAny = true
                PcmPlaybackQueue.Offer.OVERFLOW -> {
                    // Stop the whole turn visibly instead of silently dropping arbitrary speech chunks.
                    flush()
                    droppingTurn.set(true)
                    runCatching { onPlaybackFailure("PLAYBACK_BUFFER_OVERFLOW") }
                        .onFailure { Log.w(TAG, "Playback failure callback failed (${it.javaClass.simpleName})") }
                    return false
                }
                else -> return false
            }
            offset = end
        }
        if (acceptedAny) {
            if (markTurnCompleteAfterEnqueue) isTurnComplete.set(true)
            if (onDrained != null) {
                synchronized(completionLock) {
                    turnCompletionCallbacks.getOrPut(targetEpoch) { mutableListOf() }.add(onDrained)
                }
            }
        }
        return acceptedAny
    }

    private fun dispatchTurnCompletionCallbacks(epoch: Long) {
        val callbacks = synchronized(completionLock) {
            turnCompletionCallbacks.remove(epoch)?.toList().orEmpty()
        }
        callbacks.forEach { cb ->
            runCatching { cb() }.onFailure { Log.w(TAG, "Turn drained callback failed: ${it.message}") }
        }
    }

    /**
     * Instantly flushes audio playback upon interruption and advances generation epoch.
     */
    @Synchronized
    fun flush(newEpoch: Long = activeEpoch.incrementAndGet()) {
        val nextEpoch = activeEpoch.updateAndGet { maxOf(it, newEpoch) }
        droppingTurn.set(false)
        synchronized(completionLock) { turnCompletionCallbacks.clear() }
        audioQueue.advance(nextEpoch)
        resetMeter()
        stopMeter()
        isTurnComplete.set(true)
        synchronized(trackLock) {
            try {
                audioTrack?.let {
                    if (it.state == AudioTrack.STATE_INITIALIZED) {
                        try { it.pause() } catch (_: Exception) {}
                        try { it.flush() } catch (_: Exception) {}
                        try { it.stop() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error flushing AudioTrack: ${e.message}")
            }
        }
        if (isPlayingState.getAndSet(false)) {
            notifyPlaybackState(false)
            notifyAmplitude(0f)
        }
    }

    fun isPlaying(): Boolean = isPlayingState.get() || !audioQueue.isEmpty()

    @Synchronized
    fun release() {
        released.compareAndSet(false, true).also { if (!it) return }
        isRunning.set(false)
        synchronized(completionLock) { turnCompletionCallbacks.clear() }
        playbackThread?.interrupt()
        playbackThread = null
        audioQueue.close()
        resetMeter()
        stopMeter()
        meterExecutor?.shutdownNow(); meterExecutor = null

        synchronized(trackLock) {
            try {
                audioTrack?.let {
                    try {
                        if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop()
                    } finally { releaseTrack(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioTrack: ${e.message}")
            } finally {
                audioTrack = null
            }
        }
        if (isPlayingState.getAndSet(false)) {
            runCatching { notifyPlaybackState(false); notifyAmplitude(0f) }
                .onFailure { Log.w(TAG, "Playback stop callback failed (${it.javaClass.simpleName})") }
        }
    }

    private fun notifyPlaybackState(playing: Boolean) {
        try { onPlaybackStateChanged(playing) }
        catch (error: Exception) { Log.w(TAG, "Playback state callback failed (${error.javaClass.simpleName})") }
    }

    private fun notifyAmplitude(amplitude: Float) {
        try { onPlaybackAmplitude(amplitude) }
        catch (error: Exception) { Log.w(TAG, "Playback amplitude callback failed (${error.javaClass.simpleName})") }
    }

    private fun failHardware(): Boolean {
        if (released.get()) return true
        if (hardwareFailures.incrementAndGet() < 3) return false
        release()
        runCatching { onPlaybackFailure("PLAYBACK_HARDWARE_UNAVAILABLE") }
            .onFailure { Log.w(TAG, "Playback failure callback failed (${it.javaClass.simpleName})") }
        return true
    }

    @Synchronized private fun beginPlayback(frame: PcmPlaybackQueue.Frame): Boolean {
        if (released.get() || !isRunning.get() || !audioQueue.isCurrent(frame)) return false
        isTurnComplete.set(false)
        isPlayingState.set(true)
        synchronized(meterLock) { activePlaybackSource = frame.source }
        notifyPlaybackState(true)
        return true
    }

    private fun writeToTrack(frame: PcmPlaybackQueue.Frame) {
        if (!audioQueue.isCurrent(frame)) return
        synchronized(meterLock) { activePlaybackSource = frame.source }
        val chunk = frame.bytes
        var track: AudioTrack?
        synchronized(trackLock) {
            if (released.get() || !isRunning.get() || !audioQueue.isCurrent(frame)) return
            track = audioTrack
            if (track != null && track?.state != AudioTrack.STATE_INITIALIZED) {
                try { track?.let(::releaseTrack) } catch (_: Exception) {}
                audioTrack = null
                track = null
            }
            if (track == null) {
                initAudioTrack()
                track = audioTrack
            }
            track?.let { t ->
                if (t.state == AudioTrack.STATE_INITIALIZED && t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    try { t.play() } catch (_: Exception) {}
                }
            }
        }

        startMeter()
        track?.let { t ->
            if (t.state == AudioTrack.STATE_INITIALIZED) {
                val written = PcmWritePump.write(chunk, {
                    isRunning.get() && !released.get() && audioQueue.isCurrent(frame)
                }) { offset, length ->
                    // 10 ms writes expose accepted frame positions without waiting for a whole network packet.
                    val count = t.write(chunk, offset, minOf(length, 480), AudioTrack.WRITE_BLOCKING)
                    var referenceDelayMs=0
                    var referenceAccepted=false
                    if (count > 0) synchronized(meterLock) {
                        if (audioTrack === t && audioQueue.isCurrent(frame)) {
                            timeline.accepted(chunk, offset, count, frame.epoch)
                            referenceDelayMs=((timeline.writtenFrames-playbackClock.head(t.playbackHeadPosition))
                                .coerceIn(0,12000)*1000/SAMPLE_RATE).toInt()
                            referenceAccepted=true
                        }
                    }
                    if(referenceAccepted && audioQueue.isCurrent(frame))
                        onAcceptedRender(chunk,offset,count,referenceDelayMs,frame.epoch)
                    count
                }
                if (written > 0 && audioQueue.isCurrent(frame)) hardwareFailures.set(0)
                if (written < 0 && audioQueue.isCurrent(frame)) {
                    if (failHardware()) return
                    Log.w(TAG, "AudioTrack write failed ($written). Reinitializing AudioTrack...")
                    VoiceFlightRecorder.recordUnderrun()
                    synchronized(trackLock) {
                        if (released.get() || !isRunning.get() || !audioQueue.isCurrent(frame)) return
                        try { releaseTrack(t) } catch (_: Exception) {}
                        if (audioTrack === t) audioTrack = null
                        initAudioTrack()
                    }
                }
            }
        }
    }
}
