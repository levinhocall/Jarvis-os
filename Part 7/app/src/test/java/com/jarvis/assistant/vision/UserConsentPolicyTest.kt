package com.jarvis.assistant.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserConsentPolicyTest {
    @Test
    fun blocksPermissionGrantButtonsOnlyInsidePermissionContext() {
        assertTrue(UserConsentPolicy.requiresExplicitConfirmation("Allow", null, "Allow JARVIS to record audio?"))
        assertTrue(UserConsentPolicy.requiresExplicitConfirmation("While using the app", null, "Allow JARVIS to access this device location"))
        assertTrue(UserConsentPolicy.requiresExplicitConfirmation("Only this time", null, "Permission request: allow microphone access"))
        assertFalse(UserConsentPolicy.requiresExplicitConfirmation("Allow", null, "Allow this website to show a welcome message"))
    }

    @Test
    fun blocksTermsAcceptanceAndCredentialAutofill() {
        assertTrue(UserConsentPolicy.requiresExplicitConfirmation("Agree and continue", null, "Please review our Terms and Conditions and Privacy Policy"))
        assertTrue(UserConsentPolicy.requiresExplicitConfirmation("Autofill from Messages", null, "Enter the OTP verification code sent to your phone"))
        assertFalse(UserConsentPolicy.requiresExplicitConfirmation("Continue", null, "Choose a profile picture"))
    }
}