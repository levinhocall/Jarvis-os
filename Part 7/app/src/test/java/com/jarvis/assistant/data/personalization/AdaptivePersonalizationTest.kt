package com.jarvis.assistant.data.personalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdaptivePersonalizationTest {

    @Before
    fun setUp() {
        AdaptivePersonalizationEngine.clearAllForTest()
    }

    @Test
    fun testExplicitPreferenceTakesPrecedenceAndIsActionable() {
        val pref = AdaptivePersonalizationEngine.setExplicitPreference(
            key = "preferred_music_app",
            value = "Spotify",
            category = PreferenceCategory.MEDIA
        )

        assertTrue(pref.isActionable)
        assertEquals(1.0f, pref.confidence, 0.001f)
        assertEquals(PreferenceSource.EXPLICIT_USER, pref.source)

        val resolved = AdaptivePersonalizationEngine.resolveEffectivePreference("preferred_music_app")
        assertEquals("Spotify", resolved)
    }

    @Test
    fun testInferredPreferenceIsLowConfidenceUntilConfirmed() {
        AdaptivePersonalizationEngine.recordInferredPreference(
            key = "preferred_messenger",
            value = "Telegram",
            category = PreferenceCategory.COMMUNICATION,
            initialConfidence = 0.5f
        )

        val unconfirmedPref = AdaptivePersonalizationEngine.getPreference("preferred_messenger")
        assertNotNull(unconfirmedPref)
        assertFalse("Unconfirmed inferred preference must not be actionable without reinforcement", unconfirmedPref!!.isActionable)

        // Resolving it returns null because it's not yet actionable
        val unresolved = AdaptivePersonalizationEngine.resolveEffectivePreference("preferred_messenger")
        assertNull(unresolved)

        // User confirms preference
        val confirmed = AdaptivePersonalizationEngine.confirmPreference("preferred_messenger")
        assertTrue(confirmed)

        val activePref = AdaptivePersonalizationEngine.getPreference("preferred_messenger")
        assertTrue(activePref!!.isActionable)
        assertEquals(1.0f, activePref.confidence, 0.001f)

        val resolved = AdaptivePersonalizationEngine.resolveEffectivePreference("preferred_messenger")
        assertEquals("Telegram", resolved)
    }

    @Test
    fun testExplicitCommandOverridesStoredPreference() {
        // User explicitly stored Spotify
        AdaptivePersonalizationEngine.setExplicitPreference(
            key = "preferred_music_app",
            value = "Spotify",
            category = PreferenceCategory.MEDIA
        )

        // But in current invocation, user explicitly requests YouTube
        val effective = AdaptivePersonalizationEngine.resolveEffectivePreference(
            key = "preferred_music_app",
            currentExplicitCommandOverride = "YouTube"
        )

        assertEquals("YouTube", effective)
    }

    @Test
    fun testFeedbackDecreasesConfidenceAndDisablesOnRepetition() {
        AdaptivePersonalizationEngine.recordInferredPreference(
            key = "response_verbosity",
            value = "super_verbose",
            category = PreferenceCategory.RESPONSE_STYLE,
            initialConfidence = 0.6f
        )

        // 1st negative feedback
        AdaptivePersonalizationEngine.recordFeedback(
            UserFeedback(preferenceKey = "response_verbosity", isPositive = false)
        )
        val pref1 = AdaptivePersonalizationEngine.getPreference("response_verbosity")
        assertTrue(pref1!!.confidence < 0.6f)
        assertTrue(pref1.isEnabled)

        // 2nd negative feedback
        AdaptivePersonalizationEngine.recordFeedback(
            UserFeedback(preferenceKey = "response_verbosity", isPositive = false)
        )
        val pref2 = AdaptivePersonalizationEngine.getPreference("response_verbosity")
        assertFalse("Preference should be disabled after repeated negative feedback", pref2!!.isEnabled)
    }

    @Test
    fun testUserCorrectionElevatesToExplicitPreference() {
        AdaptivePersonalizationEngine.recordInferredPreference(
            key = "preferred_browser",
            value = "Opera",
            category = PreferenceCategory.PRODUCTIVITY,
            initialConfidence = 0.5f
        )

        // User corrects it to Chrome
        AdaptivePersonalizationEngine.recordFeedback(
            UserFeedback(
                preferenceKey = "preferred_browser",
                isPositive = false,
                userCorrection = "Chrome"
            )
        )

        val corrected = AdaptivePersonalizationEngine.getPreference("preferred_browser")
        assertNotNull(corrected)
        assertEquals("Chrome", corrected!!.value)
        assertEquals(PreferenceSource.EXPLICIT_USER, corrected.source)
        assertEquals(1.0f, corrected.confidence, 0.001f)
        assertTrue(corrected.isActionable)
    }

    @Test
    fun testUserControlsToggleAndDelete() {
        AdaptivePersonalizationEngine.setExplicitPreference(
            key = "voice_persona",
            value = "Tony Stark",
            category = PreferenceCategory.RESPONSE_STYLE
        )

        assertTrue(AdaptivePersonalizationEngine.getPreference("voice_persona")!!.isEnabled)

        // Toggle OFF
        AdaptivePersonalizationEngine.togglePreference("voice_persona", false)
        assertFalse(AdaptivePersonalizationEngine.getPreference("voice_persona")!!.isEnabled)
        assertNull(AdaptivePersonalizationEngine.resolveEffectivePreference("voice_persona"))

        // Delete
        val deleted = AdaptivePersonalizationEngine.deletePreference("voice_persona")
        assertTrue(deleted)
        assertNull(AdaptivePersonalizationEngine.getPreference("voice_persona"))
    }

    @Test
    fun testClearAllInferredLeavesExplicitUntouched() {
        AdaptivePersonalizationEngine.setExplicitPreference(
            key = "theme",
            value = "dark",
            category = PreferenceCategory.RESPONSE_STYLE
        )
        AdaptivePersonalizationEngine.recordInferredPreference(
            key = "lunch_time",
            value = "13:00",
            category = PreferenceCategory.PRODUCTIVITY
        )

        assertEquals(2, AdaptivePersonalizationEngine.getAllPreferences().size)

        AdaptivePersonalizationEngine.clearAllInferred()

        val remaining = AdaptivePersonalizationEngine.getAllPreferences()
        assertEquals(1, remaining.size)
        assertEquals("theme", remaining[0].key)
    }
}
