package com.jarvis.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLauncherMatchingTest {

    private fun createApp(label: String, pkg: String = "com.example.${label.lowercase().replace(" ", "")}"): AppLauncher.InstalledApp {
        return AppLauncher.InstalledApp(
            label = label,
            packageName = pkg
        )
    }

    private val sampleApps = listOf(
        createApp("Camera", "com.android.camera"),
        createApp("Camera Pro", "com.thirdparty.camera.pro"),
        createApp("Calculator", "com.android.calculator"),
        createApp("Google Chrome", "com.android.chrome"),
        createApp("Chrome Canary", "com.chrome.canary"),
        createApp("Podcast", "com.google.android.apps.podcasts"),
        createApp("Instagram", "com.instagram.android"),
        createApp("WhatsApp", "com.whatsapp"),
        createApp("YouTube", "com.google.android.youtube"),
        createApp("YT Studio", "com.google.android.apps.youtube.creator"),
        createApp("Google Pay", "com.google.android.apps.nbu.paisa.user"),
        createApp("PhonePe", "com.phonepe.app"),
        createApp("Mi Remote", "com.duokan.phone.remotecontroller")
    )

    @Test
    fun testExactLabelMatching_firstPriority() {
        val matches = AppLauncher.matchAppsFromList("Camera", sampleApps)
        assertEquals(1, matches.size)
        assertEquals("Camera", matches.first().label)
    }

    @Test
    fun testExactLabelMatching_caseInsensitive() {
        val matches = AppLauncher.matchAppsFromList("camera", sampleApps)
        assertEquals(1, matches.size)
        assertEquals("Camera", matches.first().label)

        val chromeMatches = AppLauncher.matchAppsFromList("google chrome", sampleApps)
        assertEquals(1, chromeMatches.size)
        assertEquals("Google Chrome", chromeMatches.first().label)
    }

    @Test
    fun testPrefixMatching_lengthAtLeastThree() {
        // "calc" -> should prefix match "Calculator"
        val matches = AppLauncher.matchAppsFromList("calc", sampleApps)
        assertEquals(1, matches.size)
        assertEquals("Calculator", matches.first().label)

        // "cam" -> matches "Camera" and "Camera Pro"
        val camMatches = AppLauncher.matchAppsFromList("cam", sampleApps)
        assertTrue(camMatches.any { it.label == "Camera" })
        assertTrue(camMatches.any { it.label == "Camera Pro" })
    }

    @Test
    fun testWordPrefixMatching_matchesSecondWordPrefix() {
        // "chrome" -> word-prefix matches "Google Chrome" and prefix matches "Chrome Canary"
        val matches = AppLauncher.matchAppsFromList("chrome", sampleApps)
        assertTrue(matches.any { it.label == "Google Chrome" })
        assertTrue(matches.any { it.label == "Chrome Canary" })

        // "studio" -> word-prefix matches "YT Studio"
        val studioMatches = AppLauncher.matchAppsFromList("studio", sampleApps)
        assertEquals(1, studioMatches.size)
        assertEquals("YT Studio", studioMatches.first().label)
    }

    @Test
    fun testKeyLengthUnderThree_neverMatchesPrefixOrLooseSubstring() {
        // Query length < 3 (e.g. "ca"):
        // "ca" should NOT prefix-match "Camera", "Calculator", or substring-match "Podcast"
        val matches = AppLauncher.matchAppsFromList("ca", sampleApps)
        assertTrue("Query of length < 3 must not prefix or substring match", matches.isEmpty())

        val pMatches = AppLauncher.matchAppsFromList("po", sampleApps)
        assertTrue(pMatches.isEmpty())
    }

    @Test
    fun testNeverLooseSubstring_interiorSubstringRejected() {
        // "cast" is inside "Podcast", but "Podcast" doesn't start with "cast"
        // and doesn't have a word starting with "cast". Loose substring must be rejected!
        val matches = AppLauncher.matchAppsFromList("cast", sampleApps)
        assertTrue("Interior substring match 'cast' in 'Podcast' should be rejected", matches.none { it.label == "Podcast" })

        // "odcast" is inside "Podcast" -> rejected
        val odcastMatches = AppLauncher.matchAppsFromList("odcast", sampleApps)
        assertTrue(odcastMatches.isEmpty())

        // "culator" inside "Calculator" -> rejected
        val culatorMatches = AppLauncher.matchAppsFromList("culator", sampleApps)
        assertTrue(culatorMatches.isEmpty())
    }

    @Test
    fun testSpokenAliases() {
        val instaMatches = AppLauncher.matchAppsFromList("insta", sampleApps)
        assertEquals(1, instaMatches.size)
        assertEquals("Instagram", instaMatches.first().label)

        val ytMatches = AppLauncher.matchAppsFromList("yt", sampleApps)
        assertEquals(1, ytMatches.size)
        assertEquals("YouTube", ytMatches.first().label)

        val gpayMatches = AppLauncher.matchAppsFromList("gpay", sampleApps)
        assertEquals(1, gpayMatches.size)
        assertEquals("Google Pay", gpayMatches.first().label)

        val phonepeMatches = AppLauncher.matchAppsFromList("phone pay", sampleApps)
        assertEquals(1, phonepeMatches.size)
        assertEquals("PhonePe", phonepeMatches.first().label)
    }

    @Test
    fun testOrdinalStripping_dualSuffix() {
        // "whatsapp 2" -> strips "2" and matches "WhatsApp"
        val matches = AppLauncher.matchAppsFromList("whatsapp 2", sampleApps)
        assertEquals(1, matches.size)
        assertEquals("WhatsApp", matches.first().label)

        // "camera dual" -> strips "dual" and matches "Camera"
        val camMatches = AppLauncher.matchAppsFromList("camera dual", sampleApps)
        assertEquals(1, camMatches.size)
        assertEquals("Camera", camMatches.first().label)
    }

    @Test
    fun testPackageNameMatch_fallbackWhenKeyLengthAtLeastThree() {
        val pkgApp = createApp("Random Utility", "com.example.flashlight")
        val apps = listOf(pkgApp)
        val matches = AppLauncher.matchAppsFromList("flashlight", apps)
        assertEquals(1, matches.size)
        assertEquals("Random Utility", matches.first().label)
    }

    @Test
    fun testEmptyOrBlankQuery_returnsEmpty() {
        assertTrue(AppLauncher.matchAppsFromList("", sampleApps).isEmpty())
        assertTrue(AppLauncher.matchAppsFromList("   ", sampleApps).isEmpty())
    }
}
