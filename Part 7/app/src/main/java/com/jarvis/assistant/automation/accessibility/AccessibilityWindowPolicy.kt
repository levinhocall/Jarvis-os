package com.jarvis.assistant.automation.accessibility

/** Window metadata only; no retained Android node handles or inactive-app fallback. */
internal data class AccessibilityWindowCandidate(val id: Int, val application: Boolean, val system: Boolean,
    val focused: Boolean, val active: Boolean, val ownApp: Boolean)
internal object AccessibilityWindowPolicy {
    fun select(windows: List<AccessibilityWindowCandidate>, excludeOwnApp: Boolean): Int? = windows
        .filter { (it.application || it.system) && (it.focused || it.active) && (!excludeOwnApp || !it.ownApp) }
        .maxByOrNull { (if (it.focused) 100 else 0) + (if (it.active) 20 else 0) + (if (it.system) 2 else 1) }?.id
}
