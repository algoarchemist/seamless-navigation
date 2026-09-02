package com.sih26168.idr.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Named off the class's own defaults, not re-guessed — these tests exercise
// the real shipped configuration, same convention as StationaryDetectorTest.
private val PRIOR_SPEED_MPS = StopEventClassifier.DEFAULT_SUDDEN_STOP_PRIOR_SPEED_MPS
private val LOOKBACK_MS = StopEventClassifier.DEFAULT_SUDDEN_STOP_LOOKBACK_MS
private val NEAR_ZERO_CONFIRM_MS = StopEventClassifier.DEFAULT_NEAR_ZERO_CONFIRM_MS
private val LONG_IDLE_MS = StopEventClassifier.DEFAULT_LONG_IDLE_DURATION_MS
private const val ACCEL_DWELL_MS = 300L // com.sih26168.idr.dr.StationaryDetector.DEFAULT_MIN_STATIONARY_DWELL_MS

class StopEventClassifierTest {

    private fun newClassifier() = StopEventClassifier()

    @Test
    fun `starts as MOVING with no ZUPT`() {
        val result = newClassifier().evaluate(
            nowMs = 0L,
            linearAccelMagnitudeMps2 = 5.0f,
            gyroMagnitudeRadPerSec = 0f,
            currentSpeedEstimateMps = 10.0f,
        )
        assertEquals(StationaryContext.MOVING, result.context)
        assertFalse(result.shouldApplyZupt)
    }

    @Test
    fun `long-duration idle becomes LONG_IDLE after sustained accel-gyro quiet with no prior motion`() {
        val classifier = newClassifier()
        classifier.evaluate(0L, linearAccelMagnitudeMps2 = 0.01f, gyroMagnitudeRadPerSec = 0.01f, currentSpeedEstimateMps = 0f)
        val brief = classifier.evaluate(ACCEL_DWELL_MS, linearAccelMagnitudeMps2 = 0.01f, gyroMagnitudeRadPerSec = 0.01f, currentSpeedEstimateMps = 0f)
        assertEquals(StationaryContext.BRIEF_STOP, brief.context)

        val idle = classifier.evaluate(
            ACCEL_DWELL_MS + LONG_IDLE_MS,
            linearAccelMagnitudeMps2 = 0.01f,
            gyroMagnitudeRadPerSec = 0.01f,
            currentSpeedEstimateMps = 0f,
        )
        assertEquals(StationaryContext.LONG_IDLE, idle.context)
        assertTrue(idle.shouldApplyZupt)
    }

    @Test
    fun `a brief stop with no prior motion stays BRIEF_STOP under the long-idle bound`() {
        val classifier = newClassifier()
        classifier.evaluate(0L, linearAccelMagnitudeMps2 = 0.01f, gyroMagnitudeRadPerSec = 0.01f, currentSpeedEstimateMps = 0f)
        val result = classifier.evaluate(
            ACCEL_DWELL_MS + 500L,
            linearAccelMagnitudeMps2 = 0.01f,
            gyroMagnitudeRadPerSec = 0.01f,
            currentSpeedEstimateMps = 0f,
        )
        assertEquals(StationaryContext.BRIEF_STOP, result.context)
        assertTrue(result.shouldApplyZupt)
    }

    @Test
    fun `sudden stop after high-speed movement fires fast, before the accel-gyro dwell alone would`() {
        val classifier = newClassifier()
        // genuinely moving well above the sudden-stop prior-speed bound
        classifier.evaluate(0L, linearAccelMagnitudeMps2 = 1.0f, gyroMagnitudeRadPerSec = 0.02f, currentSpeedEstimateMps = 12.0f)
        // braking complete: speed at ~0, accel/gyro also settled, but the accel/gyro
        // dwell (300ms) has not elapsed since it started at this same instant (t=500)
        classifier.evaluate(500L, linearAccelMagnitudeMps2 = 0.01f, gyroMagnitudeRadPerSec = 0.01f, currentSpeedEstimateMps = 0.1f)
        val result = classifier.evaluate(
            500L + NEAR_ZERO_CONFIRM_MS,
            linearAccelMagnitudeMps2 = 0.01f,
            gyroMagnitudeRadPerSec = 0.01f,
            currentSpeedEstimateMps = 0.1f,
        )
        assertEquals(StationaryContext.SUDDEN_STOP, result.context)
        assertTrue(result.shouldApplyZupt)
        assertTrue(
            "must fire before StationaryDetector's own 300ms dwell would independently confirm",
            500L + NEAR_ZERO_CONFIRM_MS < 500L + ACCEL_DWELL_MS,
        )
    }

