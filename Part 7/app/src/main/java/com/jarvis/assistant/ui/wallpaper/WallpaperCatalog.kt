package com.jarvis.assistant.ui.wallpaper

import android.content.Context
import android.net.Uri

object WallpaperCatalog {
    val names = listOf("Aurora", "Neon Drift", "Ember", "Skyline", "Frost Muse", "Crimson Tide", "Violet Gaze", "Fallen Angel", "Inferno", "Red Dusk", "Cobalt Dreamer", "Midnight Lace", "Sakura Blush", "Shadow Call", "Golden Hour", "Frozen Tear")
    fun uri(context: Context, name: String): Uri {
        require(name in names)
        val id=context.resources.getIdentifier("aura_"+name.lowercase(java.util.Locale.ROOT).replace(' ','_'),"raw",context.packageName)
        check(id != 0) { "Wallpaper asset missing" }
        return Uri.parse("android.resource://${context.packageName}/$id")
    }
}
