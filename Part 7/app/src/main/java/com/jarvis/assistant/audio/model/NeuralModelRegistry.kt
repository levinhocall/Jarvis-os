package com.jarvis.assistant.audio.model

import java.io.File
import java.security.MessageDigest

enum class NeuralRuntimeKind {
    LITERT,
    ONNX_RUNTIME,
    NATIVE_CPP,
    VOSK_KALDI
}

enum class ModelLifecycleState {
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    INSTALLED,
    LOADING,
    READY,
    RUNNING,
    IDLE,
    FAILED,
    UNLOADED
}

/**
 * Complete metadata & integrity contract for bundled and dynamically provisioned neural models,
 * aligned with Part 1 §13 and 6 GB RAM resource budgeting.
 */
data class NeuralModelManifest(
    val modelId: String,
    val version: String,
    val runtime: NeuralRuntimeKind,
    val assetPath: String,
    val expectedSizeBytes: Long,
    val expectedSha256: String,
    val upstreamSource: String,
    val license: String,
    val inputShapes: List<IntArray>,
    val outputShapes: List<IntArray>,
    val dataType: String,
    val quantization: String,
    val sampleRateHz: Int,
    val hasRecurrentState: Boolean,
    val estimatedMemoryMb: Int,
    val bundledInApk: Boolean
)

data class ModelIntegrityCheckResult(
    val modelId: String,
    val assetPath: String,
    val exists: Boolean,
    val sizeMatches: Boolean,
    val sha256Matches: Boolean,
    val actualSizeBytes: Long,
    val actualSha256: String
) {
    val passed: Boolean get() = exists && sizeMatches && sha256Matches
}

/**
 * Authoritative clean-room model registry for JARVIS OS Next Generation.
 * Tracks every bundled Phase 1–3 model's cryptographic checksum, tensor shape, license, and RAM footprint.
 */
object NeuralModelRegistry {

    const val PHASE_1_ACTIVE_MEMORY_CAP_MB = 256
    const val DEVICE_6GB_SAFE_TOTAL_MODEL_MB = 512

