package com.jarvis.assistant.ui.settings

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager

/** Persistent Orb appearance controls; all choices reuse the existing WebView Orb renderer. */
class AuraControlActivity : AppCompatActivity() {
    private val preferences by lazy { JarvisApp.instance.preferences }
    private lateinit var selected: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(ThemeManager.getThemeBgColor(preferences.appTheme, preferences.customThemeColor))
        }
        root.addView(TextView(this).apply {
            text = "AURA CONTROL"
            textSize = 23f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(color(R.attr.themeTextPrimary))
        })
        root.addView(TextView(this).apply {
            text = "Choose a persistent Orb identity. Your selection is saved immediately."
            textSize = 13f
            setPadding(0, dp(8), 0, dp(16))
            setTextColor(color(R.attr.themeTextMuted))
        })
        selected = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, dp(12))
            setTextColor(color(R.attr.themeTextPrimary))
        }
        root.addView(selected)
        listOf(
            "classic" to "Classic Orb",
            "aura" to "Aura Orb",
            "crimson" to "Crimson Orb",
            "midnight" to "Midnight Orb"
        ).forEach { (key, label) ->
            root.addView(action(label) {
                preferences.orbPreset = key
                preferences.appTheme = when (key) {
                    "crimson" -> "red"
                    "aura", "midnight" -> "dark"
                    else -> "sunset"
                }
                selected.text = "Active Orb: $label"
                ThemeManager.applyTheme(this)
                root.setBackgroundColor(ThemeManager.getThemeBgColor(preferences.appTheme, preferences.customThemeColor))
            })
        }
        root.addView(action("Customize accent color") { showAccentDialog() })
        root.addView(action(if (preferences.hapticFeedbackEnabled) "Haptic feedback: ON" else "Haptic feedback: OFF") {
            preferences.hapticFeedbackEnabled = !preferences.hapticFeedbackEnabled
            it.text = if (preferences.hapticFeedbackEnabled) "Haptic feedback: ON" else "Haptic feedback: OFF"
            if (preferences.hapticFeedbackEnabled) it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        })
        setContentView(android.widget.ScrollView(this).apply { addView(root) })
        renderSelection()
    }

    private fun renderSelection() {
        selected.text = "Active Orb: " + when (preferences.orbPreset) {
            "aura" -> "Aura Orb"
            "crimson" -> "Crimson Orb"
            "midnight" -> "Midnight Orb"
            else -> "Classic Orb"
        }
    }

    private fun showAccentDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "#RRGGBB"
            setText(preferences.customThemeColor)
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Accent / custom theme color")
            .setMessage("Enter a valid hexadecimal color such as #35C8FF.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val value = input.text.toString().trim()
                try {
                    android.graphics.Color.parseColor(value)
                    preferences.customThemeColor = value
                    preferences.appTheme = "custom"
                    ThemeManager.applyTheme(this)
                    recreate()
                } catch (_: IllegalArgumentException) {
                    android.widget.Toast.makeText(this, "Invalid color. Use #RRGGBB.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun action(label: String, onAction: (Button) -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener {
            if (preferences.hapticFeedbackEnabled) performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onAction(this)
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }

    private fun color(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
