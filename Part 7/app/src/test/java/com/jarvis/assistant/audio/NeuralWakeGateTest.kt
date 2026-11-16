package com.jarvis.assistant.audio

import com.jarvis.assistant.audio.wake.NeuralWakeGate
import org.junit.Assert.*
import org.junit.Test

class NeuralWakeGateTest {
    @Test fun warmupAndConsecutiveScoresAreRequired() {
        val gate = NeuralWakeGate()
        repeat(24) { assertFalse(gate.accept(1f,50,it*80L)) }
        assertFalse(gate.accept(1f,50,2000))
        assertTrue(gate.accept(1f,50,2080))
        repeat(10) { assertFalse(gate.accept(1f,50,2160+it*80L)) }
        assertTrue(gate.accept(1f,50,5000))
    }
    @Test fun silenceAndInvalidScoresNeverWake() {
        val gate = NeuralWakeGate()
        repeat(100) { assertFalse(gate.accept(if(it%2==0) 0f else Float.NaN,100,it*80L)) }
    }
    @Test fun wakeScoreCannotBypassSpeechGate() {
        val gate = NeuralWakeGate()
        repeat(100) { assertFalse(gate.accept(1f,100,it*80L,speechRecent=false)) }
    }
    @Test fun missingNeuralAbiUsesSupportedFallbackInsteadOfLoadingAnUnavailableLibrary() {
        assertFalse(NeuralWakeGate.supportsRuntime(listOf("x86")))
        assertFalse(NeuralWakeGate.supportsRuntime(emptyList()))
        assertTrue(NeuralWakeGate.supportsRuntime(listOf("x86_64","x86")))
        assertTrue(NeuralWakeGate.supportsRuntime(listOf("arm64-v8a","armeabi-v7a")))
    }

}
