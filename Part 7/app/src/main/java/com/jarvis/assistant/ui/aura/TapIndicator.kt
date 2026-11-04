package com.jarvis.assistant.ui.aura

import android.accessibilityservice.AccessibilityService
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*

object TapIndicator {
    fun show(service: AccessibilityService, x: Float, y: Float) {
        if (!AuraOptions.enabled("tapIndicator")) return
        Handler(Looper.getMainLooper()).post {
            val size=(48*service.resources.displayMetrics.density).toInt()
            val view=object : View(service) {
                val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.RED; style=Paint.Style.STROKE; strokeWidth=3*resources.displayMetrics.density }
                override fun onDraw(canvas: Canvas) { canvas.drawCircle(width/2f,height/2f,width*.34f,pen) }
            }
            val manager=service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            val params=WindowManager.LayoutParams(size,size,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
                gravity=Gravity.TOP or Gravity.LEFT; this.x=(x-size/2).toInt(); this.y=(y-size/2).toInt()
            }
            runCatching { manager.addView(view,params); Handler(Looper.getMainLooper()).postDelayed({ runCatching { manager.removeView(view) } },450) }
        }
    }
}
