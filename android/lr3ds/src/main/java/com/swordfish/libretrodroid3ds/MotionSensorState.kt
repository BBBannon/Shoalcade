package com.swordfish.libretrodroid3ds

import kotlin.math.abs

internal data class SensorVector(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    fun rotated(rotation: Int): SensorVector = when (rotation) {
        1 -> SensorVector(y, -x, z)
        2 -> SensorVector(-x, -y, z)
        3 -> SensorVector(-y, x, z)
        else -> this
    }

    fun scaled(factor: Float) = SensorVector(x * factor, y * factor, z * factor)
}

internal data class MotionSensorState(
    val gyroscope: SensorVector = SensorVector(),
    val accelerometer: SensorVector = SensorVector(),
    val accelerometerRest: SensorVector = SensorVector()
)

internal class MotionSensorCalibration {
    private var rotation: Int? = null
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravitySamples = 0
    private var rest = SensorVector()
    private var restSum = SensorVector()
    private var restSamples = 0
    private var accelerometer = SensorVector()
    private var gyroscope = SensorVector()

    fun resume() {
        gravityX = 0f
        gravityY = 0f
        gravitySamples = 0
        rest = SensorVector()
        restSum = SensorVector()
        restSamples = 0
        accelerometer = SensorVector()
        gyroscope = SensorVector()
    }

    fun accelerometerChanged(x: Float, y: Float, z: Float): MotionSensorState {
        accelerometer = SensorVector(x, y, z)
        if (rotation == null) {
            gravityX += x
            gravityY += y
            gravitySamples++
            if (gravitySamples == SAMPLE_COUNT) {
                val averageX = gravityX / SAMPLE_COUNT
                val averageY = gravityY / SAMPLE_COUNT
                if (abs(averageX) > GRAVITY_THRESHOLD || abs(averageY) > GRAVITY_THRESHOLD) {
                    rotation = if (abs(averageY) >= abs(averageX)) {
                        if (averageY > 0f) 0 else 2
                    } else {
                        if (averageX < 0f) 1 else 3
                    }
                }
                gravityX = 0f
                gravityY = 0f
                gravitySamples = 0
            }
        } else if (restSamples < SAMPLE_COUNT) {
            val rotated = accelerometerInG()
            restSum = SensorVector(restSum.x + rotated.x, restSum.y + rotated.y, restSum.z + rotated.z)
            restSamples++
            if (restSamples == SAMPLE_COUNT) {
                rest = restSum.scaled(1f / SAMPLE_COUNT)
            }
        }
        return snapshot()
    }

    fun gyroscopeChanged(x: Float, y: Float, z: Float): MotionSensorState {
        gyroscope = SensorVector(x, y, z)
        return snapshot()
    }

    private fun accelerometerInG() = accelerometer.rotated(rotation ?: 0).scaled(1f / STANDARD_GRAVITY)

    private fun snapshot() = MotionSensorState(
        gyroscope.rotated(rotation ?: 0),
        accelerometerInG(),
        rest
    )

    private companion object {
        const val SAMPLE_COUNT = 30
        const val GRAVITY_THRESHOLD = 5f
        const val STANDARD_GRAVITY = 9.80665f
    }
}
