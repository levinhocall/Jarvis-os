package com.jarvis.assistant.audio.model

import java.io.File
import java.security.MessageDigest

data class DownloadableModelFile(
    val relativeName: String,
    val url: String,
    val expectedSizeBytes: Long,
    val expectedSha256: String? = null
)

data class DownloadableModelPack(
    val packId: String,
    val displayName: String,
    val category: String,
    val license: String,
    val upstreamRepo: String,
    val totalSizeBytes: Long,
    val estimatedRamMb: Int,
    val files: List<DownloadableModelFile>,
    val archiveUrl: String? = null
)

/**
 * Clean-room registry of downloadable neural model packs verified from public open-source repos.
 * Supports SHA-256 verification, incremental disk validation, and 6 GB RAM budget awareness.
 */
object DynamicModelCatalog {

    const val POCKET_TTS_EN_DIR = "models/pocket_tts_en_int8"
    const val POCKET_TTS_HI_DIR = "models/pocket_tts_hi_int8"
    const val SHERPA_STT_EN_DIR = "models/sherpa_zipformer_en"
    const val SPEAKER_CAMPPLUS_DIR = "models/speaker_campplus"
    const val YAMNET_DIR = "models/yamnet"
    const val MEMORY_EMBED_DIR = "models/memory_minilm_int8"

