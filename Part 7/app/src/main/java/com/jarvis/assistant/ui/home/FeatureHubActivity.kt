package com.jarvis.assistant.ui.home

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.permission.PermissionActivity
import kotlinx.coroutines.launch

/** Feature launcher backed only by capabilities already present in this build. */
class FeatureHubActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(com.jarvis.assistant.util.ThemeManager.getThemeBgColor(
                com.jarvis.assistant.JarvisApp.instance.preferences.appTheme,
                com.jarvis.assistant.JarvisApp.instance.preferences.customThemeColor
            ))
        }
        root.addView(TextView(this).apply {
            text = "JARVIS FEATURE HUB"
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(themeColor(R.attr.themeTextPrimary))
        })
        root.addView(TextView(this).apply {
            text = "Assistant tools and system controls"
            textSize = 13f
            setTextColor(themeColor(R.attr.themeTextMuted))
            setPadding(0, dp(6), 0, dp(18))
        })

        entry(root, "Voice Mode", "Start or interrupt the live voice assistant", true) {
            finish()
            MainActivity.activeInstance?.handleMicOrOrbClick()
        }
        entry(root, "Neural Lens", "Run the existing on-screen OCR and tactical scanner", true) {
            lifecycleScope.launch {
                val result = com.jarvis.assistant.vision.TacticalScannerManager.scanCurrentScreen(this@FeatureHubActivity)
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@FeatureHubActivity)
                    .setTitle("Screen scan result")
                    .setMessage(result.summary)
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
        entry(root, "Security & Permissions", "Review app permissions and access status", true) {
            startActivity(Intent(this, PermissionActivity::class.java))
        }
        entry(root, "Aura Control", "Open existing appearance and assistant settings", true) {
            finish()
            MainActivity.activeInstance?.selectTab(MainActivity.TAB_SETTINGS)
        }
        entry(root, "Mission Mode", "Inspect and control the active agent task", true) {
            val engine = com.jarvis.assistant.automation.agent.AgentTaskEngine
            val task = engine.taskState.value
            val message = task?.let {
                "${it.originalGoal}\n\n${it.progressPercentage}% • ${it.currentStatus.replace('_', ' ')}"
            } ?: "No active mission. Start a task through the assistant chat or voice command."
            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Mission control")
                .setMessage(message)
                .setPositiveButton("Close", null)
            if (task != null) {
                if (engine.isPaused.value) {
                    dialog.setNeutralButton("Resume") { _, _ ->
                        val resumed = engine.resumeTask()
                        android.widget.Toast.makeText(this, if (resumed) "Mission resumed" else "Mission could not resume", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    dialog.setNeutralButton("Pause") { _, _ ->
                        val paused = engine.pauseTask()
                        android.widget.Toast.makeText(this, if (paused) "Mission paused" else "Mission cannot be paused", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                dialog.setNegativeButton("Cancel mission") { _, _ -> engine.requestCancellation() }
            }
            dialog.show()
        }

        entry(root, "AI Personalization", "Configure assistant personality and response style", true) {
            startActivity(Intent(this, com.jarvis.assistant.ui.settings.PersonaActivity::class.java))
        }
        entry(root, "Notification Access", "Open Android notification-listener settings", true) {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
        entry(root, "AMOLED Map", "Search a destination and hand off live routing to an installed maps app", true) {
            val destinationInput = android.widget.EditText(this).apply {
                hint = "Enter destination or place"
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Navigate to")
                .setMessage("Jarvis will open an installed maps app. Your device location and live route are provided by that app; Jarvis does not fabricate coordinates.")
                .setView(destinationInput)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open navigation") { _, _ ->
                    val destination = destinationInput.text?.toString()?.trim().orEmpty()
                    if (destination.isBlank()) {
                        android.widget.Toast.makeText(this, "Enter a destination first", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        val navigationUri = android.net.Uri.parse("google.navigation:q=" + android.net.Uri.encode(destination))
                        val navigationIntent = Intent(Intent.ACTION_VIEW, navigationUri)
                        try {
                            startActivity(navigationIntent)
                        } catch (_: android.content.ActivityNotFoundException) {
                            val searchUri = android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(destination))
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, searchUri))
                            } catch (_: android.content.ActivityNotFoundException) {
                                android.widget.Toast.makeText(this, "No compatible maps app is installed", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                .show()
        }
        entry(root, "Video Wallpapers", "A live wallpaper service is not configured in this build", false) {}

        setContentView(android.widget.ScrollView(this).apply { addView(root) })
    }

    private fun entry(parent: LinearLayout, title: String, detail: String, enabled: Boolean, action: () -> Unit) {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = dp(2).toFloat()
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.55f
            setCardBackgroundColor(themeColor(R.attr.themeCardBg))
            setOnClickListener { if (enabled) action() }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        content.addView(TextView(this).apply {
            text = title
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(themeColor(R.attr.themeTextPrimary))
        })
        content.addView(TextView(this).apply {
            text = detail
            textSize = 12f
            setTextColor(themeColor(R.attr.themeTextMuted))
            setPadding(0, dp(5), 0, 0)
        })
        card.addView(content)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun themeColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId)
        else value.data
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
