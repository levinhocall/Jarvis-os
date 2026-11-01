package com.jarvis.assistant.automation.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPrivacyFilterTest {
    @Test
    fun credentialInSenderIsRedactedAlongWithMessage() {
        val result = NotificationPrivacyFilter.sanitize("Your OTP is 482913", "Use this code to sign in")
        assertTrue(result.containsCredential)
        assertEquals("[Sensitive notification]", result.sender)
        assertEquals("[Sensitive credential notification hidden]", result.message)
        assertFalse(result.sender.contains("482913"))
    }

    @Test
    fun credentialInMessageIsRedactedWithoutRetainingSenderIdentity() {
        val result = NotificationPrivacyFilter.sanitize("Bank", "Your verification code is 654321")
        assertTrue(result.containsCredential)
        assertEquals("[Sensitive notification]", result.sender)
        assertEquals("[Sensitive credential notification hidden]", result.message)
    }

    @Test
    fun ordinaryBankTransactionIsNotSuppressedWithoutCredentialContext() {
        val result = NotificationPrivacyFilter.sanitize("Bank", "₹2500 credited to your account")
        assertFalse(result.containsCredential)
        assertEquals("Bank", result.sender)
        assertEquals("₹2500 credited to your account", result.message)
    }
}