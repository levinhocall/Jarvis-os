package com.jarvis.assistant.background

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages low-latency AudioRecord capture, hardware echo cancellation,
 * loudspeaker barge-in coordination, and audio frame dispatch.
 */
class AudioPipelineManager(
    private val scope: CoroutineScope,
    private val sampleRateHz: Int = 16000,
    private val onAudioChunkReady: (ByteArray, Int) -> Unit
) {
    companion object {
        private const val TAG = "AudioPipelineManager"
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private var recordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)
    private val isBargeInSuppressed = AtomicBoolean(false)

    val isActive: Boolean
        get() = isRecording.get()

    /**
     * Initializes and starts continuous audio recording.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (isRecording.get()) {
            Log.d(TAG, "Audio recording already in progress.")
            return true
        }

        val minBufSize = AudioRecord.getMinBufferSize(sampleRateHz, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufSize <= 0) {
            Log.e(TAG, "Invalid minimum buffer size for AudioRecord: $minBufSize")
            return false
        }

        val bufferSize = (minBufSize * 2).coerceAtLeast(4096)

        return try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRateHz,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioRecord.")
                record.release()
                return false
            }

            // Attempt hardware Acoustic Echo Cancellation
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply {
                        enabled = true
                        Log.i(TAG, "Hardware AcousticEchoCanceler enabled on session ${record.audioSessionId}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not initialize AcousticEchoCanceler: ${e.message}")
                }
            }

            // Attempt Noise Suppressor
            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply {
                        enabled = true
                        Log.i(TAG, "NoiseSuppressor enabled on session ${record.audioSessionId}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not initialize NoiseSuppressor: ${e.message}")
                }
            }

            record.startRecording()
            audioRecord = record
            isRecording.set(true)

            launchCaptureLoop(bufferSize)
            Log.i(TAG, "AudioPipelineManager recording loop started successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioPipelineManager", e)
            stopRecording()
            false
        }
    }

    /**
     * Stops audio capture and frees hardware resources.
     */
    fun stopRecording() {
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null

        try {
            echoCanceler?.release()
            echoCanceler = null

            noiseSuppressor?.release()
            noiseSuppressor = null

            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }
            audioRecord = null
            Log.i(TAG, "Audio recording stopped and hardware freed.")
        } catch (e: Exception) {
            Log.e(TAG, "Error during audio pipeline shutdown", e)
        }
    }

    /**
     * Coordinates loudspeaker barge-in: suppresses mic transmission when assistant is speaking
     * unless loud user speech exceeds energy threshold.
     */
    fun setBargeInSuppressed(suppressed: Boolean) {
        isBargeInSuppressed.set(suppressed)
    }

    /**
     * Mutes microphone audio dispatch.
     */
    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
    }

    private fun launchCaptureLoop(bufferSize: Int) {
        recordingJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize / 2)

            while (isActive && isRecording.get()) {
                val record = audioRecord ?: break
                val bytesRead = record.read(buffer, 0, buffer.size)

                if (bytesRead > 0) {
                    if (isMuted.get()) {
                        // Deliver zeroed buffer to keep WebSocket pipeline timing intact
                        buffer.fill(0)
                        onAudioChunkReady(buffer, bytesRead)
                    } else if (isBargeInSuppressed.get()) {
                        // Assistant is actively speaking: check RMS energy
                        val rms = calculateRms(buffer, bytesRead)
                        if (rms > 2200.0) {
                            // User is actively breaking in / shouting stop!
                            onAudioChunkReady(buffer, bytesRead)
                        } else {
                            // Subdue playback leakage
                            buffer.fill(0)
                            onAudioChunkReady(buffer, bytesRead)
                        }
                    } else {
                        onAudioChunkReady(buffer, bytesRead)
                    }
                }
            }
        }
    }

    private fun calculateRms(pcmBytes: ByteArray, length: Int): Double {
        var sum = 0.0
        val shortCount = length / 2
        for (i in 0 until shortCount) {
            val sample = (pcmBytes[i * 2 + 1].toInt() shl 8) or (pcmBytes[i * 2].toInt() and 0xFF)
            sum += sample.toShort() * sample.toShort()
        }
        return if (shortCount > 0) Math.sqrt(sum / shortCount) else 0.0
    }
}
