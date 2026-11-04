package com.jarvis.assistant.ui.directives

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import com.jarvis.assistant.JarvisApp

/** Brief visual wake acknowledgement; no activity launch or permanent overlay. */
object WakeGlow {
    fun show(context: Context) = Handler(Looper.getMainLooper()).post {
        val activity = JarvisApp.instance.currentResumedActivity
        val view = object : View(context) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val start = android.os.SystemClock.uptimeMillis()
            override fun onDraw(canvas: Canvas) {
                val progress = ((android.os.SystemClock.uptimeMillis() - start) / 1500f).coerceIn(0f, 1f)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 8 * resources.displayMetrics.density
                paint.shader = SweepGradient(width / 2f, height / 2f, intArrayOf(Color.CYAN, Color.BLUE, 0xffbd71ff.toInt(), 0xffffc85e.toInt(), Color.CYAN), null)
                paint.alpha = (200 * kotlin.math.sin(progress * Math.PI)).toInt().coerceIn(0, 200)
                canvas.drawRoundRect(5f, 5f, width - 5f, height - 5f, 60f, 60f, paint)
                if (progress < 1) postInvalidateDelayed(33)
            }
        }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val handler = Handler(Looper.getMainLooper())
        if (activity != null && !activity.isFinishing) {
            val decor = activity.window.decorView as? FrameLayout ?: return@post
            decor.addView(view, FrameLayout.LayoutParams(-1, -1))
            handler.postDelayed({ decor.removeView(view) }, 1550)
        } else if (android.provider.Settings.canDrawOverlays(context)) {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { alpha = 0.7f }
            runCatching { manager.addView(view, params); handler.postDelayed({ runCatching { manager.removeView(view) } }, 1550) }
        }
    }
}
