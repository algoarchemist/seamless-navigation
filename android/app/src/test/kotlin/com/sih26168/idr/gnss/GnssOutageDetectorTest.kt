package com.sih26168.idr.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val OUTAGE_ENTER_DWELL_MS = 1_000L
private const val REACQUISITION_ENTER_DWELL_MS = 1_000L
private const val TRANSITION_DWELL_MS = 500L
private const val REACQUISITION_DWELL_MS = 500L
private const val REACQUISITION_EXIT_DWELL_MS = 700L

class GnssOutageDetectorTest {

    private fun newDetector() = GnssOutageDetector(
        outageEnterDwellMs = OUTAGE_ENTER_DWELL_MS,
        reacquisitionEnterDwellMs = REACQUISITION_ENTER_DWELL_MS,
        transitionDwellMs = TRANSITION_DWELL_MS,
        reacquisitionDwellMs = REACQUISITION_DWELL_MS,
        reacquisitionExitDwellMs = REACQUISITION_EXIT_DWELL_MS,
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

        // The good-streak clock starts on the first in-REACQUISITION good
        // sample (entry itself doesn't count — it's the transition, not a
        // REACQUISITION-branch evaluation), same pattern as every other
        // streak in this class.
        detector.evaluate(nowAtReacquisition + 1, gnssGoodNow = true)
        val nowAtAided = nowAtReacquisition + 1 + REACQUISITION_DWELL_MS
        detector.evaluate(nowAtAided, gnssGoodNow = true)
        assertEquals(GnssMode.GNSS_AIDED, detector.mode)
    }

    @Test
    fun `GNSS degrading continuously through the exit dwell during REACQUISITION returns to DEAD_RECKONING`() {
        val (detector, now0) = driveToDeadReckoning()

        detector.evaluate(now0 + 1, gnssGoodNow = true)
        val nowAtReacquisition = now0 + 1 + REACQUISITION_ENTER_DWELL_MS
        detector.evaluate(nowAtReacquisition, gnssGoodNow = true)
        assertEquals(GnssMode.REACQUISITION, detector.mode)

        detector.evaluate(nowAtReacquisition + 1, gnssGoodNow = false)
        detector.evaluate(nowAtReacquisition + 1 + REACQUISITION_EXIT_DWELL_MS, gnssGoodNow = false)
        assertEquals(GnssMode.DEAD_RECKONING, detector.mode)
    }

    // REAL BUG #2 (2026-09-02, on-device test — mode flapping constantly
    // between DEAD_RECKONING and REACQUISITION again, ~every 7s, phone
    // stationary indoors): REACQUISITION used to bail to DEAD_RECKONING on
    // the very FIRST bad sample after entry — itself the single-noisy-
    // sample flip CLAUDE.md Rule 16 prohibits, just moved to the exit side.
    // Marginal indoor GNSS accuracy flickers in and out of "good" faster
    // than the reacquisition dwell, so this fired almost every cycle and
    // REACQUISITION never once reached GNSS_AIDED — which in turn corrupted
    // StateEstimator's "time since last real GNSS fix" bookkeeping (see
    // that class's outageDurationS use). These tests lock in the fix: a
    // single bad blip must NOT bail immediately, only a bad streak
    // sustained for the full exit dwell.
    @Test
    fun `a single bad blip partway through REACQUISITION does not bail to DEAD_RECKONING`() {
        val (detector, now0) = driveToDeadReckoning()

        detector.evaluate(now0 + 1, gnssGoodNow = true)
        val nowAtReacquisition = now0 + 1 + REACQUISITION_ENTER_DWELL_MS
        detector.evaluate(nowAtReacquisition, gnssGoodNow = true)
        assertEquals(GnssMode.REACQUISITION, detector.mode)

        // One bad sample, well short of REACQUISITION_EXIT_DWELL_MS.
        detector.evaluate(nowAtReacquisition + (REACQUISITION_EXIT_DWELL_MS / 2), gnssGoodNow = false)
        assertEquals(
            "a single noisy sample must not flip REACQUISITION back to DEAD_RECKONING (Rule 16)",
            GnssMode.REACQUISITION,
            detector.mode,
        )
    }

    @Test
    fun `a bad blip that recovers before the exit dwell resets the good-streak clock instead of bailing`() {
        val (detector, now0) = driveToDeadReckoning()

        detector.evaluate(now0 + 1, gnssGoodNow = true)
        val nowAtReacquisition = now0 + 1 + REACQUISITION_ENTER_DWELL_MS
        detector.evaluate(nowAtReacquisition, gnssGoodNow = true)
        assertEquals(GnssMode.REACQUISITION, detector.mode)

        // Goes bad briefly, recovers well before the exit dwell would fire.
        detector.evaluate(nowAtReacquisition + (REACQUISITION_EXIT_DWELL_MS / 2), gnssGoodNow = false)
        detector.evaluate(nowAtReacquisition + REACQUISITION_EXIT_DWELL_MS, gnssGoodNow = true)
        assertEquals(
            "brief blip recovered before the exit dwell elapsed; must still be attempting REACQUISITION",
            GnssMode.REACQUISITION,
            detector.mode,
        )

        // GNSS_AIDED requires a FRESH continuous-good streak from the blip,
        // not credit for time elapsed since REACQUISITION was first entered.
        val notYetFullStreak = nowAtReacquisition + REACQUISITION_EXIT_DWELL_MS + REACQUISITION_DWELL_MS - 1
        detector.evaluate(notYetFullStreak, gnssGoodNow = true)
        assertEquals(
            "the good streak must have restarted at the blip's recovery point, not at REACQUISITION entry",
            GnssMode.REACQUISITION,
            detector.mode,
        )

        val fullStreakFromRecovery = nowAtReacquisition + REACQUISITION_EXIT_DWELL_MS + REACQUISITION_DWELL_MS
        detector.evaluate(fullStreakFromRecovery, gnssGoodNow = true)
        assertEquals(GnssMode.GNSS_AIDED, detector.mode)
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
