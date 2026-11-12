package com.jarvis.assistant.automation.agent

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.automation.accessibility.AccessibilityHealthModel
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.delay

/**
 * Closed-loop action verification engine.
 * Never assumes an action succeeded just because a gesture or API call returned true.
 * Performs rigorous before/after state comparison.
 */
object ActionVerifier {
    private const val TAG = "ActionVerifier"

    /**
     * Verifies that typing actually inserted the expected text into the target input field.
     */
    suspend fun verifyTyping(
        service: JarvisAccessibilityService,
        targetRect: Rect?,
        expectedText: String,
        retries: Int = 2
    ): VerificationResult {
        val owner = service.connectionToken
        fun recordOwned(result: VerificationResult) = record(result, owner)
        val cleanExpected = expectedText.trim()
        if (cleanExpected.isEmpty()) {
            return VerificationResult(isVerified = true, reason = "Empty text to type")
        }

        for (attempt in 0..retries.coerceIn(0, 2)) {
            delay(80)
            val root = service.getActiveRootNode() ?: service.getAppRootNode()
            if (root == null) {
                if (attempt < retries.coerceIn(0, 2)) continue
                return VerificationResult(
                    isVerified = false,
                    reason = "No active root node found to verify typed text"
                )
            }

            var targetNode: AccessibilityNodeInfo? = null
            try {
            // 1. Check node at target rect
            if (targetRect != null && targetRect.width() > 0) {
                targetNode = service.findNodeCoveringRect(root, targetRect)
            }

            // A target rectangle must resolve back to that same visible editable field.
            // Falling back to an unrelated focused/first editable field could verify
            // text in the wrong input and falsely report success.
            if (targetNode == null) {
                if (targetRect == null) {
                    targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        ?: service.findNodeWithInputFocus(root)
                }
            }

            if (targetNode != null && targetNode.refresh() && targetNode.isVisibleToUser && targetNode.isEditable) {
                val actualText = targetNode.text?.toString() ?: ""
                // Do not log entered text: it may contain searches, messages, or credentials.
                Log.d(TAG, "Verify typing: target length=${actualText.length}, expected length=${cleanExpected.length}")
                val normalize = { value: String ->
                    value.replace("\r\n", "\n").replace(Regex("\\s+"), " ").trim()
                }

                if (normalize(actualText) == normalize(cleanExpected)) {
                    return recordOwned(VerificationResult(
                        isVerified = true,
                        reason = "Text verified in target field",
                        stateChanged = true,
                        actualValue = null
                    ))
                }
            }
            } finally {
                if (targetNode !== root) service.recycleOwnedNode(targetNode)
                service.recycleOwnedNode(root)
            }
        }

        return recordOwned(VerificationResult(
            isVerified = false,
            reason = "Text mismatch: the target input value did not match the requested text",
            stateChanged = false,
            isRetryable = true
        ))
    }

    /**
     * Verifies that a click caused a measurable UI state change.
     */
    suspend fun verifyClick(
        preSignature: ScreenSignature,
        service: JarvisAccessibilityService,
        waitMs: Long = 300L
    ): VerificationResult {
        val owner = service.connectionToken
        fun recordOwned(result: VerificationResult) = record(result, owner)
        delay(waitMs)
        val postSignature = captureSignature(service)
        if (!canCompare(preSignature, postSignature)) return recordOwned(VerificationResult(
            false, "A before/after screen observation is unavailable; click outcome is unverified", isRetryable = false))

        // 1. Window or package transition
        if (postSignature.packageName != preSignature.packageName ||
            postSignature.className != preSignature.className) {
            return recordOwned(VerificationResult(
                isVerified = true,
                reason = "Window transitioned successfully (${postSignature.packageName ?: postSignature.className})",
                stateChanged = true
            ))
        }

        // 2. Element tree change
        if (!postSignature.isEquivalentTo(preSignature)) {
            return recordOwned(VerificationResult(
                isVerified = true,
                reason = "UI content updated following click",
                stateChanged = true
            ))
        }

        // Slight extra delay in case of slow animation
        delay(200L)
        val secondSignature = captureSignature(service)
        if (canCompare(preSignature, secondSignature) && !secondSignature.isEquivalentTo(preSignature)) {
            return recordOwned(VerificationResult(
                isVerified = true,
                reason = "UI transitioned after layout animation",
                stateChanged = true
            ))
        }

        return recordOwned(VerificationResult(
            isVerified = false,
            reason = "No detectable UI change after click (UI state remained identical)",
            stateChanged = false,
            isRetryable = true
        ))
    }

    /**
     * Verifies that a scroll produced new visible items or reached the end of list.
     */
    fun verifyScroll(
        preSignature: ScreenSignature,
        postSignature: ScreenSignature
    ): VerificationResult {
        if (!canCompare(preSignature, postSignature)) return record(VerificationResult(
            false, "A before/after screen observation is unavailable; scroll outcome is unverified"))
        if (postSignature.isEquivalentTo(preSignature)) {
            return record(VerificationResult(
                isVerified = false,
                reason = "No visible scroll progress detected; the list end is unproven",
                stateChanged = false
            ))
        }
        return record(VerificationResult(
            isVerified = true,
            reason = "Scroll revealed new content",
            stateChanged = true
        ))
    }

    internal fun canCompare(before: ScreenSignature, after: ScreenSignature): Boolean =
        before.elementCount > 0 && after.elementCount > 0 && before.packageName.isNotBlank() && after.packageName.isNotBlank()

    private fun captureSignature(service: JarvisAccessibilityService): ScreenSignature {
        val root = service.getActiveRootNode()
        return try { ScreenSignatureCalculator.computeSignature(root) } finally { service.recycleOwnedNode(root) }
    }

    private fun record(result: VerificationResult, owner: Long = JarvisAccessibilityService.instance?.connectionToken ?: 0L): VerificationResult {
        AccessibilityHealthModel.recordActionVerified(result.isVerified, owner = owner)
        return result
    }
}
