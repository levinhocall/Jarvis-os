package com.jarvis.assistant.automation.accessibility

/** Shared by a legacy recursive search and all its children; no one budget per recursive node. */
internal class AccessibilityTraversalBudget(
    private val maximumNodes: Int = 256, private val maximumDepth: Int = 48,
    private val maximumMs: Long = 40, private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val started = now()
    private var visited = 0
    private var depth = 0
    fun exhausted(): Boolean = visited >= maximumNodes || depth >= maximumDepth || now() - started >= maximumMs
    fun enter(): Boolean { if (exhausted()) return false; visited++; depth++; return true }
    fun leave() { check(depth > 0); depth-- }
}

internal object AccessibilityInputTargetPolicy {
    fun sameTarget(expectedPackage: String, actualPackage: String, expectedWindow: Int, actualWindow: Int,
        expectedId: String?, actualId: String?, sameBounds: Boolean): Boolean =
        expectedPackage.isNotBlank() && expectedPackage == actualPackage && expectedWindow == actualWindow &&
            (if (!expectedId.isNullOrBlank()) expectedId == actualId else sameBounds)
}
