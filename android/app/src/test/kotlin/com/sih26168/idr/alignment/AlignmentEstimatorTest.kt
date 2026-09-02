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

    // --- Pitch/roll mounting baseline (PRD.md Section 15's motorcycle-lean carve-out) ---

    private fun newPitchRollEstimator(minSamples: Int = 3) =
        AlignmentEstimator(minSamplesForPitchRollAligned = minSamples)

    @Test
    fun `roll baseline does not accumulate while moving above the stationary threshold`() {
        val estimator = newPitchRollEstimator(minSamples = 3)
        val tiltRad = Math.toRadians(15.0).toFloat()
        var estimate = estimator.evaluate(0L, 0f, null, 10f, pitchRad = 0f, rollRad = tiltRad) // 10 m/s, not stationary
        repeat(5) { i ->
            estimate = estimator.evaluate((i + 1) * oneSecondNs, 0f, null, 10f, pitchRad = 0f, rollRad = tiltRad)
        }
        assertEquals(0, estimate.pitchRollSampleCount)
        assertNull(estimate.rollOffsetRad)
        assertFalse(estimate.isPitchRollAligned)
    }

    @Test
    fun `roll baseline accumulates while near-stationary with a GNSS fix and converges to the mounting tilt`() {
        val estimator = newPitchRollEstimator(minSamples = 3)
        val tiltRad = Math.toRadians(15.0).toFloat()
        var estimate = estimator.evaluate(0L, 0f, null, 0.2f, pitchRad = 0f, rollRad = tiltRad) // 0.2 m/s, below DEFAULT_MAX_SPEED_FOR_STATIONARY_MPS
        repeat(3) { i ->
            estimate = estimator.evaluate((i + 1) * oneSecondNs, 0f, null, 0.2f, pitchRad = 0f, rollRad = tiltRad)
        }
        assertTrue(estimate.isPitchRollAligned)
        assertEquals(4, estimate.pitchRollSampleCount)
        assertEquals(tiltRad, estimate.rollOffsetRad!!, tolerance)
        assertEquals(0f, estimate.pitchOffsetRad!!, tolerance)
    }

    @Test
    fun `roll excursion beyond the established baseline flags reduced confidence`() {
        val estimator = newPitchRollEstimator(minSamples = 3)
        // Establish a ~0 rad mounting baseline while parked.
        repeat(4) { i ->
            estimator.evaluate(i * oneSecondNs, 0f, null, 0.0f, pitchRad = 0f, rollRad = 0f)
        }
        // Now moving, with a large sudden roll (e.g. a lean or a slipped mount) — well
        // above DEFAULT_MAX_ROLL_EXCURSION_RAD (~20 degrees) from the 0 rad baseline.
        val leanRollRad = Math.toRadians(35.0).toFloat()
        val estimate = estimator.evaluate(5 * oneSecondNs, 0f, null, 10f, pitchRad = 0f, rollRad = leanRollRad)
        assertTrue(estimate.reducedConfidenceDueToRoll)
    }

    @Test
    fun `roll within the established baseline's tolerance does not flag reduced confidence`() {
        val estimator = newPitchRollEstimator(minSamples = 3)
        repeat(4) { i ->
            estimator.evaluate(i * oneSecondNs, 0f, null, 0.0f, pitchRad = 0f, rollRad = 0f)
        }
        val smallRollRad = Math.toRadians(5.0).toFloat() // well below the ~20 degree threshold
        val estimate = estimator.evaluate(5 * oneSecondNs, 0f, null, 10f, pitchRad = 0f, rollRad = smallRollRad)
        assertFalse(estimate.reducedConfidenceDueToRoll)
    }

    @Test
    fun `reset also clears the pitch-roll baseline`() {
        val estimator = newPitchRollEstimator(minSamples = 2)
        estimator.evaluate(0L, 0f, null, 0.0f, pitchRad = 0f, rollRad = Math.toRadians(15.0).toFloat())
        estimator.evaluate(oneSecondNs, 0f, null, 0.0f, pitchRad = 0f, rollRad = Math.toRadians(15.0).toFloat())

        estimator.reset()
        val estimate = estimator.evaluate(2 * oneSecondNs, 0f, null, 10f, pitchRad = 0f, rollRad = Math.toRadians(35.0).toFloat())
        assertEquals(0, estimate.pitchRollSampleCount)
        assertNull(estimate.rollOffsetRad)
        assertFalse(estimate.isPitchRollAligned)
        assertFalse(estimate.reducedConfidenceDueToRoll) // no baseline yet, so nothing to compare against
    }
}
