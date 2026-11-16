package com.jarvis.assistant.ui.aura

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import java.lang.ref.WeakReference
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** One non-interactive ripple. Repeated gestures replace it; teardown removes the window. */
object TapIndicator {
    private val main = Handler(Looper.getMainLooper())
    private var owner = WeakReference<AccessibilityService>(null)
    private var window = WeakReference<View>(null)
    private var animator: ValueAnimator? = null
    private var pending: Runnable? = null
    private val remove = Runnable { clearNow() }

    fun show(service: AccessibilityService, x: Float, y: Float) {
        if (!AuraOptions.enabled("tapIndicator") || !x.isFinite() || !y.isFinite()) return
        synchronized(this) {
            pending?.let(main::removeCallbacks)
            val task = Runnable { showNow(service, x, y) }
            pending = task
            main.post(task)
        }
    }

    fun clear(service: AccessibilityService) {
        synchronized(this) { pending?.let(main::removeCallbacks); pending = null }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (owner.get() === service) clearNow()
        } else main.post { if (owner.get() === service) clearNow() }
    }

    private fun clearNow() {
        main.removeCallbacks(remove)
        val previous = animator
        animator = null
        previous?.cancel()
        val old = window.get(); val service = owner.get()
        window.clear(); owner.clear()
        if (old != null && service != null) runCatching {
            (service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager).removeViewImmediate(old)
        }
    }

    private fun showNow(service: AccessibilityService, x: Float, y: Float) {
        clearNow()
        if (!AuraOptions.enabled("tapIndicator")) return
        val size = (64 * service.resources.displayMetrics.density).toInt()
        val dot = object : View(service) {
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.RED; style = Paint.Style.STROKE
                strokeWidth = 2 * resources.displayMetrics.density
            }
            var progress = 0f
            override fun onDraw(canvas: Canvas) {
                pen.alpha = (200 * (1 - progress)).toInt()
                canvas.drawCircle(width / 2f, height / 2f, width * (.12f + progress * .32f), pen)
            }
        }
        val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = (x - size / 2).toInt(); this.y = (y - size / 2).toInt()
        }
        try {
            (service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager).addView(dot, params)
            window = WeakReference(dot); owner = WeakReference(service)
            if (ValueAnimator.areAnimatorsEnabled() && !com.jarvis.assistant.util.VisualMotionSettings.isReduced(service)) {
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 450
                    addUpdateListener { dot.progress = it.animatedValue as Float; dot.invalidate() }
                    start()
                }
            }
            main.postDelayed(remove, 460)
        } catch (_: RuntimeException) { clearNow() }
    }
}
