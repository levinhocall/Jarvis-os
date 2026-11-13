package com.jarvis.assistant.automation.agent
import org.junit.Assert.*
import org.junit.Test
class ActionObservationStabilityTest {
    private val valid = ScreenSignature("app","window",5,123,listOf("Control"))
    private val missing = ScreenSignature("","",0,0)
    @Test fun nullRootCannotBecomeASuccessfulWindowTransition() {
        assertFalse(ActionVerifier.canCompare(valid,missing))
        assertFalse(ActionVerifier.canCompare(missing,valid))
        assertFalse(ActionVerifier.canCompare(missing,missing))
    }
    @Test fun vanishedPostScrollScreenIsNotVerifiedSuccess() {
        assertFalse(ActionVerifier.verifyScroll(valid,missing).isVerified)
    }
    @Test fun realObservedPackageChangeIsComparable() {
        assertTrue(ActionVerifier.canCompare(valid,valid.copy(packageName="other")))
    }
}