    @Test
    fun `stop after low-speed movement does not qualify for the sudden-stop fast path`() {
        val classifier = newClassifier()
        val slowCrawlMps = PRIOR_SPEED_MPS - 0.5f
        classifier.evaluate(0L, linearAccelMagnitudeMps2 = 0.05f, gyroMagnitudeRadPerSec = 0.01f, currentSpeedEstimateMps = slowCrawlMps)
        val tooEarly = classifier.evaluate(
            NEAR_ZERO_CONFIRM_MS,
            linearAccelMagnitudeMps2 = 0.01f,
            gyroMagnitudeRadPerSec = 0.01f,
            currentSpeedEstimateMps = 0.1f,
        )
        assertEquals(
            "no meaningful prior-speed evidence -- must not take the fast path",
            StationaryContext.MOVING,
            tooEarly.context,
        )
        val settled = classifier.evaluate(
            ACCEL_DWELL_MS,
            linearAccelMagnitudeMps2 = 0.01f,
            gyroMagnitudeRadPerSec = 0.01f,
            currentSpeedEstimateMps = 0.1f,
        )
        assertEquals(StationaryContext.BRIEF_STOP, settled.context)
    }

    @Test
    fun `a brief noisy near-zero speed glitch during real continued motion does not trigger a false stop`() {
        val classifier = newClassifier()
        classifier.evaluate(0L, linearAccelMagnitudeMps2 = 2.0f, gyroMagnitudeRadPerSec = 0.3f, currentSpeedEstimateMps = 10.0f)
        // one glitchy tick: the speed ESTIMATE misreads near-zero, but accel/gyro make
        // clear the vehicle is still actively moving -- a real stop would not show this
        val glitch = classifier.evaluate(100L, linearAccelMagnitudeMps2 = 2.0f, gyroMagnitudeRadPerSec = 0.3f, currentSpeedEstimateMps = 0.1f)
        assertEquals(StationaryContext.MOVING, glitch.context)
        assertFalse(
            "a single noisy near-zero speed sample must not ZUPT a vehicle whose accel/gyro clearly still show real motion",
            glitch.shouldApplyZupt,
        )
        val recovered = classifier.evaluate(200L, linearAccelMagnitudeMps2 = 2.0f, gyroMagnitudeRadPerSec = 0.3f, currentSpeedEstimateMps = 9.5f)
        assertEquals(StationaryContext.MOVING, recovered.context)
    }

    @Test
    fun `resuming real motion from LONG_IDLE returns to MOVING and resets stationary duration`() {
        val classifier = newClassifier()
        classifier.evaluate(0L, linearAccelMagnitudeMps2 = 0.01f, gyroMagnitudeRadPerSec = 0.01f, currentSpeedEstimateMps = 0f)
        classifier.evaluate(ACCEL_DWELL_MS, linearAccelMagnitudeMps2 = 0.01f, gyroMagnitudeRadPerSec = 0.01f, currentSpeedEstimateMps = 0f)
        val idle = classifier.evaluate(
            ACCEL_DWELL_MS + LONG_IDLE_MS,
            linearAccelMagnitudeMps2 = 0.01f,
            gyroMagnitudeRadPerSec = 0.01f,
            currentSpeedEstimateMps = 0f,
        )
        assertEquals(StationaryContext.LONG_IDLE, idle.context)

        val resumed = classifier.evaluate(
            ACCEL_DWELL_MS + LONG_IDLE_MS + 200L,
            linearAccelMagnitudeMps2 = 3.0f,
            gyroMagnitudeRadPerSec = 0.4f,
            currentSpeedEstimateMps = 6.0f,
        )
        assertEquals(StationaryContext.MOVING, resumed.context)
        assertFalse(resumed.shouldApplyZupt)
        assertEquals(0L, resumed.stationaryDurationMs)
    }

