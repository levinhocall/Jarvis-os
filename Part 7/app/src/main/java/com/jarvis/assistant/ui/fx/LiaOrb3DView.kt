package com.jarvis.assistant.ui.fx

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.annotation.RequiresApi
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.ConversationState

/**
 * 3D visual presence orb using AGSL RuntimeShader on Android 13+ (API 33+).
 *
 * Implements:
 * - Analytic 3D sphere normals
 * - Rotating fractional Brownian motion (FBM) energy field
 * - Diffuse + Blinn-Phong specular lighting reacting to device tilt (accelerometer)
 * - Low-pass filtered tilt smoothing
 * - Fresnel rim lighting and soft ambient halo
 * - Amplitude displacement and core brightening
 * - State-specific color palettes
 * - Graceful Canvas fallback on API < 33 or when reduce-motion is enabled
 */
class LiaOrb3DView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), SensorEventListener {

    data class OrbPalette(
        val coreR: Float, val coreG: Float, val coreB: Float,
        val rimR: Float, val rimG: Float, val rimB: Float,
        val haloR: Float, val haloG: Float, val haloB: Float
    ) {
        companion object {
            fun fromHex(coreHex: String, rimHex: String, haloHex: String): OrbPalette {
                fun parseRgb(hex: String): Triple<Float, Float, Float> {
                    val clean = hex.removePrefix("#")
                    val r = clean.substring(0, 2).toInt(16) / 255f
                    val g = clean.substring(2, 4).toInt(16) / 255f
                    val b = clean.substring(4, 6).toInt(16) / 255f
                    return Triple(r, g, b)
                }
                val (cr, cg, cb) = parseRgb(coreHex)
                val (rr, rg, rb) = parseRgb(rimHex)
                val (hr, hg, hb) = parseRgb(haloHex)
                return OrbPalette(cr, cg, cb, rr, rg, rb, hr, hg, hb)
            }
        }
    }

    companion object {
        const val AGSL_SOURCE = """
            uniform float2 iResolution;
            uniform float iTime;
            uniform float3 colorCore;
            uniform float3 colorRim;
            uniform float3 colorHalo;
            uniform float amplitude;
            uniform float2 tilt;

            float hash(float2 p) {
                p = fract(p * float2(123.34, 456.21));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y);
            }

            float noise(float2 p) {
                float2 i = floor(p);
                float2 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                float a = hash(i);
                float b = hash(i + float2(1.0, 0.0));
                float c = hash(i + float2(0.0, 1.0));
                float d = hash(i + float2(1.0, 1.0));
                return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
            }

            float fbm(float2 p) {
                float v = 0.0;
                float a = 0.5;
                float2 shift = float2(100.0, 100.0);
                float2x2 rot = float2x2(cos(0.5), sin(0.5), -sin(0.5), cos(0.5));
                for (int i = 0; i < 4; ++i) {
                    v += a * noise(p);
                    p = rot * p * 2.0 + shift;
                    a *= 0.5;
                }
                return v;
            }

            half4 main(float2 fragCoord) {
                float minDim = min(iResolution.x, iResolution.y);
                float2 center = iResolution * 0.5;
                float2 p = (fragCoord - center) / (minDim * 0.5);

                float baseRadius = 0.68 + amplitude * 0.08;
                float dist = length(p);

                float3 lightDir = normalize(float3(tilt.x, -tilt.y, 1.2));
                float3 viewDir = float3(0.0, 0.0, 1.0);

                if (dist <= baseRadius) {
                    float rNorm = dist / baseRadius;
                    float z = sqrt(max(0.0, 1.0 - rNorm * rNorm));
                    float3 normal = normalize(float3(p / baseRadius, z));

                    float angle = iTime * 0.6;
                    float2 rotUV = float2(
                        normal.x * cos(angle) - normal.y * sin(angle),
                        normal.x * sin(angle) + normal.y * cos(angle)
                    );
                    float energy = fbm(rotUV * 3.0 + normal.z * 2.0 + iTime * 0.4);

                    float diff = max(0.0, dot(normal, lightDir));
                    float3 halfVec = normalize(lightDir + viewDir);
                    float spec = pow(max(0.0, dot(normal, halfVec)), 24.0);
                    float fresnel = pow(1.0 - max(0.0, dot(normal, viewDir)), 2.5);

                    float3 core = colorCore * (0.6 + 0.4 * diff + energy * 0.35 + amplitude * 0.4);
                    float3 rim = colorRim * (fresnel * 1.4 + spec * 0.8);
                    float3 finalCol = core + rim;

                    float edgeAlpha = smoothstep(baseRadius, baseRadius - 0.02, dist);
                    return half4(finalCol * edgeAlpha, edgeAlpha);
                } else {
                    float haloDist = (dist - baseRadius) / (1.0 - baseRadius);
                    if (haloDist < 1.0) {
                        float haloAlpha = pow(1.0 - haloDist, 3.0) * (0.35 + amplitude * 0.25);
                        float3 haloCol = colorHalo * haloAlpha;
                        return half4(haloCol, haloAlpha);
                    }
                }
                return half4(0.0, 0.0, 0.0, 0.0);
            }
        """

        // Standard Palettes per prompt specifications
        val PALETTE_IDLE = OrbPalette.fromHex("#FF6FAE", "#8B5CF6", "#6D28D9") // Calm lotus/violet
        val PALETTE_LISTENING = OrbPalette.fromHex("#3FE0D0", "#06B6D4", "#0891B2") // Lagoon with breathing pulse
        val PALETTE_THINKING = OrbPalette.fromHex("#8B5CF6", "#3FE0D0", "#4C1D95") // Faster swirl violet/lagoon
        val PALETTE_SPEAKING = OrbPalette.fromHex("#FFB547", "#FF6FAE", "#FF8A3D") // Warm marigold-pink
        val PALETTE_CONNECTING = OrbPalette.fromHex("#F59E0B", "#FF8A3D", "#D97706") // Amber slow spin
        val PALETTE_ERROR = OrbPalette.fromHex("#EF4444", "#7F1D1D", "#991B1B") // Dim red

        fun getPaletteForState(state: ConversationState): OrbPalette {
            return when (state) {
                ConversationState.IDLE, ConversationState.WAITING_FOR_APPROVAL -> PALETTE_IDLE
                ConversationState.LISTENING, ConversationState.INTERRUPTED -> PALETTE_LISTENING
                ConversationState.THINKING, ConversationState.EXECUTING -> PALETTE_THINKING
                ConversationState.SPEAKING -> PALETTE_SPEAKING
                ConversationState.RECOVERING -> PALETTE_CONNECTING
                ConversationState.FAILED -> PALETTE_ERROR
            }
        }

        fun filterTilt(current: Float, raw: Float, alpha: Float = 0.85f): Float {
            return current * alpha + raw * (1f - alpha)
        }
    }

