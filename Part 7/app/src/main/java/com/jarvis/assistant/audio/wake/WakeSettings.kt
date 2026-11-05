package com.jarvis.assistant.audio.wake

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class WakeSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("offline_wake",Context.MODE_PRIVATE)
    var name: String
        get() = prefs.getString("name","jarvis") ?: "jarvis"
        set(value) { require(WakeMatcher.validName(value)); prefs.edit().putString("name",WakeMatcher.normalize(value)).apply() }
    var alias: String
        get() = prefs.getString("alias","") ?: ""
        set(value) { require(value.isBlank() || WakeMatcher.validName(value)); prefs.edit().putString("alias",WakeMatcher.normalize(value)).apply() }
    var sensitivity: Int
        get() = prefs.getInt("sensitivity",50).coerceIn(0,100)
        set(value) { prefs.edit().putInt("sensitivity",value.coerceIn(0,100)).apply() }
    var silentWake: Boolean
        get() = prefs.getBoolean("silent_wake",false)
        set(value) { prefs.edit().putBoolean("silent_wake",value).apply() }
    var allowPocket: Boolean
        get() = prefs.getBoolean("allow_pocket",false)
        set(value) { prefs.edit().putBoolean("allow_pocket",value).apply() }
}

/** Ephemeral diagnostic text: never written to disk or sent to a provider. */
object WakeDiagnostics {
    private val capture = MutableStateFlow(false)
    val listening = capture.asStateFlow()
    private val test = MutableStateFlow(false)
    val testing = test.asStateFlow()
    fun captureChanged(active: Boolean) { capture.value = active }
    fun testChanged(active: Boolean) { test.value = active }
    private val mutable = MutableStateFlow("Wake listener stopped")
    val status = mutable.asStateFlow()
    fun update(message: String) { mutable.value = message.take(400) }
}
