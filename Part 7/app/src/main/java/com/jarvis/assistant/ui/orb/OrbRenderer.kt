package com.jarvis.assistant.ui.orb

import android.graphics.*
import android.os.Build
import kotlin.math.*

/** Cached native paints/gradients; one Compose Canvas, no per-particle objects. */
internal class OrbRenderer {
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE }
    private val rect=RectF()
    private val particles=OrbParticles()
    private val ticks=FloatArray(64*4)
    private val core=FloatArray(3);private val rim=FloatArray(3);private val accent=FloatArray(3)
    private var cachedPalette:OrbPalette?=null
    private var cachedWidth=0f;private var cachedHeight=0f
    private var bloom:Shader?=null;private var surface:Shader?=null;private var nucleus:Shader?=null
    private var rimInt=Color.CYAN
    private var gpu=if(Build.VERSION.SDK_INT>=33) runCatching { OrbShader() }.getOrNull() else null
    var reducedQuality=false
    var reflection=true
    var drawCount=0L;private set
    fun release(){fill.shader=null;line.shader=null;bloom=null;surface=null;nucleus=null;gpu=null}
    init { for(i in 0 until 64){val a=i*PI.toFloat()/32f;val p=i*4;val inside=if(i%4==0).92f else .96f;ticks[p]=cos(a)*inside;ticks[p+1]=sin(a)*inside;ticks[p+2]=cos(a);ticks[p+3]=sin(a)} }
    private fun colors(p:OrbPalette,m:OrbMotion) {
        val muted=m.mix[6];val error=m.mix[5];val offline=m.mix[7]
        val base=(1-error-offline).coerceIn(0f,1f)
        // Offline amber and error red are separate linear-light crossfades.
        rim[0]=(OrbMotion.linear(p.rim.red)*base+OrbMotion.linear(p.warn.red)*error+offline)*(1-muted)+.18f*muted
        rim[1]=(OrbMotion.linear(p.rim.green)*base+OrbMotion.linear(p.warn.green)*error+OrbMotion.linear(.65f)*offline)*(1-muted)+.18f*muted
        rim[2]=(OrbMotion.linear(p.rim.blue)*base+OrbMotion.linear(p.warn.blue)*error+OrbMotion.linear(.18f)*offline)*(1-muted)+.18f*muted
        core[0]=OrbMotion.linear(p.core.red);core[1]=OrbMotion.linear(p.core.green);core[2]=OrbMotion.linear(p.core.blue)
        accent[0]=OrbMotion.linear(p.accent.red);accent[1]=OrbMotion.linear(p.accent.green);accent[2]=OrbMotion.linear(p.accent.blue)
        rimInt=Color.rgb(OrbMotion.srgb(rim[0]).coerceIn(0f,1f),OrbMotion.srgb(rim[1]).coerceIn(0f,1f),OrbMotion.srgb(rim[2]).coerceIn(0f,1f))
    }
    fun draw(c:Canvas,w:Float,h:Float,m:OrbMotion,p:OrbPalette,reduced:Boolean) {
        drawCount++
        val d=min(w,h);if(d<=0)return
        colors(p,m)
        val cx=w*.5f;val cy=h*.5f;val r=d*.325f
        if(cachedWidth!=w||cachedHeight!=h||cachedPalette!=p){
            cachedWidth=w;cachedHeight=h;cachedPalette=p
            // Rebuilt only on size/palette changes, never on steady animation frames.
            val rgb=Color.rgb(p.rim.red,p.rim.green,p.rim.blue)
            bloom=RadialGradient(cx,cy,d*.5f,intArrayOf((rgb and 0xffffff) or 0x44000000,rgb and 0xffffff),null,Shader.TileMode.CLAMP)
            surface=RadialGradient(cx-r*.25f,cy-r*.3f,r*1.4f,intArrayOf(Color.rgb(24,65,83),Color.rgb(3,10,22)),null,Shader.TileMode.CLAMP)
            nucleus=RadialGradient(cx,cy,r*.3f,intArrayOf(Color.WHITE,rgb,rgb and 0xffffff),null,Shader.TileMode.CLAMP)
        }
        c.save()
        val shake=m.glitch(reduced)*r*.018f
        c.translate(if(reduced)0f else sin(m.time*15f)*shake,0f)
        c.scale(m.scale(reduced),m.scale(reduced),cx,cy)
        fill.alpha=255
        val activeShader=gpu
        if(activeShader!=null&&Build.VERSION.SDK_INT>=33){activeShader.update(w,h,m,core,rim,accent,reduced);fill.shader=activeShader.shader;c.drawRect(0f,0f,w,h,fill)}else{
            fill.shader=bloom;fill.alpha=(m.energy*180).toInt().coerceIn(0,255);c.drawCircle(cx,cy,d*.5f,fill)
            fill.alpha=255;fill.shader=surface;c.drawCircle(cx,cy,r,fill)
            fill.shader=null;fill.color=rimInt;fill.alpha=(24+m.rms*40).toInt()
            for(i in 0..3){val a=i*1.57f+m.time*(.1f+m.mix[3]*.3f);c.drawOval(cx+cos(a)*r*.22f-r*.58f,cy+sin(a)*r*.22f-r*.48f,cx+cos(a)*r*.22f+r*.58f,cy+sin(a)*r*.22f+r*.48f,fill)}
            fill.shader=nucleus;fill.alpha=(180+m.bass*75).toInt();c.drawCircle(cx,cy,r*(.28f+m.bass*.08f),fill)
        }
        fill.shader=null;fill.color=rimInt
        line.color=rimInt;line.strokeWidth=max(1f,d/350f)
        for(i in 0..2){
            c.save();c.translate(cx,cy);c.rotate(m.rotation*(if(i==1)-1 else 1)+i*57f)
            c.scale(1f,.42f+i*.18f)
            val rr=r*(1.10f+i*.13f)
            rect.set(-rr,-rr,rr,rr);line.alpha=(70+m.mid*130).toInt()
            val ringAlpha=line.alpha
            line.alpha=(ringAlpha*(1-m.mix[7])).toInt();c.drawOval(rect,line)
            line.alpha=(ringAlpha*m.mix[7]).toInt()
            if(line.alpha>0)for(j in 0..11)c.drawArc(rect,j*30f,18f,false,line)
            if(i==0){for(j in 0 until 64){val k=j*4;line.alpha=if(j%4==0)100 else 45;c.drawLine(ticks[k]*rr,ticks[k+1]*rr,ticks[k+2]*rr,ticks[k+3]*rr,line)}}
            c.restore()
        }
        line.alpha=(75+m.treble*100).toInt();rect.set(cx-r*.88f,cy-r*.88f,cx+r*.88f,cy+r*.88f)
        for(i in 0..2)c.drawArc(rect,m.rotation*2f+i*120f,35f+m.mid*15f,false,line)
        val shock=m.wakeRadius()
        if(!reduced&&shock>0){line.alpha=((1-shock)*120).toInt();c.drawCircle(cx,cy,r*(1+shock*.7f),line)}
        val activeCount=(48+72*(m.mix[2]+m.mix[3]+m.mix[4]).coerceIn(0f,1f)).toInt()
        val count=if(reducedQuality)minOf(60,activeCount) else activeCount
        particles.draw(c,fill,cx,cy,d*.46f,m,count,reduced)
        if(!reducedQuality){
            line.alpha=6;line.strokeWidth=1f
            var y=cy-r;while(y<cy+r){val extent=sqrt(max(0f,r*r-(y-cy)*(y-cy)));c.drawLine(cx-extent,y,cx+extent,y,line);y+=4f}
            if(!reduced){val sweep=(m.time%6f)/6f;line.alpha=15;c.drawLine(cx-r,cy-r+sweep*r*2,cx+r,cy-r+sweep*r*2,line)}
            if(reflection){fill.shader=bloom;fill.alpha=25;c.save();c.scale(1f,.12f,cx,cy+r*1.65f);c.drawCircle(cx,cy+r*1.65f,r,fill);c.restore();fill.shader=null}
        }
        if(m.mix[6]>.3f){line.alpha=(m.mix[6]*230).toInt();line.color=Color.LTGRAY;line.strokeWidth=d*.008f;c.drawLine(cx-r*.14f,cy-r*.18f,cx+r*.14f,cy+r*.18f,line);rect.set(cx-r*.06f,cy-r*.13f,cx+r*.06f,cy+r*.1f);c.drawRoundRect(rect,r*.06f,r*.06f,line)}
        c.restore()
    }
}
