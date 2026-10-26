package com.jarvis.assistant.ui.debug

import android.media.audiofx.AcousticEchoCanceler
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.R
import com.jarvis.assistant.audio.VoiceInterruptionCoordinator
import com.jarvis.assistant.audio.VoiceLatencyTracker
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.Personality
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Diagnostic HUD for real-time inspection of voice pipeline telemetry:
 * - Connection & Conversation StateFlows
 * - VAD / Mic Amplitude Level
 * - Configured vs Detected Spoken Language
 * - Assistant Name & Active Personality Mode
 * - Turn-level Latency Metrics (TTFT, TTFA, p50, p95, min, max)
 * - Echo Guard Protection, Force-Unmute Watchdogs, Hardware AEC State
 */
class VoiceDebugActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences

    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvConversationState: TextView
    private lateinit var tvReconnectCount: TextView
    private lateinit var pbMicLevel: ProgressBar
    private lateinit var tvMicLevelVal: TextView

    private lateinit var tvAssistantName: TextView
    private lateinit var tvPersonality: TextView
    private lateinit var tvConfiguredLang: TextView
    private lateinit var tvDetectedLang: TextView

    private lateinit var tvTurnCount: TextView
    private lateinit var tvTtftStats: TextView
    private lateinit var tvTtfaStats: TextView
    private lateinit var tvLastTurnStats: TextView

    private lateinit var tvEchoGuardStatus: TextView
    private lateinit var tvWatchdogCount: TextView
    private lateinit var tvHardwareAec: TextView

    private lateinit var btnResetMetrics: Button
    private lateinit var btnCyclePersonality: Button
    private lateinit var btnBack: ImageButton
    private lateinit var btnRefresh: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_debug)

        prefs = AppPreferences(this)
        bindViews()
        setupListeners()
        observePipelineFlows()
        startPeriodicStatsPoller()
    }

    private fun bindViews() {
        tvConnectionStatus = findViewById(R.id.tv_connection_status)
        tvConversationState = findViewById(R.id.tv_conversation_state)
        tvReconnectCount = findViewById(R.id.tv_reconnect_count)
        pbMicLevel = findViewById(R.id.pb_mic_level)
        tvMicLevelVal = findViewById(R.id.tv_mic_level_val)

        tvAssistantName = findViewById(R.id.tv_assistant_name)
        tvPersonality = findViewById(R.id.tv_personality)
        tvConfiguredLang = findViewById(R.id.tv_configured_lang)
        tvDetectedLang = findViewById(R.id.tv_detected_lang)

        tvTurnCount = findViewById(R.id.tv_turn_count)
        tvTtftStats = findViewById(R.id.tv_ttft_stats)
        tvTtfaStats = findViewById(R.id.tv_ttfa_stats)
        tvLastTurnStats = findViewById(R.id.tv_last_turn_stats)

        tvEchoGuardStatus = findViewById(R.id.tv_echo_guard_status)
        tvWatchdogCount = findViewById(R.id.tv_watchdog_count)
        tvHardwareAec = findViewById(R.id.tv_hardware_aec)

        btnResetMetrics = findViewById(R.id.btn_reset_metrics)
        btnCyclePersonality = findViewById(R.id.btn_cycle_personality)
        btnBack = findViewById(R.id.btn_back)
        btnRefresh = findViewById(R.id.btn_refresh)

        findViewById<TextView>(R.id.tv_build_stamp).text =
            "Build: ${com.jarvis.assistant.BuildConfig.BUILD_GIT_SHA} | ${com.jarvis.assistant.BuildConfig.BUILD_TIMESTAMP}"
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        btnRefresh.setOnClickListener { refreshStaticTelemetry() }

        btnResetMetrics.setOnClickListener {
            VoiceLatencyTracker.shared.clear()
            VoiceInterruptionCoordinator.resetWatchdogTriggerCount()
            refreshStaticTelemetry()
        }

        btnCyclePersonality.setOnClickListener {
            val personalities = Personality.entries
            val current = Personality.fromId(prefs.personality)
            val nextIdx = (personalities.indexOf(current) + 1) % personalities.size
            val nextPersona = personalities[nextIdx]
            prefs.personality = nextPersona.id
            refreshStaticTelemetry()
        }

        findViewById<Button>(R.id.btn_copy_a11y_doctor).setOnClickListener {
            val report = com.jarvis.assistant.automation.accessibility.AccessibilityDoctor.generateCopyableReport(this)
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("Jarvis Accessibility Report", report))
            android.widget.Toast.makeText(this, "Accessibility Doctor report copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_open_a11y_fix).setOnClickListener {
            startActivity(android.content.Intent(this, com.jarvis.assistant.ui.accessibility.AccessibilityFixActivity::class.java))
        }

        val tvForgeResult = findViewById<TextView>(R.id.tv_forge_self_test_result)

        findViewById<Button>(R.id.btn_forge_self_test).setOnClickListener {
            tvForgeResult.text = "Running Forge probe..."
            lifecycleScope.launch {
                val res = com.jarvis.assistant.forge.ForgeSelfTester.runProbe(this@VoiceDebugActivity)
                tvForgeResult.text = res.summary
                tvForgeResult.setTextColor(if (res.success) 0xFF34D399.toInt() else 0xFFF87171.toInt())
            }
        }

        findViewById<Button>(R.id.btn_forge_mini_build).setOnClickListener {
            tvForgeResult.text = "Starting Mini Build stream..."
            lifecycleScope.launch {
                val res = com.jarvis.assistant.forge.ForgeSelfTester.runMiniBuild(this@VoiceDebugActivity) { stage ->
                    tvForgeResult.text = stage
                }
                tvForgeResult.text = res.summary
                tvForgeResult.setTextColor(if (res.success) 0xFF34D399.toInt() else 0xFFF87171.toInt())
            }
        }
    }

    private fun observePipelineFlows() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    JarvisConversationService.connectionStatus.collect { status ->
                        tvConnectionStatus.text = status
                        when (status.uppercase()) {
                            "LIVE" -> tvConnectionStatus.setTextColor(0xFF34D399.toInt())
                            "CONNECTING", "INITIALIZING" -> tvConnectionStatus.setTextColor(0xFFFBBF24.toInt())
                            "ERROR", "CLOSED" -> tvConnectionStatus.setTextColor(0xFFF87171.toInt())
                            else -> tvConnectionStatus.setTextColor(0xFF9CA3AF.toInt())
                        }
                    }
                }

                launch {
                    JarvisConversationService.conversationState.collect { state ->
                        tvConversationState.text = state.name
                    }
                }

                launch {
                    JarvisConversationService.audioLevel.collect { level ->
                        val percent = (level * 100).toInt().coerceIn(0, 100)
                        pbMicLevel.progress = percent
                        tvMicLevelVal.text = "$percent%"
                    }
                }

                launch {
                    JarvisConversationService.reconnectCount.collect { count ->
                        tvReconnectCount.text = "$count reconnects"
                    }
                }

                launch {
                    JarvisConversationService.detectedLanguage.collect { lang ->
                        tvDetectedLang.text = lang
                    }
                }
            }
        }
    }

    private fun startPeriodicStatsPoller() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    refreshStaticTelemetry()
                    delay(500L)
                }
            }
        }
    }

    private fun refreshStaticTelemetry() {
        // Brain & Personality
        tvAssistantName.text = prefs.assistantName
        val currentPersona = Personality.fromId(prefs.personality)
        tvPersonality.text = "${currentPersona.displayName} (${currentPersona.name})"
        tvConfiguredLang.text = prefs.language.ifBlank { "Auto (All Languages)" }

        // Latency
        val tracker = VoiceLatencyTracker.shared
        val turnCount = tracker.getCompletedTurnCount()
        tvTurnCount.text = "$turnCount turns"

        val avgTtft = tracker.getAverageTtftMs()?.toString() ?: "--"
        val p50Ttft = tracker.getP50TtftMs()?.toString() ?: "--"
        val p95Ttft = tracker.getP95TtftMs()?.toString() ?: "--"
        tvTtftStats.text = "$avgTtft / $p50Ttft / $p95Ttft ms"

        val avgTtfa = tracker.getAverageTtfaMs()?.toString() ?: "--"
        val p50Ttfa = tracker.getP50TtfaMs()?.toString() ?: "--"
        val p95Ttfa = tracker.getP95TtfaMs()?.toString() ?: "--"
        tvTtfaStats.text = "$avgTtfa / $p50Ttfa / $p95Ttfa ms"

        val last = tracker.getLatestTurn()
        if (last != null) {
            val ttft = last.timeToFirstTokenMs?.let { "${it}ms" } ?: "--"
            val ttfa = last.timeToFirstAudioMs?.let { "${it}ms" } ?: "--"
            val total = last.totalTurnDurationMs?.let { "${it}ms" } ?: "--"
            tvLastTurnStats.text = "Turn #${last.turnId}: TTFT $ttft, TTFA $ttfa (Total $total)"
        } else {
            tvLastTurnStats.text = "No completed turns yet"
        }

        // Echo Guard
        val isMuted = VoiceInterruptionCoordinator.isEchoGuardMuted()
        if (isMuted) {
            tvEchoGuardStatus.text = "Muted (Echo suppression active)"
            tvEchoGuardStatus.setTextColor(0xFFFBBF24.toInt())
        } else {
            tvEchoGuardStatus.text = "Unmuted (Listening normally)"
            tvEchoGuardStatus.setTextColor(0xFF34D399.toInt())
        }

        tvWatchdogCount.text = "${VoiceInterruptionCoordinator.getWatchdogTriggerCount()} triggers"

        val aecAvailable = try {
            AcousticEchoCanceler.isAvailable()
        } catch (_: Throwable) {
            false
        }
        tvHardwareAec.text = if (aecAvailable) "YES (Device Supported)" else "NO (Software suppression)"
        tvHardwareAec.setTextColor(if (aecAvailable) 0xFF34D399.toInt() else 0xFF9CA3AF.toInt())
    }
}
