package com.jarvis.assistant.ui.orb

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView

/** Keeps legacy WebGL dragging while routing real click/long-click semantics to the native host. */
class OrbTouchWebView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : WebView(context, attrs) {
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapUp(event: MotionEvent) = true
        override fun onLongPress(event: MotionEvent) { performLongClick() }
    })
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tapped = taps.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && tapped) performClick()
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean {
        super.performClick()
        return (parent as? View)?.performClick() ?: false
    }
    override fun performLongClick(): Boolean {
        super.performLongClick()
        return (parent as? View)?.performLongClick() ?: false
    }
}
