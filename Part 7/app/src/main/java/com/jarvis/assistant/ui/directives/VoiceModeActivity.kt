package com.jarvis.assistant.ui.directives

import android.Manifest
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import kotlinx.coroutines.launch

/** Native audio-reactive presentation over the existing service, never a second microphone pipeline. */
class VoiceModeActivity : AppCompatActivity() {
    private lateinit var orb: PremiumOrbView
    private lateinit var status: TextView
    private var attemptedStart = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startVoice() else status.text = "Microphone access is required for live conversation."
    }
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        attemptedStart = saved?.getBoolean("attempted") ?: false
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        if (com.jarvis.assistant.util.DirectivePowerPolicy.backgroundAllowed((application as JarvisApp).preferences.powerProfile, (getSystemService(POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(Color.BLACK) }
        fun text(size: Float) = TextView(this).apply { textSize = size; setTextColor(Color.WHITE); root.addView(this) }
        text(22f).text = "JARVIS · LIVE VOICE"
        status = text(14f)
        orb = PremiumOrbView(this)
        root.addView(orb, LinearLayout.LayoutParams(-1, 0, 1f))
        val user = text(16f).apply { maxLines = 3 }
        val assistant = text(18f).apply { maxLines = 5 }
        val controls = GridLayout(this).apply { columnCount = 3 }
        var controlIndex = 0
        fun button(title: String, action: () -> Unit) {
            val index = controlIndex++
            controls.addView(Button(this).apply { this.text = title; textSize = 11f; isAllCaps = false; setOnClickListener { action() } },
                GridLayout.LayoutParams().apply { width = 0; height = -2; columnSpec = GridLayout.spec(index % 3, 1f); rowSpec = GridLayout.spec(index / 3) })
        }
        button("Start / resume conversation") { startVoice() }
        button("Interrupt response") { JarvisConversationService.interruptPlayback() }
        button("Mute / unmute microphone") {
            val prefs = (application as JarvisApp).preferences
            prefs.isMicMuted = !prefs.isMicMuted
            JarvisConversationService.setMicMuted(prefs.isMicMuted)
            status.text = if (prefs.isMicMuted) "Microphone muted" else "Microphone active"
        }
        button("End conversation") {
            if (JarvisConversationService.isSessionOn.value) send(JarvisConversationService.ACTION_STOP_ALL)
        }
        button("‹ Main deck") { finish() }
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(24 + bars.left, 24 + bars.top, 24 + bars.right, 24 + bars.bottom)
            insets
        }
        androidx.core.view.WindowInsetsControllerCompat(window, root).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { JarvisConversationService.audioLevel.collect { orb.level = it } }
            launch { JarvisConversationService.conversationState.collect { status.text = it.name.replace('_', ' ') } }
            launch { JarvisConversationService.liveUserTranscript.collect { user.text = it } }
            launch { JarvisConversationService.liveAssistantResponse.collect { assistant.text = it } }
            launch { JarvisConversationService.liveError.collect { if (it != null) status.text = "Live voice could not connect. Check AI Provider Connections." } }
        } }
    }
    override fun onResume() { super.onResume(); if (!attemptedStart) { attemptedStart = true; startVoice() } }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("attempted", attemptedStart); super.onSaveInstanceState(outState) }
    private fun startVoice() {
        if (JarvisConversationService.isSessionOn.value) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissions.launch(Manifest.permission.RECORD_AUDIO); return
        }
        val prefs = (application as JarvisApp).preferences
        if ((prefs.getNextActiveKey("gemini") ?: prefs.apiKey).isBlank()) {
            status.text = "Configure Gemini in AI Provider Connections to start live voice."
            return
        }
        try { send(JarvisConversationService.ACTION_START_CONVERSATION) }
        catch (_: Exception) { status.text = "Android could not start the microphone service. Check permissions and try again." }
    }
    private fun send(action: String) {
        ContextCompat.startForegroundService(this, Intent(this, JarvisConversationService::class.java).setAction(action))
    }
    private class AudioOrb(context: android.content.Context) : View(context) {
        var level = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f
            val normalized = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
            val radius = minOf(width, height) * (0.23f + normalized * 0.09f)
            paint.style = Paint.Style.FILL; paint.color = Color.rgb(10, 60, 85); paint.alpha = 90
            canvas.drawCircle(cx, cy, radius * 1.35f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = Color.CYAN; paint.alpha = 220
            canvas.drawCircle(cx, cy, radius, paint)
            paint.alpha = 100
            canvas.drawOval(cx - radius * 1.3f, cy - radius * 0.45f, cx + radius * 1.3f, cy + radius * 0.45f, paint)
            paint.style = Paint.Style.FILL; paint.alpha = 230
            canvas.drawCircle(cx, cy, radius * (0.3f + normalized * 0.3f), paint)
        }
    }
}
