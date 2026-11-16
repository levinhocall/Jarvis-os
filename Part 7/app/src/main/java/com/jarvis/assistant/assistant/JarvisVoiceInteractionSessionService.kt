package com.jarvis.assistant.assistant

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.directives.VoiceModeActivity

class JarvisVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JarvisAssistantSession(this)
}

/** No screen scraping, recognition service or microphone is started by this adapter. */
internal class JarvisAssistantSession(context: android.content.Context) : VoiceInteractionSession(context) {
    private var launching = false
    override fun onCreateContentView(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        addView(TextView(context).apply { setText(R.string.system_assistant_unlock); textSize = 18f })
        addView(Button(context).apply {
            setText(R.string.system_assistant_open)
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { openVoice() }
        })
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        openVoice()
    }

    private fun openVoice() {
        if (launching || context.getSystemService(KeyguardManager::class.java).isDeviceLocked) return
        launching = true
        try {
            startVoiceActivity(Intent(context, VoiceModeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            hide()
        } catch (_: SecurityException) {
            launching = false
        } catch (_: android.content.ActivityNotFoundException) {
            launching = false
        }
    }

    override fun onHide() { launching = false; super.onHide() }
}
