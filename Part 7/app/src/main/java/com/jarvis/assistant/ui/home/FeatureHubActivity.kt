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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        entry(root, "Mission Mode", "Inspect and control active accessibility tasks and durable missions", true) {
            val agentEngine = com.jarvis.assistant.automation.agent.AgentTaskEngine
            val task = agentEngine.taskState.value
            val mission = com.jarvis.assistant.automation.mission.MissionEngine.activeMission.value
            val sections = mutableListOf<String>()
            task?.let {
                sections += "ACCESSIBILITY TASK\\n${it.originalGoal}\\n${it.progressPercentage}% • ${it.currentStatus.replace('_', ' ')}"
            }
            mission?.let {
                sections += "DURABLE MISSION\\n${it.goal}\\n${it.status.name.replace('_', ' ')} • ${it.steps.count { step -> step.status == com.jarvis.assistant.automation.mission.MissionStepStatus.COMPLETED }}/${it.steps.size} steps verified\\n${it.outcomeSummary.orEmpty()}"
            }
            val message = sections.joinToString("\\n\\n").ifBlank {
                "No active accessibility task or multi-step mission."
            }
            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Mission Control")
                .setMessage(message)
                .setPositiveButton("Close", null)
            if (task != null) {
                if (agentEngine.isPaused.value) {
                    dialog.setNeutralButton("Resume Agent") { _, _ ->
                        val resumed = agentEngine.resumeTask()
                        android.widget.Toast.makeText(this, if (resumed) "Agent resumed" else "Agent could not resume", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    dialog.setNeutralButton("Pause Agent") { _, _ ->
                        val paused = agentEngine.pauseTask()
                        android.widget.Toast.makeText(this, if (paused) "Agent paused" else "Agent cannot be paused", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            if (mission != null && mission.status !in setOf(
                    com.jarvis.assistant.automation.mission.MissionOverallStatus.COMPLETED,
                    com.jarvis.assistant.automation.mission.MissionOverallStatus.FAILED,
                    com.jarvis.assistant.automation.mission.MissionOverallStatus.CANCELLED
                )) {
                dialog.setNegativeButton("Cancel Mission") { _, _ ->
                    lifecycleScope.launch {
                        com.jarvis.assistant.automation.mission.MissionEngine.cancelMission(mission.missionId)
                    }
                }
            } else if (task != null) {
                dialog.setNegativeButton("Cancel Agent") { _, _ -> agentEngine.requestCancellation() }
            }
            dialog.show()
        }
        entry(root, "Mission History", "Review persisted multi-step missions and their verified outcomes", true) {
            lifecycleScope.launch {
                val missions = withContext(Dispatchers.IO) {
                    com.jarvis.assistant.JarvisApp.instance.memoryDatabase.missionDao().getAllMissions()
                }
                val summary = if (missions.isEmpty()) {
                    "No saved missions yet."
                } else {
                    missions.take(20).joinToString("\n\n") { mission ->
                        val updated = java.text.DateFormat.getDateTimeInstance(
                            java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                        ).format(java.util.Date(mission.updatedAt))
                        "${mission.status.replace('_', ' ')} • $updated\n${mission.goal.take(180)}\n${mission.outcomeSummary.orEmpty().take(220)}"
                    }
                }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@FeatureHubActivity)
                    .setTitle("Mission History")
                    .setMessage(summary)
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
        entry(root, "AI Personalization", "Configure assistant personality and response style", true) {
            startActivity(Intent(this, com.jarvis.assistant.ui.settings.PersonaActivity::class.java))
        }
        entry(root, "Notification Access", "Open Android notification-listener settings", true) {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }

        entry(root, "Notification History", "Encrypted, opt-in history with unread filtering and clear controls", true) {
            startActivity(Intent(this, com.jarvis.assistant.ui.notification.NotificationHistoryActivity::class.java))
        }
        entry(root, "AMOLED Map", "Native OpenStreetMap view, current location, destination search and route", true) {
            startActivity(Intent(this, com.jarvis.assistant.ui.map.AmoledMapActivity::class.java))
        }
        entry(root, "Video Wallpapers", "Import your own video, preview it, and request Android wallpaper activation", true) {
            startActivity(Intent(this, com.jarvis.assistant.ui.wallpaper.VideoWallpaperActivity::class.java))
        }

        entry(root, "Memory & Context", "Review persistent memories and manage assistant recall", true) {
            startActivity(Intent(this, com.jarvis.assistant.ui.memory.MemoryActivity::class.java))
        }
        entry(root, "Floating Avatar & Character", "Configure the assistant overlay, character and display behavior", true) {
            startActivity(Intent(this, com.jarvis.assistant.ui.settings.OverlayAvatarActivity::class.java))
        }
        entry(root, "Voice Identity", "Enroll or manage the owner voice profile", true) {
            startActivity(Intent(this, com.jarvis.assistant.ui.voice.VoiceEnrollmentActivity::class.java))
        }
        entry(root, "Hands-Free Voice", "Configure hands-free and voice session behavior", true) {
            startActivity(Intent(this, com.jarvis.assistant.ui.settings.HandsFreeActivity::class.java))
        }
        entry(root, "Privacy Shield", "Review privacy controls and sensitive-data protections", true) {
            startActivity(Intent(this, com.jarvis.assistant.ui.settings.PrivacyShieldActivity::class.java))
        }
        entry(root, "AI Provider Connections", "Manage provider credentials and connection settings", true) {
            startActivity(Intent(this, com.jarvis.assistant.ui.settings.ApiConnectionActivity::class.java))
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
