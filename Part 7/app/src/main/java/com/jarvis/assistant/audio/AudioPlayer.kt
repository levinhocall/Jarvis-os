package com.jarvis.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlayer(
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit,
    private val onPlaybackAmplitude: (amplitude: Float) -> Unit
) {
    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private val trackLock = Any()
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val isRunning = AtomicBoolean(false)
    private val isPlayingState = AtomicBoolean(false)
    private var playbackThread: Thread? = null

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        // 96,000 bytes = 2 full seconds of 24kHz 16-bit mono PCM headroom.
        // Guarantees zero buffer underruns across network jitter and prevents deadlock.
        val bufferSize = maxOf(minBufferSize * 8, 96000)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
        isTurnComplete.set(true)
    }

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        playbackThread = Thread {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {}
            var silenceCounter = 0
            while (isRunning.get()) {
                try {
                    // If not currently playing, wait for chunks and start AudioTrack before writing
                    if (!isPlayingState.get()) {
                        val firstChunk = audioQueue.poll(30, TimeUnit.MILLISECONDS)
                        if (firstChunk != null && firstChunk.isNotEmpty()) {
                            isTurnComplete.set(false)
                            isPlayingState.set(true)
                            silenceCounter = 0
                            onPlaybackStateChanged(true)

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

                            writeToTrack(firstChunk)
                            val amplitude = AudioUtils.calculateRmsNormalized(firstChunk, firstChunk.size)
                            onPlaybackAmplitude(amplitude)
                        }
                        continue
                    }

                    // Currently playing: stream incoming chunks to the hardware AudioTrack
                    val chunk = audioQueue.poll(20, TimeUnit.MILLISECONDS)
                    if (chunk != null && chunk.isNotEmpty()) {
                        silenceCounter = 0
                        val amplitude = AudioUtils.calculateRmsNormalized(chunk, chunk.size)
                        onPlaybackAmplitude(amplitude)

                        writeToTrack(chunk)
                    } else {
                        silenceCounter++
                        // Clean turn completion:
                        // 1. If Gemini signaled turnComplete: wait 25 cycles (~500ms) of empty queue
                        //    so the hardware AudioTrack buffer has rendered out to the physical speaker completely.
                        // 2. If Gemini is still generating (turnComplete not yet received): wait 150 cycles (~3000ms)
                        //    to tolerate normal chunk gaps and mobile network jitter without prematurely cutting off speech.
                        val shouldFinish = (isTurnComplete.get() && silenceCounter >= 25) || (silenceCounter >= 150)

                        if (shouldFinish && isPlayingState.get()) {
                            isPlayingState.set(false)
                            onPlaybackStateChanged(false)
                            onPlaybackAmplitude(0f)
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in playback worker: ${e.message}")
                }
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    val activeEpoch = java.util.concurrent.atomic.AtomicLong(0L)

    fun enqueueAudio(pcmData: ByteArray, epoch: Long? = null) {
        if (epoch != null && epoch != activeEpoch.get()) {
            // Drop late streaming audio chunks from an interrupted turn
            return
        }
        if (pcmData.size >= 16) {
            val validLen = pcmData.size and 1.inv()
            val aligned = if (validLen == pcmData.size) pcmData else pcmData.copyOf(validLen)
            audioQueue.offer(aligned)
        }
    }

    /**
     * Instantly flushes audio playback upon interruption and advances generation epoch.
     */
    fun flush(newEpoch: Long = activeEpoch.incrementAndGet()) {
        activeEpoch.set(newEpoch)
        audioQueue.clear()
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
            onPlaybackStateChanged(false)
            onPlaybackAmplitude(0f)
        }
    }

    fun isPlaying(): Boolean = isPlayingState.get() || !audioQueue.isEmpty()

    fun release() {
        isRunning.set(false)
        playbackThread?.interrupt()
        playbackThread = null
        audioQueue.clear()

        synchronized(trackLock) {
            try {
                audioTrack?.let {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        it.stop()
                    }
                    it.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioTrack: ${e.message}")
            } finally {
                audioTrack = null
            }
        }
    }

    private fun writeToTrack(chunk: ByteArray) {
        var track: AudioTrack?
        synchronized(trackLock) {
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
                // AudioTrack.WRITE_BLOCKING guarantees zero dropped PCM chunks on the dedicated audio thread
                val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    t.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                } else {
                    t.write(chunk, 0, chunk.size)
                }
                if (written < 0) {
                    Log.w(TAG, "AudioTrack write failed ($written). Reinitializing AudioTrack...")
                    synchronized(trackLock) {
                        try { t.release() } catch (_: Exception) {}
                        initAudioTrack()
                    }
                }
            }
        }
    }
}
