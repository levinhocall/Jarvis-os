package com.jarvis.assistant.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveInputPolicyTest {
    @Test
    fun blocksCredentialsAndFinancialInputs() {
        listOf(
            "Password",
            "Enter OTP",
            "UPI PIN",
            "CVV",
            "Card number",
            "Bank account number",
            "Beneficiary",
            "Transfer amount",
            "Recovery phrase",
            "Private key"
        ).forEach { assertTrue("Expected sensitive label: $it", SensitiveInputPolicy.isSensitiveLabel(it)) }
    }

    @Test
    fun allowsOrdinaryTextFields() {
        listOf("Search", "Message", "Prompt", "Email subject", "Recipient name").forEach {
            assertFalse("Expected ordinary label: $it", SensitiveInputPolicy.isSensitiveLabel(it))
        }
    }
}
