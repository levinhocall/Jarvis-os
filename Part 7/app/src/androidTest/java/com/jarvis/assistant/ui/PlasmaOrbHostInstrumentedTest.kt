package com.jarvis.assistant.ui

import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.ui.directives.PremiumOrbView
import com.jarvis.assistant.ui.orb.OrbWebHost
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Android WebView/bridge test. It does not request permissions or start a voice session. */
@RunWith(AndroidJUnit4::class)
class PlasmaOrbHostInstrumentedTest {
    @Test fun rendererFailureShowsNativeFallbackWithoutStartingVoiceAndDisposesViews() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = (context.applicationContext as JarvisApp).preferences
        val previous = prefs.orbEyeOptions
        val initialSession = JarvisConversationService.voicePresentation.value.sessionId
        val initialOwner = com.jarvis.assistant.audio.MicrophoneOwnership.owner.value
        lateinit var container: FrameLayout
        lateinit var web: WebView
        var host: OrbWebHost? = null
        try {
            prefs.orbEyeOptions = """{"renderer":"fluid_plasma","size":1.0}"""
            instrumentation.runOnMainSync {
                container = FrameLayout(context)
                web = WebView(context)
                container.addView(web, FrameLayout.LayoutParams(400, 500))
                container.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
                container.layout(0, 0, 400, 500)
                host = OrbWebHost(container, web)
                host!!.setVisible(true)
            }
            var ready = false
            for (attempt in 0 until 50) {
                val answer = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    if ((0 until container.childCount).any { container.getChildAt(it) is PremiumOrbView }) {
                        ready = true; answer.countDown()
                    } else web.evaluateJavascript("!!window.JarvisOrb") { ready = it == "true"; answer.countDown() }
                }
                assertTrue(answer.await(2, TimeUnit.SECONDS))
                if (ready) break
                Thread.sleep(100) // Instrumentation worker, never Android main/callback thread.
            }
            assertTrue("Local scene must initialize or fall back", ready)
            instrumentation.runOnMainSync {
                web.evaluateJavascript("window.OrbRendererBridge.onFailure();", null)
            }
            instrumentation.waitForIdleSync()
            // Bridge callbacks arrive on WebView's bridge thread before posting to Main.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var fallback = false
            while (!fallback && System.nanoTime() < deadline) {
                instrumentation.runOnMainSync { fallback = (0 until container.childCount).any { container.getChildAt(it) is PremiumOrbView } }
                if (!fallback) Thread.sleep(20)
            }
            assertTrue(fallback)
            assertEquals(initialSession, JarvisConversationService.voicePresentation.value.sessionId)
            assertEquals(initialOwner, com.jarvis.assistant.audio.MicrophoneOwnership.owner.value)
            instrumentation.runOnMainSync {
                host!!.setVisible(false); host?.dispose(); host?.dispose()
                assertEquals(0, container.childCount)
            }
        } finally {
            instrumentation.runOnMainSync { host?.dispose() }
            prefs.orbEyeOptions = previous
        }
    }
}
