package com.jarvis.assistant.vision

import java.util.Locale

/**
 * Shared deny-list for credential and high-risk financial input fields.
 * JARVIS never types secrets, authentication codes, or payment/banking credentials.
 */
object SensitiveInputPolicy {
    private val patterns = listOf(
        Regex("\\b(password|passcode|pass phrase|passphrase|otp|one[- ]time code|verification code|security code)\\b"),
        Regex("\\b(pin|upi pin|cvv|cvc|card number|credit card|debit card)\\b"),
        Regex("\\b(bank account|account number|beneficiary|ifsc|recovery phrase|seed phrase|private key|wallet phrase)\\b"),
        Regex("\\b(amount|bank|beneficiary|upi|upi id|ifsc|routing number|sort code|swift code|payment amount|transfer amount)\\b")
    )

    fun isSensitiveLabel(label: String): Boolean {
        val normalized = label.lowercase(Locale.ROOT)
        return patterns.any { it.containsMatchIn(normalized) }
    }
}
