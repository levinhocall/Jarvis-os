package com.jarvis.assistant.audio

import org.junit.Assert.*
import org.junit.Test

class WakePhraseMatcherTest {
    @Test fun recognizesConfiguredName() {
        val matcher = WakePhraseMatcher(WakePhraseMatcher.Config(callSign = "Myra"))
        assertTrue(matcher.matches("hey myra", 1000))
        assertFalse(matcher.matches("hey jarvis", 4000))
    }

    @Test fun recognizesWakeUpPrefix() {
        val matcher = WakePhraseMatcher()
        assertTrue(matcher.matches("wake up jarvis", 1000))
    }

    @Test fun sensitivityControlsFuzzyAcceptance() {
        val strict = WakePhraseMatcher(WakePhraseMatcher.Config(sensitivity = 0))
        val flexible = WakePhraseMatcher(WakePhraseMatcher.Config(sensitivity = 100))
        assertFalse(strict.matches("hey jarviss", 1000))
        assertTrue(flexible.matches("hey jarviss", 1000))
    }

    @Test fun cooldownSuppressesDuplicates() {
        val matcher = WakePhraseMatcher(WakePhraseMatcher.Config(cooldownMillis = 2500))
        assertTrue(matcher.matches("jarvis", 1000))
        assertFalse(matcher.matches("jarvis", 1500))
        assertTrue(matcher.matches("jarvis", 3500))
    }

    @Test fun ignoresUnrelatedSpeech() {
        val matcher = WakePhraseMatcher()
        assertFalse(matcher.matches("please set a timer", 1000))
    }

    @Test fun acceptsUnicodeCallSign() {
        val matcher = WakePhraseMatcher(WakePhraseMatcher.Config(callSign = "मायरा"))
        assertTrue(matcher.matches("हे मायरा", 1000))
    }
}
