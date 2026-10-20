package com.jarvis.assistant.audio

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors proximity and light sensors to determine if the device is inside
 * a pocket, bag, or facing down on a surface.
 *
 * When inside a pocket, wake-word activation and accidental barge-ins are
 * suppressed to prevent unwanted battery drain and accidental interactions.
 */
object PocketDetectionManager : SensorEventListener {

    private const val TAG = "PocketDetection"

    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var lightSensor: Sensor? = null

    private val _isInPocket = MutableStateFlow(false)
    val isInPocket: StateFlow<Boolean> = _isInPocket.asStateFlow()

    private var proximityDistance = Float.MAX_VALUE
    private var proximityMaxRange = 5.0f
    private var ambientLightLux = Float.MAX_VALUE
    private var isRegistered = false

    /**
     * Initializes and registers sensor listeners using the application [context].
     * Safe to call multiple times (duplicate registrations are ignored).
     */
    fun start(context: Context) {
        if (isRegistered) return
        try {
            val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            if (sm == null) {
                Log.w(TAG, "SensorManager not available on this device")
                return
            }
            sensorManager = sm
            proximitySensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            lightSensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT)

            proximitySensor?.let { sensor ->
                proximityMaxRange = sensor.maximumRange
                sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                Log.d(TAG, "Proximity sensor registered (maxRange=$proximityMaxRange)")
            }

            lightSensor?.let { sensor ->
                sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                Log.d(TAG, "Light sensor registered")
            }

            isRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register pocket detection sensors: ${e.message}")
        }
    }

    /**
     * Unregisters sensor listeners to preserve power when assistant services are stopped.
     */
    fun stop() {
        if (!isRegistered) return
        try {
            sensorManager?.unregisterListener(this)
            isRegistered = false
            _isInPocket.value = false
            Log.d(TAG, "Pocket detection sensors unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering sensors: ${e.message}")
        }
    }

    fun isDeviceInPocket(): Boolean {
        return _isInPocket.value
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                proximityDistance = event.values.firstOrNull() ?: Float.MAX_VALUE
                reevaluatePocketState()
            }
            Sensor.TYPE_LIGHT -> {
                ambientLightLux = event.values.firstOrNull() ?: Float.MAX_VALUE
                reevaluatePocketState()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    private fun reevaluatePocketState() {
        val isNear = proximityDistance < 3.0f || proximityDistance < (proximityMaxRange * 0.5f)
        val isDark = ambientLightLux < 5.0f

        // If proximity sensor reports NEAR and light sensor (if present) reports dark, device is enclosed
        val inPocket = if (lightSensor != null && ambientLightLux != Float.MAX_VALUE) {
            isNear && isDark
        } else {
            isNear
        }

        if (_isInPocket.value != inPocket) {
            _isInPocket.value = inPocket
            Log.i(TAG, "Pocket state transition: inPocket=$inPocket (prox=$proximityDistance, lux=$ambientLightLux)")
        }
    }
}
