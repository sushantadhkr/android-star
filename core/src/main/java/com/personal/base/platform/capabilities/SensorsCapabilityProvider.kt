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

data class SensorSnapshot(
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val accelZ: Float = 0f,
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,
    val lightLux: Float = -1f,
    val pressureHpa: Float = -1f,
    val availableSensorsCount: Int = 0,
    val lastUpdateMs: Long = 0L
)

class SensorsCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider, SensorEventListener {

    override val type: CapabilityType = CapabilityType.SENSORS

    private var sensorManager: SensorManager? = null
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var lightSensor: Sensor? = null
    private var pressureSensor: Sensor? = null

    private val _sensorData = MutableStateFlow(SensorSnapshot())
    val sensorData: StateFlow<SensorSnapshot> = _sensorData.asStateFlow()

    private var currentAccel = FloatArray(3)
    private var currentGyro = FloatArray(3)
    private var currentLight = -1f
    private var currentPressure = -1f

    override val status: CapabilityStatus
        get() {
            return if (sensorManager != null) {
                CapabilityStatus.Available
            } else {
                CapabilityStatus.Unavailable("SensorManager service unavailable")
            }
        }

    override fun initialize() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sensorManager == null) {
            logger.w("SensorsProvider", "SensorManager is not available on this device")
            return
        }

        val allSensors = sensorManager?.getSensorList(Sensor.TYPE_ALL).orEmpty()
        logger.i("SensorsProvider", "Initialized with ${allSensors.size} hardware sensors available")

        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        pressureSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

        _sensorData.value = _sensorData.value.copy(availableSensorsCount = allSensors.size)

        // Register default sensors with UI-friendly sampling rate
        accelSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyroSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        lightSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        pressureSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun release() {
        sensorManager?.unregisterListener(this)
        logger.i("SensorsProvider", "Sensor listeners unregistered")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val now = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                currentAccel = event.values.clone()
            }
            Sensor.TYPE_GYROSCOPE -> {
                currentGyro = event.values.clone()
            }
            Sensor.TYPE_LIGHT -> {
                currentLight = event.values[0]
            }
            Sensor.TYPE_PRESSURE -> {
                currentPressure = event.values[0]
            }
        }

        _sensorData.value = _sensorData.value.copy(
            accelX = currentAccel.getOrElse(0) { 0f },
            accelY = currentAccel.getOrElse(1) { 0f },
            accelZ = currentAccel.getOrElse(2) { 0f },
            gyroX = currentGyro.getOrElse(0) { 0f },
            gyroY = currentGyro.getOrElse(1) { 0f },
            gyroZ = currentGyro.getOrElse(2) { 0f },
            lightLux = currentLight,
            pressureHpa = currentPressure,
            lastUpdateMs = now
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
