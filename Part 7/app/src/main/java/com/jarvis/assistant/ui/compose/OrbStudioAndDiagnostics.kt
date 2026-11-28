package com.jarvis.assistant.ui.compose

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.audio.VoiceFlightRecorder
import com.jarvis.assistant.core.runtime.JarvisEvent
import com.jarvis.assistant.core.runtime.JarvisEventBus
import com.jarvis.assistant.core.runtime.NeuralRuntimeManager
import com.jarvis.assistant.core.runtime.ResourceGovernor
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.ui.orb.JarvisOrbColorTheme
import com.jarvis.assistant.ui.orb.JarvisOrbHostCompose
import com.jarvis.assistant.ui.orb.JarvisOrbMotionState
import com.jarvis.assistant.ui.orb.JarvisOrbPreset
import com.jarvis.assistant.ui.orb.OrbRenderConfig
import com.jarvis.assistant.ui.theme.JarvisSpacing
import com.jarvis.assistant.ui.theme.JarvisTheme
import com.jarvis.assistant.ui.theme.LocalJarvisColors
import com.jarvis.assistant.util.ThemeManager
import com.jarvis.assistant.util.VisualMotionSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import org.json.JSONObject

/**
 * Interactive Orb & Aura Studio showcasing all 12 GPU presets, 8 multi-stop palettes,
 * real-time physical motion/blink parameters, power policy, and direct access to
 * legacy Aura autonomy settings and Engine Diagnostics.
 */
