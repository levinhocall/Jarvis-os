package com.jarvis.assistant.automation.grounding

import android.graphics.Rect

data class ScreenBounds(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom
}

/**
 * Screen element metadata extracted from Accessibility node hierarchies or vision inputs.
 */
data class GroundedElement(
    val id: String,
    val text: String = "",
    val contentDescription: String? = null,
    val bounds: Rect = Rect(),
    val screenBounds: ScreenBounds = ScreenBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isVisibleToUser: Boolean = true,
    val className: String = "",
    val packageName: String = "",
    val isPassword: Boolean = false,
    val isProtected: Boolean = false
) {
    val displayLabel: String
        get() = when {
            text.isNotBlank() -> text.trim()
            !contentDescription.isNullOrBlank() -> contentDescription.trim()
            id.isNotBlank() -> id.substringAfterLast("/")
            else -> className.substringAfterLast(".")
        }
}

enum class ScreenConfidence {
    HIGH,
    APPROXIMATE,
    STALE,
    UNCERTAIN
}

enum class ActionType {
    CLICK,
    TYPE,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    OBSERVE,
    NAVIGATE_BACK
}

enum class GroundingOutcome {
    FOUND_EXACT,
    FOUND_AMBIGUOUS,
    NOT_FOUND,
    STALE_OBSERVATION,
    INACCESSIBLE,
    SECURE_CONTENT_BLOCKED,
    COORDINATE_OUT_OF_BOUNDS
}

/**
 * Coherent representation of the current Android screen.
 */
data class ScreenContext(
    val foregroundPackage: String = "",
    val screenTitle: String? = null,
    val elements: List<GroundedElement> = emptyList(),
    val capturedTimestamp: Long = System.currentTimeMillis(),
    val isInvalidated: Boolean = false,
    val confidence: ScreenConfidence = ScreenConfidence.HIGH,
    val ttlMs: Long = 2500L,
    val isSecureWindow: Boolean = false,
    val securityReason: String? = null,
    val screenWidth: Int = 1080,
    val screenHeight: Int = 2400
) {
    val isFresh: Boolean
        get() = !isInvalidated && (System.currentTimeMillis() - capturedTimestamp <= ttlMs)

    fun findElementsMatching(query: String): List<GroundedElement> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return elements.filter { el ->
            el.text.lowercase().contains(q) ||
                (el.contentDescription?.lowercase()?.contains(q) == true) ||
                el.id.lowercase().contains(q)
        }
    }
}

data class GroundingMatch(
    val target: GroundedElement?,
    val matchOutcome: GroundingOutcome,
    val confidenceScore: Float = 0f,
    val candidates: List<GroundedElement> = emptyList(),
    val explanation: String = ""
)

data class ActionPreconditionResult(
    val isValid: Boolean,
    val reason: String,
    val requiresUserApproval: Boolean = false,
    val approvalPrompt: String? = null,
    val isInterruptedOrStale: Boolean = false
)

data class ActionOutcomeResult(
    val isVerified: Boolean,
    val stateMutated: Boolean,
    val message: String,
    val confidence: Float = 1.0f
)
