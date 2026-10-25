package com.jarvis.assistant.agent

import android.graphics.RectF

data class UiElement(
    val id: String,
    val text: String = "",
    val contentDescription: String = "",
    val className: String = "",
    val bounds: RectF = RectF(),
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false
)

data class ScreenObservation(
    val observationId: String,
    val packageName: String,
    val windowClass: String,
    val elements: List<UiElement>,
    val screenSize: Pair<Int, Int>,
    val windowStamp: Long
) {
    fun isStale(currentStamp: Long, maxAgeMs: Long = 10_000L): Boolean {
        return (currentStamp - windowStamp) > maxAgeMs
    }
}
