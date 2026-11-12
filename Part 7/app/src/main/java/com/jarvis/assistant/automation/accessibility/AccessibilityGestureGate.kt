package com.jarvis.assistant.automation.accessibility

/** Single native gesture at a time; an unknown outcome blocks replay until callback or explicit recovery. */
internal class AccessibilityGestureGate {
    private var sequence = 0L
    private var active: Long? = null
    @Synchronized fun begin(): Long? {
        if (active != null) return null
        return (++sequence).also { active = it }
    }
    @Synchronized fun finish(ticket: Long): Boolean {
        if (active != ticket) return false
        active = null
        return true
    }
    @Synchronized fun isActive(ticket: Long): Boolean = active == ticket
    @Synchronized fun reset() { active = null; sequence++ }
}
