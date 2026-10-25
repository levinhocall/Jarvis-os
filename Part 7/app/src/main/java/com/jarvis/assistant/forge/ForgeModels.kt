package com.jarvis.assistant.forge

import java.io.File

/**
 * Execution phases of Website Forge.
 */
enum class ForgePhase {
    IDLE,
    BUILDING,
    DONE,
    FAILED,
    CANCELLED
}

/**
 * 6 progressive stages of website building.
 * Stages strictly never regress backwards during a single build session.
 */
enum class ForgeStage(val index: Int, val label: String, val description: String) {
    BLUEPRINT(1, "Blueprint", "Synthesizing metadata, viewport, and document head"),
    STRUCTURE(2, "Structure", "Assembling semantic body, navigation, and core sections"),
    STYLING(3, "Styling", "Compiling Tailwind CSS tokens, bento grid, and theme rules"),
    MOTION(4, "Motion", "Orchestrating GSAP scroll transitions and micro-interactions"),
    VISUALS(5, "Visuals", "Rendering responsive canvas elements, hero flair, and imagery"),
    FINALIZING(6, "Finalizing", "Polishing final layout, safety net, and paint reveal");

    companion object {
        fun fromIndex(index: Int): ForgeStage {
            return values().firstOrNull { it.index == index } ?: BLUEPRINT
        }
    }
}

/**
 * Real-time performance and size telemetry for the live build HUD.
 */
data class ForgeMetrics(
    val chars: Int = 0,
    val sections: Int = 0,
    val elapsedSec: Long = 0L,
    val tokensPerSec: Double = 0.0
)

/**
 * Process-wide UI state flow emitted to Forge HUD and 3D scene.
 */
data class ForgeUiState(
    val phase: ForgePhase = ForgePhase.IDLE,
    val prompt: String = "",
    val isEdit: Boolean = false,
    val stage: ForgeStage = ForgeStage.BLUEPRINT,
    val progress: Float = 0f,
    val metrics: ForgeMetrics = ForgeMetrics(),
    val sections: List<String> = emptyList(),
    val log: List<String> = emptyList(),
    val buildId: String = "",
    val file: File? = null,
    val error: String? = null
)

/**
 * Result returned synchronously when starting a build, with a spoken-friendly message.
 */
sealed class ForgeStartResult(val spokenMessage: String) {
    data class Started(val buildId: String) : ForgeStartResult("Website Forge is firing up. Building your website now.")
    object MissingKey : ForgeStartResult("Gemini API key is missing. Please configure it in Settings first.")
    object Offline : ForgeStartResult("You appear to be offline. Please connect to the internet to use Website Forge.")
    object Busy : ForgeStartResult("Website Forge is already building a website. Please wait for it to finish.")
    object NothingToEdit : ForgeStartResult("No existing website found to edit. Please create a new website first.")
}
