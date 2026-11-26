package com.jarvis.assistant.audio.tts

import java.util.Locale

enum class SpeechLanguageRoute {
    ENGLISH_LATIN,
    HINDI_DEVANAGARI,
    HINGLISH_MIXED
}

enum class TtsBackendChoice {
    POCKET_TTS_24K_PCM,
    ANDROID_TTS_24K_PCM
}

data class PocketTtsVoiceConfig(
    val voiceId: String = "alba",
    val languageTag: String = "en",
    val temperature: Float = 0.7f,
    val speedScale: Float = 1.0f,
    val clonePromptPath: String? = null
)

data class PocketTtsModelConfig(
    val modelDirectory: String,
    val threads: Int = 2,
    val sampleRateHz: Int = 24_000
)

/**
 * Clean-room streaming local neural synthesis interface matching Part 1 §9
 * (`kyutai-labs/pocket-tts` MIT / `VolgaGerm/PocketTTS.cpp` MIT, 24 kHz mono PCM16).
 */
interface PocketTtsBackend : AutoCloseable {
    fun loadModel(config: PocketTtsModelConfig): Boolean
    fun prepareVoice(voiceConfig: PocketTtsVoiceConfig): Boolean
    fun synthesizeStreaming(
        text: String,
        onPcm24kChunk: (ByteArray) -> Unit
    ): Boolean
    fun cancelSynthesis()
    fun reset()
    fun unloadModel()
    fun isReady(): Boolean
}

/**
 * Language-aware TTS router verifying script & Hinglish lexical tokens:
 * Kyutai Pocket TTS officially supports European Latin languages (`en/fr/de/pt/it/es/nl`)
 * and does NOT natively support Devanagari Hindi or Hinglish phonetics, so Hindi/Hinglish
 * utterances route automatically to Hindi-capable TTS -> unified 24 kHz `AudioPlayer` PCM.
 */
object TtsLanguageRouter {

    private val DEVANAGARI_REGEX = Regex("[\\u0900-\\u097F]")
    private val HINGLISH_MARKERS = setOf(
        "kya", "hai", "hain", "hoon", "hu", "karo", "kar", "karna", "karein",
        "ruko", "ruk", "bas", "suno", "chup", "batao", "btao", "kal", "aaj",
        "subah", "shaam", "raat", "mujhe", "mera", "meri", "tera", "aap", "tum",
        "bhai", "yaar", "haan", "nahi", "nhi", "theek", "thik", "acha", "accha",
        "kaisa", "kaise", "kahan", "kyun", "kyu", "abhi", "jaldi", "bolo", "laga",
        "dena", "lena", "dost", "ghar", "paani", "khana", "samay", "baje"
    )

    fun classifyText(text: String): SpeechLanguageRoute {
        if (DEVANAGARI_REGEX.containsMatchIn(text)) {
            return SpeechLanguageRoute.HINDI_DEVANAGARI
        }
        val tokens = text.lowercase(Locale.ROOT)
            .split(Regex("[^a-z]+"))
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return SpeechLanguageRoute.ENGLISH_LATIN
        val hindiHits = tokens.count { it in HINGLISH_MARKERS }
        return if (hindiHits >= 2 || (tokens.size <= 3 && hindiHits >= 1)) {
            SpeechLanguageRoute.HINGLISH_MIXED
        } else {
            SpeechLanguageRoute.ENGLISH_LATIN
        }
    }

    fun selectBackend(text: String, pocketTtsReady: Boolean): TtsBackendChoice {
        val route = classifyText(text)
        return if (pocketTtsReady && route == SpeechLanguageRoute.ENGLISH_LATIN) {
            TtsBackendChoice.POCKET_TTS_24K_PCM
        } else {
            TtsBackendChoice.ANDROID_TTS_24K_PCM
        }
    }
}
