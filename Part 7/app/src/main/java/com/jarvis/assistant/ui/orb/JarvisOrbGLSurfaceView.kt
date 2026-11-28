package com.jarvis.assistant.ui.orb

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Hardware-accelerated OpenGL ES 3.0 procedural 3D / neural shader voice orb.
 * Supports all 12 JARVIS presets, 8 multi-stop palettes, real-time audio bands,
 * and dynamic FPS throttling driven by ResourceGovernor (60 / 30 / 24 / 0 FPS).
 */
class JarvisOrbGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer = ProceduralOrbRenderer()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var targetFps: Int = 60
    @Volatile private var reducedMotion: Boolean = false
    @Volatile private var running: Boolean = false

    private val frameTick = object : Runnable {
        override fun run() {
            if (!running) return
            val fps = if (reducedMotion) 0 else targetFps.coerceIn(0, 60)
            if (fps > 0) {
                requestRender()
                mainHandler.postDelayed(this, (1000L / fps).coerceAtLeast(16L))
            }
        }
    }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        setZOrderOnTop(false)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun updateFrameBudget(maxFps: Int, isReducedMotion: Boolean) {
        val changed = targetFps != maxFps || reducedMotion != isReducedMotion
        targetFps = maxFps.coerceIn(0, 60)
        reducedMotion = isReducedMotion
        if (changed) {
            mainHandler.removeCallbacks(frameTick)
            if (running && !reducedMotion && targetFps > 0) {
                mainHandler.post(frameTick)
            } else {
                requestRender()
            }
        }
    }

    fun submitState(
        config: OrbRenderConfig,
        motionState: JarvisOrbMotionState,
        rms: Float,
        bass: Float,
        mid: Float,
        treble: Float
    ) {
        renderer.updateInputs(config, motionState, rms, bass, mid, treble)
        if (reducedMotion || targetFps <= 0) {
            requestRender()
        }
    }

    override fun onResume() {
        super.onResume()
        running = true
        mainHandler.removeCallbacks(frameTick)
        if (!reducedMotion && targetFps > 0) {
            mainHandler.post(frameTick)
        } else {
            requestRender()
        }
    }

    override fun onPause() {
        running = false
        mainHandler.removeCallbacks(frameTick)
        super.onPause()
    }

    override fun onDetachedFromWindow() {
        running = false
        mainHandler.removeCallbacks(frameTick)
        queueEvent { renderer.releaseGl() }
        super.onDetachedFromWindow()
    }

    private class ProceduralOrbRenderer : Renderer {
        private val smoother = OrbStateSmoother(stepAlpha = 0.04f)
        private val lock = Any()

        @Volatile private var activeConfig = OrbRenderConfig()
        @Volatile private var activeState = JarvisOrbMotionState.IDLE
        @Volatile private var inRms = 0f
        @Volatile private var inBass = 0f
        @Volatile private var inMid = 0f
        @Volatile private var inTreble = 0f

        private var widthPx = 1
        private var heightPx = 1
        private var startTimeNs = System.nanoTime()
        private var programId = 0
        private var quadVbo = 0
        private var quadVao = 0

        // Uniform handles
        private var uResolution = -1
        private var uTime = -1
        private var uPreset = -1
        private var uState = -1
        private var uRms = -1
        private var uBass = -1
        private var uMid = -1
        private var uTreble = -1
        private var uColor1 = -1
        private var uColor2 = -1
        private var uColor3 = -1
        private var uIntensity = -1
        private var uGlow = -1
        private var uScale = -1
        private var uBlink = -1

        private val quadVertices: FloatBuffer = ByteBuffer
            .allocateDirect(4 * 2 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
                position(0)
            }

        fun updateInputs(
            config: OrbRenderConfig,
            state: JarvisOrbMotionState,
            rms: Float,
            bass: Float,
            mid: Float,
            treble: Float
        ) {
            synchronized(lock) {
                activeConfig = config
                activeState = state
                inRms = rms
                inBass = bass
                inMid = mid
                inTreble = treble
            }
        }

        override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

            val vert = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
            val frag = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            programId = GLES30.glCreateProgram()
            GLES30.glAttachShader(programId, vert)
            GLES30.glAttachShader(programId, frag)
            GLES30.glLinkProgram(programId)
            GLES30.glDeleteShader(vert)
            GLES30.glDeleteShader(frag)

            val vaos = IntArray(1)
            val vbos = IntArray(1)
            GLES30.glGenVertexArrays(1, vaos, 0)
            GLES30.glGenBuffers(1, vbos, 0)
            quadVao = vaos[0]
            quadVbo = vbos[0]

            GLES30.glBindVertexArray(quadVao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 4 * 2 * 4, quadVertices, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glBindVertexArray(0)

            uResolution = GLES30.glGetUniformLocation(programId, "uResolution")
            uTime = GLES30.glGetUniformLocation(programId, "uTime")
            uPreset = GLES30.glGetUniformLocation(programId, "uPreset")
            uState = GLES30.glGetUniformLocation(programId, "uState")
            uRms = GLES30.glGetUniformLocation(programId, "uRms")
            uBass = GLES30.glGetUniformLocation(programId, "uBass")
            uMid = GLES30.glGetUniformLocation(programId, "uMid")
            uTreble = GLES30.glGetUniformLocation(programId, "uTreble")
            uColor1 = GLES30.glGetUniformLocation(programId, "uColor1")
            uColor2 = GLES30.glGetUniformLocation(programId, "uColor2")
            uColor3 = GLES30.glGetUniformLocation(programId, "uColor3")
            uIntensity = GLES30.glGetUniformLocation(programId, "uIntensity")
            uGlow = GLES30.glGetUniformLocation(programId, "uGlow")
            uScale = GLES30.glGetUniformLocation(programId, "uScale")
            uBlink = GLES30.glGetUniformLocation(programId, "uBlink")
        }

        override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
            widthPx = width.coerceAtLeast(1)
            heightPx = height.coerceAtLeast(1)
            GLES30.glViewport(0, 0, widthPx, heightPx)
        }

        override fun onDrawFrame(unused: GL10?) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            if (programId == 0) return

            val cfg: OrbRenderConfig
            val st: JarvisOrbMotionState
            val r: Float
            val b: Float
            val m: Float
            val tr: Float
            synchronized(lock) {
                cfg = activeConfig
                st = activeState
                r = inRms * cfg.responsiveness
                b = inBass * cfg.responsiveness
                m = inMid * cfg.responsiveness
                tr = inTreble * cfg.responsiveness
            }
            smoother.step(r, b, m, tr, st, cfg.blinkEnabled)

            val elapsedSec = ((System.nanoTime() - startTimeNs) / 1_000_000_000.0 * cfg.speed).toFloat()
            val c1 = cfg.colorTheme.primaryRgb()
            val c2 = cfg.colorTheme.secondaryRgb()
            val c3 = cfg.colorTheme.coreRgb()

            GLES30.glUseProgram(programId)
            GLES30.glUniform2f(uResolution, widthPx.toFloat(), heightPx.toFloat())
            GLES30.glUniform1f(uTime, elapsedSec)
            GLES30.glUniform1i(uPreset, cfg.preset.shaderIndex)
            GLES30.glUniform1i(uState, st.shaderValue)
            GLES30.glUniform1f(uRms, smoother.smoothedAudio)
            GLES30.glUniform1f(uBass, smoother.smoothedBass)
            GLES30.glUniform1f(uMid, smoother.smoothedMid)
            GLES30.glUniform1f(uTreble, smoother.smoothedTreble)
            GLES30.glUniform3f(uColor1, c1[0], c1[1], c1[2])
            GLES30.glUniform3f(uColor2, c2[0], c2[1], c2[2])
            GLES30.glUniform3f(uColor3, c3[0], c3[1], c3[2])
            GLES30.glUniform1f(uIntensity, cfg.intensity)
            GLES30.glUniform1f(uGlow, cfg.glow)
            GLES30.glUniform1f(uScale, cfg.scale)
            GLES30.glUniform1f(uBlink, smoother.blinkAmount)

            GLES30.glBindVertexArray(quadVao)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindVertexArray(0)
        }

        fun releaseGl() {
            if (quadVbo != 0) {
                GLES30.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
                quadVbo = 0
            }
            if (quadVao != 0) {
                GLES30.glDeleteVertexArrays(1, intArrayOf(quadVao), 0)
                quadVao = 0
            }
            if (programId != 0) {
                GLES30.glDeleteProgram(programId)
                programId = 0
            }
        }

        private fun compileShader(type: Int, source: String): Int {
            val id = GLES30.glCreateShader(type)
            GLES30.glShaderSource(id, source)
            GLES30.glCompileShader(id)
            return id
        }
    }

    companion object {
        private const val VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec2 aPos;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        private const val FRAGMENT_SHADER = """#version 300 es
precision highp float;

uniform vec2 uResolution;
uniform float uTime;
uniform int uPreset;
uniform int uState;
uniform float uRms;
uniform float uBass;
uniform float uMid;
uniform float uTreble;
uniform vec3 uColor1;
uniform vec3 uColor2;
uniform vec3 uColor3;
uniform float uIntensity;
uniform float uGlow;
uniform float uScale;
uniform float uBlink;

out vec4 fragColor;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    mat2 rot = mat2(0.8, 0.6, -0.6, 0.8);
    for (int i = 0; i < 4; i++) {
        v += a * noise(p);
        p = rot * p * 2.05;
        a *= 0.5;
    }
    return v;
}

mat2 rot2(float a) {
    float c = cos(a);
    float s = sin(a);
    return mat2(c, -s, s, c);
}

void main() {
    vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / min(uResolution.x, uResolution.y);
    float energy = uRms * 0.55 + uBass * 0.35 + uMid * 0.25 + uTreble * 0.2;
    float radius = (0.31 + 0.045 * energy + 0.012 * sin(uTime * 2.2)) * uScale;
    uv.y /= max(0.18, 1.0 - 0.72 * uBlink * (uPreset == 0 || uPreset == 11 ? 1.0 : 0.2));

    float d = length(uv);
    float ang = atan(uv.y, uv.x);

    // State energy & tint modulation
    float speedMul = (uState == 3) ? 2.1 : ((uState == 4) ? 1.7 : ((uState == 2) ? 1.3 : 1.0));
    float t = uTime * speedMul;

    vec3 c1 = uColor1;
    vec3 c2 = uColor2;
    vec3 c3 = uColor3;
    if (uState == 4) { // Speaking: energized hot core highlight
        c2 = mix(c2, vec3(0.95, 0.38, 0.72), 0.35);
    } else if (uState == 3) { // Thinking: warm violet/amber pulse
        c2 = mix(c2, vec3(0.98, 0.72, 0.22), 0.35);
    } else if (uState == 5) { // Error attention
        c1 = vec3(0.86, 0.14, 0.22);
        c2 = vec3(1.0, 0.45, 0.25);
        c3 = vec3(1.0, 0.90, 0.85);
    }

    float sphereMask = smoothstep(radius + 0.012, radius - 0.012, d);
    float z = sqrt(max(0.0, radius * radius - d * d));
    vec3 normal = normalize(vec3(uv, z + 0.0001));
    float fresnel = pow(1.0 - max(0.0, normal.z), 2.4);
    float halo = exp(-max(0.0, d - radius) * (6.2 - 1.8 * uRms)) * (0.48 + 0.45 * uGlow);

    vec3 sphereCol = vec3(0.0);
    float overlayAlpha = 0.0;
    vec3 ringCol = vec3(0.0);

    if (uPreset == 0) { // Neural Core
        float n = fbm(normal.xy * 4.5 + vec2(t * 0.4, -t * 0.3));
        sphereCol = mix(c1, c2, n) * (0.65 + 0.5 * uIntensity) + c3 * fresnel * 0.85;
    } else if (uPreset == 1) { // Arc Reactor 3D
        vec2 p = rot2(t * 0.5) * uv;
        float core = exp(-d * 10.5) * 1.4;
        float rings = smoothstep(0.018, 0.004, abs(d - radius * 0.78)) +
                      smoothstep(0.012, 0.003, abs(d - radius * 1.18)) * step(0.1, sin(atan(p.y, p.x) * 8.0 + t));
        sphereCol = mix(c1 * 0.4, c2, 0.5 + 0.5 * fresnel) + c3 * core;
        ringCol += c3 * rings * 1.25;
        overlayAlpha += rings * 0.85;
    } else if (uPreset == 2) { // Pulse Reactor
        float wave = 0.5 + 0.5 * sin(d * 34.0 - t * 4.5 - uBass * 5.0);
        float outerRipple = smoothstep(0.02, 0.0, abs(d - (radius + 0.08 + 0.05 * fract(t * 0.6))));
        sphereCol = mix(c1, c2, wave * 0.8 + fresnel * 0.5) + c3 * fresnel * 0.7;
        ringCol += c2 * outerRipple;
        overlayAlpha += outerRipple * 0.75;
    } else if (uPreset == 3) { // Liquid Mercury
        vec2 warp = normal.xy * 3.4;
        warp += vec2(sin(warp.y * 3.0 + t), cos(warp.x * 3.0 - t * 0.8)) * (0.35 + 0.4 * uRms);
        float m = fbm(warp);
        sphereCol = mix(c1 * 0.35, c3, smoothstep(0.25, 0.82, m + fresnel * 0.45));
    } else if (uPreset == 4 || uPreset == 5) { // Nova HUD / Crystal 3D
        float r1 = smoothstep(0.010, 0.002, abs(d - radius * 1.12));
        float ticks = smoothstep(0.014, 0.003, abs(d - radius * 1.24)) * step(0.35, sin(ang * (uPreset == 5 ? 12.0 : 20.0) + t * 1.1));
        float facet = (uPreset == 5) ? abs(sin(normal.x * 8.0 + t) * cos(normal.y * 8.0 - t)) : fbm(normal.xy * 5.0 + t * 0.3);
        sphereCol = mix(c1, c2, facet) + c3 * fresnel * 0.9;
        ringCol += mix(c2, c3, 0.6) * (r1 + ticks);
        overlayAlpha += (r1 + ticks) * 0.9;
    } else if (uPreset == 6) { // Crimson HUD
        float scan = 0.5 + 0.5 * sin(uv.y * 65.0 - t * 3.0);
        float visor = smoothstep(0.016, 0.004, abs(d - radius * 1.15)) * (0.4 + 0.6 * cos(ang * 3.0 + t));
        sphereCol = mix(c1, c2, 0.4 + 0.6 * scan) + c3 * pow(fresnel, 1.5);
        ringCol += c2 * visor;
        overlayAlpha += visor * 0.8;
    } else if (uPreset == 7) { // FRIDAY Matrix wireframe
        float lat = smoothstep(0.08, 0.02, abs(fract(normal.y * 5.0) - 0.5));
        float lon = smoothstep(0.08, 0.02, abs(fract((ang / 3.14159) * 6.0 + t * 0.25) - 0.5));
        float grid = max(lat, lon);
        sphereCol = mix(c1 * 0.22, c3, grid * 0.9 + fresnel * 0.5);
    } else if (uPreset == 8) { // Shattered Prism
        vec2 cell = floor(normal.xy * 7.0);
        float shard = hash21(cell);
        float edge = fbm(normal.xy * 6.0 + t * 0.4);
        sphereCol = mix(c1, c2, shard) * (0.65 + 0.6 * edge) + c3 * fresnel;
    } else if (uPreset == 9) { // Obsidian Engine 3D
        float crack = smoothstep(0.56, 0.62, abs(fbm(normal.xy * 5.2 + t * 0.25) - 0.5) + 0.12 * uBass);
        float crackGlow = exp(-abs(fbm(normal.xy * 4.8 + t * 0.2) - 0.5) * 18.0);
        sphereCol = mix(vec3(0.04, 0.05, 0.08), c2 * 1.5, crackGlow + crack * 0.2) + c1 * fresnel * 0.7;
    } else if (uPreset == 10) { // Quantum Swarm
        float swarm = 0.0;
        for (int i = 0; i < 6; i++) {
            float fi = float(i);
            float a = t * (0.7 + fi * 0.14) + fi * 1.047;
            vec2 pos = vec2(cos(a), sin(a * 1.3)) * (radius * (0.52 + 0.35 * sin(fi * 2.1 + t)));
            swarm += exp(-length(uv - pos) * (28.0 - 8.0 * uRms));
        }
        sphereCol = mix(c1 * 0.25, c2, 0.5 * fresnel) + c3 * clamp(swarm, 0.0, 1.4);
        ringCol += c3 * swarm * 0.4;
        overlayAlpha += clamp(swarm * 0.35, 0.0, 0.8);
    } else { // 11: JARVIS 2047 Aurora Nebula
        vec2 q = vec2(fbm(normal.xy * 3.0 + vec2(0.0, t * 0.35)),
                      fbm(normal.xy * 3.0 + vec2(5.2, -t * 0.28)));
        float aurora = fbm(normal.xy * 3.6 + 1.8 * q + uMid * 0.4);
        sphereCol = mix(mix(c1, c2, aurora), c3, smoothstep(0.65, 1.0, aurora + fresnel * 0.6));
    }

    vec3 haloCol = mix(c1, c2, 0.5 + 0.5 * sin(ang * 2.0 + t)) * halo;
    vec3 rgb = sphereCol * sphereMask * (0.75 + 0.35 * uIntensity) + haloCol * (1.0 - sphereMask * 0.45) + ringCol;
    float alpha = clamp(max(sphereMask, max(halo * 0.85, overlayAlpha)), 0.0, 1.0);
    fragColor = vec4(rgb * alpha, alpha);
}
"""
    }
}
