package com.swordfish.libretrodroid3ds

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionSensorCalibrationTest {
    private val g = 9.80665f

    private fun assertVector(message: String, expected: SensorVector, actual: SensorVector) {
        assertEquals("$message x", expected.x, actual.x, 1e-5f)
        assertEquals("$message y", expected.y, actual.y, 1e-5f)
        assertEquals("$message z", expected.z, actual.z, 1e-5f)
    }

    @Test
    fun everyMountingRotatesBothSensorsAndReportsAccelerationInG() {
        val gravityByMounting = listOf(
            SensorVector(0f, 8f, 6f), SensorVector(-8f, 0f, 6f),
            SensorVector(0f, -8f, 6f), SensorVector(8f, 0f, 6f)
        )
        val gyroscopeByMounting = listOf(
            SensorVector(1f, 2f, 3f), SensorVector(2f, -1f, 3f),
            SensorVector(-1f, -2f, 3f), SensorVector(-2f, 1f, 3f)
        )
        val upright = SensorVector(0f, 8f / g, 6f / g)
        for ((mounting, gravity) in gravityByMounting.withIndex()) {
            val calibration = MotionSensorCalibration()
            repeat(60) { calibration.accelerometerChanged(0f, 0f, g) }
            repeat(60) { calibration.accelerometerChanged(gravity.x, gravity.y, gravity.z) }
            val state = calibration.gyroscopeChanged(1f, 2f, 3f)

            assertEquals("mounting=$mounting gyro", gyroscopeByMounting[mounting], state.gyroscope)
            assertVector("mounting=$mounting accel", upright, state.accelerometer)
            assertVector("mounting=$mounting rest", upright, state.accelerometerRest)
        }
    }

    @Test
    fun deviceAtRestFaceUpReadsOneGOnZ() {
        val calibration = MotionSensorCalibration()
        val state = calibration.accelerometerChanged(0f, 0f, g)
        assertVector("flat", SensorVector(0f, 0f, 1f), state.accelerometer)
    }

    @Test
    fun flatStartupLeavesAxesUnrotatedUntilTilted() {
        val calibration = MotionSensorCalibration()
        repeat(90) { calibration.accelerometerChanged(1f, 2f, g) }
        val state = calibration.gyroscopeChanged(1f, 2f, 3f)
        assertEquals(SensorVector(1f, 2f, 3f), state.gyroscope)
        assertEquals(SensorVector(), state.accelerometerRest)
    }

    @Test
    fun restIsHeldAfterCaptureAndRecapturedAfterResume() {
        val calibration = MotionSensorCalibration()
        repeat(60) { calibration.accelerometerChanged(0f, 8f, 6f) }
        val captured = calibration.gyroscopeChanged(0f, 0f, 0f).accelerometerRest
        assertEquals(captured, calibration.accelerometerChanged(1f, 2f, 3f).accelerometerRest)

        calibration.resume()
        assertEquals(SensorVector(), calibration.gyroscopeChanged(0f, 0f, 0f).accelerometerRest)
        repeat(30) { calibration.accelerometerChanged(0f, 6f, 7f) }
        assertVector("recaptured", SensorVector(0f, 6f / g, 7f / g), calibration.gyroscopeChanged(0f, 0f, 0f).accelerometerRest)
    }
}
