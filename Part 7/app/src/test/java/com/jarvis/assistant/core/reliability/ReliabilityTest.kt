package com.jarvis.assistant.core.reliability

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReliabilityTest {

    @Before
    fun setUp() {
        ReliabilityCoordinator.resetAllForTest()
    }

    @Test
    fun testInitialSystemStateIsHealthy() {
        assertEquals(SystemHealthState.HEALTHY, ReliabilityCoordinator.overallState)
        assertEquals(SystemHealthState.HEALTHY, ReliabilityCoordinator.getComponentState("NetworkClient"))
    }

    @Test
    fun testDiagnosticMessageSanitizesSecrets() {
        val raw = "Failed to authenticate with token=ghp_secretToken123456789 and Gemini Key AIzaSyD9876543210123456789012345678901"
        val event = DiagnosticEvent(
            category = ErrorCategory.PROVIDER_FAILURE,
            component = "GeminiProvider",
            rawMessage = raw,
            isRecoverable = false
        )

        val sanitized = event.sanitizedMessage
        assertFalse(sanitized.contains("ghp_secretToken123456789"))
        assertFalse(sanitized.contains("AIzaSyD9876543210123456789012345678901"))
        assertTrue(sanitized.contains("[REDACTED_SECRET]"))
        assertTrue(sanitized.contains("[REDACTED_GEMINI_KEY]"))
    }

    @Test
    fun testIdempotentActionRetriesAndRecovers() = runBlocking {
        var attemptsMade = 0
        val (result, recovery) = ReliabilityCoordinator.executeWithSelfHealing(
            component = "WebCrawler",
            actionName = "fetch_page",
            isIdempotent = true
        ) { attempt ->
            attemptsMade = attempt
            if (attempt < 2) {
                throw RuntimeException("SocketTimeoutException")
            }
            "Page HTML content"
        }

        assertEquals("Page HTML content", result)
        assertEquals(2, attemptsMade)
        assertTrue(recovery is RecoveryResult.Recovered)
        val recovered = recovery as RecoveryResult.Recovered
        assertEquals(2, recovered.attemptsMade)
        assertEquals(SystemHealthState.HEALTHY, ReliabilityCoordinator.getComponentState("WebCrawler"))
    }

    @Test
    fun testNonIdempotentActionRefusesRetryOnFailure() = runBlocking {
        var attemptsMade = 0
        val (result, recovery) = ReliabilityCoordinator.executeWithSelfHealing(
            component = "PaymentGateway",
            actionName = "charge_card",
            isIdempotent = false // NON-IDEMPOTENT!
        ) { attempt ->
            attemptsMade = attempt
            throw RuntimeException("Network drop during transmission")
        }

        assertNull(result)
        assertEquals(1, attemptsMade) // Must NOT retry!
        assertTrue(recovery is RecoveryResult.CannotReplayNonIdempotentAction)
        val nonIdem = recovery as RecoveryResult.CannotReplayNonIdempotentAction
        assertTrue(nonIdem.reason.contains("prevent duplicate side effects"))
    }

    @Test
    fun testLoopBreakerTripsAfterMaxFailures() {
        val component = "UnstableService"

        // Trigger 3 failures
        for (i in 1..3) {
            ReliabilityCoordinator.reportDiagnostic(
                DiagnosticEvent(
                    category = ErrorCategory.SERVICE_DISCONNECTED,
                    component = component,
                    rawMessage = "Failure #$i",
                    isRecoverable = true
                )
            )
        }

        assertEquals(SystemHealthState.UNAVAILABLE, ReliabilityCoordinator.getComponentState(component))
        assertEquals(SystemHealthState.DEGRADED, ReliabilityCoordinator.overallState)

        // Any attempt on this component should now fail fast
        runBlocking {
            val (result, recovery) = ReliabilityCoordinator.executeWithSelfHealing(
                component = component,
                actionName = "call_service",
                isIdempotent = true
            ) {
                "Should not run"
            }
            assertNull(result)
            assertTrue(recovery is RecoveryResult.Failed)
            val failed = recovery as RecoveryResult.Failed
            assertTrue(failed.loopBreakerTriggered)
        }
    }

    @Test
    fun testResetComponentRestoresHealth() {
        val component = "Database"
        ReliabilityCoordinator.reportDiagnostic(
            DiagnosticEvent(
                category = ErrorCategory.HARDWARE_UNAVAILABLE,
                component = component,
                rawMessage = "Disk I/O error",
                isRecoverable = false
            )
        )

        assertEquals(SystemHealthState.DEGRADED, ReliabilityCoordinator.getComponentState(component))

        ReliabilityCoordinator.resetComponent(component)
        assertEquals(SystemHealthState.HEALTHY, ReliabilityCoordinator.getComponentState(component))
        assertEquals(SystemHealthState.HEALTHY, ReliabilityCoordinator.overallState)
    }
}
