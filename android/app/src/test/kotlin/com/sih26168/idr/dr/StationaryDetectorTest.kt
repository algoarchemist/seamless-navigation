package com.sih26168.idr.dr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DWELL_MS = 300L

class StationaryDetectorTest {

    private fun newDetector() = StationaryDetector(minStationaryDwellMs = DWELL_MS)

    @Test
    fun `starts as not stationary`() {
        assertFalse(newDetector().isStationary)
    }

    @Test
    fun `a brief below-threshold blip does not commit to stationary`() {
        val detector = newDetector()
        detector.evaluate(0L, linearAccelMagnitudeMps2 = 0.01f, gyroMagnitudeRadPerSec = 0.01f)
        val result = detector.evaluate(DWELL_MS / 2, linearAccelMagnitudeMps2 = 0.01f, gyroMagnitudeRadPerSec = 0.01f)
        assertFalse(result)
    }

    @Test
    fun `below-threshold sustained for the full dwell commits to stationary`() {
        val detector = newDetector()
        detector.evaluate(0L, linearAccelMagnitudeMps2 = 0.01f, gyroMagnitudeRadPerSec = 0.01f)
        val result = detector.evaluate(DWELL_MS, linearAccelMagnitudeMps2 = 0.01f, gyroMagnitudeRadPerSec = 0.01f)
        assertTrue(result)
        assertTrue(detector.isStationary)
    }

    @Test
    fun `one ms before the dwell threshold is still not stationary`() {
        val detector = newDetector()
        detector.evaluate(0L, linearAccelMagnitudeMps2 = 0.01f, gyroMagnitudeRadPerSec = 0.01f)
        val result = detector.evaluate(DWELL_MS - 1, linearAccelMagnitudeMps2 = 0.01f, gyroMagnitudeRadPerSec = 0.01f)
        assertFalse(result)
    }

    @Test
    fun `high linear acceleration alone prevents stationary regardless of gyro`() {
        val detector = newDetector()
        detector.evaluate(0L, linearAccelMagnitudeMps2 = 5.0f, gyroMagnitudeRadPerSec = 0.0f)
        val result = detector.evaluate(DWELL_MS, linearAccelMagnitudeMps2 = 5.0f, gyroMagnitudeRadPerSec = 0.0f)
        assertFalse(result)
    }

    @Test
    fun `high gyro rate alone prevents stationary regardless of accel`() {
        val detector = newDetector()
        detector.evaluate(0L, linearAccelMagnitudeMps2 = 0.0f, gyroMagnitudeRadPerSec = 2.0f)
        val result = detector.evaluate(DWELL_MS, linearAccelMagnitudeMps2 = 0.0f, gyroMagnitudeRadPerSec = 2.0f)
        assertFalse(result)
    }

    @Test
    fun `a mid-streak spike resets the dwell clock`() {
        val detector = newDetector()
        detector.evaluate(0L, linearAccelMagnitudeMps2 = 0.01f, gyroMagnitudeRadPerSec = 0.01f)
        detector.evaluate(DWELL_MS / 2, linearAccelMagnitudeMps2 = 5.0f, gyroMagnitudeRadPerSec = 0.01f) // spike
        val result = detector.evaluate(DWELL_MS, linearAccelMagnitudeMps2 = 0.01f, gyroMagnitudeRadPerSec = 0.01f)
        assertFalse("the spike interrupted the streak, so the dwell clock must have restarted", result)
    }
}
