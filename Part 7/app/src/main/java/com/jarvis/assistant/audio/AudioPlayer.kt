package com.jarvis.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlayer(
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit,
    private val onPlaybackAmplitude: (amplitude: Float) -> Unit,
    private val onPlaybackFailure: (category: String) -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
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

        audioTrack = track
    }

    val audioSessionId: Int
        get() = audioTrack?.audioSessionId ?: 0

    fun pause() {
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
                            val amplitude = AudioUtils.calculateRmsNormalized(firstChunk.bytes, firstChunk.bytes.size)
                            notifyAmplitude(amplitude)
                        }
                        continue
                    }

                    // Currently playing: stream incoming chunks to the hardware AudioTrack
                    val chunk = audioQueue.poll(20, TimeUnit.MILLISECONDS)
                    if (chunk != null && audioQueue.isCurrent(chunk) && chunk.bytes.isNotEmpty()) {
                        silenceCounter = 0
                        val amplitude = AudioUtils.calculateRmsNormalized(chunk.bytes, chunk.bytes.size)
                        notifyAmplitude(amplitude)

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
                            VoiceFlightRecorder.recordPlaybackComplete()
                            notifyPlaybackState(false)
                            notifyAmplitude(0f)
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
        if (released.get() || droppingTurn.get() || pcmData.size < 16) return
        when (audioQueue.offer(pcmData, epoch ?: activeEpoch.get())) {
            PcmPlaybackQueue.Offer.OVERFLOW -> {
                // Stop the whole turn visibly instead of silently dropping arbitrary speech chunks.
                flush()
                droppingTurn.set(true)
                runCatching { onPlaybackFailure("PLAYBACK_BUFFER_OVERFLOW") }
                    .onFailure { Log.w(TAG, "Playback failure callback failed (${it.javaClass.simpleName})") }
            }
            else -> Unit // Old-epoch/released audio is intentionally rejected.
        }
    }

    /**
     * Instantly flushes audio playback upon interruption and advances generation epoch.
     */
    @Synchronized
    fun flush(newEpoch: Long = activeEpoch.incrementAndGet()) {
        val nextEpoch = activeEpoch.updateAndGet { maxOf(it, newEpoch) }
        droppingTurn.set(false)
        audioQueue.advance(nextEpoch)
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
        if (!released.compareAndSet(false, true)) return
        isRunning.set(false)
        playbackThread?.interrupt()
        playbackThread = null
        audioQueue.close()

        synchronized(trackLock) {
            try {
                audioTrack?.let {
                    try {
                        if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop()
                    } finally { it.release() }
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
        notifyPlaybackState(true)
        return true
    }

    private fun writeToTrack(frame: PcmPlaybackQueue.Frame) {
        if (!audioQueue.isCurrent(frame)) return
        val chunk = frame.bytes
        var track: AudioTrack?
        synchronized(trackLock) {
            if (released.get() || !isRunning.get() || !audioQueue.isCurrent(frame)) return
            track = audioTrack
            if (track != null && track?.state != AudioTrack.STATE_INITIALIZED) {
                try { track?.release() } catch (_: Exception) {}
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

        track?.let { t ->
            if (t.state == AudioTrack.STATE_INITIALIZED) {
                val written = PcmWritePump.write(chunk, {
                    isRunning.get() && !released.get() && audioQueue.isCurrent(frame)
                }) { offset, length ->
                    t.write(chunk, offset, length, AudioTrack.WRITE_BLOCKING)
                }
                if (written > 0 && audioQueue.isCurrent(frame)) hardwareFailures.set(0)
                if (written < 0 && audioQueue.isCurrent(frame)) {
                    if (failHardware()) return
                    Log.w(TAG, "AudioTrack write failed ($written). Reinitializing AudioTrack...")
                    VoiceFlightRecorder.recordUnderrun()
                    synchronized(trackLock) {
                        if (released.get() || !isRunning.get() || !audioQueue.isCurrent(frame)) return
                        try { t.release() } catch (_: Exception) {}
                        if (audioTrack === t) audioTrack = null
                        initAudioTrack()
                    }
                }
            }
        }
    }
}
