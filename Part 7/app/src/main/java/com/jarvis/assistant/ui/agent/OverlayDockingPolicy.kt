package com.jarvis.assistant.ui.agent

/**
 * Pure geometry for docking the floating overlay after a drag.
 * Coordinates use top/left screen gravity and are clamped before being returned.
 */
internal object OverlayDockingPolicy {
    data class Position(val edge: String, val x: Int, val y: Int)

    fun snap(
        x: Int,
        y: Int,
        overlayWidth: Int,
        overlayHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        horizontalInset: Int = 0,
        topInset: Int = 0
    ): Position {
        val maxX = (screenWidth - overlayWidth).coerceAtLeast(0)
        val maxY = (screenHeight - overlayHeight).coerceAtLeast(topInset.coerceAtLeast(0))
        val safeX = x.coerceIn(0, maxX)
        val safeY = y.coerceIn(topInset.coerceAtLeast(0), maxY)

        val distances = listOf(
            "left" to safeX,
            "right" to (maxX - safeX),
            "top" to (safeY - topInset.coerceAtLeast(0)),
            "bottom" to (maxY - safeY)
        )
        val edge = distances.minByOrNull { it.second }?.first ?: "right"
        val snappedX = when (edge) {
            "left" -> horizontalInset.coerceAtLeast(0).coerceAtMost(maxX)
            "right" -> (maxX - horizontalInset.coerceAtLeast(0)).coerceAtLeast(0)
            else -> safeX
        }
        val snappedY = when (edge) {
            "top" -> topInset.coerceAtLeast(0)
            "bottom" -> maxY
            else -> safeY
        }
        return Position(edge, snappedX, snappedY)
    }
}
