/*
 * Kotlin port of ideas from AbuZar-Ansarii/Jarvis (MIT, (c) 2026 AbuZar Ansarii).
 * Original is Flutter/Dart; this is a reimplementation for JARVIS OS. See docs/THIRD_PARTY_NOTICES.md.
 */
package com.jarvis.assistant.ui.extras

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService

/** Small device actions shared by Brain Dump and Air Gestures. Reuses existing services only. */
object DeviceActs {
    fun mediaKey(context: Context, keyCode: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /** Needs the Jarvis accessibility service enabled. Returns false if unavailable. */
    fun screenshot(): Boolean {
        val svc = JarvisAccessibilityService.instance ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
    }
}
