package com.jarvis.assistant.audio

import android.content.ComponentCallbacks2
import android.os.PowerManager
import com.jarvis.assistant.audio.model.DynamicModelCatalog
import com.jarvis.assistant.audio.model.ModelLifecycleState
import com.jarvis.assistant.audio.model.NeuralRuntimeKind
import com.jarvis.assistant.audio.stt.HybridStreamingSttManager
import com.jarvis.assistant.audio.stt.LocalTranscriptManager
import com.jarvis.assistant.audio.stt.OfflineCommandKind
import com.jarvis.assistant.audio.stt.OfflineCommandRecognizer
import com.jarvis.assistant.audio.stt.SherpaSttDriver
import com.jarvis.assistant.audio.stt.SherpaStreamingSttEngine
import com.jarvis.assistant.audio.stt.SherpaTransducerModelFiles
import com.jarvis.assistant.audio.stt.StreamingSttConfig
import com.jarvis.assistant.audio.stt.TranscriptEvent
import com.jarvis.assistant.audio.tts.SpeechLanguageRoute
import com.jarvis.assistant.audio.tts.PocketModelFileSet
import com.jarvis.assistant.audio.tts.PocketTtsDriver
import com.jarvis.assistant.audio.tts.PocketTtsEngine
import com.jarvis.assistant.audio.tts.PocketTtsModelConfig
import com.jarvis.assistant.audio.tts.PocketTtsVoiceConfig
import com.jarvis.assistant.core.runtime.InferencePriority
import com.jarvis.assistant.core.runtime.JarvisOperatingMode
import com.jarvis.assistant.core.runtime.NeuralRuntimeManager
import com.jarvis.assistant.core.runtime.ResourceGovernor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Phase2VoiceAndRuntimeTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        NeuralRuntimeManager.clearForTest()
        ResourceGovernor.setOperatingMode(JarvisOperatingMode.ACTIVE_CONVERSATION)
        ResourceGovernor.onThermalStatusChanged(PowerManager.THERMAL_STATUS_NONE)
        tempDir = Files.createTempDirectory("jarvis_phase2_test").toFile()
    }

    @After
    fun tearDown() {
        NeuralRuntimeManager.clearForTest()
        tempDir.deleteRecursively()
    }

    @Test
    fun resourceGovernorEvictsLowPriorityModelsUnderThermalPressure() {
        var unloadedEmbed = false
        var unloadedTts = false
        var unloadedAec = false

        NeuralRuntimeManager.registerLoaded("dtln_aec", NeuralRuntimeKind.LITERT, 26, InferencePriority.REALTIME_AUDIO) { unloadedAec = true }
        NeuralRuntimeManager.registerLoaded("pocket_tts_en_int8", NeuralRuntimeKind.ONNX_RUNTIME, 145, InferencePriority.CONVERSATION_SPEECH) { unloadedTts = true }
        NeuralRuntimeManager.registerLoaded("memory_minilm_int8", NeuralRuntimeKind.ONNX_RUNTIME, 64, InferencePriority.MEMORY_INDEXING) { unloadedEmbed = true }

        assertEquals(235, NeuralRuntimeManager.totalActiveMemoryMb())

        ResourceGovernor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, availableHeapMb = 90L)

        assertTrue("Memory indexer must be evicted first under pressure", unloadedEmbed)
        assertTrue("Cold conversation TTS should be evicted to meet 112 MB cap", unloadedTts)
        assertFalse("Realtime audio DTLN/AEC must remain protected under critical budget > 96 MB", unloadedAec)
        assertEquals(JarvisOperatingMode.THERMAL_OR_MEMORY_PRESSURE, ResourceGovernor.policy.value.mode)
        assertFalse(NeuralRuntimeManager.canSchedule(InferencePriority.BACKGROUND_VISION_ENV))
    }

    @Test
    fun pocketTtsStreams24kPcmAndCancelsMidUtteranceOnBargeIn() {
        val modelDir = File(tempDir, "pocket").apply { mkdirs() }
        listOf(
            "lm_flow.int8.onnx", "lm_main.int8.onnx", "encoder.onnx",
            "decoder.int8.onnx", "text_conditioner.onnx", "vocab.json", "token_scores.json"
        ).forEach { File(modelDir, it).writeBytes(byteArrayOf(1, 2, 3, 4)) }

        var destroyed = false
        var chunksEmittedByDriver = 0
        val fakeDriver = object : PocketTtsDriver {
            override fun createSession(files: PocketModelFileSet, threads: Int) = true
            override fun sampleRateHz() = 24_000
            override fun synthesize(
                text: String,
                speed: Float,
                referenceAudio: FloatArray?,
                referenceSampleRate: Int,
                onFloatChunk: (FloatArray) -> Boolean
            ): Boolean {
                repeat(5) {
                    chunksEmittedByDriver++
                    val keepGoing = onFloatChunk(FloatArray(480) { 0.25f })
                    if (!keepGoing) return true
                }
                return true
            }

            override fun destroySession() {
                destroyed = true
            }
        }

        val engine = PocketTtsEngine { fakeDriver }
        assertTrue(engine.loadModel(PocketTtsModelConfig(modelDir.absolutePath)))
        assertTrue(engine.isReady())
        assertTrue(engine.prepareVoice(PocketTtsVoiceConfig(voiceId = "jarvis_default")))

        val delivered = mutableListOf<ByteArray>()
        assertTrue(engine.synthesizeStreaming("First sentence. Second sentence.") { pcm ->
            delivered += pcm
        })
        assertEquals(960, delivered.first().size)

        // Cancel on first chunk inside streaming synthesis -> immediate sub-chunk abort
        val cancelledChunks = mutableListOf<ByteArray>()
        val completed = engine.synthesizeStreaming("Interrupt me immediately after one chunk.") { pcm ->
            cancelledChunks += pcm
            engine.cancelSynthesis()
        }
        assertFalse("Cancelled synthesis must report interrupted/false", completed)
        assertEquals(1, cancelledChunks.size)

        engine.unloadModel()
        assertFalse(engine.isReady())
        assertTrue(destroyed)
    }

    @Test
    fun sherpaStreamingSttEmitsPartialAndFinalEventsAndFallsBackGracefully() {
        val sttDir = File(tempDir, "sherpa_asr").apply { mkdirs() }
        File(sttDir, "encoder-epoch-99.int8.onnx").writeBytes(byteArrayOf(1))
        File(sttDir, "decoder-epoch-99.int8.onnx").writeBytes(byteArrayOf(1))
        File(sttDir, "joiner-epoch-99.int8.onnx").writeBytes(byteArrayOf(1))
        File(sttDir, "tokens.txt").writeText("<blk> 0\nhello 1\n")

        var step = 0
        val fakeDriver = object : SherpaSttDriver {
            override fun initialize(files: SherpaTransducerModelFiles, sampleRateHz: Int, threads: Int) = true
            override fun acceptSamples(samples: FloatArray, sampleRateHz: Int) { step++ }
            override fun decodeReadyAndReadText(): Pair<String, Boolean> =
                if (step < 2) "hello" to false else "hello jarvis" to true
            override fun resetStream() { step = 0 }
            override fun release() {}
        }

        val engine = SherpaStreamingSttEngine(sttDir.absolutePath, 2) { fakeDriver }
        assertTrue(engine.initialize(StreamingSttConfig(sampleRateHz = 16_000, languageHint = "en-US")))

        val partial = engine.acceptAudio(ByteArray(320))
        assertNotNull(partial)
        assertFalse(partial!!.isFinal)
        assertEquals("hello", partial.text)

        val finalEvt = engine.acceptAudio(ByteArray(320))
        assertNotNull(finalEvt)
        assertTrue(finalEvt!!.isFinal)
        assertEquals("hello jarvis", finalEvt.text)
        assertEquals("", engine.getPartialTranscript())
        assertEquals("hello jarvis", engine.getFinalTranscript())

        // Without Sherpa OR Vosk initialized, hybrid manager reports UNAVAILABLE cleanly (never crashes)
        val hybrid = HybridStreamingSttManager({ File(tempDir, "missing").absolutePath }) { it.orEmpty() }
        assertFalse(hybrid.initialize(StreamingSttConfig()))
        assertEquals("UNAVAILABLE", hybrid.currentBackend())
        assertNull(hybrid.acceptAudio(ByteArray(320)))

        engine.release()
    }

    @Test
    fun dynamicModelCatalogValidatesInstalledPackSizes() {
        val pack = DynamicModelCatalog.findPack("yamnet_env_audio")
        assertNotNull(pack)
        val dir = File(tempDir, "yamnet").apply { mkdirs() }
        assertFalse(DynamicModelCatalog.verifyDirectory(dir, pack!!))

        val weights = File(dir, "yamnet.tflite")
        weights.writeBytes(ByteArray(128))
        assertFalse("Wrong byte count must fail strict catalog check", DynamicModelCatalog.verifyDirectory(dir, pack))

        assertNull(NeuralRuntimeManager.getSession("missing_id"))
    }

    @Test
    fun audioFramePoolRingBufferAndOfflineCommandRecognizerWorkCleanly() {
        val pool = AudioFramePool(capacity = 4, maxFrameBytes = 320)
        val frame = pool.acquire()
        val sample = ByteArray(320) { (it % 120).toByte() }
        frame.loadFrom(sample, 0, sample.size, 16_000, 123456L, 7L)
        assertEquals(320, frame.validBytes)
        assertEquals(7L, frame.frameIndex)
        pool.recycle(frame)
        assertTrue(pool.pooledCount() >= 1)

        val ring = AudioRingBuffer(640)
        ring.write(ByteArray(400) { 1 })
        ring.write(ByteArray(400) { 2 })
        assertEquals(640, ring.availableBytes())
        val snap = ring.snapshotRecent(200)
        assertEquals(200, snap.size)
        assertTrue(snap.all { it.toInt() == 2 })

        val tm = LocalTranscriptManager()
        val cmd1 = tm.onTranscript(TranscriptEvent("torch jalao", isFinal = true), "SHERPA")
        assertEquals(OfflineCommandKind.FLASHLIGHT_ON, cmd1.kind)
        assertEquals(SpeechLanguageRoute.HINGLISH_MIXED, tm.state.value.languageRoute)

        assertEquals(OfflineCommandKind.STOP_SPEAKING, OfflineCommandRecognizer.match("ruko!").kind)
        assertEquals(OfflineCommandKind.CANCEL_MISSION, OfflineCommandRecognizer.match("Cancel mission").kind)
    }
}
