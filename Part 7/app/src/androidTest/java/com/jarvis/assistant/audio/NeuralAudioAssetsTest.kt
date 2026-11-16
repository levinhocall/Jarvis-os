package com.jarvis.assistant.audio

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jarvis.assistant.audio.wake.OpenWakeWordPipeline
import com.jarvis.assistant.audio.wake.SileroSpeechGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real shipped model/ABI smoke tests. Require an Android runtime; JVM fakes do not count. */
@RunWith(AndroidJUnit4::class)
class NeuralAudioAssetsTest {
    @Test fun packagedWakeAndSpeechModelsAcceptSilenceWithoutMicrophone() = runBlocking {
        withContext(Dispatchers.Default) {
            val assets=InstrumentationRegistry.getInstrumentation().targetContext.assets
            OpenWakeWordPipeline(assets).use { pipeline ->
                repeat(30) { pipeline.accept(ByteArray(2560))?.let { assertTrue(it.isFinite() && it in 0f..1f) } }
            }
            SileroSpeechGate(assets).use { speech ->
                repeat(5) { assertTrue(speech.accept(ByteArray(960)) in 0f..1f) }
                assertFalse(speech.speechRecent)
            }
            com.jarvis.assistant.audio.turn.SmartTurnDetector(assets).use { turn ->
                assertTrue(turn.probability(ByteArray(3200)) in 0f..1f)
            }
            assertNull(MicrophoneOwnership.owner.value)
        }
    }
    @Test fun nativeEchoLibraryOpensProcessesAndClosesWithoutAudioRecord() {
        Aec3Native().use { engine ->
            engine.render24k(ShortArray(240),0)
            val cleaned=ShortArray(160)
            engine.capture16k(ShortArray(160),cleaned)
            assertArrayEquals(ShortArray(160),cleaned)
            engine.reset()
        }
    }
}
