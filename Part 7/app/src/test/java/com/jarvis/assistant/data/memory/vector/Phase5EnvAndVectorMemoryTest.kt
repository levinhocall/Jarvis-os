package com.jarvis.assistant.data.memory.vector

import com.jarvis.assistant.audio.env.EnvironmentalAudioEngine
import com.jarvis.assistant.audio.env.EnvironmentalSceneCategory
import com.jarvis.assistant.audio.env.EnvironmentalSnapshot
import com.jarvis.assistant.audio.env.YamnetClassMapParser
import com.jarvis.assistant.audio.env.YamnetDriver
import com.jarvis.assistant.audio.model.NeuralModelRegistry
import com.jarvis.assistant.automation.proactive.ProactiveAssistantCoordinator
import com.jarvis.assistant.core.runtime.JarvisOperatingMode
import com.jarvis.assistant.core.runtime.NeuralRuntimeManager
import com.jarvis.assistant.core.runtime.ResourceGovernor
import com.jarvis.assistant.data.memory.MemoryEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec

class Phase5EnvAndVectorMemoryTest {

    @Test
    fun yamnetAssetClassMapAndDutyCycledEngineClassifyScenesWithoutHoldingRawAudio() {
        val assetsDir = listOf(
            File("app/src/main/assets"),
            File("Part 7/app/src/main/assets"),
            File("src/main/assets")
        ).firstOrNull { it.isDirectory }
        assertNotNull(assetsDir)

        val csvFile = File(assetsDir, "yamnet/yamnet_class_map.csv")
        assertTrue("Missing yamnet_class_map.csv", csvFile.isFile)
        val labels = csvFile.inputStream().use(YamnetClassMapParser::parseCsv)
        assertEquals(521, labels.size)
        assertEquals("Speech", labels[0])

        val yamnetCheck = NeuralModelRegistry.verifyBundledFiles(assetsDir!!)
            .firstOrNull { it.modelId == "yamnet_env_audio" }
        assertNotNull(yamnetCheck)
        assertTrue("YAMNet SHA-256 verification failed", yamnetCheck!!.passed)

        var released = false
        val fakeDriver = object : YamnetDriver {
            override fun initialize(): Boolean = true
            override fun classifyWaveform16k(samples: FloatArray): FloatArray? {
                val logits = FloatArray(521)
                // Simulate high Music confidence at index 137 (or first "Music" label index)
                val musicIdx = labels.indexOfFirst { it.equals("Music", ignoreCase = true) }.coerceAtLeast(1)
                logits[musicIdx] = 0.88f
                return logits
            }
            override fun release() {
                released = true
            }
        }

        val engine = EnvironmentalAudioEngine(
            classLabels = labels,
            minClassificationIntervalMs = 2_000L,
            driverFactory = { fakeDriver }
        )

        val pcm16 = ByteArray(31_200) { 0x12 } // 0.975s 16kHz PCM16
        val snap = engine.analyzePcm16(pcm16, nowMs = 5_000L)
        assertNotNull(snap)
        assertEquals(EnvironmentalSceneCategory.MUSIC, snap!!.category)
        assertTrue(snap.vadThresholdBoost > 0.05f)

        // Duty cycle blocks immediate re-invocation inside interval
        assertNull(engine.analyzePcm16(pcm16, nowMs = 5_500L))

        val advisory = ProactiveAssistantCoordinator.evaluateAmbientContext(
            EnvironmentalSnapshot(
                topLabel = "Smoke detector, smoke alarm",
                confidence = 0.82f,
                category = EnvironmentalSceneCategory.ALARM_SIREN
            )
        )
        assertNotNull(advisory)
        assertTrue(advisory!!.contains("alert"))

        engine.release()
        assertTrue(released)
    }

