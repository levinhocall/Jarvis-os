package com.jarvis.assistant.audio
import org.junit.Assert.*
import org.junit.Test
class AudioRecoveryBudgetTest {
    @Test fun repeatedFailedRecoveryTerminates() {
        val b=AudioRecoveryBudget()
        repeat(3) { assertTrue(b.nextAttempt()) }
        repeat(100) { assertFalse(b.nextAttempt()) }
    }
    @Test fun unstableOneFrameDoesNotResetRecoveryBudget() {
        val b=AudioRecoveryBudget(2,3)
        assertTrue(b.nextAttempt()); b.successfulFrame()
        assertTrue(b.nextAttempt()); b.successfulFrame()
        assertFalse(b.nextAttempt())
    }
    @Test fun sustainedHealthyFramesAllowANewRecoveryBurst() {
        val b=AudioRecoveryBudget(1,3)
        assertTrue(b.nextAttempt()); repeat(3) { b.successfulFrame() }
        assertTrue(b.nextAttempt()); assertFalse(b.nextAttempt())
    }
}
