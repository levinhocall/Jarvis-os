package com.jarvis.assistant.ui

import android.content.Intent
import android.view.View
import android.webkit.WebView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.util.VisualMotionSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlsNavigationInstrumentedTest {
    @Test fun controlsSurviveRotationAndHomeRespectsLightThemeAndLocalAssetIsolation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = (context.applicationContext as JarvisApp).preferences
        val previousTheme = preferences.appTheme
        val previousMotion = VisualMotionSettings.preference(context)
        preferences.appTheme = "light"
        VisualMotionSettings.setPreference(context, true)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            scenario.onActivity { activity ->
                assertEquals("light", preferences.appTheme)
                for (id in listOf(R.id.tabHome, R.id.tabChat, R.id.tabMissions, R.id.tabMemory, R.id.tabControls, R.id.tabSettings)) {
                    assertEquals(View.VISIBLE, activity.findViewById<View>(id).visibility)
                }
                activity.findViewById<View>(R.id.tabControls).performClick()
                assertTrue(activity.findViewById<View>(R.id.btnControlWake).isShown)
                assertTrue(activity.findViewById<TextView>(R.id.tvControlAccessibility).text.startsWith("Accessibility:"))
                assertNull(com.jarvis.assistant.audio.MicrophoneOwnership.owner.value)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.btnControlWake).isShown)
                activity.findViewById<View>(R.id.tabHome).performClick()
                val web = activity.findViewById<WebView>(R.id.orbWebView)
                assertFalse(web.settings.allowFileAccess)
                assertFalse(web.settings.allowContentAccess)
                assertFalse(web.settings.domStorageEnabled)
                assertEquals(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW, web.settings.mixedContentMode)
                assertEquals("https://appassets.androidplatform.net/assets/orb.html?embedded=1", web.url)
                activity.selectTab(MainActivity.TAB_CODING)
                assertTrue(activity.findViewById<View>(R.id.tabControls).isSelected ||
                    activity.supportFragmentManager.findFragmentByTag("TAB_2")?.isHidden == false)
                activity.selectTab(MainActivity.TAB_CONTROLS)
                assertTrue(activity.findViewById<View>(R.id.btnControlWake).isShown)
            }
        } finally {
            scenario.close()
            preferences.appTheme = previousTheme
            VisualMotionSettings.setPreference(context, previousMotion)
        }
    }
}
