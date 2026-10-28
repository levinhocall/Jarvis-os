package com.jarvis.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder(
    private val onAudioChunk: (chunk: ByteArray, amplitude: Float, frequencyData: AudioFrequencyData) -> Unit
) {
    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // Ultra-low latency 20ms chunk: 16000 samples/sec * 0.02s * 2 bytes/sample = 640 bytes
        const val CHUNK_SIZE_BYTES = 640
    }

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var recordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)
    val isMuted = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope): Boolean {
        if (isRecording.get()) return true

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Unable to get valid min buffer size")
            return false
        }

        val bufferSize = maxOf(minBufferSize * 4, 32000)

        return try {
            var record: AudioRecord? = null
            var initAttempts = 0
            while (initAttempts < 5) {
                initAttempts++
                val candidate = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    record = candidate
                    break
                }
                candidate.release()
                // start() is currently invoked from a foreground Service lifecycle path,
                // which may run on the main thread. Never sleep there: five retries would
                // otherwise block the service/UI thread for up to 750ms.
                val isMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
                Log.w(TAG, "AudioRecord initialization attempt $initAttempts failed; retrying" +
                    if (isMainThread) " immediately on main thread" else " in 150ms")
                if (!isMainThread) {
                    try {
                        Thread.sleep(150)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed after retries")
                return false
            }

            val sessionId = record.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                        enabled = true
                    }
                    Log.i(TAG, "Hardware AcousticEchoCanceler enabled on session $sessionId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enable AcousticEchoCanceler: ${e.message}")
                }
            }

            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                        enabled = true
                    }
                    Log.i(TAG, "Hardware NoiseSuppressor enabled on session $sessionId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enable NoiseSuppressor: ${e.message}")
                }
            }

            if (AutomaticGainControl.isAvailable()) {
                try {
                    automaticGainControl = AutomaticGainControl.create(sessionId)?.apply {
                        enabled = true
                    }
                    Log.i(TAG, "Hardware AutomaticGainControl enabled on session $sessionId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enable AutomaticGainControl: ${e.message}")
                }
            }

            // Publish the recorder before starting it so the failure path can
            // release it if startRecording() throws.
            audioRecord = record
            record.startRecording()
            isRecording.set(true)

            recordingJob = scope.launch(Dispatchers.IO) {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                } catch (_: Exception) {}
                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                var currentRecord: AudioRecord? = record
                while (isActive && isRecording.get()) {
                    var consecutiveErrors = 0
                    while (isActive && isRecording.get() && currentRecord != null) {
                        try {
                            val readBytes = currentRecord.read(buffer, 0, buffer.size)
                            if (readBytes > 0) {
                                consecutiveErrors = 0
                                val freq = AudioUtils.analyzeFrequency(buffer, readBytes, SAMPLE_RATE)
                                // Consumer code may throw (for example, during voice-engine
                                // teardown). Keep that failure from terminating the capture job.
                                try {
                                    if (!isMuted.get()) {
                                        val chunkCopy = buffer.copyOf(readBytes)
                                        onAudioChunk(chunkCopy, freq.amplitude, freq)
                                    } else {
                                        onAudioChunk(ByteArray(0), 0f, AudioFrequencyData())
                                    }
                                } catch (callbackError: Exception) {
                                    Log.w(TAG, "Audio chunk callback failed; capture will continue: ${callbackError.message}", callbackError)
                                }
                            } else if (readBytes < 0) {
                                consecutiveErrors++
                                Log.w(TAG, "AudioRecord read error code: $readBytes (error count: $consecutiveErrors)")
                                delay(40)
                                if (consecutiveErrors > 10 && isRecording.get()) {
                                    Log.e(TAG, "Consecutive AudioRecord read errors exceeded limit. Attempting auto-recovery...")
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            consecutiveErrors++
                            Log.e(
                                TAG,
                                "Exception during AudioRecord read loop (error count: $consecutiveErrors): ${e.message}",
                                e
                            )
                            if (consecutiveErrors > 10 && isRecording.get()) {
                                Log.e(TAG, "Repeated AudioRecord read exceptions exceeded limit. Attempting auto-recovery...")
                                break
                            }
                            delay(50)
                        }
                    }

                    // Auto-recovery if broken but still meant to be recording.
                    // Audio effects belong to an AudioRecord session ID, so they must be
                    // rebound when a replacement recorder is created.
                    if (isActive && isRecording.get()) {
                        delay(400)
                        try {
                            echoCanceler?.release()
                            noiseSuppressor?.release()
                            automaticGainControl?.release()
                        } catch (effectReleaseEx: Exception) {
                            Log.w(TAG, "Could not release effects before audio recovery: ${effectReleaseEx.message}")
                        } finally {
                            echoCanceler = null
                            noiseSuppressor = null
                            automaticGainControl = null
                        }

                        try {
                            currentRecord?.release()
                        } catch (_: Exception) {}
                        currentRecord = null
                        audioRecord = null

                        var replacementRecord: AudioRecord? = null
                        try {
                            val candidate = AudioRecord(
                                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                                SAMPLE_RATE,
                                CHANNEL_CONFIG,
                                AUDIO_FORMAT,
                                bufferSize
                            )
                            replacementRecord = candidate
                            if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                                throw IllegalStateException("Replacement AudioRecord failed to initialize")
                            }

                            val newSessionId = candidate.audioSessionId
                            if (AcousticEchoCanceler.isAvailable()) {
                                try {
                                    echoCanceler = AcousticEchoCanceler.create(newSessionId)?.apply { enabled = true }
                                } catch (e: Exception) {
                                    Log.w(TAG, "AEC rebind failed: ${e.message}")
                                }
                            }
                            if (NoiseSuppressor.isAvailable()) {
                                try {
                                    noiseSuppressor = NoiseSuppressor.create(newSessionId)?.apply { enabled = true }
                                } catch (e: Exception) {
                                    Log.w(TAG, "NoiseSuppressor rebind failed: ${e.message}")
                                }
                            }
                            if (AutomaticGainControl.isAvailable()) {
                                try {
                                    automaticGainControl = AutomaticGainControl.create(newSessionId)?.apply { enabled = true }
                                } catch (e: Exception) {
                                    Log.w(TAG, "AGC rebind failed: ${e.message}")
                                }
                            }

                            candidate.startRecording()
                            currentRecord = candidate
                            audioRecord = candidate
                            replacementRecord = null // Ownership transferred to the active recording loop.
                            Log.i(TAG, "AudioRecord recovered with effects rebound to session $newSessionId")
                        } catch (reInitEx: Exception) {
                            try { replacementRecord?.release() } catch (_: Exception) {}
                            try { echoCanceler?.release() } catch (_: Exception) {}
                            try { noiseSuppressor?.release() } catch (_: Exception) {}
                            try { automaticGainControl?.release() } catch (_: Exception) {}
                            echoCanceler = null
                            noiseSuppressor = null
                            automaticGainControl = null
                            audioRecord = null
                            currentRecord = null
                            Log.e(TAG, "AudioRecord auto-recovery failed: ${reInitEx.message}", reInitEx)
                            delay(1000)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            // startRecording() can fail after AudioRecord and audio effects have
            // already been created. Release every partially initialized resource
            // so a later start() does not inherit a stale session or leak effects.
            isRecording.set(false)
            try {
                echoCanceler?.release()
            } catch (cleanup: Exception) {
                Log.w(TAG, "AEC cleanup after start failure failed: ${cleanup.message}")
            } finally {
                echoCanceler = null
            }
            try {
                noiseSuppressor?.release()
            } catch (cleanup: Exception) {
                Log.w(TAG, "NoiseSuppressor cleanup after start failure failed: ${cleanup.message}")
            } finally {
                noiseSuppressor = null
            }
            try {
                automaticGainControl?.release()
            } catch (cleanup: Exception) {
                Log.w(TAG, "AGC cleanup after start failure failed: ${cleanup.message}")
            } finally {
                automaticGainControl = null
            }
            val failedRecorder = audioRecord
            audioRecord = null
            if (failedRecorder != null) {
                try {
                    if (failedRecorder.state == AudioRecord.STATE_INITIALIZED &&
                        failedRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
                    ) {
                        failedRecorder.stop()
                    }
                } catch (cleanup: Exception) {
                    Log.w(TAG, "AudioRecord stop after start failure failed: ${cleanup.message}")
                } finally {
                    try {
                        failedRecorder.release()
                    } catch (cleanup: Exception) {
                        Log.w(TAG, "AudioRecord release after start failure failed: ${cleanup.message}")
                    }
                }
            }
            Log.e(TAG, "Error starting AudioRecord: ${e.message}", e)
            false
        }
    }

    fun stop() {
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null

        try {
            echoCanceler?.release()
            noiseSuppressor?.release()
            automaticGainControl?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio effects: ${e.message}")
        } finally {
            echoCanceler = null
            noiseSuppressor = null
            automaticGainControl = null
        }

        val recorderToRelease = audioRecord
        audioRecord = null
        if (recorderToRelease != null) {
            try {
                if (recorderToRelease.state == AudioRecord.STATE_INITIALIZED &&
                    recorderToRelease.recordingState == AudioRecord.RECORDSTATE_RECORDING
                ) {
                    recorderToRelease.stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioRecord cleanly: ${e.message}")
            } finally {
                try {
                    recorderToRelease.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
                }
            }
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
    }
}
