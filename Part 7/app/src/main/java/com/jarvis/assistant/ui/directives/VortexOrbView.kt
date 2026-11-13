package com.jarvis.assistant.ui.directives

import android.content.Context
import android.util.AttributeSet
import com.jarvis.assistant.ui.orb.OrbTouchWebView
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.jarvis.assistant.data.model.VoicePresentationSnapshot
import com.jarvis.assistant.ui.orb.OrbWebHost

/** Shared local renderer. This view cannot open a microphone or create a voice session. */
class VortexOrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs), DefaultLifecycleObserver {
    private val web = OrbTouchWebView(context)
    private val host: OrbWebHost
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(web, LayoutParams(-1, -1))
        host = OrbWebHost(this, web)
    }
    fun submit(facts: VoicePresentationSnapshot) = host.submit(facts)
    override fun onStart(owner: LifecycleOwner) { host.setVisible(true) }
    override fun onResume(owner: LifecycleOwner) { host.reloadIfChanged(); host.setVisible(true) }
    override fun onStop(owner: LifecycleOwner) { host.setVisible(false) }
    override fun onPause(owner: LifecycleOwner) { host.setVisible(false) }
    override fun onDestroy(owner: LifecycleOwner) { host.dispose() }
}
