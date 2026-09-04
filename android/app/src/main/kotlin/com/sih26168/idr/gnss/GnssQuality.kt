package com.sih26168.idr.gnss

/**
 * Pure classification of whether a GNSS fix is "good enough to navigate
 * on right now" — no Android dependency, unit-testable on the plain JVM
 * (CLAUDE.md Rule 19). Deliberately separate from [GnssOutageDetector]
 * (CLAUDE.md Rule 5): this answers "is GNSS good *this instant*", the
 * detector answers "given a history of that, what state are we in."
 *
 * Thresholds below are engineering defaults, not yet validated against
 * a real outage test run (that's Slice 9, PRD.md Section 28) — do not
 * report them to judges as measured figures (CLAUDE.md Rule 13).
 */
object GnssQuality {

    /**
     * A fix older than this (no update received in this long) is treated as
     * unavailable.
     *
     * REAL BUG FIX (2026-09-04, docs/gnss-indoor-window-degradation.md,
     * CLAUDE.md Rule 13's "capture real data, then tune" option): a user
     * report of GNSS_AIDED flapping to DEAD_RECKONING near an open indoor
     * window was ORIGINALLY suspected to be an accuracy/multipath problem
     * (see git history / the doc above for that hypothesis). A real 135s
     * drive log captured next to the window (`scripts/analyze_drive_log.py`'s
     * `report_degraded_mode_accuracy`) disproved it: accuracy was excellent
     * the whole time (3.5-10.5m, nowhere near the 25m bar). The actual
     * cause was fix STALENESS — indoors near this window, the receiver only
     * delivered a fresh fix every ~6.2-6.5s (21 fresh fixes over 135s;
     * min=6219ms, median=6317ms, max=6539ms elapsed between them), well
     * over double the previous 3000ms bar. Combined with
     * GnssOutageDetector's dwell timers, that guaranteed the "good" window
     * after each fresh fix (~2.8s before the OLD 3000ms bar tripped again)
     * was too short to clear reacquisitionEnterDwellMs (2000ms) with enough
     * runway left for reacquisitionDwellMs (1000ms) — REACQUISITION could
     * never win the race back to GNSS_AIDED, so the phone oscillated
     * between DEAD_RECKONING and REACQUISITION indefinitely.
     *
     * Raised to comfortably clear the measured worst case (6539ms) with a
     * margin, not to some arbitrarily large number (Rule 13 — this is
     * measured, not guessed). DISCLOSED TRADEOFF: a genuine total GNSS loss
     * now takes up to ~7s (was ~3s) before a fix is even considered stale,
     * so real-outage detection latency grows by the same amount — a real
     * cost, accepted because the previous value made ANY indoor stretch
     * with this fix cadence undemoable, which is the worse failure mode for
     * this project. Still a single-drive, single-location measurement
     * (Rule 13) — not validated across devices/locations/real outdoor
     * outages yet.
     */
    const val DEFAULT_MAX_FIX_AGE_MS = 7_000L

    /** A fix less precise than this (Location.getAccuracy(), meters, 68% confidence radius)
     * is treated as too degraded to trust. */
    const val DEFAULT_MAX_ACCURACY_M = 25f

    /**
     * REAL BUG (2026-09-01, on-device test — phone sitting stationary
     * indoors, History tab still logging 0.3-30m of "drift" every
     * reacquisition cycle): [DEFAULT_MAX_ACCURACY_M] answers "is GNSS
     * available at all" for the state machine, but Android's self-reported
     * `Location.getAccuracy()` is the RECEIVER's own confidence estimate —
     * it does not detect multipath. Indoors, successive fixes can each
     * individually claim <=25m accuracy while actually landing 5-30m apart
     * from each other (signal bouncing off walls/ceiling before reaching
     * the antenna). [fusion.StateEstimator] was treating any such fix as
     * ground truth for its outage anchor and its "measured drift" claim,
     * so it was really measuring GNSS position noise and mislabeling it as
     * dead-reckoning drift — a violation of CLAUDE.md Rule 13 (no invented
     * or misattributed numbers), even though every individual number was
     * technically "real" telemetry.
     *
     * This tighter bound is a SEPARATE, stricter bar used only where a fix
     * is trusted as ground truth (the outage anchor, and the fix a drift
     * measurement is computed against) — [isGood]'s own 25m bar for
     * state-machine timing is untouched, so reacquisition attempt cadence
     * doesn't change. Engineering default, unvalidated against a real
     * outdoor test run (CLAUDE.md Rule 13) — same caveat as
     * [DEFAULT_MAX_ACCURACY_M] itself.
     */
    const val DEFAULT_MAX_ACCURACY_FOR_GROUND_TRUTH_M = 10f

    /**
     * @param fixAgeMs milliseconds since the most recent fix was received
     *   (Long.MAX_VALUE if no fix has ever been received).
     * @param accuracyM the most recent fix's accuracy in meters, or null
     *   if no fix has ever been received.
     */
    fun isGood(
        fixAgeMs: Long,
        accuracyM: Float?,
        maxFixAgeMs: Long = DEFAULT_MAX_FIX_AGE_MS,
        maxAccuracyM: Float = DEFAULT_MAX_ACCURACY_M,
    ): Boolean {
        if (accuracyM == null) return false
        if (fixAgeMs > maxFixAgeMs) return false
        if (accuracyM > maxAccuracyM) return false
        return true
    }

    /**
     * (Round 2 addition, 2026-08-28 — PRD.md FR13/Section 17) Continuous
     * confidence weight in [0, 1] for a GNSS fix, for use INSIDE a fusion
     * blend that wants to trust a very accurate fix more than a marginal
     * one — [isGood] remains the state machine's own binary enter/exit
     * trigger (Section 18, unchanged); this is a separate, additional
     * signal, not a replacement for it. A fix right at [maxAccuracyM]
     * (the edge [isGood] still accepts) gets a weight near 0; a fix much
     * more precise than that gets a weight near 1.
     *
     * Linear falloff — not modeled on any real GNSS confidence curve
     * (Location's own accuracy is already a 68%-confidence radius, not a
     * linear trust measure), a deliberately simple starting point per
     * CLAUDE.md Rule 13: no invented sophistication before it's measured
     * against a real outage/reacquisition run.
     */
    fun confidenceWeight(accuracyM: Float?, maxAccuracyM: Float = DEFAULT_MAX_ACCURACY_M): Float {
        if (accuracyM == null) return 0f
        if (accuracyM <= 0f) return 1f
        return (1f - accuracyM / maxAccuracyM).coerceIn(0f, 1f)
    }
}
