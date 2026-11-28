package com.jarvis.assistant.ui.orb

import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.data.model.VoicePresentationSnapshot
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 12 procedural GPU orb visual presets shared identically across Home, Voice Mode, and Aura Studio.
 * Preserves compatibility with legacy JARVIS keys ("classic", "aura", "crimson", "midnight").
 */
enum class JarvisOrbPreset(
    val id: String,
    val shaderIndex: Int,
    val displayName: String,
    val subtitle: String,
    val defaultTheme: JarvisOrbColorTheme,
    val legacyAliases: Set<String> = emptySet()
) {
    NEURAL_CORE(
        id = "neural_core",
        shaderIndex = 0,
        displayName = "Neural Core",
        subtitle = "Harmonic luminous plasma sphere",
        defaultTheme = JarvisOrbColorTheme.CYBER_CYAN,
        legacyAliases = setOf("classic")
    ),
    JARVIS_ARC_3D(
        id = "jarvis_3d",
        shaderIndex = 1,
        displayName = "Arc Reactor 3D",
        subtitle = "Holographic rotating core & gyro rings",
        defaultTheme = JarvisOrbColorTheme.ARCTIC_ICE,
        legacyAliases = setOf("aura")
    ),
    PULSE_REACTOR(
        id = "pulse_reactor",
        shaderIndex = 2,
        displayName = "Pulse Reactor",
        subtitle = "Concentric sonic beat shockwaves",
        defaultTheme = JarvisOrbColorTheme.DEEP_OCEAN
    ),
    LIQUID_CORE(
        id = "liquid_core",
        shaderIndex = 3,
        displayName = "Liquid Mercury",
        subtitle = "Organic Fresnel fluid refraction",
        defaultTheme = JarvisOrbColorTheme.NEBULA_VIOLET
    ),
    NOVA_HUD(
        id = "nova_hud",
        shaderIndex = 4,
        displayName = "Nova HUD",
        subtitle = "Tactical orbital targeting rings",
        defaultTheme = JarvisOrbColorTheme.CYBER_CYAN
    ),
    NOVA_3D(
        id = "nova_3d",
        shaderIndex = 5,
        displayName = "Nova Crystal 3D",
        subtitle = "Multi-ring prismatic orbital field",
        defaultTheme = JarvisOrbColorTheme.EMERALD_PULSE
    ),
    HUD_RED(
        id = "hud_red",
        shaderIndex = 6,
        displayName = "Crimson Interceptor",
        subtitle = "High-contrast tactical visor reactor",
        defaultTheme = JarvisOrbColorTheme.CRIMSON_HUD,
        legacyAliases = setOf("crimson")
    ),
    FRIDAY(
        id = "friday",
        shaderIndex = 7,
        displayName = "FRIDAY Matrix",
        subtitle = "Clean geometric latitude wireframe",
        defaultTheme = JarvisOrbColorTheme.SOLAR_GOLD
    ),
    SHARD(
        id = "shard",
        shaderIndex = 8,
        displayName = "Shattered Prism",
        subtitle = "Refractive angular crystal facets",
        defaultTheme = JarvisOrbColorTheme.NEBULA_VIOLET
    ),
    ULTRON_3D(
        id = "ultron_3d",
        shaderIndex = 9,
        displayName = "Obsidian Engine 3D",
        subtitle = "Armored dark sphere with glowing fissures",
        defaultTheme = JarvisOrbColorTheme.CRIMSON_HUD,
        legacyAliases = setOf("midnight")
    ),
    PARTICLE_SWARM(
        id = "particle_swarm",
        shaderIndex = 10,
        displayName = "Quantum Swarm",
        subtitle = "Coherent orbiting neural swarm particles",
        defaultTheme = JarvisOrbColorTheme.ARCTIC_ICE
    ),
    SIGNATURE_2047(
        id = "jarvis_2047",
        shaderIndex = 11,
        displayName = "JARVIS 2047",
        subtitle = "Domain-warped aurora nebula core",
        defaultTheme = JarvisOrbColorTheme.CYBER_CYAN
    );

    companion object {
        val ALL_ALLOWED_KEYS: Set<String> = entries.flatMap { preset ->
            listOf(preset.id) + preset.legacyAliases
        }.toSet()

        fun fromPreference(raw: String?): JarvisOrbPreset {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { preset ->
                preset.id == normalized || normalized in preset.legacyAliases
            } ?: SIGNATURE_2047
        }
    }
}