    val bundledCatalog: List<NeuralModelManifest> = listOf(
        NeuralModelManifest(
            modelId = "dtln_aec_stage1",
            version = "breizhn-dtln-aec-128",
            runtime = NeuralRuntimeKind.LITERT,
            assetPath = "dtln/dtln_aec_128_1.tflite",
            expectedSizeBytes = 1_996_600L,
            expectedSha256 = "8d241b3a732af8ca140b2e30043e56a6c3c7800c46e22c26f4eea2f70974ad1e",
            upstreamSource = "https://github.com/breizhn/DTLN-aec",
            license = "MIT",
            inputShapes = listOf(intArrayOf(1, 1, 257), intArrayOf(1, 2, 128, 2), intArrayOf(1, 1, 257)),
            outputShapes = listOf(intArrayOf(1, 1, 257), intArrayOf(1, 2, 128, 2)),
            dataType = "FLOAT32",
            quantization = "FP32",
            sampleRateHz = 16_000,
            hasRecurrentState = true,
            estimatedMemoryMb = 10,
            bundledInApk = true
        ),
        NeuralModelManifest(
            modelId = "dtln_aec_stage2",
            version = "breizhn-dtln-aec-128",
            runtime = NeuralRuntimeKind.LITERT,
            assetPath = "dtln/dtln_aec_128_2.tflite",
            expectedSizeBytes = 5_277_012L,
            expectedSha256 = "350bb01a1152ae3cabe09fe5e868ef2f7d8b988a9f22aae44f140195f6493126",
            upstreamSource = "https://github.com/breizhn/DTLN-aec",
            license = "MIT",
            inputShapes = listOf(intArrayOf(1, 1, 512), intArrayOf(1, 2, 128, 2), intArrayOf(1, 1, 512)),
            outputShapes = listOf(intArrayOf(1, 1, 512), intArrayOf(1, 2, 128, 2)),
            dataType = "FLOAT32",
            quantization = "FP32",
            sampleRateHz = 16_000,
            hasRecurrentState = true,
            estimatedMemoryMb = 16,
            bundledInApk = true
        ),
        NeuralModelManifest(
            modelId = "silero_vad_v4",
            version = "v4.0",
            runtime = NeuralRuntimeKind.ONNX_RUNTIME,
            assetPath = "openwakeword/silero_vad.onnx",
            expectedSizeBytes = 1_807_522L,
            expectedSha256 = "a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28",
            upstreamSource = "https://github.com/snakers4/silero-vad",
            license = "MIT",
            inputShapes = listOf(intArrayOf(1, 512), intArrayOf(2, 1, 64), intArrayOf(2, 1, 64), intArrayOf()),
            outputShapes = listOf(intArrayOf(1, 1), intArrayOf(2, 1, 64), intArrayOf(2, 1, 64)),
            dataType = "FLOAT32",
            quantization = "FP32",
            sampleRateHz = 16_000,
            hasRecurrentState = true,
            estimatedMemoryMb = 18,
            bundledInApk = true
        ),
        NeuralModelManifest(
            modelId = "openwakeword_melspec",
            version = "v0.5.1",
            runtime = NeuralRuntimeKind.LITERT,
            assetPath = "openwakeword/melspectrogram.tflite",
            expectedSizeBytes = 1_092_516L,
            expectedSha256 = "96fa0adccb6e8cf95cb14465409a1a2898ee4a96a85bb9ed3c7eb0e68bf163e8",
            upstreamSource = "https://github.com/dscripka/openWakeWord",
            license = "Apache-2.0",
            inputShapes = listOf(intArrayOf(1, 1760)),
            outputShapes = listOf(intArrayOf(1, 1, 8, 32, 1)),
            dataType = "FLOAT32",
            quantization = "FP32",
            sampleRateHz = 16_000,
            hasRecurrentState = false,
            estimatedMemoryMb = 8,
            bundledInApk = true
        ),
        NeuralModelManifest(
            modelId = "openwakeword_embedding",
            version = "v0.5.1",
            runtime = NeuralRuntimeKind.LITERT,
            assetPath = "openwakeword/embedding_model.tflite",
            expectedSizeBytes = 1_330_312L,
            expectedSha256 = "c0aea21eb84a4ce90a08c870da41b7a7173b45269e6a3207c71d67c40f3a59d8",
            upstreamSource = "https://github.com/dscripka/openWakeWord",
            license = "Apache-2.0",
            inputShapes = listOf(intArrayOf(1, 76, 32, 1)),
            outputShapes = listOf(intArrayOf(1, 1, 1, 96)),
            dataType = "FLOAT32",
            quantization = "FP32",
            sampleRateHz = 16_000,
            hasRecurrentState = false,
            estimatedMemoryMb = 10,
            bundledInApk = true
        ),
        NeuralModelManifest(
            modelId = "openwakeword_hey_jarvis",
            version = "v0.1",
            runtime = NeuralRuntimeKind.LITERT,
            assetPath = "openwakeword/hey_jarvis_v0.1.tflite",
            expectedSizeBytes = 1_278_912L,
            expectedSha256 = "14bff778604985e1b5c19f0f7bbe477a69cf281d8db34b232b3b972411f710e2",
            upstreamSource = "https://github.com/dscripka/openWakeWord",
            license = "CC-BY-NC-SA-4.0",
            inputShapes = listOf(intArrayOf(1, 16, 96)),
            outputShapes = listOf(intArrayOf(1, 1)),
            dataType = "FLOAT32",
            quantization = "FP32",
            sampleRateHz = 16_000,
            hasRecurrentState = false,
            estimatedMemoryMb = 6,
            bundledInApk = true
        ),
        NeuralModelManifest(
            modelId = "smart_turn_v3_2",
            version = "v3.2-cpu",
            runtime = NeuralRuntimeKind.ONNX_RUNTIME,
            assetPath = "turn/smart-turn-v3.2-cpu.onnx",
            expectedSizeBytes = 8_679_182L,
            expectedSha256 = "2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f",
            upstreamSource = "https://github.com/pipecat-ai/smart-turn",
            license = "BSD-2-Clause",
            inputShapes = listOf(intArrayOf(1, 80, 800)),
            outputShapes = listOf(intArrayOf(1, 1)),
            dataType = "FLOAT32",
            quantization = "FP32",
            sampleRateHz = 16_000,
            hasRecurrentState = false,
            estimatedMemoryMb = 32,
            bundledInApk = true
        ),
        NeuralModelManifest(
            modelId = "yamnet_env_audio",
            version = "tflite-1",
            runtime = NeuralRuntimeKind.LITERT,
            assetPath = "yamnet/yamnet.tflite",
            expectedSizeBytes = 4_126_810L,
            expectedSha256 = "10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de",
            upstreamSource = "https://github.com/tensorflow/models/tree/master/research/audioset/yamnet",
            license = "Apache-2.0",
            inputShapes = listOf(intArrayOf(15600)),
            outputShapes = listOf(intArrayOf(1, 521)),
            dataType = "FLOAT32",
            quantization = "FP32",
            sampleRateHz = 16_000,
            hasRecurrentState = false,
            estimatedMemoryMb = 16,
            bundledInApk = true
        )
    )

