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
}
