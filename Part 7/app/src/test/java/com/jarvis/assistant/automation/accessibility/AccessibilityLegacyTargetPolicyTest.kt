package com.jarvis.assistant.automation.accessibility
import org.junit.Assert.*
import org.junit.Test
class AccessibilityLegacyTargetPolicyTest {
    @Test fun appSwitchCannotAuthorizeLegacyActionsInAnotherApp() {
        val p=AccessibilityLegacyTargetPolicy
        assertTrue(p.whatsapp("com.whatsapp"));assertTrue(p.whatsapp("com.whatsapp.w4b"))
        assertFalse(p.whatsapp("com.android.chrome"));assertFalse(p.youtube("com.whatsapp"))
        assertFalse(p.whatsapp(null))
    }
    @Test fun unexpectedExistingDraftIsPreserved() {
        assertTrue(AccessibilityLegacyTargetPolicy.draftMatches("Hi","Hi"))
        assertFalse(AccessibilityLegacyTargetPolicy.draftMatches("Hi","Private old draft"))
        assertFalse(AccessibilityLegacyTargetPolicy.draftMatches("Hi",null))
    }
    @Test fun arbitraryPositiveDialogButtonIsNotCallConfirmation() {
        val p=AccessibilityLegacyTargetPolicy
        assertFalse(p.callConfirmation("Delete",""));assertFalse(p.callConfirmation("OK",""))
        assertTrue(p.callConfirmation("Call",""));assertTrue(p.callConfirmation("कॉल करें",""))
    }
    @Test fun acceptedCallIsNeverRepeatedByNextPoll() {
        val p=AccessibilityLegacyTargetPolicy
        assertTrue(p.mayTapCall(0));assertFalse(p.mayTapCall(1))
        assertTrue(p.mayConfirmCall(1));assertFalse(p.mayConfirmCall(2));assertFalse(p.mayTapCall(2))
    }
}
