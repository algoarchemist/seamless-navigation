package com.sih26168.idr.motion

import kotlin.math.PI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurningDetectorTest {

    @Test
    fun `first sample is never turning, no previous azimuth to diff against`() {
        val detector = TurningDetector()
        assertFalse(detector.evaluate(nowNs = 1_000_000_000L, azimuthRad = 0f))
    }

    @Test
    fun `slow heading drift below threshold is not turning`() {
        val detector = TurningDetector(minYawRateForTurningRadPerSec = 0.15f)
        detector.evaluate(nowNs = 0L, azimuthRad = 0f)
        // 0.05 rad over 1 second = 0.05 rad/s, well under the 0.15 threshold.
        val turning = detector.evaluate(nowNs = 1_000_000_000L, azimuthRad = 0.05f)
        assertFalse(turning)
    }

    @Test
    fun `fast heading change above threshold is turning`() {
        val detector = TurningDetector(minYawRateForTurningRadPerSec = 0.15f)
        detector.evaluate(nowNs = 0L, azimuthRad = 0f)
        // 0.5 rad over 1 second = 0.5 rad/s, well over the 0.15 threshold.
        val turning = detector.evaluate(nowNs = 1_000_000_000L, azimuthRad = 0.5f)
        assertTrue(turning)
    }

    @Test
    fun `turning in the negative direction is still detected`() {
        val detector = TurningDetector(minYawRateForTurningRadPerSec = 0.15f)
        detector.evaluate(nowNs = 0L, azimuthRad = 0f)
        val turning = detector.evaluate(nowNs = 1_000_000_000L, azimuthRad = -0.5f)
        assertTrue(turning)
    }

    @Test
    fun `wraparound near plus-minus pi is not misread as a huge turn`() {
        val detector = TurningDetector(minYawRateForTurningRadPerSec = 0.15f)
        val almostPi = (PI - 0.05).toFloat()
        val justPastNegativePi = (-PI + 0.05).toFloat()
        detector.evaluate(nowNs = 0L, azimuthRad = almostPi)
        // True angular change here is 0.1 rad (wrapping the short way around),
        // not the ~6.18 rad a naive subtraction would compute.
        val turning = detector.evaluate(nowNs = 1_000_000_000L, azimuthRad = justPastNegativePi)
        assertFalse(turning)
    }

    @Test
    fun `zero elapsed time is not turning, same guard as YawRate itself`() {
        val detector = TurningDetector(minYawRateForTurningRadPerSec = 0.15f)
        detector.evaluate(nowNs = 1_000_000_000L, azimuthRad = 0f)
        val turning = detector.evaluate(nowNs = 1_000_000_000L, azimuthRad = 1f)
        assertFalse(turning)
    }
}
