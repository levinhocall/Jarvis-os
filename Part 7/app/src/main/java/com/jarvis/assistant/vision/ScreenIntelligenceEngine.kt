package com.jarvis.assistant.vision

import android.graphics.Rect
import android.util.Log
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.agent.ActionVerifier
import com.jarvis.assistant.automation.agent.ScreenSignatureCalculator
import com.jarvis.assistant.automation.grounding.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class ScreenDiagnosis(
    val appName: String,
    val screenSummary: String,
    val visibleElementsCount: Int,
    val detectedErrors: List<String> = emptyList(),
    val suggestedActions: List<String> = emptyList(),
    val hasSensitiveFields: Boolean = false,
    val hasPromptInjection: Boolean = false,
    val injectionWarning: String? = null
)

object ScreenIntelligenceEngine {
    private const val TAG = "ScreenIntelligence"

    // Prompt injection heuristic patterns found in untrusted third-party screen content
    private val PROMPT_INJECTION_PATTERNS = listOf(
        Regex("(?i)\\bignore\\s+(?:all\\s+)?(?:previous|prior)\\s+instructions?\\b"),
        Regex("(?i)\\byou\\s+are\\s+now\\s+in\\s+(?:developer|unrestricted|god)\\s+mode\\b"),
        Regex("(?i)\\bsystem\\s+prompt\\s*:\\s*reveal\\b"),
        Regex("(?i)\\bdelete\\s+(?:all\\s+)?(?:data|database|files|photos)\\b"),
        Regex("(?i)\\bsend\\s+(?:all\\s+)?(?:sms|contacts|otp|passwords)\\s+to\\b")
    )

    /**
     * Inspects the current active screen hierarchy, detects errors, suggests next actions,
     * and checks for sensitive data and untrusted prompt injections.
     */
    fun diagnoseScreen(service: JarvisAccessibilityService?): ScreenDiagnosis {
        if (service == null) {
            return ScreenDiagnosis(
                appName = "Unknown",
                screenSummary = "Accessibility service is inactive. Screen observation unavailable.",
                visibleElementsCount = 0
            )
        }

        val screenContext = ScreenGroundingEngine.captureScreen(service)
        val pkg = screenContext.foregroundPackage
        val appName = pkg.substringAfterLast(".").replaceFirstChar { it.uppercase() }

        val detectedErrors = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        var hasSensitive = false
        var hasInjection = false
        var injectionDetails: String? = null

        val visibleLabels = mutableListOf<String>()

        for (el in screenContext.elements) {
            val label = el.displayLabel
            if (label.isBlank()) continue
            visibleLabels.add(label)

            // Sensitive field detection
            if (el.className.contains("Password", ignoreCase = true) ||
                label.contains("password", ignoreCase = true) ||
                label.contains("pin", ignoreCase = true) ||
                label.contains("otp", ignoreCase = true)
            ) {
                hasSensitive = true
            }

            // Prompt injection detection
            for (pattern in PROMPT_INJECTION_PATTERNS) {
                if (pattern.containsMatchIn(label)) {
                    hasInjection = true
                    injectionDetails = "Detected malicious instruction on screen: '${label.take(50)}'"
                    Log.w(TAG, "Screen Prompt Injection Blocked: $injectionDetails")
                }
            }

            // Error & crash detection
            val lower = label.lowercase(Locale.ROOT)
            if (lower.contains("error") || lower.contains("failed") || lower.contains("something went wrong") ||
                lower.contains("try again") || lower.contains("has stopped") || lower.contains("not responding")
            ) {
                detectedErrors.add(label)
            }

            // Action suggestions
            if (el.isClickable && (lower.contains("retry") || lower.contains("ok") || lower.contains("continue") || lower.contains("submit") || lower.contains("next"))) {
                suggestions.add("Click '${el.displayLabel}'")
            }
        }

        val summary = if (detectedErrors.isNotEmpty()) {
            "Active app is $appName with error: '${detectedErrors.first()}'."
        } else if (visibleLabels.isNotEmpty()) {
            "Active app is $appName showing: ${visibleLabels.take(3).joinToString(", ")}."
        } else {
            "Active app is $appName (empty or secure view)."
        }

        return ScreenDiagnosis(
            appName = appName,
            screenSummary = summary,
            visibleElementsCount = screenContext.elements.size,
            detectedErrors = detectedErrors,
            suggestedActions = suggestions,
            hasSensitiveFields = hasSensitive,
            hasPromptInjection = hasInjection,
            injectionWarning = injectionDetails
        )
    }

