package com.jarvis.assistant.automation.grounding

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import java.util.ArrayDeque
import java.util.Locale

/**
 * Real-Time Multimodal Screen Grounding Engine.
 *
 * Provides grounded, fresh UI context from the active Accessibility node tree,
 * resolves semantic element queries without blind guessing, handles ambiguity,
 * validates action preconditions, and verifies state transitions.
 */
object ScreenGroundingEngine {
    private const val TAG = "ScreenGroundingEngine"
    private const val MAX_TRAVERSAL_NODES = 250
    const val DEFAULT_CONTEXT_TTL_MS = 2500L

    @Volatile
    private var cachedContext: ScreenContext? = null

    /**
     * Invalidates the active screen context whenever an action mutation, gesture,
     * scroll, or app navigation begins.
     */
    fun invalidate(reason: String) {
        val current = cachedContext
        if (current != null && !current.isInvalidated) {
            Log.d(TAG, "Invalidating ScreenContext: $reason")
            cachedContext = current.copy(isInvalidated = true, confidence = ScreenConfidence.STALE)
        }
    }

    /**
     * Gets the current screen context if fresh, or re-captures it from the accessibility service.
     */
    fun getOrCaptureContext(service: JarvisAccessibilityService?, forceRefresh: Boolean = false): ScreenContext {
        val existing = cachedContext
        if (!forceRefresh && existing != null && existing.isFresh) {
            return existing
        }
        return captureScreen(service)
    }

    /**
     * Directly parses the active AccessibilityNodeInfo tree into a structured ScreenContext.
     */
    fun captureScreen(service: JarvisAccessibilityService?): ScreenContext {
        if (service == null) {
            val empty = ScreenContext(
                foregroundPackage = "",
                screenTitle = null,
                elements = emptyList(),
                capturedTimestamp = System.currentTimeMillis(),
                isInvalidated = false,
                confidence = ScreenConfidence.UNCERTAIN
            )
            cachedContext = empty
            return empty
        }

        val root = service.getActiveRootNode() ?: service.getAppRootNode()
        val context = parseNodeHierarchy(root)
        cachedContext = context
        return context
    }