    val catalog: List<DownloadableModelPack> = listOf(
        DownloadableModelPack(
            packId = "pocket_tts_en_int8",
            displayName = "Kyutai Pocket TTS English (INT8)",
            category = "LOCAL_TTS",
            license = "CC-BY-4.0 weights / Apache-2.0 Sherpa export",
            upstreamRepo = "https://huggingface.co/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26",
            totalSizeBytes = 198_291_829L,
            estimatedRamMb = 145,
            files = listOf(
                DownloadableModelFile(
                    "lm_flow.int8.onnx",
                    "https://huggingface.co/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/resolve/main/lm_flow.int8.onnx",
                    9_962_530L
                ),
                DownloadableModelFile(
                    "lm_main.int8.onnx",
                    "https://huggingface.co/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/resolve/main/lm_main.int8.onnx",
                    76_341_079L
                ),
                DownloadableModelFile(
                    "encoder.onnx",
                    "https://huggingface.co/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/resolve/main/encoder.onnx",
                    72_713_165L
                ),
                DownloadableModelFile(
                    "decoder.int8.onnx",
                    "https://huggingface.co/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/resolve/main/decoder.int8.onnx",
                    22_693_618L
                ),
                DownloadableModelFile(
                    "text_conditioner.onnx",
                    "https://huggingface.co/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/resolve/main/text_conditioner.onnx",
                    16_388_343L
                ),
                DownloadableModelFile(
                    "vocab.json",
                    "https://huggingface.co/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/resolve/main/vocab.json",
                    69_478L
                ),
                DownloadableModelFile(
                    "token_scores.json",
                    "https://huggingface.co/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/resolve/main/token_scores.json",
                    123_616L
                )
            )
        ),
        DownloadableModelPack(
            packId = "pocket_tts_hi_int8",
            displayName = "Pocket TTS Hindi (IndicVoices INT8)",
            category = "LOCAL_TTS_HINDI",
            license = "CC-BY-4.0",
            upstreamRepo = "https://huggingface.co/saryps-labs/pocket-tts-hindi",
            totalSizeBytes = 240_000_227L,
            estimatedRamMb = 165,
            files = listOf(
                DownloadableModelFile(
                    "flow_lm_flow_int8.onnx",
                    "https://huggingface.co/saryps-labs/pocket-tts-hindi/resolve/main/onnx/flow_lm_flow_int8.onnx",
                    9_935_594L,
                    "a48edf56bafc9874e053700a990395691def264bad0eb6cf152fd520a082545d"
                ),
                DownloadableModelFile(
                    "flow_lm_main_int8.onnx",
                    "https://huggingface.co/saryps-labs/pocket-tts-hindi/resolve/main/onnx/flow_lm_main_int8.onnx",
                    151_449_987L,
                    "f7592dfc5c74bea1b8d6c8e2d6839806d44d833c4995f6da5bb5bddc20857002"
                ),
                DownloadableModelFile(
                    "mimi_decoder_int8.onnx",
                    "https://huggingface.co/saryps-labs/pocket-tts-hindi/resolve/main/onnx/mimi_decoder_int8.onnx",
                    22_616_918L,
                    "62f6540215bba4bd1ef6a55423999e2a18fab0d179ead5573edaedf5c8ab5e33"
                ),
                DownloadableModelFile(
                    "mimi_encoder.onnx",
                    "https://huggingface.co/saryps-labs/pocket-tts-hindi/resolve/main/onnx/mimi_encoder.onnx",
                    39_259_746L,
                    "f88ed9c97c61b7adf26c1f82a9468196dab5d67387e1332f3f08c720d86f11cd"
                ),
                DownloadableModelFile(
                    "text_conditioner.onnx",
                    "https://huggingface.co/saryps-labs/pocket-tts-hindi/resolve/main/onnx/text_conditioner.onnx",
                    16_388_310L,
                    "3f3d7744da63d975ecaf04f131eec28aea1f91dacd9b06a975c863e3391523e2"
                ),
                DownloadableModelFile(
                    "tokenizer.model",
                    "https://huggingface.co/saryps-labs/pocket-tts-hindi/resolve/main/onnx/tokenizer.model",
                    329_232L,
                    "27e09a7096f9d4912e0642182d421458baeeddbf8f25018e77417bf260535326"
                )
            )
        ),
        DownloadableModelPack(
            packId = "sherpa_zipformer_en",
            displayName = "Sherpa-ONNX Streaming Zipformer English",
            category = "LOCAL_STT",
            license = "Apache-2.0",
            upstreamRepo = "https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models",
            totalSizeBytes = 57_267_600L,
            estimatedRamMb = 72,
            files = emptyList(),
            archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06.tar.bz2"
        ),
        DownloadableModelPack(
            packId = "yamnet_env_audio",
            displayName = "Google YAMNet AudioSet-521 TFLite",
            category = "ENVIRONMENT_AUDIO",
            license = "Apache-2.0",
            upstreamRepo = "https://github.com/tensorflow/models/tree/master/research/audioset/yamnet",
            totalSizeBytes = 4_126_810L,
            estimatedRamMb = 16,
            files = listOf(
                DownloadableModelFile(
                    "yamnet.tflite",
                    "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/audio_classification/android/lite-model_yamnet_classification_tflite_1.tflite",
                    4_126_810L,
                    "10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de"
                )
            )
        ),
        DownloadableModelPack(
            packId = "speaker_3dspeaker_campplus",
            displayName = "3D-Speaker CAM++ VoxCeleb 16kHz ONNX",
            category = "SPEAKER_VERIFICATION",
            license = "Apache-2.0",
            upstreamRepo = "https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models",
            totalSizeBytes = 29_596_979L,
            estimatedRamMb = 36,
            files = listOf(
                DownloadableModelFile(
                    "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
                    29_596_979L
                )
            )
        ),
        DownloadableModelPack(
            packId = "memory_minilm_int8",
            displayName = "all-MiniLM-L6-v2 INT8 ONNX Embeddings",
            category = "MEMORY_EMBEDDING",
            license = "Apache-2.0",
            upstreamRepo = "https://huggingface.co/Xenova/all-MiniLM-L6-v2",
            totalSizeBytes = 22_972_370L,
            estimatedRamMb = 32,
            files = listOf(
                DownloadableModelFile(
                    "model_int8.onnx",
                    "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_int8.onnx",
                    22_972_370L
                )
            )
        )
    )

    fun findPack(packId: String): DownloadableModelPack? = catalog.firstOrNull { it.packId == packId }

    fun verifyDirectory(directory: File, pack: DownloadableModelPack): Boolean {
        if (!directory.isDirectory) return false
        if (pack.files.isEmpty()) {
            return directory.listFiles()?.any { it.isFile && it.length() > 0L } == true
        }
        return pack.files.all { spec ->
            val file = File(directory, spec.relativeName)
            if (!file.isFile || file.length() <= 0L) return@all false
            if (spec.expectedSizeBytes > 0L && file.length() != spec.expectedSizeBytes) return@all false
            val expectedHash = spec.expectedSha256 ?: return@all true
            sha256Hex(file).equals(expectedHash, ignoreCase = true)
        }
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
