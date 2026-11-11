package com.jarvis.assistant.ui.orb

import android.content.Context
import android.app.ActivityManager
import android.os.PowerManager
import com.jarvis.assistant.JarvisApp
import org.json.JSONObject

object OrbAppearance {
    fun configuration(context: Context): JSONObject {
        val prefs = JarvisApp.instance.preferences
        val stored = runCatching { JSONObject(prefs.orbEyeOptions) }.getOrDefault(JSONObject())
        val low = context.getSystemService(ActivityManager::class.java).isLowRamDevice
        val saver = context.getSystemService(PowerManager::class.java).isPowerSaveMode || prefs.powerProfile == "saver"
        val result = JSONObject()
        for ((key, range) in mapOf("intensity" to (0.3..1.5), "glow" to (0.0..1.5), "density" to (0.0..1.0), "movement" to (0.0..1.0), "speed" to (0.4..1.6), "size" to (0.7..1.15))) {
            val value = stored.optDouble(key, 1.0)
            result.put(key, if(value.isFinite()) value.coerceIn(range.start, range.endInclusive) else 1.0)
        }
        result.put("blink", stored.optBoolean("blink", true))
        result.put("quality", if(saver || low) "saver" else prefs.powerProfile)
        if (saver || low) result.put("density", minOf(result.getDouble("density"), 0.3))
        return result
    }
}
