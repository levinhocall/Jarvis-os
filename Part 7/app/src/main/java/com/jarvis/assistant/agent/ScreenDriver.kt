package com.jarvis.assistant.agent

enum class DriverStatus {
    OK,
    FAILED,
    STALE,
    NOT_FOUND,
    UNSUPPORTED
}

data class DriverResult(
    val status: DriverStatus,
    val message: String? = null,
    val details: Map<String, Any?> = emptyMap()
) {
    val isSuccess: Boolean get() = status == DriverStatus.OK

    companion object {
        fun ok(message: String? = null) = DriverResult(DriverStatus.OK, message)
        fun failed(message: String) = DriverResult(DriverStatus.FAILED, message)
        fun stale(message: String = "Observation is stale") = DriverResult(DriverStatus.STALE, message)
        fun notFound(message: String = "Element not found") = DriverResult(DriverStatus.NOT_FOUND, message)
        fun unsupported(message: String = "Action not supported") = DriverResult(DriverStatus.UNSUPPORTED, message)
    }
}

/**
 * The ONLY boundary with the device hardware / accessibility layer.
 * Allows pure scripted test substitution via FakeScreenDriver.
 */
interface ScreenDriver {
    fun capture(): ScreenObservation
    fun clickElement(obs: ScreenObservation, elementId: String): DriverResult
    fun gestureTap(x: Float, y: Float): DriverResult
    fun setText(elementId: String, text: String): DriverResult
    fun scroll(direction: String): DriverResult
    fun back(): DriverResult
    fun home(): DriverResult
}
