package com.jarvis.assistant.ui.directives

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

/** Native aurora sphere; cached shaders, bounded 30fps and actual microphone amplitude. */
class PremiumOrbView(context: Context) : View(context) {
    var level = 0f
    var plasmaMode = false
    var renderingActive = false
        set(value) { field = value; if (value) invalidate() }
    private var presentation = com.jarvis.assistant.data.model.VoicePresentationSnapshot()
    private val fluidPath = Path()
    private val directions = FloatArray(192) { index ->
        val angle = (index / 2) * (Math.PI * 2 / 96)
        if (index % 2 == 0) cos(angle).toFloat() else sin(angle).toFloat()
    }
    private var motionSeconds = 0f
    private var previousFrame = 0L
    private var smoothedLevel = 0f
    private var displayedLevel = 0f
    private var receivedAt = 0L
    private var visual = com.jarvis.assistant.ui.orb.PlasmaVisualState.IDLE
    private var intensity = 1f
    private var glow = 1f
    private var sizeScale = 1f
    private var speed = 1f
    private var saverQuality = false
    private val tint = floatArrayOf(0f, .7f, 1f)
    private val targetTint = floatArrayOf(0f, .7f, 1f)
    fun configure(options: org.json.JSONObject) {
        intensity = options.optDouble("intensity", 1.0).toFloat().coerceIn(.3f, 1.5f)
        glow = options.optDouble("glow", 1.0).toFloat().coerceIn(0f, 1.5f)
        sizeScale = options.optDouble("size", 1.0).toFloat().coerceIn(.7f, 1.15f)
        speed = options.optDouble("speed", 1.0).toFloat().coerceIn(.4f, 1.6f)
        saverQuality = options.optString("quality") == "saver"
        invalidate()
    }
    fun submit(facts: com.jarvis.assistant.data.model.VoicePresentationSnapshot) {
        val invalidatesOutput = facts.sessionId != presentation.sessionId || facts.interruptionGeneration != presentation.interruptionGeneration
        val previousVisual = visual
        if (invalidatesOutput) smoothedLevel = 0f
        presentation = facts
        receivedAt = android.os.SystemClock.elapsedRealtime()
        val mapped = com.jarvis.assistant.ui.orb.OrbPresentationMapper.map(facts, receivedAt)
        visual = mapped.state
        displayedLevel = max(mapped.output, mapped.mic * if (facts.confirmedSpeech) .8f else .18f)
        level = max(mapped.output, mapped.mic)
        val color = when (visual) {
            com.jarvis.assistant.ui.orb.PlasmaVisualState.PROCESSING -> 0xff956aff.toInt()
            com.jarvis.assistant.ui.orb.PlasmaVisualState.ERROR -> 0xffd7886c.toInt()
            com.jarvis.assistant.ui.orb.PlasmaVisualState.DISCONNECTED -> 0xff4a6581.toInt()
            else -> context.getColor(com.jarvis.assistant.R.color.plasma_cyan)
        }
        targetTint[0] = Color.red(color) / 255f; targetTint[1] = Color.green(color) / 255f; targetTint[2] = Color.blue(color) / 255f
        if (invalidatesOutput || previousVisual != visual || !com.jarvis.assistant.util.VisualMotionSettings.isReduced(context)) invalidate()
    }
    private fun drawPlasma(canvas: Canvas) {
        val now = android.os.SystemClock.elapsedRealtime()
        val reduced = com.jarvis.assistant.util.VisualMotionSettings.isReduced(context)
        val dt = if (previousFrame == 0L) 0f else ((now - previousFrame) / 1000f).coerceIn(0f, .08f)
        previousFrame = now
        if (!reduced) motionSeconds += dt * speed
        val decay = ((300L - (now - receivedAt)).coerceIn(0, 200) / 200f)
        val amplitude = if (reduced) 0f else displayedLevel * decay
        if (reduced) smoothedLevel = 0f else smoothedLevel += (amplitude - smoothedLevel) * (1f - exp(-dt / .045f))
        val tintBlend = if (reduced) 1f else 1f - exp(-dt / .15f)
        for (channel in 0..2) tint[channel] += (targetTint[channel] - tint[channel]) * tintBlend
        val quiet = visual == com.jarvis.assistant.ui.orb.PlasmaVisualState.ERROR || visual == com.jarvis.assistant.ui.orb.PlasmaVisualState.DISCONNECTED
        val brightness = intensity * if (quiet) .5f else 1f
        val x = width / 2f; val y = height / 2f
        val r = min(width, height) * (.32f + smoothedLevel * .018f) * sizeScale
        paint.style = Paint.Style.FILL; paint.shader = halo; paint.alpha = (130 * glow * brightness).toInt().coerceIn(0, 255)
        canvas.drawCircle(x, y, r * 1.55f, paint)
        for (layer in 0..3) {
            fluidPath.rewind()
            for (i in 0 until 96) {
                val a = i * (Math.PI * 2 / 96).toFloat()
                val modulation = if (reduced) 0f else .025f * sin(a * 3 + motionSeconds * .23f + layer) +
                    .018f * sin(a * 5 - motionSeconds * .17f)
                val radius = r * (1f - layer * .14f + modulation)
                val px = x + directions[i * 2] * radius
                val py = y + directions[i * 2 + 1] * radius
                if (i == 0) fluidPath.moveTo(px, py) else fluidPath.lineTo(px, py)
            }
            fluidPath.close()
            paint.shader = core; paint.alpha = ((if (layer == 0) 215 else 65) * brightness).toInt().coerceIn(0, 255)
            canvas.drawPath(fluidPath, paint)
            paint.shader = null; paint.color = Color.rgb((tint[0]*255).toInt(), (tint[1]*255).toInt(), (tint[2]*255).toInt())
            paint.alpha = 45; canvas.drawPath(fluidPath, paint)
        }
        if (renderingActive && isShown && windowVisibility == VISIBLE && !reduced) {
            val saver = saverQuality || context.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode ||
                com.jarvis.assistant.JarvisApp.instance.preferences.powerProfile == "saver"
            postInvalidateDelayed(if (saver) 66L else 33L)
        }
    }
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
        val colors = if (plasmaMode) intArrayOf(context.getColor(com.jarvis.assistant.R.color.plasma_ice),
            context.getColor(com.jarvis.assistant.R.color.plasma_cyan), context.getColor(com.jarvis.assistant.R.color.plasma_blue)) else when (paletteKey) {
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
        if (!plasmaMode && paletteKey != com.jarvis.assistant.JarvisApp.instance.preferences.orbPreset) rebuild()
        if (plasmaMode) { drawPlasma(canvas); return }
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
        if (renderingActive && isShown && windowVisibility == VISIBLE && !com.jarvis.assistant.util.VisualMotionSettings.isReduced(context)) {
            val saver = com.jarvis.assistant.JarvisApp.instance.preferences.powerProfile == "saver"
            postInvalidateDelayed(if (saver) 100L else 33L)
        }
    }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (visibility == VISIBLE) invalidate() }
}
