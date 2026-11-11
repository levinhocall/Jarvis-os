package com.jarvis.assistant.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.ui.orb.OrbAppearance
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrbAppearanceInstrumentedTest {
    @Test fun appearanceSurvivesRepositoryRecreationAndInvalidNumbersAreBounded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = (context.applicationContext as JarvisApp).preferences
        val previous = prefs.orbEyeOptions
        try {
            prefs.orbEyeOptions = """{"blink":false,"size":50,"movement":-2,"speed":1.2}"""
            assertEquals(prefs.orbEyeOptions, AppPreferences(context).orbEyeOptions)
            val config = OrbAppearance.configuration(context)
            assertFalse(config.getBoolean("blink"))
            assertEquals(1.15, config.getDouble("size"), 0.001)
            assertEquals(0.0, config.getDouble("movement"), 0.001)
            prefs.orbEyeOptions = "invalid-json"
            assertTrue(OrbAppearance.configuration(context).getBoolean("blink"))
        } finally { prefs.orbEyeOptions = previous }
    }
}
