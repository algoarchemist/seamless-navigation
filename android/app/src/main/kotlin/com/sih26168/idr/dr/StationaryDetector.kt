package com.sih26168.idr.dr

/**
 * Deterministic (no ML) "is the phone stationary right now" detector,
 * used to gate the ZUPT (zero-velocity update) correction in
 * [BaselineDeadReckoningRepository]. PRD.md Section 14 describes
 * Stationary -> ZUPT as an effect of the ML motion classifier, but that
 * classifier doesn't exist until Slice 6 — CLAUDE.md's slice order puts
 * ZUPT in Slice 5, before ML, so this is a lightweight physics-only
 * stand-in: sustained low linear-acceleration AND low gyro magnitude.
 * Pure Kotlin, no Android dependency, unit-testable on the plain JVM
 * (CLAUDE.md Rule 19).
 *
 * Known, honest limitation: constant-velocity straight-line motion also
 * produces near-zero acceleration and near-zero gyro rate, so this
 * cannot distinguish "truly at rest" from "coasting at a steady speed
 * with the engine/road producing no noticeable vibration." That is an
 * inherent limitation of accelerometer/gyro-only ZUPT, not a bug — a
 * real system would additionally gate on GNSS speed or the eventual ML
 * motion classifier; neither is available to this detector.
 *
 * Requires the below-threshold condition to hold continuously for
 * [minStationaryDwellMs] before committing to "stationary" — the same
 * hysteresis principle CLAUDE.md Rule 16 requires for the GNSS state
 * machine, applied here so a single quiet accelerometer sample mid-motion
 * doesn't momentarily zero out real velocity.
 */
class StationaryDetector(
    private val maxLinearAccelMagnitudeMps2: Float = DEFAULT_MAX_LINEAR_ACCEL_MPS2,
    private val maxGyroMagnitudeRadPerSec: Float = DEFAULT_MAX_GYRO_RAD_PER_SEC,
    private val minStationaryDwellMs: Long = DEFAULT_MIN_STATIONARY_DWELL_MS,
) {
    companion object {
        // Engineering defaults, not yet validated against a real test
        // drive (PRD.md Section 28) — not to be reported as measured
        // figures (CLAUDE.md Rule 13).
        const val DEFAULT_MAX_LINEAR_ACCEL_MPS2 = 0.25f
        const val DEFAULT_MAX_GYRO_RAD_PER_SEC = 0.05f
        const val DEFAULT_MIN_STATIONARY_DWELL_MS = 300L
    }

    private var belowThresholdStreakStartMs: Long? = null

    var isStationary: Boolean = false
        private set

    /**
     * @param nowMs a monotonic clock in milliseconds — the caller uses
     *   sensor boot-time timestamps (converted to ms) rather than
     *   wall-clock, since this only needs relative durations, not
     *   alignment to GNSS time (CLAUDE.md Rule 9/14).
     */
    fun evaluate(nowMs: Long, linearAccelMagnitudeMps2: Float, gyroMagnitudeRadPerSec: Float): Boolean {
        val belowThreshold = linearAccelMagnitudeMps2 <= maxLinearAccelMagnitudeMps2 &&
            gyroMagnitudeRadPerSec <= maxGyroMagnitudeRadPerSec

        if (!belowThreshold) {
            belowThresholdStreakStartMs = null
            isStationary = false
            return isStationary
        }

        val streakStart = belowThresholdStreakStartMs ?: nowMs.also { belowThresholdStreakStartMs = it }
        isStationary = nowMs - streakStart >= minStationaryDwellMs
        return isStationary
    }
}
