package com.jarvis.assistant.audio.wake

import org.junit.Assert.*
import org.junit.Test

class WakeMatcherTest {
    private fun words(text:String, confidence:Double=.95) = text.split(' ').mapIndexed { i,w -> WakeMatcher.Word(w,confidence,i*.2,(i+1)*.2) }
    @Test fun supportsNamesPrefixesAndAliases() {
        for (text in listOf("myra","hey myra","wake up myra","hello mira")) assertTrue(text,WakeMatcher.evaluate(words(text),listOf("myra","mira"),50).accepted)
    }
    @Test fun doesNotMatchIncidentalSentencesOrSubstrings() {
        for(text in listOf("jarvisville","i told jarvis yesterday","hey jarvis do something","[unk]")) assertFalse(text,WakeMatcher.evaluate(words(text),listOf("jarvis"),100).accepted)
    }
    @Test fun fuzzyRequiresPrefixAndHighSensitivity() {
        assertTrue(WakeMatcher.evaluate(words("hey jarves"),listOf("jarvis"),80).accepted)
        assertFalse(WakeMatcher.evaluate(words("hey jarves"),listOf("jarvis"),50).accepted)
        assertFalse(WakeMatcher.evaluate(words("jarves"),listOf("jarvis"),100).accepted)
        assertFalse(WakeMatcher.evaluate(words("hey james"),listOf("jarvis"),100).accepted)
    }
    @Test fun sensitivityStillRejectsLowConfidenceAndInvalidAcoustics() {
        assertFalse(WakeMatcher.evaluate(words("hey myra",.59),listOf("myra"),100).accepted)
        assertTrue(WakeMatcher.evaluate(words("hey myra",.7),listOf("myra"),100).accepted)
        assertFalse(WakeMatcher.evaluate(words("hey myra",.7),listOf("myra"),0).accepted)
        assertFalse(WakeMatcher.evaluate(words("myra",Double.NaN),listOf("myra"),100).accepted)
        assertFalse(WakeMatcher.evaluate(listOf(WakeMatcher.Word("myra",1.0,0.0,.01)),listOf("myra"),100).accepted)
    }
    @Test fun inputIsBoundedAndEnglishModelLimitIsExplicit() {
        assertTrue(WakeMatcher.validName("Myra")); assertTrue(WakeMatcher.validName("Iron Man"))
        for(name in listOf("", "a", "[unk]", "../../../x", "three word name", "मायरा")) assertFalse(WakeMatcher.validName(name))
    }
}
