package com.sih26168.idr.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val OUTAGE_ENTER_DWELL_MS = 1_000L
private const val REACQUISITION_ENTER_DWELL_MS = 1_000L
private const val TRANSITION_DWELL_MS = 500L
private const val REACQUISITION_DWELL_MS = 500L

class GnssOutageDetectorTest {

    private fun newDetector() = GnssOutageDetector(
        outageEnterDwellMs = OUTAGE_ENTER_DWELL_MS,
        reacquisitionEnterDwellMs = REACQUISITION_ENTER_DWELL_MS,
        transitionDwellMs = TRANSITION_DWELL_MS,
        reacquisitionDwellMs = REACQUISITION_DWELL_MS,
    )

    /** Drives a fresh detector from GNSS_AIDED all the way to DEAD_RECKONING, returning it plus the "now" cursor. */
    private fun driveToDeadReckoning(): Pair<GnssOutageDetector, Long> {
        val detector = newDetector()
        detector.evaluate(0L, gnssGoodNow = false)
        detector.evaluate(OUTAGE_ENTER_DWELL_MS, gnssGoodNow = false) // -> TRANSITION
        val nowAtDeadReckoning = OUTAGE_ENTER_DWELL_MS + TRANSITION_DWELL_MS
        detector.evaluate(nowAtDeadReckoning, gnssGoodNow = false) // -> DEAD_RECKONING
        return detector to nowAtDeadReckoning
    }

    @Test
    fun `starts in GNSS_AIDED with no transitions`() {
        val detector = newDetector()
        assertEquals(GnssMode.GNSS_AIDED, detector.mode)
        assertTrue(detector.transitions.isEmpty())
    }

    @Test
    fun `a brief bad blip that recovers before the dwell time does not flip the mode`() {
        val detector = newDetector()
        detector.evaluate(0L, gnssGoodNow = false)
        detector.evaluate(OUTAGE_ENTER_DWELL_MS / 2, gnssGoodNow = true) // recovers before 1000ms
        assertEquals(GnssMode.GNSS_AIDED, detector.mode)
        assertTrue("a single noisy blip must not produce a logged transition", detector.transitions.isEmpty())
    }

    @Test
    fun `sustained bad GNSS moves through TRANSITION into DEAD_RECKONING`() {
        val detector = newDetector()
        detector.evaluate(0L, gnssGoodNow = false)
        detector.evaluate(OUTAGE_ENTER_DWELL_MS - 1, gnssGoodNow = false)
        assertEquals("must not transition one ms before the dwell threshold", GnssMode.GNSS_AIDED, detector.mode)

        detector.evaluate(OUTAGE_ENTER_DWELL_MS, gnssGoodNow = false)
        assertEquals(GnssMode.TRANSITION, detector.mode)

        val nowAtDeadReckoning = OUTAGE_ENTER_DWELL_MS + TRANSITION_DWELL_MS
        detector.evaluate(nowAtDeadReckoning, gnssGoodNow = false)
        assertEquals(GnssMode.DEAD_RECKONING, detector.mode)

        assertEquals(2, detector.transitions.size)
        assertEquals(GnssMode.GNSS_AIDED, detector.transitions[0].fromMode)
        assertEquals(GnssMode.TRANSITION, detector.transitions[0].toMode)
        assertEquals(GnssMode.TRANSITION, detector.transitions[1].fromMode)
        assertEquals(GnssMode.DEAD_RECKONING, detector.transitions[1].toMode)
    }

    @Test
    fun `GNSS recovering during the TRANSITION dwell window returns to GNSS_AIDED, not DEAD_RECKONING`() {
        val detector = newDetector()
        detector.evaluate(0L, gnssGoodNow = false)
        detector.evaluate(OUTAGE_ENTER_DWELL_MS, gnssGoodNow = false) // -> TRANSITION

        val beforeDwellElapsed = OUTAGE_ENTER_DWELL_MS + TRANSITION_DWELL_MS - 1
        detector.evaluate(beforeDwellElapsed, gnssGoodNow = true)
        assertEquals("dwell window is a minimum, not skippable on good news", GnssMode.TRANSITION, detector.mode)

        val dwellElapsed = OUTAGE_ENTER_DWELL_MS + TRANSITION_DWELL_MS
        detector.evaluate(dwellElapsed, gnssGoodNow = true)
        assertEquals(GnssMode.GNSS_AIDED, detector.mode)
    }

    @Test
    fun `sustained good GNSS in DEAD_RECKONING moves through REACQUISITION back to GNSS_AIDED`() {
        val (detector, now0) = driveToDeadReckoning()

        detector.evaluate(now0 + 1, gnssGoodNow = true)
        detector.evaluate(now0 + REACQUISITION_ENTER_DWELL_MS, gnssGoodNow = true)
        assertEquals("must not transition one ms before the dwell threshold", GnssMode.DEAD_RECKONING, detector.mode)

        val nowAtReacquisition = now0 + 1 + REACQUISITION_ENTER_DWELL_MS
        detector.evaluate(nowAtReacquisition, gnssGoodNow = true)
        assertEquals(GnssMode.REACQUISITION, detector.mode)

        val nowAtAided = nowAtReacquisition + REACQUISITION_DWELL_MS
        detector.evaluate(nowAtAided, gnssGoodNow = true)
        assertEquals(GnssMode.GNSS_AIDED, detector.mode)
    }

    @Test
    fun `GNSS degrading again during REACQUISITION returns to DEAD_RECKONING`() {
        val (detector, now0) = driveToDeadReckoning()

        detector.evaluate(now0 + 1, gnssGoodNow = true)
        val nowAtReacquisition = now0 + 1 + REACQUISITION_ENTER_DWELL_MS
        detector.evaluate(nowAtReacquisition, gnssGoodNow = true)
        assertEquals(GnssMode.REACQUISITION, detector.mode)

        val nowAfterDwell = nowAtReacquisition + REACQUISITION_DWELL_MS
        detector.evaluate(nowAfterDwell, gnssGoodNow = false)
        assertEquals(GnssMode.DEAD_RECKONING, detector.mode)
    }

    @Test
    fun `an intermittently-good streak in DEAD_RECKONING resets the good-dwell timer`() {
        val (detector, now0) = driveToDeadReckoning()

        detector.evaluate(now0 + 1, gnssGoodNow = true)
        detector.evaluate(now0 + REACQUISITION_ENTER_DWELL_MS / 2, gnssGoodNow = false) // interrupts the streak
        detector.evaluate(now0 + REACQUISITION_ENTER_DWELL_MS, gnssGoodNow = true)
        assertEquals(
            "the good streak was interrupted, so the dwell clock must have restarted",
            GnssMode.DEAD_RECKONING,
            detector.mode,
        )
    }
}