/**
 * 8 curated 3-stop RGB palettes feeding GPU uniforms directly without allocation.
 */
enum class JarvisOrbColorTheme(
    val id: String,
    val displayName: String,
    val primaryArgb: Long,
    val secondaryArgb: Long,
    val coreArgb: Long
) {
    CYBER_CYAN("cyber_cyan", "Cyber Cyan", 0xFF0EA5E9, 0xFF22D3EE, 0xFFE0F2FE),
    NEBULA_VIOLET("nebula_violet", "Nebula Violet", 0xFF7C3AED, 0xFFEC4899, 0xFFFCE7F3),
    SOLAR_GOLD("solar_gold", "Solar Gold", 0xFFD97706, 0xFFFBBF24, 0xFFFEF3C7),
    CRIMSON_HUD("crimson_hud", "Crimson HUD", 0xFFDC2626, 0xFFFF4D6D, 0xFFFFE4E6),
    EMERALD_PULSE("emerald_pulse", "Emerald Pulse", 0xFF059669, 0xFF34D399, 0xFFD1FAE5),
    ARCTIC_ICE("arctic_ice", "Arctic Ice", 0xFF38BDF8, 0xFFA5F3FC, 0xFFFFFFFF),
    DEEP_OCEAN("deep_ocean", "Deep Ocean", 0xFF1D4ED8, 0xFF06B6D4, 0xFFBAE6FD),
    MONOCHROME_PRO("monochrome_pro", "Monochrome Pro", 0xFF64748B, 0xFFE2E8F0, 0xFFFFFFFF);

    fun primaryRgb(): FloatArray = argbToFloat3(primaryArgb)
    fun secondaryRgb(): FloatArray = argbToFloat3(secondaryArgb)
    fun coreRgb(): FloatArray = argbToFloat3(coreArgb)

    companion object {
        val ALL_IDS: Set<String> = entries.map { it.id }.toSet()

        fun fromPreference(raw: String?, fallback: JarvisOrbColorTheme = CYBER_CYAN): JarvisOrbColorTheme {
            val clean = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == clean } ?: fallback
        }

        private fun argbToFloat3(argb: Long): FloatArray = floatArrayOf(
            ((argb ushr 16) and 0xFFL) / 255f,
            ((argb ushr 8) and 0xFFL) / 255f,
            (argb and 0xFFL) / 255f
        )
    }
}

enum class JarvisOrbMotionState(val shaderValue: Int, val label: String) {
    IDLE(0, "Ready"),
    WAKE(1, "Attentive"),
    LISTENING(2, "Listening"),
    THINKING(3, "Thinking"),
    SPEAKING(4, "Speaking"),
    ERROR(5, "Attention Needed");

    companion object {
        fun resolve(
            isSessionOn: Boolean,
            isStandby: Boolean,
            isMicMuted: Boolean,
            conversationState: ConversationState,
            snapshot: VoicePresentationSnapshot,
            nowMs: Long = System.currentTimeMillis()
        ): JarvisOrbMotionState {
            val mapped = OrbPresentationMapper.map(snapshot, nowMs)
            return when (mapped.state) {
                PlasmaVisualState.ERROR -> ERROR
                PlasmaVisualState.AI_SPEAKING -> SPEAKING
                PlasmaVisualState.USER_SPEAKING, PlasmaVisualState.LISTENING -> LISTENING
                PlasmaVisualState.PROCESSING -> THINKING
                PlasmaVisualState.INTERRUPTED -> WAKE
                PlasmaVisualState.DISCONNECTED -> if (!isSessionOn) IDLE else ERROR
                PlasmaVisualState.IDLE -> when {
                    !isSessionOn || isMicMuted -> IDLE
                    isStandby || conversationState == ConversationState.STANDBY -> IDLE
                    conversationState == ConversationState.CONNECTING -> WAKE
                    conversationState == ConversationState.THINKING ||
                        conversationState == ConversationState.EXECUTING ||
                        conversationState == ConversationState.RECOVERING -> THINKING
                    conversationState == ConversationState.SPEAKING -> SPEAKING
                    conversationState == ConversationState.LISTENING ||
                        conversationState == ConversationState.CONNECTED -> LISTENING
                    conversationState == ConversationState.ERROR ||
                        conversationState == ConversationState.FAILED -> ERROR
                    else -> IDLE
                }
            }
        }
    }
}