    private var runtimeShader: Any? = null
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var currentPalette = PALETTE_IDLE
    private var currentState = ConversationState.IDLE
    private var currentAmplitude = 0f

    private var filteredTiltX = 0f
    private var filteredTiltY = 0f
    private val lowPassAlpha = 0.85f

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var isSensorRegistered = false

    private var animator: ValueAnimator? = null
    private val startTime = SystemClock.uptimeMillis()

    init {
        initShaderIfSupported()
        startLoop()
    }

    private fun initShaderIfSupported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val shader = android.graphics.RuntimeShader(AGSL_SOURCE)
                runtimeShader = shader
                shaderPaint.shader = shader
            } catch (_: Exception) {
                runtimeShader = null
            }
        }
    }

    private fun startLoop() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerSensor()
        if (animator?.isRunning != true) {
            animator?.start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unregisterSensor()
        animator?.cancel()
    }

    private fun registerSensor() {
        if (!isSensorRegistered && accelerometer != null) {
            isSensorRegistered = sensorManager?.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME
            ) ?: false
        }
    }

    private fun unregisterSensor() {
        if (isSensorRegistered) {
            sensorManager?.unregisterListener(this)
            isSensorRegistered = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        // Normalize raw accelerometer values (range ~ -9.8 to 9.8) to [-1.0, 1.0]
        val rawX = (event.values[0] / 9.8f).coerceIn(-1f, 1f)
        val rawY = (event.values[1] / 9.8f).coerceIn(-1f, 1f)

        filteredTiltX = filterTilt(filteredTiltX, rawX, lowPassAlpha)
        filteredTiltY = filterTilt(filteredTiltY, rawY, lowPassAlpha)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun updateState(state: ConversationState, audioAmplitude: Float = 0f) {
        currentState = state
        currentPalette = getPaletteForState(state)
        currentAmplitude = audioAmplitude.coerceIn(0f, 1f)
        invalidate()
    }

    fun setAudioAmplitude(amplitude: Float) {
        currentAmplitude = amplitude.coerceIn(0f, 1f)
        invalidate()
    }

    fun setPalette(palette: OrbPalette) {
        currentPalette = palette
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val isReduceMotion = try {
            JarvisApp.instance.preferences.isReduceMotionEnabled
        } catch (_: Exception) {
            false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && runtimeShader != null && !isReduceMotion) {
            drawAgslShader(canvas, w, h)
        } else {
            drawCanvasFallback(canvas, w, h)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawAgslShader(canvas: Canvas, w: Float, h: Float) {
        val shader = runtimeShader as? android.graphics.RuntimeShader ?: return
        val elapsedSec = (SystemClock.uptimeMillis() - startTime) / 1000f

        shader.setFloatUniform("iResolution", w, h)
        shader.setFloatUniform("iTime", elapsedSec)
        shader.setFloatUniform("colorCore", currentPalette.coreR, currentPalette.coreG, currentPalette.coreB)
        shader.setFloatUniform("colorRim", currentPalette.rimR, currentPalette.rimG, currentPalette.rimB)
        shader.setFloatUniform("colorHalo", currentPalette.haloR, currentPalette.haloG, currentPalette.haloB)
        shader.setFloatUniform("amplitude", currentAmplitude)
        shader.setFloatUniform("tilt", filteredTiltX, filteredTiltY)

        canvas.drawRect(0f, 0f, w, h, shaderPaint)
    }

    private fun drawCanvasFallback(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f
        val radius = minOf(w, h) * 0.35f * (1f + currentAmplitude * 0.12f)

        val coreInt = Color.rgb(
            (currentPalette.coreR * 255).toInt(),
            (currentPalette.coreG * 255).toInt(),
            (currentPalette.coreB * 255).toInt()
        )
        val rimInt = Color.rgb(
            (currentPalette.rimR * 255).toInt(),
            (currentPalette.rimG * 255).toInt(),
            (currentPalette.rimB * 255).toInt()
        )
        val transparent = Color.TRANSPARENT

        fallbackPaint.shader = RadialGradient(
            cx - filteredTiltX * radius * 0.3f,
            cy + filteredTiltY * radius * 0.3f,
            radius * 1.3f,
            intArrayOf(coreInt, rimInt, transparent),
            floatArrayOf(0f, 0.75f, 1.0f),
            Shader.TileMode.CLAMP
        )

        canvas.drawCircle(cx, cy, radius * 1.3f, fallbackPaint)
    }
}
