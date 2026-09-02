package com.sih26168.idr.fusion

/**
 * PRD.md Section 17's still-open half of the GNSS+INS fusion engine:
 * "the IMU-derived velocity/heading are used to smooth short GNSS
 * gaps/jitter." The velocity-bias half of Section 17
 * ([VelocityBiasCalibrator]) and FR13's continuous accuracy weighting
 * ([com.sih26168.idr.gnss.GnssQuality.confidenceWeight], already wired
 * into that calibrator) were both already implemented — this is the
 * remaining piece: raw consecutive GNSS fixes visibly jitter a few
 * meters even while `GNSS_AIDED` (Android's `Location.getAccuracy()` is
 * a 68%-confidence RADIUS, not a promise of fix-to-fix repeatability),
 * and nothing was smoothing that before this class existed — the map
 * marker jumped to each new raw fix directly.
 *
 * A simple COMPLEMENTARY filter, deliberately NOT a Kalman filter
 * (CLAUDE.md's "What Not To Build" / PRD.md Section 7 exclude a full
 * state-space estimator with covariance propagation) — pure Kotlin, no
 * Android dependency, unit-testable per CLAUDE.md Rule 19. Each call:
 * 1. PREDICTS forward from the last smoothed position using the
 *    caller-supplied IMU/DR-derived WORLD-frame velocity over the
 *    elapsed time since the last call (short-term dead-reckoning
 *    between fixes).
 * 2. Pulls that prediction toward the newly arrived raw fix by
 *    [confidenceWeight] — reusing the SAME FR13 signal
 *    [VelocityBiasCalibrator] already uses, so a fix right at the
 *    accuracy floor barely moves the estimate (leans on the IMU
 *    prediction) while a very precise fix snaps close to raw GNSS
 *    almost immediately. This is the position-domain analogue of
 *    FR13's "a 24m fix should not be trusted identically to a 2m fix."
 *
 * Deliberately operates in whatever local-meter frame the caller
 * chooses (see [update]'s params) — [fusion.StateEstimator] uses a
 * FIXED trip-origin frame for this, kept separate from its own
 * continuously-moving `outageAnchorLatDeg/LonDeg` (which must keep
 * snapping to the literal latest good fix for reacquisition/drift-
 * measurement correctness — see that class's own doc for why this
 * class never touches that anchor).
 */
class GnssJitterFilter {
    private var smoothedEastM: Double? = null
    private var smoothedNorthM: Double? = null
    private var lastUpdateMs: Long? = null

    /**
     * @param rawFixEastM/rawFixNorthM this tick's raw GNSS fix, in a
     *   FIXED local-meter frame (caller's responsibility to keep the
     *   reference point constant across calls — a moving reference would
     *   make consecutive calls incomparable).
     * @param velocityEastMps/velocityNorthMps current IMU/DR-derived
     *   WORLD-frame velocity (m/s), for the short-term prediction step.
     * @param confidenceWeight in [0, 1] — how hard to pull the prediction
     *   toward [rawFixEastM]/[rawFixNorthM] this tick; 1 = trust the raw
     *   fix completely (no smoothing), 0 = ignore it entirely (pure IMU
     *   dead reckoning). Typically
     *   [com.sih26168.idr.gnss.GnssQuality.confidenceWeight].
     * @return the smoothed position, same local-meter frame as the input.
     */
    fun update(
        nowMs: Long,
        rawFixEastM: Double,
        rawFixNorthM: Double,
        velocityEastMps: Double,
        velocityNorthMps: Double,
        confidenceWeight: Float,
    ): Pair<Double, Double> {
        val prevEastM = smoothedEastM
        val prevNorthM = smoothedNorthM
        val prevMs = lastUpdateMs
        lastUpdateMs = nowMs

        if (prevEastM == null || prevNorthM == null || prevMs == null) {
            // First-ever sample: no prior estimate to predict from or
            // blend against — trust the raw fix outright, same "some
            // estimate beats none" convention VelocityBiasCalibrator's
            // own first sample already uses.
            smoothedEastM = rawFixEastM
            smoothedNorthM = rawFixNorthM
            return rawFixEastM to rawFixNorthM
        }

        // A non-positive/huge dt (clock oddity, or a long gap since the
        // last call — e.g. this run's very first fix after an outage,
        // where the caller is expected to have called reset() instead)
        // would make the prediction step meaningless; holding the
        // previous position as the prediction degrades to "trust the raw
        // fix" territory once blended below, which is the honest fallback.
        val dtSeconds = (nowMs - prevMs) / 1000.0
        val predictedEastM = if (dtSeconds > 0.0) prevEastM + velocityEastMps * dtSeconds else prevEastM
        val predictedNorthM = if (dtSeconds > 0.0) prevNorthM + velocityNorthMps * dtSeconds else prevNorthM

        val w = confidenceWeight.toDouble().coerceIn(0.0, 1.0)
        val newEastM = predictedEastM + w * (rawFixEastM - predictedEastM)
        val newNorthM = predictedNorthM + w * (rawFixNorthM - predictedNorthM)
        smoothedEastM = newEastM
        smoothedNorthM = newNorthM
        return newEastM to newNorthM
    }

    /** Discards accumulated state — call when GNSS is freshly (re)trusted so the next [update] starts clean rather than predicting across a stale gap. */
    fun reset() {
        smoothedEastM = null
        smoothedNorthM = null
        lastUpdateMs = null
    }
}
