package com.sih26168.idr.motion

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DWELL_MS = 2_000L
private const val THRESHOLD_M = 2.5f
private const val BASELINE_HPA = 1013.25f

/** Inverse of the production barometric formula, used only to construct realistic test pressure inputs for a target altitude. */
private fun pressureForAltitude(baselineHpa: Float, altitudeM: Float): Float =
    (baselineHpa * (1.0 - altitudeM / 44330.0).pow(5.255)).toFloat()

class FloorChangeDetectorTest {

    private fun newDetector() = FloorChangeDetector(floorHeightThresholdM = THRESHOLD_M, minDwellMs = DWELL_MS)

    @Test
    fun `relativeAltitudeMeters is zero when pressure equals baseline`() {
        assertEquals(0f, FloorChangeDetector.relativeAltitudeMeters(BASELINE_HPA, BASELINE_HPA), 0.0001f)
    }

    @Test
    fun `relativeAltitudeMeters matches the hand-derived barometric formula result`() {
        // A pressure ratio of exactly 0.5 corresponds to ~5478m of altitude
        // gain per the standard barometric formula — hand-derived via its
        // Taylor expansion (ln(0.5)/5.255 = -0.1319025, e^-0.1319025 ~=
        // 0.876427, h = 44330*(1-0.876427) ~= 5478.0), not just "close to
        // whatever the code happens to produce."
        val altitude = FloorChangeDetector.relativeAltitudeMeters(1000f, 500f)
        assertEquals(5478f, altitude, 2f)
    }

    @Test
    fun `relativeAltitudeMeters is negative when pressure increases (went down)`() {
        val altitude = FloorChangeDetector.relativeAltitudeMeters(1000f, 1010f)
        assertTrue("pressure increased, so relative altitude must be negative", altitude < 0f)
    }

    @Test
    fun `first-ever reading establishes the baseline with zero relative altitude`() {
        val detector = newDetector()
        val result = detector.evaluate(0L, BASELINE_HPA)
        assertEquals(0f, result.relativeAltitudeM, 0.0001f)
        assertFalse(result.floorChangeDetected)
    }

    @Test
    fun `a sustained crossing above the threshold for the full dwell signals an upward floor change`() {
        val detector = newDetector()
        detector.evaluate(0L, BASELINE_HPA)
        val risenPressure = pressureForAltitude(BASELINE_HPA, 3.0f) // above the 2.5m threshold
        detector.evaluate(0L, risenPressure)
        val result = detector.evaluate(DWELL_MS, risenPressure)
        assertTrue(result.floorChangeDetected)
        assertEquals(1, result.floorDelta)
    }

    @Test
    fun `a sustained crossing below the threshold for the full dwell signals a downward floor change`() {
        val detector = newDetector()
        detector.evaluate(0L, BASELINE_HPA)
        val descendedPressure = pressureForAltitude(BASELINE_HPA, -3.0f) // below the -2.5m threshold
        detector.evaluate(0L, descendedPressure)
        val result = detector.evaluate(DWELL_MS, descendedPressure)
        assertTrue(result.floorChangeDetected)
        assertEquals(-1, result.floorDelta)
    }

    @Test
    fun `a crossing that does not last the full dwell does not signal a floor change`() {
        val detector = newDetector()
        detector.evaluate(0L, BASELINE_HPA)
        val risenPressure = pressureForAltitude(BASELINE_HPA, 3.0f)
        detector.evaluate(0L, risenPressure)
        val result = detector.evaluate(DWELL_MS - 1, risenPressure)
        assertFalse(result.floorChangeDetected)
    }

    @Test
    fun `a brief blip back below threshold mid-streak resets the dwell clock`() {
        val detector = newDetector()
        detector.evaluate(0L, BASELINE_HPA)
        val risenPressure = pressureForAltitude(BASELINE_HPA, 3.0f)
        detector.evaluate(0L, risenPressure)
        detector.evaluate(DWELL_MS / 2, BASELINE_HPA) // blip back to baseline mid-streak
        val result = detector.evaluate(DWELL_MS, risenPressure)
        assertFalse(
            "the blip interrupted the streak, so the dwell clock must have restarted",
            result.floorChangeDetected,
        )
    }

    @Test
    fun `staying within the threshold band never signals a floor change`() {
        val detector = newDetector()
        detector.evaluate(0L, BASELINE_HPA)
        val smallRise = pressureForAltitude(BASELINE_HPA, 1.0f) // below the 2.5m threshold
        detector.evaluate(0L, smallRise)
        val result = detector.evaluate(DWELL_MS * 10, smallRise)
        assertFalse(result.floorChangeDetected)
    }

    @Test
    fun `after a confirmed floor change, the baseline re-anchors so a second floor change can also be detected`() {
        val detector = newDetector()
        detector.evaluate(0L, BASELINE_HPA)
        val firstRise = pressureForAltitude(BASELINE_HPA, 3.0f)
        detector.evaluate(0L, firstRise)
        val firstChange = detector.evaluate(DWELL_MS, firstRise)
        assertTrue(firstChange.floorChangeDetected)

        // Rise ANOTHER 3m relative to the NEW baseline (firstRise).
        val secondRise = pressureForAltitude(firstRise, 3.0f)
        detector.evaluate(DWELL_MS, secondRise)
        val secondChange = detector.evaluate(DWELL_MS * 2, secondRise)
        assertTrue(
            "a second floor change must also be detectable after re-anchoring",
            secondChange.floorChangeDetected,
        )
        assertEquals(1, secondChange.floorDelta)
    }

    @Test
    fun `reset clears the baseline so the next reading re-establishes it`() {
        val detector = newDetector()
        detector.evaluate(0L, BASELINE_HPA)
        detector.reset()
        val result = detector.evaluate(0L, pressureForAltitude(BASELINE_HPA, 10f))
        // Post-reset, this reading becomes the NEW baseline, not compared
        // against the pre-reset one.
        assertEquals(0f, result.relativeAltitudeM, 0.0001f)
    }
}