    /**
     * Pure parser from an AccessibilityNodeInfo root into a ScreenContext.
     * Can be invoked directly in unit tests with mock/fake nodes.
     */
    fun parseNodeHierarchy(root: AccessibilityNodeInfo?): ScreenContext {
        if (root == null) {
            return ScreenContext(
                foregroundPackage = "",
                screenTitle = null,
                elements = emptyList(),
                capturedTimestamp = System.currentTimeMillis(),
                confidence = ScreenConfidence.UNCERTAIN
            )
        }

        val pkgName = root.packageName?.toString() ?: ""
        val elements = mutableListOf<GroundedElement>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var inspectedCount = 0
        var detectedTitle: String? = null
        val tempRect = Rect()

        while (queue.isNotEmpty() && inspectedCount < MAX_TRAVERSAL_NODES) {
            val node = queue.removeFirst()
            inspectedCount++

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val viewId = node.viewIdResourceName?.toString() ?: ""
            val className = node.className?.toString() ?: ""
            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isScrollable = node.isScrollable
            val isVisible = node.isVisibleToUser

            node.getBoundsInScreen(tempRect)

            val isPassword = try { node.isPassword } catch (_: Throwable) { false }

            // Capture potential screen title from prominent top-level text
            if (detectedTitle == null && text.isNotBlank() && tempRect.top < 250 && !isClickable && !isEditable) {
                detectedTitle = text
            }

            // Only retain interactive or content-bearing elements
            if (isVisible && (text.isNotBlank() || desc.isNotBlank() || isClickable || isEditable || isPassword)) {
                elements.add(
                    GroundedElement(
                        id = viewId,
                        text = if (isPassword) "••••••" else text,
                        contentDescription = if (isPassword) "Password Field" else desc.ifBlank { null },
                        bounds = Rect(tempRect),
                        screenBounds = ScreenBounds(tempRect.left, tempRect.top, tempRect.right, tempRect.bottom),
                        isClickable = isClickable,
                        isEditable = isEditable,
                        isScrollable = isScrollable,
                        isVisibleToUser = isVisible,
                        className = className,
                        packageName = node.packageName?.toString() ?: pkgName,
                        isPassword = isPassword,
                        isProtected = isPassword
                    )
                )
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
        }

        val hasPassword = elements.any { it.isPassword }
        val isSecurePkg = pkgName.contains("keyguard", ignoreCase = true) ||
            pkgName.contains("credentials", ignoreCase = true)

        return ScreenContext(
            foregroundPackage = pkgName,
            screenTitle = detectedTitle,
            elements = elements,
            capturedTimestamp = System.currentTimeMillis(),
            isInvalidated = false,
            confidence = if (hasPassword || isSecurePkg) ScreenConfidence.APPROXIMATE else ScreenConfidence.HIGH,
            isSecureWindow = hasPassword || isSecurePkg,
            securityReason = if (hasPassword) "Password input detected" else if (isSecurePkg) "Secure system screen" else null
        )
    }

    /**
     * Grounds a screen coordinate (x, y) into a semantic UI element with bounds checking.
     * Prevents blind blind clicks if the coordinate falls outside screen bounds or hits protected content.
     */
    fun groundCoordinate(
        screen: ScreenContext,
        x: Int,
        y: Int,
        actionType: ActionType = ActionType.CLICK
    ): GroundingMatch {
        if (!screen.isFresh) {
            return GroundingMatch(
                target = null,
                matchOutcome = GroundingOutcome.STALE_OBSERVATION,
                explanation = "Screen context expired. Re-observation required before dispatching coordinate ($x, $y)."
            )
        }

        if (screen.isSecureWindow) {
            return GroundingMatch(
                target = null,
                matchOutcome = GroundingOutcome.SECURE_CONTENT_BLOCKED,
                explanation = "Screen is in a secure window (${screen.securityReason ?: "FLAG_SECURE"}). Automated interaction blocked."
            )
        }

        if (x < 0 || x > screen.screenWidth || y < 0 || y > screen.screenHeight) {
            return GroundingMatch(
                target = null,
                matchOutcome = GroundingOutcome.COORDINATE_OUT_OF_BOUNDS,
                explanation = "Coordinate ($x, $y) is outside screen boundaries (${screen.screenWidth}x${screen.screenHeight})."
            )
        }

        val covering = screen.elements.filter { el ->
            el.screenBounds.contains(x, y)
        }.sortedBy { it.screenBounds.width * it.screenBounds.height }

        val best = covering.firstOrNull()
        if (best != null) {
            if (best.isPassword || best.isProtected) {
                return GroundingMatch(
                    target = best,
                    matchOutcome = GroundingOutcome.SECURE_CONTENT_BLOCKED,
                    explanation = "Element at ($x, $y) is a protected or password field."
                )
            }
            return GroundingMatch(
                target = best,
                matchOutcome = GroundingOutcome.FOUND_EXACT,
                confidenceScore = 0.95f,
                candidates = covering,
                explanation = "Grounded coordinate ($x, $y) to semantic node '${best.displayLabel}' [${best.className}]."
            )
        }

        return GroundingMatch(
            target = null,
            matchOutcome = GroundingOutcome.NOT_FOUND,
            confidenceScore = 0.3f,
            explanation = "Coordinate ($x, $y) does not hit any registered semantic interactive element."
        )
    }

    /**
     * Provides clear recovery guidance when grounding fails.
     */
    fun getRecoveryGuidance(outcome: GroundingOutcome, query: String = ""): String {
        return when (outcome) {
            GroundingOutcome.FOUND_EXACT -> "Target successfully identified."
            GroundingOutcome.FOUND_AMBIGUOUS -> "Multiple elements match '$query'. Please specify by unique label or location."
            GroundingOutcome.NOT_FOUND -> "Target '$query' was not found on screen. Try scrolling or check if the target app is open."
            GroundingOutcome.STALE_OBSERVATION -> "Screen changed during action planning. Please refresh view."
            GroundingOutcome.INACCESSIBLE -> "Screen is currently inaccessible. Check if Accessibility Service is enabled."
            GroundingOutcome.SECURE_CONTENT_BLOCKED -> "Content is protected by Android security (FLAG_SECURE/credentials). User must complete this action manually."
            GroundingOutcome.COORDINATE_OUT_OF_BOUNDS -> "Coordinates were out of current screen bounds. Recalculation required."
        }
    }

    /**
     * Resolves a semantic target query against the provided screen context.
     * Prevents blind coordinate clicking if target is ambiguous, missing, or stale.
     */
    fun resolveTarget(
        screen: ScreenContext,
        query: String,
        actionType: ActionType
    ): GroundingMatch {
        if (!screen.isFresh) {
            return GroundingMatch(
                target = null,
                matchOutcome = GroundingOutcome.STALE_OBSERVATION,
                explanation = "Screen context is stale or invalidated. Re-observation required."
            )
        }

        if (screen.isSecureWindow) {
            return GroundingMatch(
                target = null,
                matchOutcome = GroundingOutcome.SECURE_CONTENT_BLOCKED,
                explanation = "Screen is in a secure window (${screen.securityReason ?: "FLAG_SECURE"}). Automated interaction blocked."
            )
        }

        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        if (cleanQuery.isEmpty()) {
            return GroundingMatch(
                target = null,
                matchOutcome = GroundingOutcome.NOT_FOUND,
                explanation = "Empty target query provided."
            )
        }

        // Filter elements by compatibility with requested action
        val candidates = screen.elements.filter { el ->
            when (actionType) {
                ActionType.CLICK -> el.isClickable || el.text.isNotBlank() || !el.contentDescription.isNullOrBlank()
                ActionType.TYPE -> el.isEditable || el.className.contains("EditText", ignoreCase = true)
                ActionType.SCROLL_FORWARD, ActionType.SCROLL_BACKWARD -> el.isScrollable || el.className.contains("Scroll", ignoreCase = true) || el.className.contains("Recycler", ignoreCase = true)
                ActionType.OBSERVE -> true
                ActionType.NAVIGATE_BACK -> false
            }
        }

        // Score candidates based on text match, contentDescription match, viewId match, and bounds validity
        data class ScoredCandidate(val element: GroundedElement, val score: Float)

        val scored = candidates.mapNotNull { el ->
            var score = 0f
            val elText = el.text.lowercase(Locale.ROOT)
            val elDesc = el.contentDescription?.lowercase(Locale.ROOT) ?: ""
            val elId = el.id.lowercase(Locale.ROOT)

            // Exact match
            if (elText == cleanQuery || elDesc == cleanQuery) {
                score += 1.0f
            } else if (elText.contains(cleanQuery) || elDesc.contains(cleanQuery)) {
                score += 0.8f
            } else if (elId.contains(cleanQuery)) {
                score += 0.6f
            } else {
                // Word-level token match
                val queryWords = cleanQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
                val matchedWords = queryWords.count { word -> elText.contains(word) || elDesc.contains(word) }
                if (matchedWords > 0) {
                    score += 0.5f * (matchedWords.toFloat() / queryWords.size)
                }
            }

            if (score > 0f) {
                // Priority bonus for clickability on click actions
                if (actionType == ActionType.CLICK && el.isClickable) score += 0.1f
                if (actionType == ActionType.TYPE && el.isEditable) score += 0.1f
                ScoredCandidate(el, score)
            } else null
        }.sortedByDescending { it.score }

        if (scored.isEmpty()) {
            return GroundingMatch(
                target = null,
                matchOutcome = GroundingOutcome.NOT_FOUND,
                explanation = "No eligible UI element matched query '$query'."
            )
        }

        val best = scored.first()

        // Check for Ambiguity: If multiple top candidates have nearly identical high scores
        val nearMatches = scored.filter { it.score >= 0.7f && (best.score - it.score) < 0.15f }
        if (nearMatches.size > 1) {
            // Check if they are distinct elements
            val distinctBounds = nearMatches.map { it.element.bounds }.distinct()
            if (distinctBounds.size > 1) {
                return GroundingMatch(
                    target = null,
                    matchOutcome = GroundingOutcome.FOUND_AMBIGUOUS,
                    confidenceScore = best.score,
                    candidates = nearMatches.map { it.element },
                    explanation = "Multiple elements (${nearMatches.size}) match '$query'. Clarification or refined selector required."
                )
            }
        }

        return GroundingMatch(
            target = best.element,
            matchOutcome = GroundingOutcome.FOUND_EXACT,
            confidenceScore = best.score,
            candidates = listOf(best.element),
            explanation = "Grounded element '${best.element.displayLabel}' with confidence ${best.score}."
        )
    }

    /**
     * Validates preconditions before an action is dispatched to the accessibility service.
     */
    fun validatePrecondition(
        target: GroundedElement?,
        actionType: ActionType,
        screen: ScreenContext
    ): ActionPreconditionResult {
        if (!screen.isFresh) {
            return ActionPreconditionResult(
                isValid = false,
                reason = "Screen context expired or invalidated before action dispatch.",
                isInterruptedOrStale = true
            )
        }

        if (screen.isSecureWindow) {
            return ActionPreconditionResult(
                isValid = false,
                reason = "Cannot execute action in secure window: ${screen.securityReason ?: "FLAG_SECURE"}.",
                isInterruptedOrStale = false
            )
        }

        if (actionType == ActionType.NAVIGATE_BACK) {
            return ActionPreconditionResult(isValid = true, reason = "System back action is always valid.")
        }

        if (target == null) {
            return ActionPreconditionResult(
                isValid = false,
                reason = "Cannot execute $actionType without a grounded target element."
            )
        }

        if (target.isPassword || target.isProtected) {
            return ActionPreconditionResult(
                isValid = false,
                reason = "Target '${target.displayLabel}' is a secure/password element. Automated interaction rejected."
            )
        }

        // Validate bounds
        if (target.screenBounds.width <= 0 || target.screenBounds.height <= 0) {
            return ActionPreconditionResult(
                isValid = false,
                reason = "Target element '${target.displayLabel}' has invalid screen bounds: ${target.screenBounds}."
            )
        }

        // Verify element is still present in current screen elements
        val stillPresent = screen.elements.any { el ->
            el.screenBounds == target.screenBounds && el.className == target.className
        }
        if (!stillPresent) {
            return ActionPreconditionResult(
                isValid = false,
                reason = "Target element '${target.displayLabel}' is no longer visible on screen.",
                isInterruptedOrStale = true
            )
        }

        return ActionPreconditionResult(isValid = true, reason = "Preconditions verified for ${target.displayLabel}.")
    }

    /**
     * Verifies the actual UI outcome by comparing before and after screen contexts.
     * Never falsely reports success without evidence.
     */
    fun verifyOutcome(
        actionType: ActionType,
        before: ScreenContext,
        after: ScreenContext,
        expectedMutation: String = ""
    ): ActionOutcomeResult {
        if (after.elements.isEmpty()) {
            return ActionOutcomeResult(
                isVerified = false,
                stateMutated = false,
                message = "Post-action screen is empty or inaccessible.",
                confidence = 0f
            )
        }

        // Check if package changed (e.g. app launched or switched)
        val packageChanged = before.foregroundPackage != after.foregroundPackage
        if (packageChanged) {
            return ActionOutcomeResult(
                isVerified = true,
                stateMutated = true,
                message = "Foreground application transitioned from ${before.foregroundPackage} to ${after.foregroundPackage}.",
                confidence = 1.0f
            )
        }

        // Compare element count and texts
        val beforeTexts = before.elements.mapNotNull { it.text.ifBlank { null } }.toSet()
        val afterTexts = after.elements.mapNotNull { it.text.ifBlank { null } }.toSet()
        val textMutated = beforeTexts != afterTexts

        if (expectedMutation.isNotBlank()) {
            val expectedClean = expectedMutation.trim().lowercase(Locale.ROOT)
            val appearsInAfter = after.elements.any {
                it.text.lowercase(Locale.ROOT).contains(expectedClean) ||
                    (it.contentDescription?.lowercase(Locale.ROOT)?.contains(expectedClean) == true)
            }
            if (appearsInAfter) {
                return ActionOutcomeResult(
                    isVerified = true,
                    stateMutated = true,
                    message = "Expected mutation '$expectedMutation' verified on screen.",
                    confidence = 1.0f
                )
            }
        }

        if (textMutated) {
            return ActionOutcomeResult(
                isVerified = true,
                stateMutated = true,
                message = "Screen content updated successfully after $actionType.",
                confidence = 0.9f
            )
        }

        return ActionOutcomeResult(
            isVerified = false,
            stateMutated = false,
            message = "No detectable UI mutation observed after $actionType.",
            confidence = 0.4f
        )
    }
}
