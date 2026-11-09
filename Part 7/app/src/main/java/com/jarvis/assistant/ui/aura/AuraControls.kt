package com.jarvis.assistant.ui.aura

import android.app.Activity
import android.content.Intent
import android.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp

object AuraControls {
    fun add(activity: Activity, root: LinearLayout) {
        fun button(title: String, action: () -> Unit) { root.addView(Button(activity).apply { text=title; isAllCaps=false; setOnClickListener { if (JarvisApp.instance.preferences.hapticFeedbackEnabled) performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); action() } },LinearLayout.LayoutParams(-1,-2)) }
        fun toggle(title: String, key: String, explanation: String? = null, changed: () -> Unit = {}) {
            val switch=androidx.appcompat.widget.SwitchCompat(activity).apply { text=title; isChecked=AuraOptions.enabled(key); minHeight=(48*resources.displayMetrics.density).toInt() }
            root.addView(switch)
            switch.setOnCheckedChangeListener { _, enabled ->
                if (enabled && explanation != null) MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(explanation)
                    .setPositiveButton("Enable") { _, _ -> AuraOptions.set(key,true); changed() }
                    .setNegativeButton("Keep off",null).setOnDismissListener { switch.isChecked=AuraOptions.enabled(key) }.show()
                else { AuraOptions.set(key,enabled); changed() }
            }
        }
        button("Offline wake word · Hey Jarvis") { activity.startActivity(Intent(activity,com.jarvis.assistant.ui.directives.WakeWordActivity::class.java)) }
        button("Emergency contact · optional fall monitoring") { activity.startActivity(Intent(activity,com.jarvis.assistant.emergency.EmergencyContactActivity::class.java)) }
        button("App icon · Default / Aura Red / Midnight / Crimson") {
            MaterialAlertDialogBuilder(activity).setTitle("Home-screen icon").setItems(AppIconSwitcher.icons.keys.toTypedArray()) { _, i ->
                runCatching { AppIconSwitcher.select(activity,AppIconSwitcher.icons.keys.elementAt(i)) }.onFailure { Toast.makeText(activity,"Launcher could not update the icon.",Toast.LENGTH_LONG).show() }
            }.show()
        }
        button("Conversation language · ${JarvisApp.instance.preferences.language}") {
            val languages=com.jarvis.assistant.data.model.GeminiConstants.SUPPORTED_LANGUAGES
            MaterialAlertDialogBuilder(activity).setTitle("Conversation language (provider support varies)").setSingleChoiceItems(languages.map { if(it=="Auto") "Auto (Hinglish / match speaker)" else it }.toTypedArray(),languages.indexOf(JarvisApp.instance.preferences.language)) { dialog, i ->
                JarvisApp.instance.preferences.language=languages[i]; dialog.dismiss()
                Toast.makeText(activity,"Language saved for the next voice connection.",Toast.LENGTH_SHORT).show()
            }.show()
        }
        button("Live video wallpapers · library & in-app toggle") { activity.startActivity(Intent(activity,com.jarvis.assistant.ui.wallpaper.VideoWallpaperActivity::class.java)) }
        button("Floating avatar · 114 combinations") { activity.startActivity(Intent(activity,AvatarStudioActivity::class.java)) }
        toggle("Background Agent (separate UI mission)","backgroundAgent","Allows user-requested long UI tasks to use the separate LLM mission planner while you keep talking. Accessibility labels may go to Gemini. Approvals and recovery remain in Mission Mode; concurrent UI writers are blocked.")
        toggle("Autonomous review · every ~30 minutes","autonomy","Sends battery and notification counts (not message bodies) to Gemini. Can post a suggestion; never sends messages or purchases automatically. Quiet 23:00–07:00 in your phone's timezone. Android scheduling is inexact and API quota may apply.") { AutonomyWorker.schedule(activity.applicationContext) }
        toggle("Speak autonomous suggestions during an active voice session","autonomySpeak")
        button("What AI decided · encrypted decision logs") { activity.startActivity(Intent(activity,DecisionLogsActivity::class.java)) }
        root.addView(androidx.appcompat.widget.SwitchCompat(activity).apply {
            text="Silent execution · final result only"; isChecked=JarvisApp.instance.preferences.isSilentExecutionMode
            setOnCheckedChangeListener { _, value -> JarvisApp.instance.preferences.isSilentExecutionMode=value }
        })
        toggle("Background Web Search","backgroundSearch","Questions are sent to Gemini with web grounding. Results include returned source links; no browser is opened. Provider access/quota is required.")
        toggle("Make images with Gemini","imageGeneration","Image prompts go to Gemini's image-generation model and may use paid quota. Generated images are saved to Pictures/Jarvis; sharing requires your command and WhatsApp's Send confirmation.")
        button("Open Gemini Image Studio") { activity.startActivity(Intent(activity,MediaStudioActivity::class.java)) }
        root.addView(androidx.appcompat.widget.SwitchCompat(activity).apply {
            text="Reduce background audio · hardware echo/noise filters"; isChecked=!AuraOptions.enabled("disableNoiseSuppression")
            setOnCheckedChangeListener { _, value -> AuraOptions.set("disableNoiseSuppression",!value); Toast.makeText(activity,"Applies on the next microphone session. Music/other voices cannot be perfectly isolated.",Toast.LENGTH_LONG).show() }
        })
        toggle("Show red tap indicator during automation","tapIndicator")
        button("Android 16 accessibility · status & permissions") { activity.startActivity(Intent(activity,com.jarvis.assistant.ui.permission.PermissionActivity::class.java)) }
    }
}
