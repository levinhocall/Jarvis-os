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
        entry(root, "Mission Status", "Inspect the current agent task progress", true) {
            val task = com.jarvis.assistant.automation.agent.AgentTaskEngine.taskState.value
            val message = task?.let {
                "${it.originalGoal}\n\n${it.progressPercentage}% • ${it.currentStatus.replace('_', ' ')}"
            } ?: "No active agent task."
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Agent status")
                .setMessage(message)
                .setPositiveButton("Close", null)
                .show()
        }
        entry(root, "Notification Access", "Open Android notification-listener settings", true) {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
        entry(root, "AMOLED Map", "A map provider is not configured in this build", false) {}
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