@Composable
fun AuraStudioComposeScreen(
    preferences: AppPreferences,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenLegacyAuraControls: () -> Unit,
    onAppThemeChanged: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    var selectedPreset by remember { mutableStateOf(JarvisOrbPreset.fromPreference(preferences.orbPreset)) }
    var selectedColorTheme by remember {
        mutableStateOf(JarvisOrbColorTheme.fromPreference(preferences.orbColorTheme, selectedPreset.defaultTheme))
    }

    val initialJson = remember { runCatching { JSONObject(preferences.orbEyeOptions) }.getOrDefault(JSONObject()) }
    var intensity by remember { mutableFloatStateOf(initialJson.optDouble("intensity", 1.0).toFloat().coerceIn(0.3f, 1.5f)) }
    var glow by remember { mutableFloatStateOf(initialJson.optDouble("glow", 1.0).toFloat().coerceIn(0f, 1.5f)) }
    var speed by remember { mutableFloatStateOf(initialJson.optDouble("speed", 1.0).toFloat().coerceIn(0.4f, 1.6f)) }
    var orbScale by remember { mutableFloatStateOf(initialJson.optDouble("size", 1.0).toFloat().coerceIn(0.7f, 1.15f)) }
    var blinkEnabled by remember { mutableStateOf(initialJson.optBoolean("blink", true)) }
    var reducedMotion by remember { mutableStateOf(VisualMotionSettings.preference(context)) }
    var previewMotion by remember { mutableStateOf(JarvisOrbMotionState.LISTENING) }

    fun persistOptions() {
        val json = runCatching { JSONObject(preferences.orbEyeOptions) }.getOrDefault(JSONObject())
        json.put("intensity", intensity.toDouble())
        json.put("glow", glow.toDouble())
        json.put("speed", speed.toDouble())
        json.put("size", orbScale.toDouble())
        json.put("blink", blinkEnabled)
        json.put("renderer", "neural_wave")
        preferences.orbEyeOptions = json.toString()
    }

    val renderConfig = remember(selectedPreset, selectedColorTheme, intensity, glow, speed, orbScale, blinkEnabled) {
        OrbRenderConfig(
            preset = selectedPreset,
            colorTheme = selectedColorTheme,
            intensity = intensity,
            glow = glow,
            speed = speed,
            scale = orbScale,
            blinkEnabled = blinkEnabled
        )
    }

    JarvisTheme(themeKey = preferences.appTheme, customHex = preferences.customThemeColor) {
        val colors = LocalJarvisColors.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(JarvisSpacing.md),
            verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Aura & Orb Studio",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "12 GPU shader presets • 8 dynamic color palettes",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textMuted
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.xs)) {
                    OutlinedButton(onClick = onOpenDiagnostics) {
                        Text("Diagnostics", color = colors.accent)
                    }
                    OutlinedButton(onClick = onBack) {
                        Text("Done", color = colors.textPrimary)
                    }
                }
            }

            // Live Interactive Preview Stage
            Surface(
                color = Color(0xFF05080F),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, colors.cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = JarvisSpacing.sm, horizontal = JarvisSpacing.md)
                ) {
                    JarvisOrbHostCompose(
                        config = renderConfig,
                        motionState = previewMotion,
                        rms = if (previewMotion == JarvisOrbMotionState.SPEAKING) 0.65f else 0.32f,
                        bass = 0.40f,
                        mid = 0.35f,
                        treble = 0.25f,
                        accessibilityLabel = "${selectedPreset.displayName} preview",
                        onTap = {
                            val states = JarvisOrbMotionState.entries
                            previewMotion = states[(previewMotion.ordinal + 1) % states.size]
                        },
                        modifier = Modifier.size(188.dp)
                    )
                    Text(
                        text = "${selectedPreset.displayName} • ${previewMotion.label} (tap to cycle state)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFCBD5E1)
                    )
                }
            }

            // 12 Preset Cards
            Text(
                text = "Orb Archetype (${JarvisOrbPreset.entries.size} Presets)",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.xs),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                items(JarvisOrbPreset.entries, key = { it.id }) { preset ->
                    val active = preset == selectedPreset
                    Surface(
                        color = if (active) colors.accent.copy(alpha = 0.16f) else colors.surfaceCard,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, if (active) colors.accent else colors.cardBorder),
                        modifier = Modifier
                            .width(156.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                if (preferences.hapticFeedbackEnabled) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                }
                                selectedPreset = preset
                                preferences.orbPreset = preset.id
                                persistOptions()
                            }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = preset.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (active) colors.accent else colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = preset.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 8 Color Theme Swatches
            Text(
                text = "Spectrum Palette (${JarvisOrbColorTheme.entries.size} Themes)",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)
            ) {
                items(JarvisOrbColorTheme.entries, key = { it.id }) { theme ->
                    val active = theme == selectedColorTheme
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                selectedColorTheme = theme
                                preferences.orbColorTheme = theme.id
                            }
                            .padding(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            Color(theme.primaryArgb),
                                            Color(theme.secondaryArgb),
                                            Color(theme.coreArgb)
                                        )
                                    )
                                )
                                .border(
                                    width = if (active) 2.5.dp else 1.dp,
                                    color = if (active) colors.textPrimary else colors.cardBorder,
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = theme.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) colors.accent else colors.textMuted
                        )
                    }
                }
            }

            // Motion & Shader Sliders
            Surface(
                color = colors.surfaceCard,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, colors.cardBorder)
            ) {
                Column(
                    modifier = Modifier.padding(JarvisSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(JarvisSpacing.xs)
                ) {
                    Text("Shader Kinetics & Light", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    SliderControlRow("Visual Intensity", intensity, 0.3f..1.5f) {
                        intensity = it; persistOptions()
                    }
                    SliderControlRow("Halo Glow", glow, 0f..1.5f) {
                        glow = it; persistOptions()
                    }
                    SliderControlRow("Rotation Speed", speed, 0.4f..1.6f) {
                        speed = it; persistOptions()
                    }
                    SliderControlRow("Orb Scale", orbScale, 0.7f..1.15f) {
                        orbScale = it; persistOptions()
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Natural Blinking", style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                        Switch(checked = blinkEnabled, onCheckedChange = { blinkEnabled = it; persistOptions() })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Reduced Motion (0 Hz GPU)", style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                        Switch(
                            checked = reducedMotion,
                            onCheckedChange = {
                                reducedMotion = it
                                VisualMotionSettings.setPreference(context, it)
                            }
                        )
                    }
                }
            }

            // System / Theme / Legacy Aura Controls Bridge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.xs)
            ) {
                OutlinedButton(
                    onClick = onAppThemeChanged,
                    border = BorderStroke(1.dp, colors.cardBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cycle Home Theme", color = colors.textPrimary)
                }
                Button(
                    onClick = onOpenLegacyAuraControls,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceCard, contentColor = colors.accent),
                    border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.5f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Aura & Power Suite")
                }
            }
        }
    }
}

@Composable
private fun SliderControlRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    val colors = LocalJarvisColors.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            Text(String.format(java.util.Locale.US, "%.2f", value), style = MaterialTheme.typography.labelSmall, color = colors.accent)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent)
        )
    }
}

/**
 * Dedicated engineering observability Activity surfacing live RAM Governor, Thermal Status,
 * NeuralRuntimeManager active models, YAMNet ambient audio, AEC3/Silero/Wake-Word toggles,
 * USearch HNSW memory status, and VoiceFlightRecorder telemetry outside the Home screen.
 */
class EngineDiagnosticsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val preferences = JarvisApp.instance.preferences
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                EngineDiagnosticsScreen(
                    preferences = preferences,
                    onBack = { finish() }
                )
            }
        })
    }
}

