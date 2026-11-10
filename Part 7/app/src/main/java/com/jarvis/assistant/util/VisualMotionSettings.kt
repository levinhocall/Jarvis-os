package com.jarvis.assistant.util

import android.animation.ValueAnimator
import android.content.Context

/** One inexpensive UI policy shared by existing native and WebView renderers. */
object VisualMotionSettings {
    fun preference(context: Context): Boolean = context.getSharedPreferences("jarvis_visual_settings", Context.MODE_PRIVATE)
        .getBoolean("reduced_motion", false)
    fun setPreference(context: Context, enabled: Boolean) {
        context.getSharedPreferences("jarvis_visual_settings", Context.MODE_PRIVATE).edit().putBoolean("reduced_motion", enabled).apply()
    }
    fun isReduced(context: Context): Boolean = preference(context) || !ValueAnimator.areAnimatorsEnabled()
}
