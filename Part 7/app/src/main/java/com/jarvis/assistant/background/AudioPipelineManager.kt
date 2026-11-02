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

        internal fun isFatalReadError(errorCode: Int): Boolean =
            errorCode == AudioRecord.ERROR ||
                errorCode == AudioRecord.ERROR_BAD_VALUE ||
                errorCode == AudioRecord.ERROR_INVALID_OPERATION ||
                errorCode == AudioRecord.ERROR_DEAD_OBJECT

        /** Returns an owned frame so consumers may safely queue it after this callback returns. */
        internal fun copyFrameForDispatch(buffer: ByteArray, bytesRead: Int): ByteArray {
            require(bytesRead in 0..buffer.size) { "Invalid audio frame length: $bytesRead" }
            return buffer.copyOf(bytesRead)
        }
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
    @Synchronized
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

            // Publish ownership before startRecording(): if the platform throws, the
            // common shutdown path can still release this recorder and its effects.
            audioRecord = record
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord did not enter RECORDSTATE_RECORDING.")
            }
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
    @Synchronized
    fun stopRecording() {
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null

        val record = audioRecord
        audioRecord = null
        // Stop the blocking read first, then cancel the loop. Each resource is
        // released independently so one platform cleanup exception cannot leak the rest.
        runCatching {
            if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        }.onFailure { Log.w(TAG, "AudioRecord stop failed during shutdown", it) }
        runCatching { echoCanceler?.release() }
            .onFailure { Log.w(TAG, "AcousticEchoCanceler release failed", it) }
        echoCanceler = null
        runCatching { noiseSuppressor?.release() }
            .onFailure { Log.w(TAG, "NoiseSuppressor release failed", it) }
        noiseSuppressor = null
        runCatching { record?.release() }
            .onFailure { Log.w(TAG, "AudioRecord release failed", it) }
        Log.i(TAG, "Audio recording stopped and hardware cleanup attempted.")
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
                    try {
                        if (isMuted.get()) {
                            // Deliver zeroed buffer to keep WebSocket pipeline timing intact.
                            buffer.fill(0)
                            onAudioChunkReady(copyFrameForDispatch(buffer, bytesRead), bytesRead)
                        } else if (isBargeInSuppressed.get()) {
                            // Assistant is actively speaking: only forward likely user speech.
                            if (calculateRms(buffer, bytesRead) > 2200.0) {
                                onAudioChunkReady(buffer, bytesRead)
                            } else {
                                buffer.fill(0)
                                onAudioChunkReady(buffer, bytesRead)
                            }
                        } else {
                            onAudioChunkReady(buffer, bytesRead)
                        }
                    } catch (callbackError: Exception) {
                        // A downstream WebSocket/voice callback must not silently kill mic capture.
                        Log.w(TAG, "Audio chunk callback failed; capture remains active.", callbackError)
                    }
                } else if (bytesRead < 0) {
                    Log.w(TAG, "AudioRecord.read failed with code $bytesRead")
                    if (isFatalReadError(bytesRead)) {
                        // Dead/invalid recorders cannot recover by polling. Tear down and
                        // expose an inactive state instead of spinning forever on a dead mic.
                        stopRecording()
                        break
                    }
                    kotlinx.coroutines.delay(40L)
                } else {
                    // Defensive backoff for unexpected zero-length reads.
                    kotlinx.coroutines.delay(10L)
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
