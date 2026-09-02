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

    /** A fix older than this (no update received in this long) is treated as unavailable. */
    const val DEFAULT_MAX_FIX_AGE_MS = 3_000L

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
