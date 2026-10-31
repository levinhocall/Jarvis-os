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
        entry(root, "Neural Lens", "Capture or select a real image, run AI analysis, or scan visible screen text", true) {
            startActivity(Intent(this, com.jarvis.assistant.ui.vision.NeuralLensActivity::class.java))
        }
        entry(root, "Security & Permissions", "Review app permissions and access status", true) {
            startActivity(Intent(this, PermissionActivity::class.java))
        }
        entry(root, "Aura Control", "Choose Classic, Aura, Crimson or Midnight Orb and customize appearance", true) {
            startActivity(Intent(this, com.jarvis.assistant.ui.settings.AuraControlActivity::class.java))
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

        entry(root, "Notification History", "View recent notifications captured by the enabled listener", true) {
            val service = com.jarvis.assistant.automation.notification.JarvisNotificationListenerService
            val items = service.getRecentNotifications()
            val message = if (!service.isConnected()) {
                "Notification access is not connected. Enable Jarvis in Android Notification Access settings."
            } else if (items.isEmpty()) {
                "No notifications have been captured in this session."
            } else {
                items.take(20).joinToString("\\n\\n") { item ->
                    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(item.timestamp))
                    "$time • ${item.sender}\\n${item.message}\\n${item.packageName}"
                }
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Recent notifications")
                .setMessage(message)
                .setPositiveButton("Close", null)
                .setNeutralButton("Notification settings") { _, _ ->
                    startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                }
                .show()
        }
        entry(root, "AMOLED Map", "Native OpenStreetMap view, current location, destination search and route", true) {
            startActivity(Intent(this, com.jarvis.assistant.ui.map.AmoledMapActivity::class.java))
        }
        entry(root, "Video Wallpapers", "Import your own video, preview it, and request Android wallpaper activation", true) {
            startActivity(Intent(this, com.jarvis.assistant.ui.wallpaper.VideoWallpaperActivity::class.java))
        }

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
