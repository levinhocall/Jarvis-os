package com.jarvis.assistant.core.runtime

import android.content.ComponentCallbacks2
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class JarvisOperatingMode {
    STANDBY,
    ACTIVE_CONVERSATION,
    OFFLINE_CONVERSATION,
    AUTONOMOUS_MISSION,
    THERMAL_OR_MEMORY_PRESSURE
}

data class ResourceGovernancePolicy(
    val mode: JarvisOperatingMode = JarvisOperatingMode.STANDBY,
    val maxOrbFps: Int = 60,
    val allowBackgroundYamnet: Boolean = true,
    val allowPocketTtsKeepWarm: Boolean = true,
    val allowDenseEmbedder: Boolean = true,
    val activeModelBudgetMb: Int = 384,
    val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
    val lastTrimLevel: Int = 0,
    val batterySaverEnabled: Boolean = false
)

/**
 * Centralized 6 GB RAM, thermal, and battery-aware resource policy coordinator.
 * Ensures real-time audio (AEC3 + Silero VAD + Barge-In) never starves under memory or thermal load.
 */
object ResourceGovernor {
    private const val DEVICE_6GB_SAFE_BUDGET_MB = 384
    private const val REDUCED_BUDGET_MB = 192
    private const val CRITICAL_BUDGET_MB = 112

    @Volatile private var baseMode = JarvisOperatingMode.STANDBY
    @Volatile private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    @Volatile private var lastTrimLevel = 0
    @Volatile private var batterySaver = false

    private val _policy = MutableStateFlow(computePolicy())
    val policy: StateFlow<ResourceGovernancePolicy> = _policy.asStateFlow()

    @Synchronized
    fun setOperatingMode(mode: JarvisOperatingMode) {
        baseMode = mode
        refresh()
    }

    @Synchronized
    fun onThermalStatusChanged(status: Int) {
        thermalStatus = status
        refresh()
        val label = when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            status >= PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            status >= PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            else -> "NORMAL"
        }
        JarvisEventBus.tryPublish(
            JarvisEvent.ThermalStateChanged(
                thermalStatus = status,
                pressureLevel = label,
                eventId = JarvisEventBus.nextId(),
                timestampMs = System.currentTimeMillis(),
                sessionId = 0L
            )
        )
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
            NeuralRuntimeManager.trimToBudget(CRITICAL_BUDGET_MB, "thermal_severe")
        } else if (status >= PowerManager.THERMAL_STATUS_MODERATE) {
            NeuralRuntimeManager.trimToBudget(REDUCED_BUDGET_MB, "thermal_moderate")
        }
    }

    @Synchronized
    fun onTrimMemory(level: Int, availableHeapMb: Long = estimateAvailableHeapMb()) {
        lastTrimLevel = level
        refresh()
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            JarvisEventBus.tryPublish(
                JarvisEvent.ResourcePressureDetected(
                    trimLevel = level,
                    availableHeapMb = availableHeapMb,
                    eventId = JarvisEventBus.nextId(),
                    timestampMs = System.currentTimeMillis(),
                    sessionId = 0L
                )
            )
            val targetMb = if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                CRITICAL_BUDGET_MB
            } else {
                REDUCED_BUDGET_MB
            }
            NeuralRuntimeManager.trimToBudget(targetMb, "trim_memory_$level")
        }
    }

    @Synchronized
    fun setBatterySaverEnabled(enabled: Boolean) {
        batterySaver = enabled
        refresh()
    }

    fun estimateAvailableHeapMb(): Long {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        return ((rt.maxMemory() - used).coerceAtLeast(0L)) / (1024L * 1024L)
    }

    private fun refresh() {
        _policy.value = computePolicy()
    }

    private fun computePolicy(): ResourceGovernancePolicy {
        val thermalPressure = thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        val memPressure = lastTrimLevel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        val effectiveMode = if (thermalPressure || memPressure) {
            JarvisOperatingMode.THERMAL_OR_MEMORY_PRESSURE
        } else {
            baseMode
        }
        return when (effectiveMode) {
            JarvisOperatingMode.THERMAL_OR_MEMORY_PRESSURE -> ResourceGovernancePolicy(
                mode = effectiveMode,
                maxOrbFps = 24,
                allowBackgroundYamnet = false,
                allowPocketTtsKeepWarm = false,
                allowDenseEmbedder = false,
                activeModelBudgetMb = CRITICAL_BUDGET_MB,
                thermalStatus = thermalStatus,
                lastTrimLevel = lastTrimLevel,
                batterySaverEnabled = batterySaver
            )
            JarvisOperatingMode.STANDBY -> ResourceGovernancePolicy(
                mode = effectiveMode,
                maxOrbFps = if (batterySaver) 24 else 30,
                allowBackgroundYamnet = !batterySaver,
                allowPocketTtsKeepWarm = false,
                allowDenseEmbedder = false,
                activeModelBudgetMb = REDUCED_BUDGET_MB,
                thermalStatus = thermalStatus,
                lastTrimLevel = lastTrimLevel,
                batterySaverEnabled = batterySaver
            )
            JarvisOperatingMode.ACTIVE_CONVERSATION,
            JarvisOperatingMode.OFFLINE_CONVERSATION -> ResourceGovernancePolicy(
                mode = effectiveMode,
                maxOrbFps = if (batterySaver) 30 else 60,
                allowBackgroundYamnet = true,
                allowPocketTtsKeepWarm = !batterySaver,
                allowDenseEmbedder = true,
                activeModelBudgetMb = DEVICE_6GB_SAFE_BUDGET_MB,
                thermalStatus = thermalStatus,
                lastTrimLevel = lastTrimLevel,
                batterySaverEnabled = batterySaver
            )
            JarvisOperatingMode.AUTONOMOUS_MISSION -> ResourceGovernancePolicy(
                mode = effectiveMode,
                maxOrbFps = 30,
                allowBackgroundYamnet = false,
                allowPocketTtsKeepWarm = !batterySaver,
                allowDenseEmbedder = true,
                activeModelBudgetMb = REDUCED_BUDGET_MB,
                thermalStatus = thermalStatus,
                lastTrimLevel = lastTrimLevel,
                batterySaverEnabled = batterySaver
            )
        }
    }
}
