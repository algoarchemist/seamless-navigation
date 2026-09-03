package com.sih26168.idr.motion

import kotlin.math.PI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EXIT_DWELL_MS = TurningDetector.DEFAULT_EXIT_DWELL_MS // 300L
private val ENTER_RATE = TurningDetector.DEFAULT_ENTER_YAW_RATE_RADPS // 0.15f

class TurningDetectorTest {

    @Test
    fun `first sample is never turning, no previous azimuth to diff against`() {
        val detector = TurningDetector()
        assertFalse(detector.evaluate(nowNs = 1_000_000_000L, azimuthRad = 0f))
    }

    @Test
    fun `slow heading drift below threshold is not turning`() {
        val detector = TurningDetector(enterYawRateRadPerSec = ENTER_RATE)
        detector.evaluate(nowNs = 0L, azimuthRad = 0f)
        // 0.05 rad over 1 second = 0.05 rad/s, well under the 0.15 threshold.
        val turning = detector.evaluate(nowNs = 1_000_000_000L, azimuthRad = 0.05f)
        assertFalse(turning)
    }

    @Test
    fun `a single noisy tick above threshold does not trigger Turning -- must sustain for the enter dwell`() {
        // REAL BUG this test guards against (2026-09-03, on-device shake
        // test): the pre-hysteresis version flipped Turning true on this
        // ONE sample alone -- exactly what let ordinary phone vibration
        // flicker the display label every tick.
        val detector = TurningDetector()
        detector.evaluate(nowNs = 0L, azimuthRad = 0f)
        // 0.5 rad/s, well over threshold, but only ONE tick -- the enter
        // dwell (200ms) can't have elapsed from a streak that just started
        // this same tick.
        val turning = detector.evaluate(nowNs = 100_000_000L, azimuthRad = 0.05f)
        assertFalse(turning)
    }

    @Test
    fun `fast heading change sustained across the enter dwell is turning`() {
        val detector = TurningDetector()
        // 0.05 rad every 100ms = 0.5 rad/s throughout, well over the 0.15
        // rad/s enter threshold -- sustained across 3 ticks (200ms streak).
        detector.evaluate(nowNs = 0L, azimuthRad = 0f)
        val tooEarly = detector.evaluate(nowNs = 100_000_000L, azimuthRad = 0.05f)
        assertFalse(tooEarly)
        val stillTooEarly = detector.evaluate(nowNs = 200_000_000L, azimuthRad = 0.10f)
        assertFalse(stillTooEarly)
        val turning = detector.evaluate(nowNs = 300_000_000L, azimuthRad = 0.15f)
        assertTrue(turning)
    }

    @Test
    fun `turning in the negative direction is still detected`() {
        val detector = TurningDetector()
        detector.evaluate(nowNs = 0L, azimuthRad = 0f)
        detector.evaluate(nowNs = 100_000_000L, azimuthRad = -0.05f)
        detector.evaluate(nowNs = 200_000_000L, azimuthRad = -0.10f)
        val turning = detector.evaluate(nowNs = 300_000_000L, azimuthRad = -0.15f)
        assertTrue(turning)
    }

    @Test
    fun `wraparound near plus-minus pi is not misread as a huge turn`() {
        val detector = TurningDetector()
        val almostPi = (PI - 0.05).toFloat()
        val justPastNegativePi = (-PI + 0.05).toFloat()
        detector.evaluate(nowNs = 0L, azimuthRad = almostPi)
        // True angular change here is 0.1 rad (wrapping the short way around),
        // not the ~6.18 rad a naive subtraction would compute -- 0.1 rad/s
        // never even crosses the enter threshold, so dwell is irrelevant.
        val turning = detector.evaluate(nowNs = 1_000_000_000L, azimuthRad = justPastNegativePi)
        assertFalse(turning)
    }

    @Test
    fun `zero elapsed time is not turning, same guard as YawRate itself`() {
        val detector = TurningDetector()
        detector.evaluate(nowNs = 1_000_000_000L, azimuthRad = 0f)
        val turning = detector.evaluate(nowNs = 1_000_000_000L, azimuthRad = 1f)
        assertFalse(turning)
    }

    @Test
    fun `a brief dip into the enter-exit dead zone does not end a confirmed turn`() {
        val detector = TurningDetector()
        // establish a confirmed turn first (same pattern as the sustained test above)
        detector.evaluate(nowNs = 0L, azimuthRad = 0f)
        detector.evaluate(nowNs = 100_000_000L, azimuthRad = 0.05f)
        detector.evaluate(nowNs = 200_000_000L, azimuthRad = 0.10f)
        assertTrue(detector.evaluate(nowNs = 300_000_000L, azimuthRad = 0.15f))

        // yaw rate drops to 0.1 rad/s -- BELOW the 0.15 enter threshold, but
        // still ABOVE the 0.08 exit threshold: must stay Turning (the dead
        // zone between enter/exit is exactly what absorbs this kind of dip).
        val stillTurning = detector.evaluate(nowNs = 400_000_000L, azimuthRad = 0.16f)
        assertTrue(
            "yaw rate is between exit and enter thresholds -- must not exit yet",
            stillTurning,
        )
    }

    @Test
    fun `turning ends only after yaw rate sustains at or below the exit threshold for the exit dwell`() {
        val detector = TurningDetector()
        detector.evaluate(nowNs = 0L, azimuthRad = 0f)
        detector.evaluate(nowNs = 100_000_000L, azimuthRad = 0.05f)
        detector.evaluate(nowNs = 200_000_000L, azimuthRad = 0.10f)
        assertTrue(detector.evaluate(nowNs = 300_000_000L, azimuthRad = 0.15f))

        // yaw rate drops to 0.0 rad/s (well below the 0.08 exit threshold)
        // starting at t=400ms -- this is when the exit streak STARTS, so
        // dwell (300ms) can't have elapsed yet; must still hold Turning.
        val tooEarly = detector.evaluate(nowNs = 400_000_000L, azimuthRad = 0.15f)
        assertTrue("exit streak just started -- must not have ended yet", tooEarly)

        // exactly EXIT_DWELL_MS after the exit streak started (t=400ms + 300ms = 700ms).
        val endedTurn = detector.evaluate(nowNs = (400 + EXIT_DWELL_MS) * 1_000_000L, azimuthRad = 0.15f)
        assertFalse(endedTurn)
    }

    @Test
    fun `reset forgets confirmed-turning state and dwell history`() {
        val detector = TurningDetector()
        detector.evaluate(nowNs = 0L, azimuthRad = 0f)
        detector.evaluate(nowNs = 100_000_000L, azimuthRad = 0.05f)
        detector.evaluate(nowNs = 200_000_000L, azimuthRad = 0.10f)
        assertTrue(detector.evaluate(nowNs = 300_000_000L, azimuthRad = 0.15f))

        detector.reset()

        // same fast heading change, but only ONE tick after reset -- must
        // NOT still read as Turning just because it was before reset().
        detector.evaluate(nowNs = 0L, azimuthRad = 0f)
        val turning = detector.evaluate(nowNs = 100_000_000L, azimuthRad = 0.05f)
        assertFalse(turning)
    }
}