    val plannedOnDemandCatalog: List<NeuralModelManifest> = listOf(
        NeuralModelManifest(
            modelId = "pocket_tts_english",
            version = "v1-onnx",
            runtime = NeuralRuntimeKind.NATIVE_CPP,
            assetPath = "tts/pocket_tts_pack",
            expectedSizeBytes = 0L,
            expectedSha256 = "",
            upstreamSource = "https://github.com/VolgaGerm/PocketTTS.cpp + https://github.com/kyutai-labs/pocket-tts",
            license = "MIT / CC-BY-4.0 weights",
            inputShapes = listOf(intArrayOf(1, -1)),
            outputShapes = listOf(intArrayOf(1, -1)),
            dataType = "FLOAT32",
            quantization = "INT8/FP32",
            sampleRateHz = 24_000,
            hasRecurrentState = true,
            estimatedMemoryMb = 95,
            bundledInApk = false
        ),
        NeuralModelManifest(
            modelId = "sherpa_zipformer_streaming_stt",
            version = "v2.0.3-bilingual-en-hi",
            runtime = NeuralRuntimeKind.ONNX_RUNTIME,
            assetPath = "stt/sherpa_zipformer",
            expectedSizeBytes = 0L,
            expectedSha256 = "",
            upstreamSource = "https://github.com/k2-fsa/sherpa-onnx",
            license = "Apache-2.0",
            inputShapes = listOf(intArrayOf(1, -1, 80)),
            outputShapes = listOf(intArrayOf(1, -1)),
            dataType = "FLOAT32",
            quantization = "INT8",
            sampleRateHz = 16_000,
            hasRecurrentState = true,
            estimatedMemoryMb = 68,
            bundledInApk = false
        )
    )

    fun activePhase1MemoryMb(): Int = bundledCatalog.sumOf { it.estimatedMemoryMb }

    fun verifyBundledFiles(assetsRootDir: File): List<ModelIntegrityCheckResult> =
        bundledCatalog.map { entry ->
            val file = File(assetsRootDir, entry.assetPath)
            if (!file.isFile) {
                ModelIntegrityCheckResult(entry.modelId, entry.assetPath, false, false, false, 0L, "")
            } else {
                val bytes = file.readBytes()
                val actualSha = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                ModelIntegrityCheckResult(
                    modelId = entry.modelId,
                    assetPath = entry.assetPath,
                    exists = true,
                    sizeMatches = bytes.size.toLong() == entry.expectedSizeBytes,
                    sha256Matches = actualSha == entry.expectedSha256,
                    actualSizeBytes = bytes.size.toLong(),
                    actualSha256 = actualSha
                )
            }
        }
}
