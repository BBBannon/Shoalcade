package com.swordfish.libretrodroid3ds

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.swordfish.libretrodroid3ds.LibretroDroid.SENSOR_ACCELEROMETER
import com.swordfish.libretrodroid3ds.LibretroDroid.SENSOR_GYROSCOPE

internal class MotionSensors(context: Context) : SensorEventListener {
    private val manager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val calibration = MotionSensorCalibration()
    private var sensorUsage = 0

    val availableSensors: Int =
        (if (accelerometer != null) SENSOR_ACCELEROMETER else 0) or
            (if (gyroscope != null) SENSOR_GYROSCOPE else 0)

    @Volatile
    var state = MotionSensorState()
        private set

    fun setRequiredSensors(usage: Int) {
        val listened = listenedSensors(usage and availableSensors)
        if (listened == sensorUsage) return
        manager?.unregisterListener(this)
        sensorUsage = listened
        state = MotionSensorState()
        if (listened == 0) return
        calibration.resume()
        if (listened and SENSOR_ACCELEROMETER != 0) {
            manager?.registerListener(this, accelerometer, SAMPLE_PERIOD_US)
        }
        if (listened and SENSOR_GYROSCOPE != 0) {
            manager?.registerListener(this, gyroscope, SAMPLE_PERIOD_US)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (sensorUsage == 0) return
        val values = event.values
        state = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> calibration.accelerometerChanged(values[0], values[1], values[2])
            Sensor.TYPE_GYROSCOPE -> {
                if (sensorUsage and SENSOR_GYROSCOPE == 0) return
                calibration.gyroscopeChanged(values[0], values[1], values[2])
            }
            else -> return
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun listenedSensors(usage: Int): Int =
        if (usage and SENSOR_GYROSCOPE != 0) usage or (SENSOR_ACCELEROMETER and availableSensors) else usage

    private companion object {
        const val SAMPLE_PERIOD_US = 16_667
    }
}
