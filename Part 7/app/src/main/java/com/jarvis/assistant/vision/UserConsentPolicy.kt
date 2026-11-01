package com.jarvis.assistant.vision

import java.util.Locale

/**
 * Pure policy for actions that must pause at Android or account consent boundaries.
 * It uses the visible screen context so an ordinary button named Allow is not blocked
 * when no permission or terms dialog is present.
 */
object UserConsentPolicy {
    private val permissionContext = Regex(
        """\b(permission request|permission|wants to access|allow access to|allow .* to (access|use|record)|restricted settings|notification access|accessibility service|display over other apps|precise location)\b""",
        RegexOption.IGNORE_CASE
    )
    private val termsContext = Regex(
        """\b(terms and conditions|terms of service|privacy policy|end user license|eula)\b""",
        RegexOption.IGNORE_CASE
    )
    private val credentialContext = Regex(
        """\b(otp|one[- ]time code|verification code|password|passcode|security code|recovery code)\b""",
        RegexOption.IGNORE_CASE
    )
    private val permissionGrantActions = setOf(
        "allow", "allow once", "allow this time", "only this time", "while using the app",
        "allow while using the app", "allow only while using the app", "allow all the time",
        "always allow", "enable", "turn on", "grant access"
    )
    private val termsAcceptActions = setOf(
        "agree", "i agree", "agree and continue", "accept", "i accept", "accept terms", "accept and continue", "accept all", "continue"
    )

    fun requiresExplicitConfirmation(
        target: String,
        description: String?,
        visibleScreenText: String,
        packageName: String? = null
    ): Boolean {
        val normalizedTarget = target.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        val targetAndDescription = "$normalizedTarget ${description.orEmpty().lowercase(Locale.ROOT)}"
        val screen = visibleScreenText.lowercase(Locale.ROOT)

        val isPermissionGrantAction = normalizedTarget in permissionGrantActions ||
            normalizedTarget.startsWith("allow ") || normalizedTarget.startsWith("enable ") ||
            normalizedTarget.startsWith("grant ")
        val isPermissionGrant = isPermissionGrantAction && permissionContext.containsMatchIn(screen)
        val isTermsAcceptance = normalizedTarget in termsAcceptActions && termsContext.containsMatchIn(screen)
        val isCredentialAutofill = (targetAndDescription.contains("autofill") || targetAndDescription.contains("use code")) &&
            credentialContext.containsMatchIn(screen)
        val pkg = packageName.orEmpty().lowercase(Locale.ROOT)
        val communicationOrSharingSurface = listOf(
            "whatsapp", "telegram", "messenger", "signal", "messages", "messaging",
            "com.google.android.gm", "outlook", "mail", "instagram"
        ).any(pkg::contains)
        val dispatchActions = setOf(
            "send", "send message", "send email", "send now", "share", "share now",
            "post", "publish", "forward", "unsend", "delete", "delete message", "remove"
        )
        val isCommunicationDispatch = communicationOrSharingSurface && normalizedTarget in dispatchActions
        val isExplicitDataSubmission = normalizedTarget in setOf("submit", "place order", "confirm order") &&
            Regex("""\b(payment|checkout|purchase|order|booking|transfer|subscription)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(screen)
        return isPermissionGrant || isTermsAcceptance || isCredentialAutofill ||
            isCommunicationDispatch || isExplicitDataSubmission
    }
}