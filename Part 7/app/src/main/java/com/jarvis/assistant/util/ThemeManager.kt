package com.jarvis.assistant.util

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.core.view.WindowInsetsControllerCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R

object ThemeManager {

    fun resolveThemeKey(context: android.content.Context, key: String): String =
        if (key != "system") key else if (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES) "dark" else "light"

    fun applyNightPreference(key: String) {
        val mode = when (key) {
            "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun cycleHomeTheme(context: android.content.Context): String {
        val preferences = JarvisApp.instance.preferences
        val previous = preferences.appTheme
        val next = HomeThemePolicy.next(previous)
        preferences.appTheme = next
        applyNightPreference(next)
        // AppCompat performs the required resource rebind; voice/database services retain ownership.
        if (context is Activity) {
            applyTheme(context)
            if (previous !in setOf("system", "light", "dark")) context.recreate()
        }
        return next
    }

    fun isLight(colorInt: Int): Boolean {
        val r = Color.red(colorInt) / 255.0
        val g = Color.green(colorInt) / 255.0
        val b = Color.blue(colorInt) / 255.0
        val lum = 0.299 * r + 0.587 * g + 0.114 * b
        return lum > 0.55
    }

    fun getThemeBgColor(themeKey: String, customHex: String?): Int {
        val resolved = if (themeKey == "system") runCatching { resolveThemeKey(JarvisApp.instance, themeKey) }.getOrDefault("light") else themeKey
        return when (resolved) {
            "dark" -> Color.parseColor("#050B16")
            "red" -> Color.parseColor("#0E0307")
            "sunset" -> Color.parseColor("#FFF0EA")
            "custom" -> try { Color.parseColor(customHex ?: "#FFFFFF") } catch (_: Exception) { Color.WHITE }
            else -> Color.parseColor("#EEF2F9")
        }
    }

    fun applyTheme(activity: Activity) {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        val themeKey = resolveThemeKey(activity, prefs?.appTheme ?: "sunset")

        val (themeRes, isLightStatusBar, bgColor) = when (themeKey) {
            "dark" -> Triple(R.style.Theme_JarvisAssistant_Dark, false, Color.parseColor("#050B16"))
            "red" -> Triple(R.style.Theme_JarvisAssistant_Red, false, Color.parseColor("#0E0307"))
            "sunset" -> Triple(R.style.Theme_JarvisAssistant_Sunset, true, Color.parseColor("#FFF0EA"))
            "custom" -> {
                val customHex = prefs?.customThemeColor ?: "#FFFFFF"
                val parsedColor = try { Color.parseColor(customHex) } catch (_: Exception) { Color.WHITE }
                val light = isLight(parsedColor)
                val res = if (light) R.style.Theme_JarvisAssistant_CustomLight else R.style.Theme_JarvisAssistant_CustomDark
                Triple(res, light, parsedColor)
            }
            else -> Triple(R.style.Theme_JarvisAssistant_Light, true, Color.parseColor("#EEF2F9"))
        }

        activity.setTheme(themeRes)
        activity.window.setBackgroundDrawable(ColorDrawable(bgColor))
        activity.window.decorView.setBackgroundColor(bgColor)
        activity.window.statusBarColor = bgColor
        activity.window.navigationBarColor = bgColor

        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = isLightStatusBar
        controller.isAppearanceLightNavigationBars = isLightStatusBar

        try {
            activity.findViewById<View>(android.R.id.content)?.setBackgroundColor(bgColor)
        } catch (_: Exception) {}
        activity.window.decorView.post {
            try {
                activity.findViewById<View>(android.R.id.content)?.setBackgroundColor(bgColor)
            } catch (_: Exception) {}
        }
    }

    fun applyDynamicBackground(view: View) {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        val themeKey = prefs?.appTheme ?: "sunset"
        if (resolveThemeKey(view.context, themeKey) == "dark") {
            view.setBackgroundResource(R.drawable.bg_premium_night)
            return
        }
        val customHex = prefs?.customThemeColor ?: "#FFFFFF"
        val color = getThemeBgColor(themeKey, customHex)
        view.setBackgroundColor(color)
    }
}
