package com.jarvis.assistant.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.permission.PermissionActivity
import com.jarvis.assistant.ui.directives.DirectiveGrid
import com.jarvis.assistant.ui.directives.DirectiveId
import com.jarvis.assistant.ui.directives.MissionDeskActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Feature launcher backed only by capabilities already present in this build. */
class FeatureHubActivity : AppCompatActivity() {
    private val searchableCards = mutableListOf<Pair<String, View>>()

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
            setText(R.string.workspace_title)
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(themeColor(R.attr.themeTextPrimary))
        })
        root.addView(TextView(this).apply {
            setText(R.string.workspace_subtitle)
            textSize = 13f
            setTextColor(themeColor(R.attr.themeTextMuted))
            setPadding(0, dp(6), 0, dp(18))
        })

        val search = EditText(this).apply {
            id = R.id.workspaceSearch
            hint = getString(R.string.workspace_search)
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setTextColor(themeColor(R.attr.themeTextPrimary))
            setHintTextColor(themeColor(R.attr.themeTextMuted))
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        entry(root, DirectiveId.VOICE.title, getString(R.string.workspace_voice_detail), true) {
            DirectiveGrid.open(this, DirectiveId.VOICE)
        }
        entry(root, getString(R.string.workflow_library), getString(R.string.workspace_workflows_detail), true) {
            startActivity(MissionDeskActivity.libraryIntent(this))
        }
        DirectiveId.entries.filterNot { it == DirectiveId.VOICE }.forEach { directive ->
            entry(root, directive.title, directive.description, true) {
                if (directive == DirectiveId.THEME) com.jarvis.assistant.util.ThemeManager.cycleHomeTheme(this)
                else DirectiveGrid.open(this, directive)
            }
        }
        entry(root, "Security & Permissions", "Review app permissions and access status", true) {
            startActivity(Intent(this, PermissionActivity::class.java))
        }
        entry(root, "Accessibility Task Controls", "Inspect and control active accessibility tasks and durable missions", true) {
            val agentEngine = com.jarvis.assistant.automation.agent.AgentTaskEngine
            val task = agentEngine.taskState.value
            val mission = com.jarvis.assistant.automation.mission.MissionEngine.activeMission.value
            val sections = mutableListOf<String>()
            task?.let {
                sections += "ACCESSIBILITY TASK\n${it.originalGoal}\n${it.progressPercentage}% • ${it.currentStatus.replace('_', ' ')}"
            }
            mission?.let {
                sections += "DURABLE MISSION\n${it.goal}\n${it.status.name.replace('_', ' ')} • ${it.steps.count { step -> step.status == com.jarvis.assistant.automation.mission.MissionStepStatus.COMPLETED }}/${it.steps.size} steps verified\n${it.outcomeSummary.orEmpty()}"
            }
            val message = sections.joinToString("\n\n").ifBlank {
                "No active accessibility task or multi-step mission."
            }
            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Mission Control")
                .setMessage(message)
                .setPositiveButton("Close", null)
            if (mission != null && mission.status in setOf(
                    com.jarvis.assistant.automation.mission.MissionOverallStatus.EXECUTING,
                    com.jarvis.assistant.automation.mission.MissionOverallStatus.VERIFYING
                )) {
                dialog.setNeutralButton("Pause Mission") { _, _ ->
                    lifecycleScope.launch {
                        val paused = com.jarvis.assistant.automation.mission.MissionEngine.pauseMission(mission.missionId)
                        android.widget.Toast.makeText(this@FeatureHubActivity, if (paused) "Mission pause requested" else "Mission could not be paused", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } else if (mission?.status == com.jarvis.assistant.automation.mission.MissionOverallStatus.PAUSED) {
                dialog.setNeutralButton("Resume Mission") { _, _ ->
                    lifecycleScope.launch {
                        val resumed = com.jarvis.assistant.automation.mission.MissionEngine.resumeMission(this@FeatureHubActivity, mission.missionId)
                        android.widget.Toast.makeText(this@FeatureHubActivity, if (resumed) "Mission resumed" else "Mission could not resume safely", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } else if (task != null) {
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
            if (mission?.status == com.jarvis.assistant.automation.mission.MissionOverallStatus.UNVERIFIED) {
                dialog.setNegativeButton("Acknowledge & Clear") { _, _ ->
                    lifecycleScope.launch {
                        val acknowledged = com.jarvis.assistant.automation.mission.MissionEngine.acknowledgeUnverifiedMission(mission.missionId)
                        android.widget.Toast.makeText(this@FeatureHubActivity, if (acknowledged) "Unverified outcome acknowledged" else "Could not save acknowledgement; mission remains locked", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } else if (mission != null && mission.status !in setOf(
                    com.jarvis.assistant.automation.mission.MissionOverallStatus.COMPLETED,
                    com.jarvis.assistant.automation.mission.MissionOverallStatus.PARTIALLY_COMPLETED,
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
                val missions = try {
                    withContext(Dispatchers.IO) {
                        com.jarvis.assistant.JarvisApp.instance.memoryDatabase.missionDao().getAllMissions()
                    }
                } catch (_: Exception) {
                    android.widget.Toast.makeText(
                        this@FeatureHubActivity,
                        "Mission history could not be loaded. Check database availability and try again.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@launch
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

        entry(root, getString(R.string.studio_title), getString(R.string.studio_tool_detail), true) {
            startActivity(Intent(this, com.jarvis.assistant.ui.documents.DocumentStudioActivity::class.java))
        }
        entry(root, "JARVIS Browser", "Browse HTTPS pages and read selected page text with an offline voice", true) {
            startActivity(Intent(this, com.jarvis.assistant.ui.browser.JarvisBrowserActivity::class.java))
        }
        entry(root, "Plasma Live Wallpaper", "Preview the touch-reactive core in Android’s wallpaper picker; no microphone starts", true) {
            runCatching {
                startActivity(Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                    android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    android.content.ComponentName(this, com.jarvis.assistant.wallpaper.JarvisWallpaperService::class.java)))
            }.onFailure { android.widget.Toast.makeText(this,"Live wallpaper selection is unavailable on this device.",android.widget.Toast.LENGTH_LONG).show() }
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

        val empty = TextView(this).apply {
            setText(R.string.workspace_no_results)
            setTextColor(themeColor(R.attr.themeTextMuted))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        root.addView(empty)
        search.doAfterTextChanged { text ->
            val terms = text.toString().trim().split(Regex("\\s+")).filter(String::isNotBlank)
            var hasMatches = false
            searchableCards.forEach { (label, card) ->
                val visible = terms.all { label.contains(it, ignoreCase = true) }
                card.visibility = if (visible) View.VISIBLE else View.GONE
                hasMatches = hasMatches || visible
            }
            empty.visibility = if (hasMatches) View.GONE else View.VISIBLE
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isFocusableInTouchMode = true
            addView(root)
        }
        setContentView(scroll)
        scroll.requestFocus()
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
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
        searchableCards.add("$title $detail" to card)
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
