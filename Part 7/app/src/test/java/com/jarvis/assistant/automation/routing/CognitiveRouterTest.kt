package com.jarvis.assistant.automation.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CognitiveRouterTest {

    private lateinit var router: CognitiveRouter

    @Before
    fun setUp() {
        router = CognitiveRouter()
    }

    @Test
    fun `privacy sensitive query routes strictly on-device`() {
        val decision = router.route("Find contact phone number for Alice and verify password")
        assertEquals(PrivacyTier.STRICT_ON_DEVICE, decision.privacyTier)
        assertEquals(ModelTier.LOCAL_REFLEX, decision.selectedTier)
        assertEquals("local_reflex", decision.providerId)
        assertTrue(decision.reason.contains("Privacy constraint"))
    }

    @Test
    fun `offline network routes to local reflex tier`() {
        val decision = router.route("What is the capital of France?", isNetworkAvailable = false)
        assertEquals(ModelTier.LOCAL_REFLEX, decision.selectedTier)
        assertEquals("OfflineFallbackHandler", decision.specialistTarget)
    }

    @Test
    fun `device control routes directly to local device controller`() {
        val decision = router.route("Turn on flashlight and increase volume")
        assertEquals(TaskTaxonomy.DEVICE_CONTROL, decision.taskType)
        assertEquals(ModelTier.LOCAL_REFLEX, decision.selectedTier)
        assertEquals("DeviceController", decision.specialistTarget)
    }

    @Test
    fun `coding task routes to software engineering agent`() {
        val decision = router.route("Refactor symbol and commit git changes")
        assertEquals(TaskTaxonomy.CODING_DEV, decision.taskType)
        assertEquals(ModelTier.FRONTIER_REASONING, decision.selectedTier)
        assertEquals("SoftwareEngineeringAgent", decision.specialistTarget)
    }

    @Test
    fun `cloud provider degradation triggers automatic failover cascade`() {
        // Record 3 consecutive failures for gemini_pro
        router.recordProviderResult("gemini_pro", 1000L, isSuccess = false)
        router.recordProviderResult("gemini_pro", 1000L, isSuccess = false)
        router.recordProviderResult("gemini_pro", 1000L, isSuccess = false)

        assertFalse(router.isProviderHealthy("gemini_pro"))

        // Planning query should now failover to gemini_flash
        val decision = router.route("Plan a multi-step milestone mission to organize receipts")
        assertEquals(TaskTaxonomy.LONG_REASONING, decision.taskType)
        assertEquals(ModelTier.FAST_CLOUD, decision.selectedTier)
        assertEquals("gemini_flash", decision.providerId)
        assertTrue(decision.reason.contains("Frontier model degraded"))

        // Now degrade gemini_flash as well
        router.recordProviderResult("gemini_flash", 500L, isSuccess = false)
        router.recordProviderResult("gemini_flash", 500L, isSuccess = false)
        router.recordProviderResult("gemini_flash", 500L, isSuccess = false)

        // Should degrade to local reflex
        val degradedDecision = router.route("Plan a multi-step milestone mission to organize receipts")
        assertEquals(ModelTier.LOCAL_REFLEX, degradedDecision.selectedTier)
        assertEquals("local_reflex", degradedDecision.providerId)
    }

    @Test
    fun `provider health recovers after successful execution`() {
        router.recordProviderResult("gemini_pro", 1000L, isSuccess = false)
        router.recordProviderResult("gemini_pro", 1000L, isSuccess = false)
        router.recordProviderResult("gemini_pro", 1000L, isSuccess = false)
        assertFalse(router.isProviderHealthy("gemini_pro"))

        // Successful execution recovers health
        router.recordProviderResult("gemini_pro", 400L, isSuccess = true)
        assertTrue(router.isProviderHealthy("gemini_pro"))
        assertEquals(0, router.getProviderHealth("gemini_pro")?.consecutiveErrors)
    }
}
