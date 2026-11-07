/*
 * Kotlin port of ideas from AbuZar-Ansarii/Jarvis (MIT, (c) 2026 AbuZar Ansarii).
 * Original is Flutter/Dart; this is a reimplementation for JARVIS OS. See docs/THIRD_PARTY_NOTICES.md.
 */
package com.jarvis.assistant.ui.extras

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.jarvis.assistant.ui.directives.DirectiveActivity

/** Turns proximity-sensor near/far transitions into wave / double wave / hover gestures. */
class AirGestureController(
    context: Context,
    private val onGesture: (String) -> Unit,
) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val handler = Handler(Looper.getMainLooper())
    private var nearAt = 0L
    private var lastWaveAt = 0L
    private var single: Runnable? = null
    private var hover: Runnable? = null

    val available get() = sensor != null

    fun start(): Boolean = sensor != null && sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)

    fun stop() {
        sm.unregisterListener(this)
        single?.let(handler::removeCallbacks); hover?.let(handler::removeCallbacks)
        single = null; hover = null; nearAt = 0L
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(e: SensorEvent) {
        val range = sensor?.maximumRange ?: return
        val near = e.values[0] < minOf(range, 5f)
        val now = SystemClock.elapsedRealtime()
        if (near) {
            if (nearAt == 0L) nearAt = now
            hover?.let(handler::removeCallbacks)
            hover = Runnable { nearAt = 0L; onGesture("hover") }.also { handler.postDelayed(it, HOVER_MS) }
        } else {
            hover?.let(handler::removeCallbacks); hover = null
            val held = if (nearAt == 0L) -1L else now - nearAt
            nearAt = 0L
            if (held in 1 until WAVE_MAX_MS) {
                if (now - lastWaveAt < DOUBLE_MS) {
                    single?.let(handler::removeCallbacks); single = null
                    lastWaveAt = 0L
                    onGesture("double_wave")
                } else {
                    lastWaveAt = now
                    single = Runnable { single = null; onGesture("wave") }.also { handler.postDelayed(it, DOUBLE_MS) }
                }
            }
        }
    }

    private companion object {
        const val HOVER_MS = 1200L
        const val WAVE_MAX_MS = 700L
        const val DOUBLE_MS = 900L
    }
}

/** Active only while this screen is open (a background gesture service is not included). */
class AirGesturesActivity : DirectiveActivity() {
    private var controller: AirGestureController? = null
    private lateinit var info: TextView
    private lateinit var last: TextView
    private var toggleRef: SwitchMaterial? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        heading("Air Gestures")
        label("Wave over the proximity sensor (top of the phone): wave = next track, hover 1 s = play/pause, double wave = screenshot (needs the Jarvis accessibility service). Works only while this screen is open.", 12f)
        info = label("")
        last = label("No gesture yet.")
        val ctl = AirGestureController(this) { g -> handle(g) }
        controller = ctl
        val toggle = SwitchMaterial(this).apply { text = "Enable air gestures" }
        page.addView(toggle)
        toggleRef = toggle
        if (!ctl.available) { toggle.isEnabled = false; info.text = "This phone has no proximity sensor." }
        toggle.setOnCheckedChangeListener { _, on ->
            if (on) info.text = if (ctl.start()) "Listening for gestures." else "Could not start the sensor."
            else { ctl.stop(); info.text = "Off." }
        }
    }

    private fun handle(g: String) {
        when (g) {
            "wave" -> { DeviceActs.mediaKey(this, KeyEvent.KEYCODE_MEDIA_NEXT); last.text = "Wave: next track" }
            "hover" -> { DeviceActs.mediaKey(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); last.text = "Hover: play/pause" }
            "double_wave" -> last.text = if (DeviceActs.screenshot()) "Double wave: screenshot requested" else "Double wave: enable the Jarvis accessibility service for screenshots"
        }
    }

    override fun onStop() { controller?.stop(); toggleRef?.isChecked = false; super.onStop() }
    override fun onDestroy() { controller?.stop(); super.onDestroy() }
}
