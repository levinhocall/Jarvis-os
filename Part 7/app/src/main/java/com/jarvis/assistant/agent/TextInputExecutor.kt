package com.jarvis.assistant.agent

class TextInputExecutor(private val driver: ScreenDriver) {

    fun executeSetText(
        elementId: String,
        text: String,
        verifyPresent: Boolean = true
    ): ExecutionResult {
        val result = driver.setText(elementId, text)
        if (!result.isSuccess) {
            return ExecutionResult.Failed(result.message ?: "Set text failed on $elementId")
        }

        if (verifyPresent) {
            val obs = driver.capture()
            val hasText = obs.elements.any {
                (it.id == elementId || it.isEditable) && it.text.contains(text, ignoreCase = true)
            }
            if (!hasText) {
                return ExecutionResult.Failed("Text was set but not observed in element $elementId")
            }
        }
        return ExecutionResult.Success
    }
}
