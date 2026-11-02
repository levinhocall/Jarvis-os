package com.jarvis.assistant.ui.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayDockingPolicyTest {
    @Test
    fun snapsToNearestHorizontalEdgeAndPreservesVerticalPosition() {
        val result = OverlayDockingPolicy.snap(
            x = 4, y = 420, overlayWidth = 88, overlayHeight = 88,
            screenWidth = 1080, screenHeight = 2400, horizontalInset = 8, topInset = 24
        )
        assertEquals("left", result.edge)
        assertEquals(8, result.x)
        assertEquals(420, result.y)
    }

    @Test
    fun snapsToNearestVerticalEdgeAndPreservesHorizontalPosition() {
        val result = OverlayDockingPolicy.snap(
            x = 470, y = 28, overlayWidth = 88, overlayHeight = 88,
            screenWidth = 1080, screenHeight = 2400, horizontalInset = 8, topInset = 24
        )
        assertEquals("top", result.edge)
        assertEquals(470, result.x)
        assertEquals(24, result.y)
    }

    @Test
    fun clampsCoordinatesBeforeChoosingEdge() {
        val result = OverlayDockingPolicy.snap(
            x = 9000, y = -500, overlayWidth = 100, overlayHeight = 100,
            screenWidth = 1080, screenHeight = 2400, horizontalInset = 8, topInset = 24
        )
        assertEquals("top", result.edge)
        assertEquals(980, result.x)
        assertEquals(24, result.y)
    }
}
