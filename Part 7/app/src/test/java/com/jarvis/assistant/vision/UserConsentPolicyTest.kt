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
    fun blocksCommunicationDispatchAndPaymentSubmission() {
        assertTrue(UserConsentPolicy.requiresExplicitConfirmation("Send", null, "Chat with Alex", "com.whatsapp"))
        assertTrue(UserConsentPolicy.requiresExplicitConfirmation("Share", null, "Photo viewer", "com.google.android.apps.photos"))
        assertTrue(UserConsentPolicy.requiresExplicitConfirmation("Send", null, "Email to recipient", "com.android.chrome"))
        assertTrue(UserConsentPolicy.requiresExplicitConfirmation("Delete", null, "Photo details", "com.google.android.apps.photos"))
        assertTrue(UserConsentPolicy.requiresExplicitConfirmation("Submit", null, "Checkout payment amount total", "com.android.chrome"))
        assertFalse(UserConsentPolicy.requiresExplicitConfirmation("Send", null, "Search results", "com.android.chrome"))
        assertFalse(UserConsentPolicy.requiresExplicitConfirmation("Submit", null, "Create profile", "com.android.chrome"))
    }
    @Test
    fun blocksTermsAcceptanceAndCredentialAutofill() {
        assertTrue(UserConsentPolicy.requiresExplicitConfirmation("Agree and continue", null, "Please review our Terms and Conditions and Privacy Policy"))
        assertTrue(UserConsentPolicy.requiresExplicitConfirmation("Autofill from Messages", null, "Enter the OTP verification code sent to your phone"))
        assertFalse(UserConsentPolicy.requiresExplicitConfirmation("Continue", null, "Choose a profile picture"))
    }
}