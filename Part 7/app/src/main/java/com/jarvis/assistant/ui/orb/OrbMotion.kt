package com.jarvis.assistant.ui.orb

import kotlin.math.*
import androidx.compose.runtime.staticCompositionLocalOf

class OrbDebugCounters {
    var compositions=0L; internal set
    var draws=0L; internal set
}
val LocalOrbDebugCounters=staticCompositionLocalOf<OrbDebugCounters?> { null }
val LocalOrbReflection=staticCompositionLocalOf { true }

/** Monotonic, frame-rate independent state weights. No per-frame objects. */
internal class OrbMotion(initialState:OrbState=OrbState.IDLE) {
    val mix = FloatArray(8).apply { this[initialState.ordinal] = 1f }
    var time = 0f; private set
    var age = 0f; private set
    var state = initialState; private set
    var rms = 0f; private set
    var bass = 0f; private set
    var mid = 0f; private set
    var treble = 0f; private set
    var rotation = 0f; private set
    fun step(dtInput: Float, input: OrbState, levels: OrbAudioLevels, reduced: Boolean) {
        val dt = dtInput.coerceIn(0f, .1f)
        if (state != input) {
            if (state == OrbState.SPEAKING) { rms=0f;bass=0f;mid=0f;treble=0f }
            state = input; age = 0f
        }
        age += dt
        if (!reduced) time += dt
        val target = when { state == OrbState.WAKE && age > .4f -> OrbState.LISTENING
            state == OrbState.ERROR && age > 1.2f -> OrbState.IDLE; else -> state }
        val k = if (reduced) 1f else 1f - exp(-dt / .11f)
        for (i in mix.indices) mix[i] += ((if (i == target.ordinal) 1f else 0f) - mix[i]) * k
        val live = !reduced && (input == OrbState.LISTENING || input == OrbState.SPEAKING)
        val interrupted = !live || input != state
        if (interrupted) { rms = 0f; bass = 0f; mid = 0f; treble = 0f }
        rms = smoothLevel(rms, if(live) levels.rms else 0f, dt)
        bass = smoothLevel(bass, if(live) levels.bass else 0f, dt)
        mid = smoothLevel(mid, if(live) levels.mid else 0f, dt)
        treble = smoothLevel(treble, if(live) levels.treble else 0f, dt)
        if(!reduced) rotation += dt * (2f + mix[3] * 40f) * (1f - mix[6])
    }
    val energy get() = .35f + mix[1]*.5f + mix[2]*.4f + mix[3]*.35f + mix[4]*.65f - mix[6]*.25f - mix[7]*.2f
    fun scale(reduced: Boolean): Float = if(reduced) 1f else 1f + .03f*sin(time*PI.toFloat()/2f) + bass*.04f + mix[3]*.012f*sin(time*5.24f)
    fun wakeRadius(): Float = if(state == OrbState.WAKE && age < .4f) age / .4f else 0f
    fun glitch(reduced: Boolean): Float = if(!reduced && state == OrbState.ERROR) (1f-age/.25f).coerceIn(0f,1f) else 0f
    companion object {
        fun smoothLevel(old: Float, value: Float, dt: Float): Float {
            val v = OrbAudioLevels.clean(value)
            return old + (v-old)*(1f-exp(-dt/(if(v>old) .05f else .25f)))
        }
        fun linear(c: Float): Float = if(c<=.04045f)c/12.92f else ((c+.055f)/1.055f).pow(2.4f)
        fun srgb(c: Float): Float = if(c<=.0031308f)c*12.92f else 1.055f*c.pow(1f/2.4f)-.055f
        fun mixLinear(a:Float,b:Float,t:Float):Float = linear(a)+(linear(b)-linear(a))*t.coerceIn(0f,1f)
    }
}
