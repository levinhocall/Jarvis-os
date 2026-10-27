package com.jarvis.assistant.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageDetectorTest {

    @Test
    fun testDetectEnglish() {
        assertEquals(
            DetectedLanguage.ENGLISH,
            LanguageDetector.detect("Open the camera and take a picture")
        )
        assertEquals(
            DetectedLanguage.ENGLISH,
            LanguageDetector.detect("What is the current weather in New York?")
        )
        assertEquals(
            DetectedLanguage.ENGLISH,
            LanguageDetector.detect("Turn on the flashlight and play music")
        )
    }

    @Test
    fun testDetectHindiDevanagari() {
        assertEquals(
            DetectedLanguage.HINDI,
            LanguageDetector.detect("नमस्ते, आप कैसे हैं?")
        )
        assertEquals(
            DetectedLanguage.HINDI,
            LanguageDetector.detect("आज का मौसम कैसा है?")
        )
        assertEquals(
            DetectedLanguage.HINDI,
            LanguageDetector.detect("टॉर्च जलाओ और अलार्म लगाओ")
        )
    }

    @Test
    fun testDetectHinglish() {
        assertEquals(
            DetectedLanguage.HINGLISH,
            LanguageDetector.detect("Haan bhai, kya haal chaal hai?")
        )
        assertEquals(
            DetectedLanguage.HINGLISH,
            LanguageDetector.detect("Mera phone charge pe laga do")
        )
        assertEquals(
            DetectedLanguage.HINGLISH,
            LanguageDetector.detect("Ye gaana play karo na please")
        )
        assertEquals(
            DetectedLanguage.HINGLISH,
            LanguageDetector.detect("Batao kya plan hai")
        )
        assertEquals(
            DetectedLanguage.HINGLISH,
            LanguageDetector.detect("Kya")
        )
    }

    @Test
    fun testDetectUnknown() {
        assertEquals(
            DetectedLanguage.UNKNOWN,
            LanguageDetector.detect(null)
        )
        assertEquals(
            DetectedLanguage.UNKNOWN,
            LanguageDetector.detect("")
        )
        assertEquals(
            DetectedLanguage.UNKNOWN,
            LanguageDetector.detect("   ")
        )
        assertEquals(
            DetectedLanguage.UNKNOWN,
            LanguageDetector.detect("12345 67890")
        )
        assertEquals(
            DetectedLanguage.UNKNOWN,
            LanguageDetector.detect("??? !!! @#$%^&*()")
        )
    }
}
