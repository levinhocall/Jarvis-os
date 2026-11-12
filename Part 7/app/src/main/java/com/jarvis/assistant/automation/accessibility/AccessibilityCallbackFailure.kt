package com.jarvis.assistant.automation.accessibility

/** Never persist/log a provider's exception message: it can embed screen text. */
internal fun accessibilityCallbackFailure(error: Exception): AccessibilityIssue = when (error) {
    is SecurityException -> AccessibilityIssue.PERMISSION_DENIED
    is IllegalStateException -> AccessibilityIssue.STALE_NODE
    else -> AccessibilityIssue.INTERNAL
}
