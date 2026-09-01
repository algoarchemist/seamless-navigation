package com.sih26168.idr.motion

import kotlin.math.PI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMovedDetectorTest {

    @Test
    fun `first sample only establishes the reference, never reports moved`() {
        val detector = PhoneMovedDetector()
        assertFalse(detector.evaluate(nowMs = 0L, pitchRad = 0.1f, rollRad = 0.2f))
    }

    @Test
    fun `small deviation within threshold is not moved`() {
        val detector = PhoneMovedDetector(pitchRollChangeThresholdRad = 0.26f, minSustainedDeviationMs = 1000L)
        detector.evaluate(nowMs = 0L, pitchRad = 0.0f, rollRad = 0.0f)
        val moved = detector.evaluate(nowMs = 2000L, pitchRad = 0.1f, rollRad = 0.1f)
        assertFalse(moved)
    }

    @Test
    fun `large deviation not yet sustained long enough is not moved`() {
        val detector = PhoneMovedDetector(pitchRollChangeThresholdRad = 0.26f, minSustainedDeviationMs = 1000L)
        detector.evaluate(nowMs = 0L, pitchRad = 0.0f, rollRad = 0.0f)
        detector.evaluate(nowMs = 100L, pitchRad = 0.6f, rollRad = 0.0f) // deviation starts
        val moved = detector.evaluate(nowMs = 500L, pitchRad = 0.6f, rollRad = 0.0f) // only 400ms sustained
        assertFalse(moved)
    }

    @Test
    fun `large deviation sustained past the dwell threshold is moved`() {
        val detector = PhoneMovedDetector(pitchRollChangeThresholdRad = 0.26f, minSustainedDeviationMs = 1000L)
        detector.evaluate(nowMs = 0L, pitchRad = 0.0f, rollRad = 0.0f)
        detector.evaluate(nowMs = 100L, pitchRad = 0.6f, rollRad = 0.0f) // deviation starts
        val moved = detector.evaluate(nowMs = 1200L, pitchRad = 0.6f, rollRad = 0.0f) // 1100ms sustained
        assertTrue(moved)
    }

    @Test
    fun `after firing, the reference updates so it does not immediately refire`() {
        val detector = PhoneMovedDetector(pitchRollChangeThresholdRad = 0.26f, minSustainedDeviationMs = 1000L)
        detector.evaluate(nowMs = 0L, pitchRad = 0.0f, rollRad = 0.0f)
        detector.evaluate(nowMs = 100L, pitchRad = 0.6f, rollRad = 0.0f)
        assertTrue(detector.evaluate(nowMs = 1200L, pitchRad = 0.6f, rollRad = 0.0f))
        // Same orientation as just adopted as the new reference — no new deviation.
        val movedAgain = detector.evaluate(nowMs = 1300L, pitchRad = 0.6f, rollRad = 0.0f)
        assertFalse(movedAgain)
    }

    @Test
    fun `a deviation that clears before the dwell elapses resets the streak`() {
        val detector = PhoneMovedDetector(pitchRollChangeThresholdRad = 0.26f, minSustainedDeviationMs = 1000L)
        detector.evaluate(nowMs = 0L, pitchRad = 0.0f, rollRad = 0.0f)
        detector.evaluate(nowMs = 100L, pitchRad = 0.6f, rollRad = 0.0f) // brief jolt
        detector.evaluate(nowMs = 200L, pitchRad = 0.0f, rollRad = 0.0f) // back to normal
        // Even though 1100ms has now passed since the jolt started, it
        // cleared at 200ms, so the streak should have reset, not carried over.
        val moved = detector.evaluate(nowMs = 1300L, pitchRad = 0.05f, rollRad = 0.0f)
        assertFalse(moved)
    }

    @Test
    fun `roll deviation near the plus-minus pi wrap boundary is not a false positive`() {
        val detector = PhoneMovedDetector(pitchRollChangeThresholdRad = 0.26f, minSustainedDeviationMs = 1000L)
        val almostPi = (PI - 0.05).toFloat()
        val justPastNegativePi = (-PI + 0.05).toFloat()
        detector.evaluate(nowMs = 0L, pitchRad = 0f, rollRad = almostPi)
        // True roll change here is 0.1 rad (wrapping the short way), not
        // the ~6.18 rad a naive subtraction would compute.
        val moved = detector.evaluate(nowMs = 1200L, pitchRad = 0f, rollRad = justPastNegativePi)
        assertFalse(moved)
    }

    @Test
    fun `reset discards the reference so the next sample establishes a new baseline`() {
        val detector = PhoneMovedDetector(pitchRollChangeThresholdRad = 0.26f, minSustainedDeviationMs = 1000L)
        detector.evaluate(nowMs = 0L, pitchRad = 0.0f, rollRad = 0.0f)
        detector.reset()
        // A big jump right after reset is just the new baseline, not a move.
        val moved = detector.evaluate(nowMs = 100L, pitchRad = 1.0f, rollRad = 1.0f)
        assertFalse(moved)
    }
}
