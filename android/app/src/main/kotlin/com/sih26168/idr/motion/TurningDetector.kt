package com.sih26168.idr.motion

import com.sih26168.idr.alignment.YawRate
import kotlin.math.abs

/**
 * A deterministic stand-in for PRD.md Section 14's `Turning` class — same
 * precedent as [MotionStateClassifier] (Stationary/Cruising) and
 * [PotholeShockDetector] (Pothole): no labeled training data exists yet for
 * a real 8-class motion classifier, so this resolves exactly one binary
 * question (is the vehicle turning right now?) from a deterministic
 * threshold instead.
 *
 * Reuses [YawRate] — the SAME WORLD-frame-azimuth-derived yaw-rate signal
 * [com.sih26168.idr.alignment.AlignmentEstimator] already computes to
 * detect "moving straight" — just inverted: flagging Turning ABOVE a
 * threshold instead of straight-line motion below one. Sharing the
 * signal (not recomputing yaw rate a third way) keeps "is this vehicle
 * turning" answered consistently everywhere in the pipeline.
 *
 * Built for `dr/NonHolonomicConstraint.kt`'s PRD.md Section 20 gap: "any
 * ML/physics-estimated lateral velocity component is suppressed toward
 * zero EXCEPT during classifier-flagged Turning windows, where the
 * constraint is relaxed." Without this, a genuine turn's real lateral
 * velocity was being suppressed as if it were sensor noise.
 *
 * Pure Kotlin, no Android dependency, unit-testable on the plain JVM
 * (CLAUDE.md Rule 19). Stateful (tracks the previous azimuth/timestamp
 * needed to compute a rate) — one instance per live DR session, same
 * lifecycle convention [com.sih26168.idr.alignment.AlignmentEstimator]
 * and [StationaryDetector] already use.
 */
class TurningDetector(
    private val minYawRateForTurningRadPerSec: Float = DEFAULT_MIN_YAW_RATE_RADPS,
) {
    companion object {
        // Engineering default, unvalidated against a real outdoor test drive
        // (CLAUDE.md Rule 13). Deliberately set ABOVE
        // AlignmentEstimator.DEFAULT_MAX_YAW_RATE_RADPS (0.1 rad/s, its own
        // "reasonably straight" ceiling) so there is a small dead zone
        // between "straight" and "turning" rather than the two thresholds
        // touching exactly at the same value.
        const val DEFAULT_MIN_YAW_RATE_RADPS = 0.15f // ~8.6 deg/s
    }

    private var lastAzimuthRad: Float? = null
    private var lastTimestampNs: Long? = null

    /**
     * Call once per orientation tick, WORLD-frame device azimuth (see
     * [com.sih26168.idr.sensors.OrientationSample.azimuthRad]).
     *
     * @return true if the yaw rate between this sample and the previous one
     *   meets or exceeds [minYawRateForTurningRadPerSec]. False on the very
     *   first call (no previous sample to diff against — same "not enough
     *   information yet" convention [YawRate] itself already uses) and on
     *   any sample that isn't turning by that threshold.
     */
    fun evaluate(nowNs: Long, azimuthRad: Float): Boolean {
        val yawRateRadPerSec = YawRate.radPerSecond(lastAzimuthRad, lastTimestampNs, azimuthRad, nowNs)
        lastAzimuthRad = azimuthRad
        lastTimestampNs = nowNs
        return yawRateRadPerSec != null && abs(yawRateRadPerSec) >= minYawRateForTurningRadPerSec
    }

    /** Discards tracked azimuth/timestamp history — used when (re)starting a DR run. */
    fun reset() {
        lastAzimuthRad = null
        lastTimestampNs = null
    }
}
