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
import java.util.concurrent.atomic.AtomicLong

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
    private var microphoneLease: MicrophoneOwnership.Lease? = null
    private val isRecording = AtomicBoolean(false)
    private val lifecycleGeneration = AtomicLong(0L)
    val isMuted = AtomicBoolean(false)

    /** True while this recorder owns the microphone capture session. */
    val isActive: Boolean
        get() = isRecording.get()

    private fun releaseAudioEffects(reason: String) {
        val aec = echoCanceler.also { echoCanceler = null }
        val ns = noiseSuppressor.also { noiseSuppressor = null }
        val agc = automaticGainControl.also { automaticGainControl = null }
        runCatching { aec?.release() }.onFailure { Log.w(TAG, "$reason: AEC release failed", it) }
        runCatching { ns?.release() }.onFailure { Log.w(TAG, "$reason: NoiseSuppressor release failed", it) }
        runCatching { agc?.release() }.onFailure { Log.w(TAG, "$reason: AGC release failed", it) }
    }
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(scope: CoroutineScope): Boolean {
        if (isRecording.get()) return true

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Unable to get valid min buffer size")
            return false
        }

        val bufferSize = maxOf(minBufferSize * 4, 32000)
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Log.e(TAG, "AudioRecord initialization must run off the main thread; refusing to block UI.")
            return false
        }

        val lease = MicrophoneOwnership.acquire(MicrophoneOwner.VOICE_SESSION) ?: return false
        microphoneLease = lease
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
                // Initialization is guarded above to run only off the main thread.
                // Back off briefly between hardware initialization attempts.
                Log.w(TAG, "AudioRecord initialization attempt $initAttempts failed; retrying in 150ms")
                try {
                    Thread.sleep(150)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed after retries")
                microphoneLease = null
                lease.close()
                return false
            }

            val sessionId = record.audioSessionId
            if (AcousticEchoCanceler.isAvailable() && !com.jarvis.assistant.ui.aura.AuraOptions.enabled("disableNoiseSuppression")) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                        enabled = true
                    }
                    Log.i(TAG, "Hardware AcousticEchoCanceler enabled on session $sessionId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enable AcousticEchoCanceler: ${e.message}")
                }
            }

            if (NoiseSuppressor.isAvailable() && !com.jarvis.assistant.ui.aura.AuraOptions.enabled("disableNoiseSuppression")) {
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
            val generation = lifecycleGeneration.incrementAndGet()

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
                    if (isActive && isRecording.get() && lifecycleGeneration.get() == generation) {
                        delay(400)
                        // stop() may be requested while recovery is waiting. Re-check
                        // after the suspension so a stopped session does not recreate
                        // microphone hardware in the background.
                        if (!isActive || !isRecording.get() || lifecycleGeneration.get() != generation) break
                        releaseAudioEffects("Audio recovery")

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
                            if (AcousticEchoCanceler.isAvailable() && !com.jarvis.assistant.ui.aura.AuraOptions.enabled("disableNoiseSuppression")) {
                                try {
                                    echoCanceler = AcousticEchoCanceler.create(newSessionId)?.apply { enabled = true }
                                } catch (e: Exception) {
                                    Log.w(TAG, "AEC rebind failed: ${e.message}")
                                }
                            }
                            if (NoiseSuppressor.isAvailable() && !com.jarvis.assistant.ui.aura.AuraOptions.enabled("disableNoiseSuppression")) {
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
                            val published = synchronized(this@AudioRecorder) {
                                if (isRecording.get() && lifecycleGeneration.get() == generation) {
                                    currentRecord = candidate
                                    audioRecord = candidate
                                    replacementRecord = null // Ownership transferred to the active recording loop.
                                    true
                                } else {
                                    false
                                }
                            }
                            if (!published) {
                                runCatching { candidate.stop() }
                                runCatching { candidate.release() }
                                releaseAudioEffects("Unpublished replacement recorder")
                                currentRecord = null
                                break
                            }
                            Log.i(TAG, "AudioRecord recovered with effects rebound to session $newSessionId")
                        } catch (reInitEx: Exception) {
                            try { replacementRecord?.release() } catch (_: Exception) {}
                            releaseAudioEffects("Failed audio recovery")
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
            releaseAudioEffects("Audio start failure")
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
            microphoneLease?.close()
            microphoneLease = null
            false
        }
    }

    @Synchronized
    fun stop() {
        lifecycleGeneration.incrementAndGet()
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null

        releaseAudioEffects("Audio stop")

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
        microphoneLease?.close()
        microphoneLease = null
    }

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
    }
}
