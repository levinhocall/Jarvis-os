package com.jarvis.assistant.automation.integration

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UniversalActionTest {

    @Before
    fun setUp() {
        UniversalActionDispatcher.resetCircuitBreaker("test_flaky_action")
        UniversalActionDispatcher.resetCircuitBreaker("whatsapp_send")
        UniversalActionDispatcher.resetCircuitBreaker("launch_app")
    }

    @Test
    fun testAllBuiltInsAreRegistered() {
        val launchApp = UniversalActionRegistry.getContract("launch_app")
        assertNotNull(launchApp)
        assertEquals(ActionRiskLevel.LOW, launchApp?.riskLevel)

        val callContact = UniversalActionRegistry.getContract("call_contact")
        assertNotNull(callContact)
        assertTrue(callContact?.requiredPermissions?.contains(android.Manifest.permission.CALL_PHONE) == true)

        val whatsapp = UniversalActionRegistry.getContract("whatsapp_send")
        assertNotNull(whatsapp)
        assertEquals(ActionRiskLevel.HIGH_CONSEQUENTIAL, whatsapp?.riskLevel)
        assertTrue(whatsapp?.requiresAccessibility == true)
    }

    @Test
    fun testMissingRequiredParametersReturnsFailure() = runBlocking {
        val result = UniversalActionDispatcher.executeAction(
            identifier = "launch_app",
            parameters = emptyMap(), // missing required "package_name"
            isConfirmedByUser = true
        ) {
            "Launched"
        }

        assertTrue(result is UniversalExecutionResult.Failure)
        val failure = result as UniversalExecutionResult.Failure
        assertTrue(failure.error.contains("Missing required parameters"))
        assertTrue(failure.error.contains("package_name"))
    }

    @Test
    fun testHighConsequentialActionRequiresConfirmation() = runBlocking {
        val params = mapOf("contact" to "Tony Stark", "message" to "Suit ready")
        val result = UniversalActionDispatcher.executeAction(
            identifier = "whatsapp_send",
            parameters = params,
            isConfirmedByUser = false // NOT confirmed
        ) {
            "Sent"
        }

        assertTrue(result is UniversalExecutionResult.ConfirmationRequired)
        val confirmation = result as UniversalExecutionResult.ConfirmationRequired
        assertEquals("whatsapp_send", confirmation.toolIdentifier)
        assertTrue(confirmation.prompt.contains("Please confirm"))
    }

    @Test
    fun testAccessibilityServiceUnavailableDetected() = runBlocking {
        com.jarvis.assistant.automation.accessibility.AccessibilityHealthModel.updateState(
            com.jarvis.assistant.automation.accessibility.AccessibilityState.DISABLED
        )
        val params = mapOf("contact" to "Tony Stark", "message" to "Suit ready")
        val result = UniversalActionDispatcher.executeAction(
            identifier = "whatsapp_send",
            parameters = params,
            isConfirmedByUser = true // Confirmed, but A11y is DISABLED
        ) {
            "Sent"
        }

        assertTrue(result is UniversalExecutionResult.ServiceUnavailable)
        val serviceUnavailable = result as UniversalExecutionResult.ServiceUnavailable
        assertEquals("whatsapp_send", serviceUnavailable.toolIdentifier)
        assertTrue(serviceUnavailable.reason.contains("Accessibility service is inactive"))
    }

    @Test
    fun testSuccessfulExecutionReturnsSuccessWithEvidence() = runBlocking {
        val result = UniversalActionDispatcher.executeAction(
            identifier = "launch_app",
            parameters = mapOf("package_name" to "com.example.app"),
            isConfirmedByUser = true
        ) {
            "Application launched successfully"
        }

        assertTrue(result is UniversalExecutionResult.Success)
        val success = result as UniversalExecutionResult.Success
        assertEquals("launch_app", success.toolIdentifier)
        assertEquals("Application launched successfully", success.output)
        assertNotNull(success.evidence)
    }

    @Test
    fun testTimeoutReturnsTimedOutResult() = runBlocking {
        // Register a tool with a very short timeout (50ms)
        UniversalActionRegistry.register(
            UniversalToolContract(
                identifier = "test_slow_tool",
                description = "Simulates slow network call",
                parameterSchemas = emptyList(),
                defaultTimeoutMs = 50L
            )
        )

        val result = UniversalActionDispatcher.executeAction(
            identifier = "test_slow_tool",
            parameters = emptyMap(),
            isConfirmedByUser = true
        ) {
            delay(200L) // Exceeds 50ms timeout
            "Finished slow task"
        }

        assertTrue(result is UniversalExecutionResult.TimedOut)
        val timeout = result as UniversalExecutionResult.TimedOut
        assertEquals("test_slow_tool", timeout.toolIdentifier)
        assertEquals(50L, timeout.timeoutMs)
    }

    @Test
    fun testCircuitBreakerTripsAfterMaxFailures() = runBlocking {
        UniversalActionRegistry.register(
            UniversalToolContract(
                identifier = "test_flaky_action",
                description = "Simulates unstable integration",
                parameterSchemas = emptyList(),
                defaultTimeoutMs = 1000L
            )
        )

        // Induce 3 consecutive failures
        for (i in 1..3) {
            val res = UniversalActionDispatcher.executeAction(
                identifier = "test_flaky_action",
                parameters = emptyMap(),
                isConfirmedByUser = true
            ) {
                throw RuntimeException("Connection dropped")
            }
            assertTrue(res is UniversalExecutionResult.Failure)
        }

        // 4th call should be rejected immediately by circuit breaker
        val fourthCall = UniversalActionDispatcher.executeAction(
            identifier = "test_flaky_action",
            parameters = emptyMap(),
            isConfirmedByUser = true
        ) {
            "Success now"
        }

        assertTrue("Expected ServiceUnavailable due to open circuit", fourthCall is UniversalExecutionResult.ServiceUnavailable)
        val unavailable = fourthCall as UniversalExecutionResult.ServiceUnavailable
        assertTrue(unavailable.reason.contains("Circuit breaker is OPEN"))
    }
}
