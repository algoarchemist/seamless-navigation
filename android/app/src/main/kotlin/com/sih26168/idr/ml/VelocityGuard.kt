package com.sih26168.idr.ml

/**
 * PRD.md Section 13's "fall back... rather than trusting an out-of-
 * distribution ML output" + Section 13's damping addition (Round 2,
 * 2026-08-28 — FR3): the physics path's integrator has "memory" that
 * absorbs a single bad reading (BaselinePhysicsIntegrator double-
 * integrates from acceleration, so one anomalous accel sample only
 * nudges velocity, it doesn't teleport it); this model instead predicts
 * an absolute speed directly each tick, so one anomalous prediction
 * previously reached the position integrator completely unguarded and
 * produced a visible position jump (Round 2 Day 1 test finding).
 *
 * Two independent corrections, applied in order:
 * 1. OOD REJECTION: a prediction that is NaN/infinite or exceeds a
 *    generously wide plausible-speed bound is discarded outright — the
 *    guard HOLDS the last accepted (already-smoothed) value rather than
 *    integrating a physically implausible number. This is a coarse
 *    sanity clamp, NOT full training-distribution out-of-distribution
 *    detection (no per-feature training bounds are exported from the
 *    Python training pipeline yet) — documented here as a known
 *    limitation, not an invented capability (CLAUDE.md Rule 13).
 * 2. DAMPING: an accepted prediction is exponentially smoothed against
 *    the previous accepted+smoothed value, the same EMA technique
 *    [com.sih26168.idr.fusion.VelocityBiasCalibrator] already uses for
 *    bias learning — a small-ish alpha spreads a single-tick spike
 *    across several ticks instead of it appearing in full immediately.
 */
class VelocityGuard(
    private val maxPlausibleSpeedMps: Float = DEFAULT_MAX_PLAUSIBLE_SPEED_MPS,
    private val emaAlpha: Float = DEFAULT_EMA_ALPHA,
) {
    companion object {
        // ~200 km/h — generously above any plausible car/motorcycle demo
        // speed, deliberately wide so this only catches genuinely
        // implausible outputs (e.g. a feature-extraction bug), not real
        // fast driving. Engineering default, not yet validated against a
        // real test drive (CLAUDE.md Rule 13).
        const val DEFAULT_MAX_PLAUSIBLE_SPEED_MPS = 55.0f

        // Engineering default, not yet validated against a real test
        // drive. Deliberately less aggressive than
        // VelocityBiasCalibrator's 0.05 (that one smooths a slowly-varying
        // systematic offset over many seconds); this smooths tick-to-tick
        // sensor/model noise, which should settle out within roughly a
        // second at ~10 Hz, not tens of seconds.
        const val DEFAULT_EMA_ALPHA = 0.3f
    }

    data class Result(val velocityMps: Float, val wasOutOfDistribution: Boolean)

    private var smoothedVelocityMps: Float = 0f
    private var hasAcceptedSample: Boolean = false

    fun apply(rawVelocityMps: Float): Result {
        val plausible = rawVelocityMps.isFinite() && kotlin.math.abs(rawVelocityMps) <= maxPlausibleSpeedMps
        if (!plausible) {
            return Result(velocityMps = smoothedVelocityMps, wasOutOfDistribution = true)
        }

        smoothedVelocityMps = if (!hasAcceptedSample) {
            rawVelocityMps // first accepted sample: no prior average to blend with
        } else {
            smoothedVelocityMps + emaAlpha * (rawVelocityMps - smoothedVelocityMps)
        }
        hasAcceptedSample = true
        return Result(velocityMps = smoothedVelocityMps, wasOutOfDistribution = false)
    }

    fun reset() {
        smoothedVelocityMps = 0f
        hasAcceptedSample = false
    }
}