    @Test
    fun `GNSS speed is preferred over the caller's own speed estimate when supplied`() {
        val classifier = newClassifier()
        // the DR/ML speed estimate reads near-zero (would suggest a stop), but GNSS --
        // the more trustworthy signal here -- says the vehicle is still moving for real
        classifier.evaluate(
            0L,
            linearAccelMagnitudeMps2 = 0.01f,
            gyroMagnitudeRadPerSec = 0.01f,
            currentSpeedEstimateMps = 0.1f,
            gnssSpeedMps = 8.0f,
        )
        val result = classifier.evaluate(
            NEAR_ZERO_CONFIRM_MS,
            linearAccelMagnitudeMps2 = 0.01f,
            gyroMagnitudeRadPerSec = 0.01f,
            currentSpeedEstimateMps = 0.1f,
            gnssSpeedMps = 7.5f,
        )
        assertEquals(
            "GNSS speed says still moving -- must not treat this as stationary just because the own estimate does",
            StationaryContext.MOVING,
            result.context,
        )
    }

    @Test
    fun `falls back to the caller's own speed estimate when GNSS speed is not supplied (mid-outage)`() {
        val classifier = newClassifier()
        classifier.evaluate(0L, linearAccelMagnitudeMps2 = 1.0f, gyroMagnitudeRadPerSec = 0.1f, currentSpeedEstimateMps = 10.0f, gnssSpeedMps = null)
        classifier.evaluate(500L, linearAccelMagnitudeMps2 = 0.01f, gyroMagnitudeRadPerSec = 0.01f, currentSpeedEstimateMps = 0.0f, gnssSpeedMps = null)
        val result = classifier.evaluate(
            500L + NEAR_ZERO_CONFIRM_MS,
            linearAccelMagnitudeMps2 = 0.01f,
            gyroMagnitudeRadPerSec = 0.01f,
            currentSpeedEstimateMps = 0.0f,
            gnssSpeedMps = null,
        )
        assertEquals(StationaryContext.SUDDEN_STOP, result.context)
    }

    @Test
    fun `SUDDEN_STOP decays to BRIEF_STOP once the prior-speed evidence ages out of the lookback window`() {
        val classifier = newClassifier()
        classifier.evaluate(0L, linearAccelMagnitudeMps2 = 1.0f, gyroMagnitudeRadPerSec = 0.02f, currentSpeedEstimateMps = 10.0f)
        classifier.evaluate(500L, linearAccelMagnitudeMps2 = 0.01f, gyroMagnitudeRadPerSec = 0.01f, currentSpeedEstimateMps = 0.0f)
        val sudden = classifier.evaluate(
            500L + NEAR_ZERO_CONFIRM_MS,
            linearAccelMagnitudeMps2 = 0.01f,
            gyroMagnitudeRadPerSec = 0.01f,
            currentSpeedEstimateMps = 0.0f,
        )
        assertEquals(StationaryContext.SUDDEN_STOP, sudden.context)

        // well past the lookback window measured from the last meaningful-speed sample (t=0)
        val decayed = classifier.evaluate(
            LOOKBACK_MS + 500L,
            linearAccelMagnitudeMps2 = 0.01f,
            gyroMagnitudeRadPerSec = 0.01f,
            currentSpeedEstimateMps = 0.0f,
        )
        assertEquals(StationaryContext.BRIEF_STOP, decayed.context)
        assertTrue(
            "still stationary, just reclassified once the fast-path evidence expired",
            decayed.shouldApplyZupt,
        )
    }

    @Test
    fun `reset forgets prior speed history so a stale high-speed sample can no longer trigger a sudden stop`() {
        val classifier = newClassifier()
        classifier.evaluate(0L, linearAccelMagnitudeMps2 = 1.0f, gyroMagnitudeRadPerSec = 0.1f, currentSpeedEstimateMps = 20.0f)

        classifier.reset()

        classifier.evaluate(0L, linearAccelMagnitudeMps2 = 0.01f, gyroMagnitudeRadPerSec = 0.01f, currentSpeedEstimateMps = 0.0f)
        val result = classifier.evaluate(
            NEAR_ZERO_CONFIRM_MS,
            linearAccelMagnitudeMps2 = 0.01f,
            gyroMagnitudeRadPerSec = 0.01f,
            currentSpeedEstimateMps = 0.0f,
        )
        assertEquals(
            "the pre-reset 20 m/s sample must not still count as recent prior-speed evidence",
            StationaryContext.MOVING,
            result.context,
        )
    }
}
