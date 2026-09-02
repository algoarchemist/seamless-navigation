package com.sih26168.idr.fusion

import org.junit.Assert.assertEquals
import org.junit.Test

class GnssJitterFilterTest {

    private val tolerance = 1e-6

    @Test
    fun `first sample is trusted outright, no prior estimate to blend against`() {
        val filter = GnssJitterFilter()
        val (eastM, northM) = filter.update(
            nowMs = 0L,
            rawFixEastM = 10.0,
            rawFixNorthM = 5.0,
            velocityEastMps = 0.0,
            velocityNorthMps = 0.0,
            confidenceWeight = 0.3f,
        )
        assertEquals(10.0, eastM, tolerance)
        assertEquals(5.0, northM, tolerance)
    }

    @Test
    fun `full confidence weight snaps to the raw fix, ignoring the imu prediction`() {
        val filter = GnssJitterFilter()
        filter.update(0L, rawFixEastM = 0.0, rawFixNorthM = 0.0, velocityEastMps = 0.0, velocityNorthMps = 0.0, confidenceWeight = 1f)
        // 1 second later, stationary (velocity 0), but the raw fix jumped to (100, 0) —
        // at full confidence the filter should track it exactly regardless of the "predicted" (0,0).
        val (eastM, northM) = filter.update(
            nowMs = 1000L,
            rawFixEastM = 100.0,
            rawFixNorthM = 0.0,
            velocityEastMps = 0.0,
            velocityNorthMps = 0.0,
            confidenceWeight = 1f,
        )
        assertEquals(100.0, eastM, tolerance)
        assertEquals(0.0, northM, tolerance)
    }

    @Test
    fun `zero confidence weight ignores the raw fix entirely, pure imu prediction`() {
        val filter = GnssJitterFilter()
        filter.update(0L, rawFixEastM = 0.0, rawFixNorthM = 0.0, velocityEastMps = 10.0, velocityNorthMps = 0.0, confidenceWeight = 1f)
        // 1 second later moving at 10 m/s east: predicted = (10, 0). A raw fix
        // claiming (500, 0) at confidenceWeight 0 must be completely ignored.
        val (eastM, northM) = filter.update(
            nowMs = 1000L,
            rawFixEastM = 500.0,
            rawFixNorthM = 0.0,
            velocityEastMps = 10.0,
            velocityNorthMps = 0.0,
            confidenceWeight = 0f,
        )
        assertEquals(10.0, eastM, tolerance)
        assertEquals(0.0, northM, tolerance)
    }

    @Test
    fun `partial confidence weight blends the imu prediction toward the raw fix`() {
        val filter = GnssJitterFilter()
        filter.update(0L, rawFixEastM = 0.0, rawFixNorthM = 0.0, velocityEastMps = 0.0, velocityNorthMps = 0.0, confidenceWeight = 1f)
        // Stationary (predicted stays at 0), raw fix jitters to 10m east,
        // weight 0.5 -> halfway between predicted (0) and raw (10) = 5.
        val (eastM, _) = filter.update(
            nowMs = 1000L,
            rawFixEastM = 10.0,
            rawFixNorthM = 0.0,
            velocityEastMps = 0.0,
            velocityNorthMps = 0.0,
            confidenceWeight = 0.5f,
        )
        assertEquals(5.0, eastM, tolerance)
    }

    @Test
    fun `reset clears state so the next update is trusted outright again`() {
        val filter = GnssJitterFilter()
        filter.update(0L, rawFixEastM = 0.0, rawFixNorthM = 0.0, velocityEastMps = 0.0, velocityNorthMps = 0.0, confidenceWeight = 1f)
        filter.reset()
        val (eastM, northM) = filter.update(
            nowMs = 1000L,
            rawFixEastM = 42.0,
            rawFixNorthM = -7.0,
            velocityEastMps = 999.0, // would blow up the prediction if reset() hadn't cleared lastUpdateMs
            velocityNorthMps = 999.0,
            confidenceWeight = 0.1f,
        )
        assertEquals(42.0, eastM, tolerance)
        assertEquals(-7.0, northM, tolerance)
    }

    @Test
    fun `confidenceWeight outside 0-1 is clamped`() {
        val filter = GnssJitterFilter()
        filter.update(0L, rawFixEastM = 0.0, rawFixNorthM = 0.0, velocityEastMps = 0.0, velocityNorthMps = 0.0, confidenceWeight = 1f)
        val (eastM, _) = filter.update(
            nowMs = 1000L,
            rawFixEastM = 10.0,
            rawFixNorthM = 0.0,
            velocityEastMps = 0.0,
            velocityNorthMps = 0.0,
            confidenceWeight = 5f, // out of range, must clamp to 1
        )
        assertEquals(10.0, eastM, tolerance)
    }
}