    @Test
    fun encryptedHnswStoreSealsVectorIndexAndHybridSearchRanksResults() = runTest {
        val index = HnswVectorIndex(dimensions = 8, forceKotlinFallback = true)
        assertTrue(index.upsert(1L, floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)))
        assertTrue(index.upsert(2L, floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)))

        val tmpFile = Files.createTempFile("jarvis_hnsw_test", ".enc").toFile().apply { deleteOnExit() }
        val keyBytes = ByteArray(32) { (it + 7).toByte() }
        val store = EncryptedHnswStore { SecretKeySpec(keyBytes, "AES") }

        assertTrue(store.saveEncrypted(tmpFile, index))
        val rawSnapshot = index.exportSerialized()!!
        val onDisk = tmpFile.readBytes()
        assertFalse("Encrypted HNSW file must never match plaintext bytes", onDisk.contentEquals(rawSnapshot))

        val restored = HnswVectorIndex(dimensions = 8, forceKotlinFallback = true)
        assertTrue(store.loadEncrypted(tmpFile, restored))
        assertEquals(2, restored.size())

        val hits = restored.search(floatArrayOf(0.99f, 0.05f, 0f, 0f, 0f, 0f, 0f, 0f), limit = 1)
        assertEquals(1, hits.size)
        assertEquals(1L, hits[0].key)

        // Verify two-stage HNSW + keyword/recency fusion
        val memories = listOf(
            MemoryEntity(id = 1L, category = "PREFERENCES", key = "tea", value = "prefers masala chai", rawText = "prefers masala chai in evening", importance = 0.8f),
            MemoryEntity(id = 2L, category = "WORK", key = "repo", value = "working on jarvis", rawText = "working on jarvis os", importance = 0.5f)
        )
        val constantEmbedder = object : EmbeddingProvider {
            override val modelName: String = "test-8d"
            override val dimension: Int = 8
            override fun isAvailable(): Boolean = true
            override suspend fun generateEmbedding(text: String): FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
        val ranked = VectorSearchEngine().searchWithHnsw("masala chai", memories, constantEmbedder, restored, limit = 2)
        assertEquals(1, ranked.size)
        assertEquals(1L, ranked[0].memory.id)

        index.close()
        restored.close()
    }

    @Test
    fun wordPieceTokenizerAndHybridOnnxEmbeddingHonorResourcePressureAndFallback() = runTest {
        val vocabFile = Files.createTempFile("vocab_test", ".txt").toFile().apply {
            writeText("[PAD]\n[UNK]\n[CLS]\n[SEP]\nopen\nwhatsapp\n##app\nfast\n##ly\n")
            deleteOnExit()
        }
        val dummyModel = Files.createTempFile("minilm_test", ".onnx").toFile().apply {
            writeBytes(ByteArray(32) { 1 })
            deleteOnExit()
        }

        val tok = WordPieceTokenizer.fromVocabFile(vocabFile, maxSequenceLength = 16)
        val encoded = tok.encode("open fastly")
        assertEquals(2L, encoded.inputIds.first()) // [CLS]
        assertEquals(3L, encoded.inputIds.last())  // [SEP]
        assertEquals(encoded.inputIds.size, encoded.attentionMask.size)

        val onnxProvider = OnnxTextEmbeddingProvider(dimension = 384, driverFactory = {
            object : OnnxEmbeddingDriver {
                override fun initialize(modelFile: File): Boolean = true
                override fun embeddingDimension(): Int = 384
                override fun encodeTokens(encoded: WordPieceTokenizer.EncodedSequence): FloatArray =
                    FloatArray(384) { if (it == 0) 1f else 0f }
                override fun release() {}
            }
        })
        assertTrue(onnxProvider.loadModel(dummyModel, vocabFile))

        ResourceGovernor.onThermalStatusChanged(android.os.PowerManager.THERMAL_STATUS_NONE)
        ResourceGovernor.onTrimMemory(0, 1024L)
        ResourceGovernor.setOperatingMode(JarvisOperatingMode.ACTIVE_CONVERSATION)
        val hybrid = HybridNeuralEmbeddingProvider(onnxProvider)
        val neuralVec = hybrid.generateEmbedding("open whatsapp")
        assertNotNull(neuralVec)
        assertEquals("minilm-l6-v2-int8-onnx", hybrid.modelName)

        // Under critical memory/thermal pressure, background dense ONNX is throttled cleanly to fallback
        ResourceGovernor.onThermalStatusChanged(android.os.PowerManager.THERMAL_STATUS_CRITICAL)
        assertEquals(JarvisOperatingMode.THERMAL_OR_MEMORY_PRESSURE, ResourceGovernor.policy.value.mode)
        val fallbackVec = hybrid.generateEmbedding("open whatsapp")
        assertNotNull(fallbackVec)
        assertTrue(hybrid.modelName.startsWith("local-unicode"))

        ResourceGovernor.onThermalStatusChanged(android.os.PowerManager.THERMAL_STATUS_NONE)
        ResourceGovernor.onTrimMemory(0, 1024L)
        ResourceGovernor.setOperatingMode(JarvisOperatingMode.STANDBY)
        NeuralRuntimeManager.clearForTest()
        onnxProvider.close()
    }
}