@Composable
fun EngineDiagnosticsScreen(
    preferences: AppPreferences,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val policy by ResourceGovernor.policy.collectAsStateWithLifecycle()
    val runtimeSnapshot by NeuralRuntimeManager.snapshot.collectAsStateWithLifecycle()

    var availRamMb by remember { mutableIntStateOf(0) }
    var batteryPct by remember { mutableIntStateOf(-1) }
    var lastEnvScene by remember { mutableStateOf("No samples classified yet") }
    var flightSummary by remember { mutableStateOf(VoiceFlightRecorder.getDiagnosticSummary(context)) }
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTick) {
        while (true) {
            val mi = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(mi)
            availRamMb = (mi.availMem / (1024L * 1024L)).toInt()
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            flightSummary = VoiceFlightRecorder.getDiagnosticSummary(context)
            delay(2_000L)
        }
    }

    LaunchedEffect(Unit) {
        JarvisEventBus.events
            .filterIsInstance<JarvisEvent.EnvironmentChanged>()
            .collect { ev ->
                val pct = (ev.confidence * 100f).toInt().coerceIn(0, 100)
                lastEnvScene = "${ev.label} ($pct%)"
            }
    }

    JarvisTheme(themeKey = preferences.appTheme, customHex = preferences.customThemeColor) {
        val colors = LocalJarvisColors.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(JarvisSpacing.md),
            verticalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Engine Diagnostics", style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
                    Text("6 GB RAM Governor • Audio DSP • HNSW", style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
                }
                OutlinedButton(onClick = onBack) {
                    Text("Close", color = colors.textPrimary)
                }
            }

            DiagnosticCard("System & 6 GB Memory Governor") {
                DiagnosticRow("Operating Mode", policy.mode.name)
                DiagnosticRow("System Available RAM", "$availRamMb MB")
                DiagnosticRow("Neural Memory", "${runtimeSnapshot.activeMemoryMb} MB / ${runtimeSnapshot.budgetMb} MB")
                DiagnosticRow("Governor Budget", "${policy.activeModelBudgetMb} MB (Peak Thermal: ${policy.thermalStatus})")
                DiagnosticRow("Orb GPU FPS Cap", "${policy.maxOrbFps} FPS")
                DiagnosticRow("Battery Level", if (batteryPct in 0..100) "$batteryPct%" else "Unavailable")
            }

            DiagnosticCard("Audio, Wake & Turn Stack") {
                DiagnosticRow("WebRTC AEC3", if (preferences.softwareAecEnabled) "ACTIVE (16 KB libjarvis_aec3)" else "DISABLED")
                DiagnosticRow("Dual DTLN LSTM", if (preferences.dtlnEchoEnabled) "ACTIVE (Post-AEC3)" else "DISABLED")
                DiagnosticRow("Silero VAD v4", if (preferences.neuralSpeechEnabled) "ACTIVE" else "DISABLED")
                DiagnosticRow("Smart Semantic Turn", if (preferences.neuralTurnEnabled) "ONNX + EOU" else "Acoustic / Pause Gate")
                DiagnosticRow("Wake Word Phrases", "Hey Jarvis / Hello Jarvis / Wake Up Jarvis")
                DiagnosticRow("YAMNet Ambient Scene", lastEnvScene)
                DiagnosticRow("Vector Index Engine", "USearch HNSW (libjarvis_vector.so) + ONNX")
            }

            DiagnosticCard("Loaded Neural Sessions (${runtimeSnapshot.sessions.size})") {
                if (runtimeSnapshot.sessions.isEmpty()) {
                    Text("No active neural models loaded in RAM.", style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                } else {
                    runtimeSnapshot.sessions.forEachIndexed { index, session ->
                        if (index > 0) HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 4.dp))
                        DiagnosticRow(
                            label = "${session.modelId} (${session.runtime.name})",
                            value = "${session.estimatedMemoryMb} MB • ${session.state.name} • ${session.avgInferenceMicros} µs"
                        )
                    }
                }
            }

            DiagnosticCard("Voice Flight Recorder") {
                Text(
                    text = flightSummary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(JarvisSpacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.xs)) {
                    Button(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("JARVIS Diagnostics", flightSummary))
                            Toast.makeText(context, "Diagnostic report copied", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                    ) {
                        Text("Copy Summary", color = colors.background)
                    }
                    OutlinedButton(onClick = {
                        VoiceFlightRecorder.clearHistory()
                        refreshTick++
                    }) {
                        Text("Clear History", color = colors.textPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticCard(
    title: String,
    content: @Composable () -> Unit
) {
    val colors = LocalJarvisColors.current
    Surface(
        color = colors.surfaceCard,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, colors.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(JarvisSpacing.md),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.accent)
            content()
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    val colors = LocalJarvisColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textMuted,
            modifier = Modifier.weight(0.45f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textPrimary,
            modifier = Modifier.weight(0.55f)
        )
    }
}
