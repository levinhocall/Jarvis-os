package com.jarvis.assistant.ui.orb

import com.jarvis.assistant.JarvisApp
import org.json.JSONObject

enum class OrbStyle { CORE, GHOST, AVATAR }

/** Uses the existing persisted appearance blob; no second DataStore/preferences file. */
object OrbSettings {
    const val STYLE_KEY="voice_core_style"
    var style:OrbStyle
        get()=runCatching {OrbStyle.valueOf(JSONObject(JarvisApp.instance.preferences.orbEyeOptions).optString(STYLE_KEY,"CORE"))}.getOrDefault(OrbStyle.CORE)
        set(value){val prefs=JarvisApp.instance.preferences;val json=runCatching {JSONObject(prefs.orbEyeOptions)}.getOrDefault(JSONObject());json.put(STYLE_KEY,value.name);prefs.orbEyeOptions=json.toString()}
}
