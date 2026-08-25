package com.sih26168.idr.alignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentEstimatorTest {

    private val tolerance = 1e-3f
    private val oneSecondNs = 1_000_000_000L

    private fun newEstimator(minSamples: Int = 3) = AlignmentEstimator(minSamplesForAligned = minSamples)

    @Test
    fun `starts unaligned with no estimate`() {
        val estimate = newEstimator().evaluate(0L, azimuthRad = 0f, gnssBearingDeg = 0f, gnssSpeedMps = 10f)
        assertNull(estimate.yawOffsetRad) // first tick never counts — no prior azimuth for a yaw-rate gate
        assertFalse(estimate.isAligned)
    }

    @Test
    fun `matching azimuth and bearing converges to zero offset once enough samples accumulate`() {
        val estimator = newEstimator(minSamples = 3)
        var estimate = estimator.evaluate(0L, 0f, 0f, 10f)
        repeat(3) { i ->
            estimate = estimator.evaluate((i + 1) * oneSecondNs, 0f, 0f, 10f)
        }
        assertTrue(estimate.isAligned)
        assertEquals(3, estimate.sampleCount)
        assertEquals(0f, estimate.yawOffsetRad!!, tolerance)
    }

    @Test
    fun `consistent 10 degree offset is recovered`() {
        val estimator = newEstimator(minSamples = 3)
        val azimuthRad = Math.toRadians(10.0).toFloat()
        var estimate = estimator.evaluate(0L, azimuthRad, 0f, 10f)
        repeat(3) { i ->
            estimate = estimator.evaluate((i + 1) * oneSecondNs, azimuthRad, 0f, 10f)
        }
        assertTrue(estimate.isAligned)
        assertEquals(Math.toRadians(10.0).toFloat(), estimate.yawOffsetRad!!, tolerance)
    }

    @Test
    fun `below minimum speed does not count toward alignment`() {
        val estimator = newEstimator(minSamples = 3)
        var estimate = estimator.evaluate(0L, 0f, 0f, 2f) // below DEFAULT_MIN_SPEED_MPS = 5.0
        repeat(5) { i ->
            estimate = estimator.evaluate((i + 1) * oneSecondNs, 0f, 0f, 2f)
        }
        assertEquals(0, estimate.sampleCount)
        assertNull(estimate.yawOffsetRad)
        assertFalse(estimate.isAligned)
    }

    @Test
    fun `high yaw rate while turning does not count toward alignment`() {
        val estimator = newEstimator(minSamples = 3)
        var azimuth = 0f
        var estimate = estimator.evaluate(0L, azimuth, 0f, 10f)
        // 1 rad/s turn rate, far above DEFAULT_MAX_YAW_RATE_RADPS = 0.1.
        repeat(5) { i ->
            azimuth += 1f
            estimate = estimator.evaluate((i + 1) * oneSecondNs, azimuth, azimuth, 10f)
        }
        assertEquals(0, estimate.sampleCount)
        assertFalse(estimate.isAligned)
    }

    @Test
    fun `null gnss bearing does not count but still updates internal yaw-rate tracking`() {
        val estimator = newEstimator(minSamples = 1)
        // First call: no GNSS fix at all, but azimuth is recorded internally.
        estimator.evaluate(0L, 0f, null, null)
        // Second call: same azimuth (zero yaw rate relative to the first),
        // now with a valid GNSS fix — must count, proving the first call's
        // azimuth was retained despite having no GNSS data of its own.
        val estimate = estimator.evaluate(oneSecondNs, 0f, 0f, 10f)
        assertEquals(1, estimate.sampleCount)
        assertTrue(estimate.isAligned)
    }

    @Test
    fun `wraps correctly across the plus-minus 180 degree boundary`() {
        val estimator = newEstimator(minSamples = 3)
        val azimuthRad = Math.toRadians(179.0).toFloat()
        val bearingDeg = -179f
        // Raw diff = 179 - (-179) = 358 degrees, which must wrap to -2
        // degrees (the shortest path from -179 to 179 is backward/
        // wrapping by 2 degrees, not forward by 358) — verified by hand
        // with Python's math.atan2 before trusting this expectation.
        var estimate = estimator.evaluate(0L, azimuthRad, bearingDeg, 10f)
        repeat(3) { i ->
            estimate = estimator.evaluate((i + 1) * oneSecondNs, azimuthRad, bearingDeg, 10f)
        }
        assertTrue(estimate.isAligned)
        val expected = Math.toRadians(-2.0).toFloat()
        assertEquals(expected, estimate.yawOffsetRad!!, tolerance)
    }

    @Test
    fun `reset clears accumulated state`() {
        val estimator = newEstimator(minSamples = 2)
        estimator.evaluate(0L, 0f, 0f, 10f)
        estimator.evaluate(oneSecondNs, 0f, 0f, 10f)
        estimator.evaluate(2 * oneSecondNs, 0f, 0f, 10f)

        estimator.reset()
        val estimate = estimator.evaluate(3 * oneSecondNs, 0f, 0f, 10f)
        assertEquals(0, estimate.sampleCount) // first tick after reset never counts (no prior azimuth)
        assertNull(estimate.yawOffsetRad)
        assertFalse(estimate.isAligned)
    }
}
