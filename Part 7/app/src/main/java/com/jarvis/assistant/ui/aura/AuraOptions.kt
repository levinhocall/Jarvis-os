package com.jarvis.assistant.ui.aura

import com.jarvis.assistant.JarvisApp

object AuraOptions {
    private val prefs get() = JarvisApp.instance.getSharedPreferences("aura_options", 0)
    fun enabled(key: String) = prefs.getBoolean(key, false)
    fun set(key: String, enabled: Boolean) { prefs.edit().putBoolean(key, enabled).apply() }
    var avatarStyle: String
        get() = prefs.getString("avatar_style", "plain") ?: "plain"
        set(value) { require(value in styles); prefs.edit().putString("avatar_style", value).apply() }
    var avatarSize: Int
        get() = prefs.getInt("avatar_size", 88).coerceIn(48,144)
        set(value) { prefs.edit().putInt("avatar_size", value.coerceIn(48,144)).apply() }
    val shapes = listOf("alien","blob","bot","cat","circle","cloud","clover","droid","drop","flower","ghost","hexagon","mech","pebble","pill","puddle","square","star","triangle")
    val styles = listOf("plain","crown","party","shades","headphones","bow")
}
