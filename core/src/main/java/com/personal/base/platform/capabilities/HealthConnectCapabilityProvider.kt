package com.personal.base.platform.capabilities

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 22. HealthConnectCapabilityProvider: step tracking, heart rate, and health sensors.
 */
class HealthConnectCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider, SensorEventListener {

    override val type: CapabilityType = CapabilityType.HEALTH_CONNECT

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private val _stepCount = MutableStateFlow(0f)
    val stepCount: StateFlow<Float> = _stepCount.asStateFlow()

    fun startStepTracking() {
        if (stepSensor != null) {
            sensorManager?.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)
            logger.i("HealthConnect", "Registered step counter sensor listener.")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER) {
            _stepCount.value = event.values.firstOrNull() ?: 0f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun checkStatus(): CapabilityStatus {
        return if (stepSensor != null) CapabilityStatus.Available
        else CapabilityStatus.Unavailable("Hardware step sensor not present")
    }

    override fun release() {
        sensorManager?.unregisterListener(this)
    }
}
