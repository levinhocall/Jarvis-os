package com.jarvis.assistant.ui.orb

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.*

internal class OrbParticles {
    private val pool = FloatArray(120*4)
    init { for(i in 0 until 120){ val p=i*4;pool[p]=i*2.399963f;pool[p+1]=.76f+(i*37%101)/220f;pool[p+2]=(i*17%79)/79f;pool[p+3]=.7f+(i%4)*.25f } }
    fun draw(canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float, motion: OrbMotion, count: Int, reduced: Boolean) {
        val pull = 1f - motion.mix[2]*.14f + motion.mix[4]*motion.bass*.18f
        for(i in 0 until count){
            val p=i*4;val a=pool[p]+if(reduced)0f else motion.time*(.025f+pool[p+2]*.035f)
            val depth=sin(a*.7f+pool[p+2]*6f)
            val distance=radius*pool[p+1]*pull
            paint.alpha=((.15f+motion.energy*.35f)*(depth*.3f+.7f)*255).toInt().coerceIn(0,200)
            canvas.drawCircle(cx+cos(a)*distance,cy+sin(a)*distance*.88f,pool[p+3]*radius/100f,paint)
        }
    }
}
