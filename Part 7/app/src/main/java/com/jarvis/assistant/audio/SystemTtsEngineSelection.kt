package com.jarvis.assistant.audio

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import com.jarvis.assistant.JarvisApp

/** Standard Android binding to installed engines, including Pocket TTS. No second audio engine. */
object SystemTtsEngineSelection {
    data class Engine(val packageName: String, val label: String)
    const val POCKET_ENGINE = "org.pockettts.android.engine"
    const val POCKET_SOURCE = "https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine"
    @Suppress("DEPRECATION")
    fun installed(context: Context): List<Engine> = context.packageManager.queryIntentServices(
        Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),0).map { resolved ->
        Engine(resolved.serviceInfo.packageName,resolved.loadLabel(context.packageManager).toString())
    }.distinctBy { it.packageName }.sortedBy { it.label }
    fun requested(): String? = JarvisApp.instance.preferences.localTtsEngine.takeIf(String::isNotBlank)
    fun save(packageName: String) {
        require(packageName.isEmpty() || packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
        JarvisApp.instance.preferences.localTtsEngine=packageName
    }
}
