package com.jarvis.assistant.agent

class UiActionExecutor(private val driver: ScreenDriver) {

    fun executeClick(
        observation: ScreenObservation,
        targetElement: UiElement,
        expectTargetGone: Boolean = false
    ): ExecutionResult {
        if (observation.isStale(System.currentTimeMillis())) {
            return ExecutionResult.Stale
        }

        val result = driver.clickElement(observation, targetElement.id)
        if (!result.isSuccess) {
            return ExecutionResult.Failed(result.message ?: "Click dispatch failed")
        }

        if (expectTargetGone) {
            val nextObs = driver.capture()
            val stillPresent = nextObs.elements.any { it.id == targetElement.id }
            if (stillPresent) {
                return ExecutionResult.Failed("Target element ${targetElement.id} still present after click")
            }
        }
        return ExecutionResult.Success
    }

    fun executeTap(x: Float, y: Float): ExecutionResult {
        val result = driver.gestureTap(x, y)
        return if (result.isSuccess) ExecutionResult.Success
        else ExecutionResult.Failed(result.message ?: "Tap failed at ($x, $y)")
    }
}