    /**
     * Explains the screen in plain English for voice responses.
     */
    fun explainScreen(service: JarvisAccessibilityService?): String {
        val diagnosis = diagnoseScreen(service)
        if (diagnosis.hasPromptInjection) {
            return "Screen content contains untrusted instructions which were blocked for security, Nazib Sir."
        }
        val errorPart = if (diagnosis.detectedErrors.isNotEmpty()) {
            " Warning: detected issue '${diagnosis.detectedErrors.first()}'."
        } else ""
        val suggestionPart = if (diagnosis.suggestedActions.isNotEmpty()) {
            " Recommended action: ${diagnosis.suggestedActions.first()}."
        } else ""

        return "${diagnosis.screenSummary}$errorPart$suggestionPart"
    }

    /**
     * Safely executes an action on a grounded element after validating permissions and preconditions.
     */
    suspend fun executeValidatedAction(
        service: JarvisAccessibilityService?,
        elementQuery: String,
        actionType: ActionType,
        textToType: String? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.Default) {
        if (service == null) {
            return@withContext Pair(false, "Accessibility service is disabled; action cannot be performed.")
        }

        val diagnosis = diagnoseScreen(service)
        if (diagnosis.hasPromptInjection) {
            return@withContext Pair(false, "Refused to execute action: screen contains untrusted prompt injection instructions.")
        }

        val context = ScreenGroundingEngine.captureScreen(service)
        val matches = context.findElementsMatching(elementQuery)
        if (matches.isEmpty()) {
            return@withContext Pair(false, "Element matching '$elementQuery' was not found on the active screen.")
        }

        val target = matches.first()
        val rootNode = service.getActiveRootNode() ?: service.getAppRootNode()
            ?: return@withContext Pair(false, "Could not acquire root node from Accessibility service.")

        val preSignature = ScreenSignatureCalculator.computeSignature(rootNode)

        return@withContext when (actionType) {
            ActionType.CLICK -> {
                val nodeToClick = service.findNodeCoveringRect(rootNode, target.bounds)
                if (nodeToClick != null && service.clickNodeAndTap(nodeToClick)) {
                    val verifyResult = ActionVerifier.verifyClick(preSignature, service)
                    Pair(true, "Clicked '${target.displayLabel}'. Verified: ${verifyResult.reason}")
                } else {
                    val clicked = service.clickAt(target.bounds.centerX().toFloat(), target.bounds.centerY().toFloat())
                    Pair(clicked, if (clicked) "Dispatched gesture tap at (${target.bounds.centerX()}, ${target.bounds.centerY()})" else "Click gesture failed")
                }
            }
            ActionType.TYPE -> {
                val text = textToType ?: ""
                val nodeToType = service.findNodeCoveringRect(rootNode, target.bounds)
                if (nodeToType != null) {
                    service.clickNodeAndTap(nodeToType)
                }
                val typed = service.typeText(text)
                val verifyResult = ActionVerifier.verifyTyping(service, target.bounds, text)
                Pair(typed && verifyResult.isVerified, "Typing into '${target.displayLabel}': ${verifyResult.reason}")
            }
            ActionType.SCROLL_FORWARD -> {
                val scrolled = service.performSmartScroll("down")
                Pair(scrolled, if (scrolled) "Scrolled down" else "Scroll forward was not supported on current container")
            }
            ActionType.SCROLL_BACKWARD -> {
                val scrolled = service.performSmartScroll("up")
                Pair(scrolled, if (scrolled) "Scrolled up" else "Scroll backward was not supported on current container")
            }
            ActionType.NAVIGATE_BACK -> {
                val back = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                Pair(back, if (back) "Navigated back" else "Back action failed")
            }
            ActionType.OBSERVE -> {
                Pair(true, "Observed '${target.displayLabel}' at bounds ${target.bounds}")
            }
        }
    }
}
