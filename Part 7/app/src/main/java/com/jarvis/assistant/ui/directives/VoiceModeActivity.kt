package com.jarvis.assistant.ui.directives

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.AudioTimingConfidence
import com.jarvis.assistant.databinding.ActivityVoiceModeBinding
import com.jarvis.assistant.ui.orb.OrbPresentationLabels
import com.jarvis.assistant.ui.orb.OrbPresentationMapper
import kotlinx.coroutines.launch

/** XML/ViewBinding presentation; the existing service alone owns microphone and session lifetime. */
class VoiceModeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVoiceModeBinding
    private var attemptedStart = false
    private var transcriptExpanded = false
    private var localExplanation = 0
    private fun explain(resource: Int) {
        localExplanation = resource
        binding.voiceReason.setText(resource)
        binding.permissionSettings.visibility = if (resource == R.string.plasma_permission) View.VISIBLE else View.GONE
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (com.jarvis.assistant.util.PermissionHelper.hasAllRequiredPermissions(this)) startVoice() else explain(R.string.plasma_permission)
    }

    override fun onCreate(saved: Bundle?) {
        setTheme(R.style.Theme_JarvisAssistant_Voice)
        super.onCreate(saved)
        attemptedStart = saved?.getBoolean("attempted") ?: false
        transcriptExpanded = saved?.getBoolean("transcript") ?: false
        localExplanation = saved?.getInt("explanation") ?: 0
        binding = ActivityVoiceModeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (!com.jarvis.assistant.util.VisualMotionSettings.isReduced(this)) {
            binding.root.alpha = 0f
            binding.root.animate().alpha(1f).setDuration(240).start()
        }
        binding.permissionSettings.setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:$packageName".toUri()))
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val prefs = (application as JarvisApp).preferences
        if (com.jarvis.assistant.util.DirectivePowerPolicy.backgroundAllowed(prefs.powerProfile,
                getSystemService(android.os.PowerManager::class.java).isPowerSaveMode))
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        lifecycle.addObserver(binding.voiceOrb)
        // Scrollable content preserves access with landscape, small windows and large font scales.
        binding.voiceScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val density = resources.displayMetrics.density
            val height = minOf(binding.voiceScroll.width * .82f, 380f * density,
                binding.voiceScroll.height * .65f).toInt().coerceAtLeast(1)
            if (binding.voiceOrb.layoutParams.height != height) {
                binding.voiceOrb.layoutParams = binding.voiceOrb.layoutParams.apply { this.height = height }
            }
        }
        binding.voiceOrb.setOnClickListener {
            if (JarvisConversationService.isSessionOn.value) JarvisConversationService.interruptPlayback() else startVoice()
        }
        binding.voiceOrb.setOnLongClickListener { openAppearance(); true }
        binding.startVoice.setOnClickListener { startVoice() }
        binding.interruptVoice.setOnClickListener { JarvisConversationService.interruptPlayback() }
        binding.muteVoice.setOnClickListener {
            prefs.isMicMuted = !prefs.isMicMuted
            JarvisConversationService.setMicMuted(prefs.isMicMuted)
            binding.muteVoice.setText(if (prefs.isMicMuted) R.string.plasma_unmute else R.string.plasma_mute)
        }
        binding.endVoice.setOnClickListener {
            if (JarvisConversationService.isSessionOn.value) send(JarvisConversationService.ACTION_STOP_ALL)
        }
        binding.backVoice.setOnClickListener { finish() }
        binding.voiceSettings.setOnClickListener {
            startActivity(Intent(this, com.jarvis.assistant.ui.settings.ApiConnectionActivity::class.java))
        }
        binding.orbAppearance.setOnClickListener { openAppearance() }
        binding.toggleTranscript.setOnClickListener { transcriptExpanded = !transcriptExpanded; renderTranscriptVisibility() }
        renderTranscriptVisibility()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { JarvisConversationService.voicePresentation.collect { facts ->
                    binding.voiceOrb.submit(facts)
                    val label = OrbPresentationLabels.status(this@VoiceModeActivity, facts)
                    if (binding.voiceStatus.text.toString() != label) binding.voiceStatus.text = label
                    val presentation = OrbPresentationMapper.map(facts, android.os.SystemClock.elapsedRealtime())
                    if (facts.active) localExplanation = 0
                    val reason = presentation.reason ?: if (facts.playing && facts.timing == AudioTimingConfidence.STATE_ONLY)
                        getString(R.string.plasma_timing_state_only) else if (localExplanation != 0) getString(localExplanation) else ""
                    binding.permissionSettings.visibility = if (localExplanation == R.string.plasma_permission) View.VISIBLE else View.GONE
                    if (binding.voiceReason.text.toString() != reason) binding.voiceReason.text = reason
                    binding.startVoice.visibility = if (facts.active) View.GONE else View.VISIBLE
                    binding.endVoice.isEnabled = facts.active
                    binding.interruptVoice.isEnabled = facts.active
                    val muteLabel = getString(if (prefs.isMicMuted) R.string.plasma_unmute else R.string.plasma_mute)
                    if (binding.muteVoice.text.toString() != muteLabel) binding.muteVoice.text = muteLabel
                } }
                launch { JarvisConversationService.liveUserTranscript.collect { binding.userTranscript.text = it.ifBlank { getString(R.string.plasma_transcript_empty) } } }
                launch { JarvisConversationService.liveAssistantResponse.collect { binding.assistantTranscript.text = it } }
                launch { JarvisConversationService.liveError.collect { if (it != null) binding.voiceReason.text = it.message } }
            }
        }
    }
    private fun renderTranscriptVisibility() {
        binding.transcriptPanel.visibility = if (transcriptExpanded) View.VISIBLE else View.GONE
        binding.toggleTranscript.setText(if (transcriptExpanded) R.string.plasma_hide_transcript else R.string.plasma_show_transcript)
    }
    private fun openAppearance() = startActivity(Intent(this, com.jarvis.assistant.ui.settings.AuraControlActivity::class.java))
    override fun onResume() { super.onResume(); if (!attemptedStart) { attemptedStart = true; startVoice() } }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("explanation", localExplanation)
        outState.putBoolean("attempted", attemptedStart); outState.putBoolean("transcript", transcriptExpanded)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { binding.root.animate().cancel(); super.onDestroy() }
    private fun startVoice() {
        if (JarvisConversationService.isSessionOn.value) return
        val missing = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { permissions.launch(missing.toTypedArray()); return }
        val prefs = (application as JarvisApp).preferences
        if ((prefs.getNextActiveKey("gemini") ?: prefs.apiKey).isBlank()) { explain(R.string.plasma_key_required); return }
        try { send(JarvisConversationService.ACTION_START_CONVERSATION) }
        catch (_: Exception) { explain(R.string.plasma_start_failed) }
    }
    private fun send(action: String) {
        ContextCompat.startForegroundService(this, Intent(this, JarvisConversationService::class.java).setAction(action))
    }
}
