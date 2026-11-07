package com.jarvis.assistant.core.capability

/**
 * Declarative capability model. This class never grants permissions or executes commands.
 * Runtime probes must supply the actual availability of each execution path.
 */
enum class ExecutionPath {
    ANDROID_API,
    ACCESSIBILITY,
    ADB,
    ROOT
}

enum class DeviceCapability {
    OPEN_APP,
    UI_INTERACTION,
    READ_NOTIFICATIONS,
    SET_BRIGHTNESS,
    TOGGLE_FLASHLIGHT,
    PRIVILEGED_SHELL,
    REBOOT_DEVICE
}

data class CapabilityAvailability(
    val availablePaths: Set<ExecutionPath>
)

object CapabilityPolicy {
    private val supportedPaths: Map<DeviceCapability, List<ExecutionPath>> = mapOf(
        DeviceCapability.OPEN_APP to listOf(ExecutionPath.ANDROID_API),
        DeviceCapability.UI_INTERACTION to listOf(ExecutionPath.ACCESSIBILITY, ExecutionPath.ADB, ExecutionPath.ROOT),
        DeviceCapability.READ_NOTIFICATIONS to listOf(ExecutionPath.ANDROID_API),
        DeviceCapability.SET_BRIGHTNESS to listOf(ExecutionPath.ANDROID_API, ExecutionPath.ADB, ExecutionPath.ROOT),
        DeviceCapability.TOGGLE_FLASHLIGHT to listOf(ExecutionPath.ANDROID_API),
        DeviceCapability.PRIVILEGED_SHELL to listOf(ExecutionPath.ADB, ExecutionPath.ROOT),
        DeviceCapability.REBOOT_DEVICE to listOf(ExecutionPath.ADB, ExecutionPath.ROOT)
    )

    /** Returns a candidate path, not authorization to execute an action. */
    fun preferredPath(
        capability: DeviceCapability,
        availability: CapabilityAvailability
    ): ExecutionPath? = supportedPaths.getValue(capability)
        .firstOrNull { it in availability.availablePaths }

    fun supportedPaths(capability: DeviceCapability): List<ExecutionPath> =
        supportedPaths.getValue(capability).toList()
}
