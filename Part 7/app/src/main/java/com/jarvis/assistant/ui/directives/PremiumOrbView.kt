package com.jarvis.assistant.ui.directives

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

/** Native aurora sphere; cached shaders, bounded 30fps and actual microphone amplitude. */
class PremiumOrbView(context: Context) : View(context) {
    var level = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var core: Shader? = null
    private var halo: Shader? = null
    private var rim: Shader? = null
    private val started = android.os.SystemClock.uptimeMillis()
    private var paletteKey = ""
    init { contentDescription = "JARVIS aurora voice orb"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { rebuild() }
    private fun rebuild() {
        paletteKey = com.jarvis.assistant.JarvisApp.instance.preferences.orbPreset
        val colors = when (paletteKey) {
            "crimson" -> intArrayOf(0xffffcb8c.toInt(), 0xffff416a.toInt(), 0xff791747.toInt())
            "midnight" -> intArrayOf(0xff9acbff.toInt(), 0xff6a5dff.toInt(), 0xff161740.toInt())
            else -> intArrayOf(0xffa0fff4.toInt(), 0xff558eff.toInt(), 0xff702dd8.toInt())
        }
        val r = min(width, height).coerceAtLeast(1) * .29f
        val x = width / 2f; val y = height / 2f
        core = RadialGradient(x-r*.32f, y-r*.4f, r*1.65f, intArrayOf(Color.WHITE, colors[0], colors[1], colors[2], 0xff050915.toInt()), floatArrayOf(0f,.12f,.38f,.7f,1f), Shader.TileMode.CLAMP)
        halo = RadialGradient(x,y,r*1.7f,intArrayOf(colors[1] and 0x00ffffff or 0x55000000, Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
        rim = SweepGradient(x,y,intArrayOf(colors[0],colors[1],colors[2],colors[0]),null)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (paletteKey != com.jarvis.assistant.JarvisApp.instance.preferences.orbPreset) rebuild()
        val t = (android.os.SystemClock.uptimeMillis()-started)/1000f
        val amplitude = if (level.isFinite()) level.coerceIn(0f,1f) else 0f
        val cx=width/2f; val cy=height/2f; val r=min(width,height)*(.285f+amplitude*.035f)
        paint.style=Paint.Style.FILL; paint.alpha=255; paint.shader=halo
        canvas.drawCircle(cx,cy,r*1.7f,paint)
        paint.shader=core; canvas.drawCircle(cx,cy,r,paint)
        paint.shader=rim; paint.style=Paint.Style.STROKE; paint.strokeWidth=2f*resources.displayMetrics.density
        canvas.drawCircle(cx,cy,r,paint)
        canvas.save(); canvas.rotate(t*9f,cx,cy)
        for (i in 0..2) {
            paint.alpha=110+i*35; paint.strokeWidth=(1f+i*.3f)*resources.displayMetrics.density
            rect.set(cx-r*(1.17f+i*.09f),cy-r*(.45f+i*.15f),cx+r*(1.17f+i*.09f),cy+r*(.45f+i*.15f))
            canvas.drawArc(rect,35f+i*83f,235f,false,paint)
        }
        canvas.restore()
        paint.shader=null; paint.style=Paint.Style.FILL; paint.color=0xffbdfaff.toInt()
        for (i in 0 until 22) {
            val a=i*2.39996f+t*(.12f+(i%3)*.02f)
            val radius=r*(1.3f+(i%5)*.07f)
            paint.alpha=60+(i*19%150)
            canvas.drawCircle(cx+cos(a)*radius,cy+sin(a)*radius*.83f,resources.displayMetrics.density*(.6f+i%3*.35f),paint)
        }
        if (isShown && windowVisibility == VISIBLE && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            val saver = com.jarvis.assistant.JarvisApp.instance.preferences.powerProfile == "saver"
            postInvalidateDelayed(if (saver) 100L else 33L)
        }
    }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (visibility == VISIBLE) invalidate() }
}
