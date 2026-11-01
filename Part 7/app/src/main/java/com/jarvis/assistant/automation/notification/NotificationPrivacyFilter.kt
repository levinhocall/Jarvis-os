package com.jarvis.assistant.automation.notification

data class SanitizedNotification(
    val sender: String,
    val message: String,
    val containsCredential: Boolean
)

/** Prevents credentials in notification previews from entering AI context, storage or TTS. */
object NotificationPrivacyFilter {
    private val credentialTerms = Regex("""\b(otp|one[- ]time password|verification code|security code|authentication code|passcode|password|upi pin|pin|cvv|recovery code|login code|two[- ]factor code|2fa code|api key|access token|secret token)\b""", RegexOption.IGNORE_CASE)
    private val shortCode = Regex("""\b\d{4,8}\b""")
    private val sensitiveSender = Regex("""\b(bank|banking|security|verification|authentication|login|account)\b""", RegexOption.IGNORE_CASE)

    fun sanitize(sender: String, message: String): SanitizedNotification {
        val combined = "$sender $message"
        val hasCredentialTerm = credentialTerms.containsMatchIn(combined)
        val hasBankCodePattern = sensitiveSender.containsMatchIn(sender) && shortCode.containsMatchIn(message) &&
            Regex("""\b(code|otp|verification|authentication|login|security|pin|passcode|authorize)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(message)
        val hasExplicitCredentialCode = shortCode.containsMatchIn(message) &&
            Regex("(?i)\\b(code|otp|pin|passcode|verification|security)\\b").containsMatchIn(message)
        val sensitive = hasCredentialTerm || hasBankCodePattern || hasExplicitCredentialCode
        return if (sensitive) {
            SanitizedNotification(sender.take(120), "[Sensitive credential notification hidden]", true)
        } else {
            SanitizedNotification(sender.take(120), message.take(2000), false)
        }
    }
}