data class OrbRenderConfig(
    val preset: JarvisOrbPreset = JarvisOrbPreset.SIGNATURE_2047,
    val colorTheme: JarvisOrbColorTheme = JarvisOrbColorTheme.CYBER_CYAN,
    val intensity: Float = 1.0f,
    val glow: Float = 1.0f,
    val speed: Float = 1.0f,
    val scale: Float = 1.0f,
    val responsiveness: Float = 1.0f,
    val blinkEnabled: Boolean = true
) {
    companion object {
        fun fromPreferences(
            orbPresetRaw: String?,
            colorThemeRaw: String?,
            eyeOptionsJson: String?
        ): OrbRenderConfig {
            val preset = JarvisOrbPreset.fromPreference(orbPresetRaw)
            val theme = JarvisOrbColorTheme.fromPreference(colorThemeRaw, fallback = preset.defaultTheme)
            val json = runCatching { JSONObject(eyeOptionsJson ?: "{}") }.getOrDefault(JSONObject())
            return OrbRenderConfig(
                preset = preset,
                colorTheme = theme,
                intensity = json.optDouble("intensity", 1.0).toFloat().coerceIn(0.3f, 1.5f),
                glow = json.optDouble("glow", 1.0).toFloat().coerceIn(0f, 1.5f),
                speed = json.optDouble("speed", 1.0).toFloat().coerceIn(0.4f, 1.6f),
                scale = json.optDouble("size", 1.0).toFloat().coerceIn(0.7f, 1.15f),
                responsiveness = json.optDouble("responsiveness", 1.0).toFloat().coerceIn(0f, 1f),
                blinkEnabled = json.optBoolean("blink", true)
            )
        }
    }
}

/**
 * Temporal smoother applying 0.04f exponential transition damping and natural 18-frame blinking.
 */
class OrbStateSmoother(
    private val stepAlpha: Float = 0.04f
) {
    var smoothedAudio: Float = 0f
        private set
    var smoothedBass: Float = 0f
        private set
    var smoothedMid: Float = 0f
        private set
    var smoothedTreble: Float = 0f
        private set
    var smoothedStateMix: Float = 0f
        private set
    var blinkAmount: Float = 0f
        private set

    private var frameCount: Long = 0L

    fun step(
        targetAudio: Float,
        targetBass: Float,
        targetMid: Float,
        targetTreble: Float,
        motionState: JarvisOrbMotionState,
        blinkEnabled: Boolean
    ) {
        frameCount++
        val fastAlpha = (stepAlpha * 3.5f).coerceIn(0.04f, 0.35f)
        smoothedAudio += (targetAudio.coerceIn(0f, 1f) - smoothedAudio) * fastAlpha
        smoothedBass += (targetBass.coerceIn(0f, 1f) - smoothedBass) * fastAlpha
        smoothedMid += (targetMid.coerceIn(0f, 1f) - smoothedMid) * fastAlpha
        smoothedTreble += (targetTreble.coerceIn(0f, 1f) - smoothedTreble) * fastAlpha

        val targetMix = motionState.shaderValue.toFloat()
        smoothedStateMix += (targetMix - smoothedStateMix) * stepAlpha

        blinkAmount = if (blinkEnabled && (motionState == JarvisOrbMotionState.IDLE || motionState == JarvisOrbMotionState.LISTENING)) {
            val cyclePos = (frameCount % 258L).toInt()
            if (cyclePos in 240 until 258) {
                val t = (cyclePos - 240) / 18f
                abs(sin(t * PI)).toFloat()
            } else {
                0f
            }
        } else {
            0f
        }
    }

    fun reset() {
        smoothedAudio = 0f
        smoothedBass = 0f
        smoothedMid = 0f
        smoothedTreble = 0f
        smoothedStateMix = 0f
        blinkAmount = 0f
        frameCount = 0L
    }
}
