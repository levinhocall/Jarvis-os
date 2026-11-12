package com.jarvis.assistant.automation.accessibility

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jarvis.assistant.automation.agent.ScreenSignatureCalculator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native SDK object fixtures only: these do not prove a system-bound service or live app behavior. */
@RunWith(AndroidJUnit4::class)
class AccessibilitySnapshotInstrumentedTest {
    @Test fun missingRootIsNotASuccessfulObservation() {
        val signature = ScreenSignatureCalculator.computeSignature(null)
        assertEquals(0, signature.elementCount)
        assertEquals("", signature.packageName)
        assertTrue(signature.primaryTexts.isEmpty())
    }

    @Test fun unlabelledRootDoesNotInventScreenText() {
        withNode { node ->
            node.packageName = "test.fixture"
            node.className = "android.widget.FrameLayout"
            val signature = ScreenSignatureCalculator.computeSignature(node)
            assertEquals(1, signature.elementCount)
            assertTrue(signature.primaryTexts.isEmpty())
        }
    }

    @Test fun passwordTextAndDescriptionNeverEnterSignature() {
        withNode { node ->
            node.packageName = "test.fixture"
            node.className = "android.widget.EditText"
            node.isPassword = true
            node.text = "private-password-fixture"
            node.contentDescription = "private-description-fixture"
            val signature = ScreenSignatureCalculator.computeSignature(node)
            assertTrue(signature.primaryTexts.isEmpty())
            assertFalse(signature.toString().contains("private-"))
        }
    }

    private inline fun withNode(block: (AccessibilityNodeInfo) -> Unit) {
        @Suppress("DEPRECATION") val node = AccessibilityNodeInfo.obtain()
        try { block(node) }
        finally { if (Build.VERSION.SDK_INT < 33) { @Suppress("DEPRECATION") node.recycle() } }
    }
}
