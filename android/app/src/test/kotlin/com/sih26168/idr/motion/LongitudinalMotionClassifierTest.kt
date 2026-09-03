package com.sih26168.idr.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ENTER_DWELL_MS = LongitudinalMotionClassifier.DEFAULT_ENTER_DWELL_MS // 200L
private const val EXIT_DWELL_MS = LongitudinalMotionClassifier.DEFAULT_EXIT_DWELL_MS // 300L

class LongitudinalMotionClassifierTest {

    private fun newClassifier() = LongitudinalMotionClassifier(enterAccelMps2 = 1.0f)

    @Test
    fun `a single noisy tick above threshold does not trigger Accelerating -- must sustain for the enter dwell`() {
        // REAL BUG this test guards against (2026-09-03, on-device shake
        // test): the pre-hysteresis version flipped Accelerating true on
        // this ONE sample alone -- exactly what let ordinary phone
        // vibration flicker the display label every tick.
        val classifier = newClassifier()
        val result = classifier.classify(nowMs = 0L, accelForwardMps2 = 2.0f)
        assertFalse(result.isAccelerating)
        assertFalse(result.isBraking)
    }

    @Test
    fun `strong positive forward acceleration sustained across the enter dwell is accelerating, not braking`() {
        val classifier = newClassifier()
        classifier.classify(nowMs = 0L, accelForwardMps2 = 2.0f)
        val result = classifier.classify(nowMs = ENTER_DWELL_MS, accelForwardMps2 = 2.0f)
        assertTrue(result.isAccelerating)
        assertFalse(result.isBraking)
    }

    @Test
    fun `strong negative forward acceleration sustained across the enter dwell is braking, not accelerating`() {
        val classifier = newClassifier()
        classifier.classify(nowMs = 0L, accelForwardMps2 = -2.0f)
        val result = classifier.classify(nowMs = ENTER_DWELL_MS, accelForwardMps2 = -2.0f)
        assertFalse(result.isAccelerating)
        assertTrue(result.isBraking)
    }

    @Test
    fun `small forward acceleration within threshold is neither`() {
        val classifier = newClassifier()
        val result = classifier.classify(nowMs = 0L, accelForwardMps2 = 0.3f)
        assertFalse(result.isAccelerating)
        assertFalse(result.isBraking)
    }

    @Test
    fun `zero forward acceleration is neither`() {
        val classifier = newClassifier()
        val result = classifier.classify(nowMs = 0L, accelForwardMps2 = 0.0f)
        assertFalse(result.isAccelerating)
        assertFalse(result.isBraking)
    }

    @Test
    fun `exactly at the positive threshold sustained across the enter dwell counts as accelerating`() {
        val classifier = newClassifier()
        classifier.classify(nowMs = 0L, accelForwardMps2 = 1.0f)
        val result = classifier.classify(nowMs = ENTER_DWELL_MS, accelForwardMps2 = 1.0f)
        assertTrue(result.isAccelerating)
    }

    @Test
    fun `exactly at the negative threshold sustained across the enter dwell counts as braking`() {
        val classifier = newClassifier()
        classifier.classify(nowMs = 0L, accelForwardMps2 = -1.0f)
        val result = classifier.classify(nowMs = ENTER_DWELL_MS, accelForwardMps2 = -1.0f)
        assertTrue(result.isBraking)
    }

    @Test
    fun `a brief dip into the enter-exit dead zone does not end confirmed Accelerating`() {
        val classifier = newClassifier()
        classifier.classify(nowMs = 0L, accelForwardMps2 = 2.0f)
        assertTrue(classifier.classify(nowMs = ENTER_DWELL_MS, accelForwardMps2 = 2.0f).isAccelerating)

        // drops to 0.7 m/s^2 -- BELOW the 1.0 enter threshold, but still
        // ABOVE the 0.5 exit threshold: must stay Accelerating.
        val result = classifier.classify(nowMs = ENTER_DWELL_MS + 50L, accelForwardMps2 = 0.7f)
        assertTrue(
            "forward accel is between exit and enter thresholds -- must not exit yet",
            result.isAccelerating,
        )
    }

    @Test
    fun `Accelerating ends only after forward accel sustains inside the exit band for the exit dwell`() {
        val classifier = newClassifier()
        classifier.classify(nowMs = 0L, accelForwardMps2 = 2.0f)
        assertTrue(classifier.classify(nowMs = ENTER_DWELL_MS, accelForwardMps2 = 2.0f).isAccelerating)

        // forward accel drops to 0.0 (well inside the +-0.5 exit band)
        // starting now -- exit streak just started, dwell can't have
        // elapsed yet.
        val tooEarly = classifier.classify(nowMs = ENTER_DWELL_MS + 50L, accelForwardMps2 = 0.0f)
        assertTrue("exit streak just started -- must not have ended yet", tooEarly.isAccelerating)

        val endedAccelerating = classifier.classify(
            nowMs = ENTER_DWELL_MS + 50L + EXIT_DWELL_MS,
            accelForwardMps2 = 0.0f,
        )
        assertFalse(endedAccelerating.isAccelerating)
        assertFalse(endedAccelerating.isBraking)
    }

    @Test
    fun `reset forgets confirmed state and dwell history`() {
        val classifier = newClassifier()
        classifier.classify(nowMs = 0L, accelForwardMps2 = 2.0f)
        assertTrue(classifier.classify(nowMs = ENTER_DWELL_MS, accelForwardMps2 = 2.0f).isAccelerating)

        classifier.reset()

        // same strong forward accel, but only ONE tick after reset -- must
        // NOT still read as Accelerating just because it was before reset().
        val result = classifier.classify(nowMs = 0L, accelForwardMps2 = 2.0f)
        assertFalse(result.isAccelerating)
    }
}
