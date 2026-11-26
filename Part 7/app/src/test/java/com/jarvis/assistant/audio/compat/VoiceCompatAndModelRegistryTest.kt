package com.jarvis.assistant.audio.compat

import com.jarvis.assistant.audio.model.NeuralModelRegistry
import com.jarvis.assistant.audio.stt.StreamingSttConfig
import com.jarvis.assistant.audio.stt.StreamingSttEngine
import com.jarvis.assistant.audio.stt.TranscriptEvent
import com.jarvis.assistant.audio.tts.SpeechLanguageRoute
import com.jarvis.assistant.audio.tts.TtsBackendChoice
import com.jarvis.assistant.audio.tts.TtsLanguageRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceCompatAndModelRegistryTest {

    @Test
    fun bundledModelCatalogMatchesExactAssetBytesAndSha256() {
        val assetsRoot = listOf(
            File("app/src/main/assets"),
            File("Part 7/app/src/main/assets"),
            File("src/main/assets")
        ).firstOrNull { it.isDirectory }
        assertNotNull("Expected assets root in repository", assetsRoot)

        val results = NeuralModelRegistry.verifyBundledFiles(assetsRoot!!)
        assertEquals(NeuralModelRegistry.bundledCatalog.size, results.size)
        results.forEach { res ->
            assertTrue("Missing asset: ${res.assetPath}", res.exists)
            assertTrue("Size mismatch on ${res.assetPath} (actual=${res.actualSizeBytes})", res.sizeMatches)
            assertTrue("SHA-256 mismatch on ${res.assetPath} (actual=${res.actualSha256})", res.sha256Matches)
            assertTrue("${res.modelId} failed integrity check", res.passed)
        }
    }

    @Test
    fun activePhase1ModelMemoryStaysWithin6GbDeviceBudget() {
        val activeMb = NeuralModelRegistry.activePhase1MemoryMb()
        assertTrue(
            "Expected active Phase 1 neural memory <= ${NeuralModelRegistry.PHASE_1_ACTIVE_MEMORY_CAP_MB} MB, was $activeMb MB",
            activeMb <= NeuralModelRegistry.PHASE_1_ACTIVE_MEMORY_CAP_MB
        )
    }

    @Test
    fun ttsLanguageRouterIsolatesEnglishFromHindiAndHinglish() {
        assertEquals(SpeechLanguageRoute.ENGLISH_LATIN, TtsLanguageRouter.classifyText("Open WhatsApp and check notifications."))
        assertEquals(SpeechLanguageRoute.HINDI_DEVANAGARI, TtsLanguageRouter.classifyText("नमस्ते जार्विस, मौसम बताओ"))
        assertEquals(SpeechLanguageRoute.HINGLISH_MIXED, TtsLanguageRouter.classifyText("Kal subah 7 baje alarm laga dena"))
        assertEquals(SpeechLanguageRoute.HINGLISH_MIXED, TtsLanguageRouter.classifyText("Ruko bhai"))

        assertEquals(
            TtsBackendChoice.POCKET_TTS_24K_PCM,
            TtsLanguageRouter.selectBackend("Good morning Sir, systems are ready.", pocketTtsReady = true)
        )
        assertEquals(
            TtsBackendChoice.ANDROID_TTS_24K_PCM,
            TtsLanguageRouter.selectBackend("Kal subah 7 baje alarm laga dena", pocketTtsReady = true)
        )
        assertEquals(
            TtsBackendChoice.ANDROID_TTS_24K_PCM,
            TtsLanguageRouter.selectBackend("Hello Sir.", pocketTtsReady = false)
        )
    }

    @Test
    fun streamingSttContractSupportsLifecycleAndReset() {
        val fakeEngine = object : StreamingSttEngine {
            var initialized = false
            var cancelled = false
            var partial = ""
            var finalResult = ""

            override fun initialize(config: StreamingSttConfig): Boolean {
                initialized = true
                cancelled = false
                return true
            }

            override fun acceptAudio(pcm16kMono: ByteArray): TranscriptEvent? {
                if (!initialized || cancelled || pcm16kMono.isEmpty()) return null
                partial = "open whatsapp"
                finalResult = "open whatsapp now"
                return TranscriptEvent(finalResult, isFinal = true, languageTag = "en-IN")
            }

            override fun getPartialTranscript(): String = partial
            override fun getFinalTranscript(): String = finalResult
            override fun reset() { partial = ""; finalResult = ""; cancelled = false }
            override fun cancel() { cancelled = true; partial = "" }
            override fun release() { initialized = false; partial = ""; finalResult = "" }
        }

        assertTrue(fakeEngine.initialize(StreamingSttConfig()))
        val event = fakeEngine.acceptAudio(ByteArray(320) { 1 })
        assertNotNull(event)
        assertEquals("open whatsapp now", fakeEngine.getFinalTranscript())
        fakeEngine.cancel()
        assertFalse(fakeEngine.acceptAudio(ByteArray(320) { 1 }) != null)
        fakeEngine.reset()
        assertEquals("", fakeEngine.getFinalTranscript())
        fakeEngine.release()
    }
}
