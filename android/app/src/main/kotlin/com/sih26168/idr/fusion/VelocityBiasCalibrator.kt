package com.sih26168.idr.fusion

/**
 * PRD.md Section 17's "continuously calibrate the velocity model's bias
 * against GNSS speed" — a simple online correction, explicitly NOT a full
 * Kalman filter (Section 17: "a loosely-coupled complementary approach is
 * preferred over a tightly-coupled EKF for feasibility").
 *
 * While GNSS is trustworthy (caller only invokes [update] during
 * `GNSS_AIDED`) and moving fast enough for GNSS speed to be a reliable
 * reference (reuses [com.sih26168.idr.alignment.AlignmentEstimator]'s own
 * 5.0 m/s / ~18 km/h minimum-speed rationale — GNSS-derived speed is noisy
 * at low speed), this tracks an exponentially-weighted moving average of
 * `gnssSpeedMps - rawPredictedVelocityMps`. [correctedVelocity] then adds
 * that learned offset to any raw model prediction.
 *
 * The bias is only ever UPDATED while there's real GNSS ground truth to
 * compare against; it is simply held constant (still applied via
 * [correctedVelocity]) once GNSS is lost and the caller stops calling
 * [update] — there is no ground truth left to recalibrate against during
 * `DEAD_RECKONING`, so "last known good bias" is the honest best guess.
 */
class VelocityBiasCalibrator(
    private val minSpeedForCalibrationMps: Float = DEFAULT_MIN_SPEED_MPS,
    private val emaAlpha: Float = DEFAULT_EMA_ALPHA,
) {
    companion object {
        // Matches AlignmentEstimator.DEFAULT_MIN_SPEED_MPS's rationale exactly
        // (GNSS speed is noisy below this) — kept as an independent constant
        // rather than a shared reference, since the two classes have no other
        // coupling and this one may need its own tuning later.
        const val DEFAULT_MIN_SPEED_MPS = 5.0f // ~18 km/h

        // Engineering default, not yet validated against a real test drive
        // (CLAUDE.md Rule 13). Small alpha = slow-moving average — the bias
        // is assumed to be a slowly-varying systematic offset (e.g. from this
        // phone's mounting or the model's training-distribution gap), not
        // something that should jump around sample to sample.
        const val DEFAULT_EMA_ALPHA = 0.05f
    }

    var currentBiasMps: Float = 0f
        private set

    var sampleCount: Int = 0
        private set

    /**
     * Call only while GNSS is trustworthy (GNSS_AIDED) with a real GNSS
     * speed reading.
     *
     * @param confidenceWeight (Round 2 addition, 2026-08-28 — PRD.md
     *   FR13/Section 17) scales the effective EMA alpha for THIS sample,
     *   typically [com.sih26168.idr.gnss.GnssQuality.confidenceWeight] —
     *   a very accurate fix pulls the bias toward its error faster than a
     *   marginal one, instead of every fix that merely passes the binary
     *   [com.sih26168.idr.gnss.GnssQuality.isGood] gate being trusted
     *   identically. Defaults to 1 (full trust) so existing callers/tests
     *   that don't pass it see unchanged behavior. The FIRST sample still
     *   initializes the bias directly regardless of weight — there is no
     *   prior estimate to blend a low-confidence correction against, so
     *   "some estimate" beats "none" even if it's a marginal fix.
     */
    fun update(gnssSpeedMps: Float, rawPredictedVelocityMps: Float, confidenceWeight: Float = 1f) {
        if (gnssSpeedMps < minSpeedForCalibrationMps) return

        val error = gnssSpeedMps - rawPredictedVelocityMps
        currentBiasMps = if (sampleCount == 0) {
            error // first sample: no prior average to blend with
        } else {
            val effectiveAlpha = emaAlpha * confidenceWeight.coerceIn(0f, 1f)
            currentBiasMps + effectiveAlpha * (error - currentBiasMps)
        }
        sampleCount++
    }

    /** Adds the currently learned bias to a raw model prediction. A no-op (+0) before the first sample. */
    fun correctedVelocity(rawPredictedVelocityMps: Float): Float = rawPredictedVelocityMps + currentBiasMps

    fun reset() {
        currentBiasMps = 0f
        sampleCount = 0
    }
}
