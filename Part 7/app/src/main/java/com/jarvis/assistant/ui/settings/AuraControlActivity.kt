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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

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
        val telemetry = TextView(this).apply {
            setTextColor(color(R.attr.themeTextPrimary)); textSize = 13f
            setPadding(0, dp(10), 0, dp(10))
        }
        root.addView(telemetry)
        root.addView(action("Background power policy") {
            val profiles = arrayOf("Battery Saver", "Balanced", "Performance")
            val values = arrayOf("saver", "balanced", "performance")
            MaterialAlertDialogBuilder(this).setTitle("App power policy")
                .setSingleChoiceItems(profiles, values.indexOf(preferences.powerProfile)) { dialog, which -> preferences.powerProfile = values[which]; dialog.dismiss() }
                .setNegativeButton("Cancel", null).show()
        })
        root.addView(action("System / Light / Dark") { ThemeManager.cycleHomeTheme(this) })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val battery = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
                    val level = battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val saver = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode
                    val memory = android.app.ActivityManager.MemoryInfo()
                    (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memory)
                    val batteryText = if (level in 0..100) "$level%" else "unavailable"
                    telemetry.text = "CORE TELEMETRY\nBattery: $batteryText · Android saver: ${if (saver) "ON" else "OFF"}\nAvailable RAM: ${memory.availMem / (1024 * 1024)} MB\nApp policy: ${preferences.powerProfile} · Scan interval: ${com.jarvis.assistant.util.DirectivePowerPolicy.scanIntervalMs(preferences.powerProfile, saver)} ms"
                    delay(15_000)
                }
            }
        }
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
