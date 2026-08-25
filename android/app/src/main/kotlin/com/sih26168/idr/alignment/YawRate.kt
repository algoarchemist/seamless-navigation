package com.sih26168.idr.alignment

import kotlin.math.PI

/**
 * Pure Kotlin (no Android dependency, unit-testable per CLAUDE.md
 * Rule 19) computation of WORLD-frame yaw rate (rad/s) from two
 * consecutive azimuth readings. Deliberately NOT derived from raw
 * device gyro Z — that only approximates yaw rate if the phone happens
 * to be held upright/flat, which is exactly the unknown-mounting
 * problem [AlignmentEstimator] exists to solve, so it can't be assumed
 * here. Azimuth (from the rotation-vector sensor's own gravity+
 * magnetometer fusion) is WORLD-frame by construction regardless of
 * device tilt, sidestepping that circularity.
 *
 * Shared by [AlignmentEstimator] (gates "is the vehicle turning right
 * now") and the still-PLANNED FeatureExtractor.kt (needs the same
 * yaw-rate signal as a training feature) so the angle-unwrapping logic
 * — the fiddly, easy-to-get-wrong part — exists in exactly one place.
 */
object YawRate {

    /**
     * @return yaw rate in rad/s, or null if this is the first sample
     *   (no previous azimuth to diff against) or the clock didn't
     *   advance (same guard convention as SampleRate.hzFromDeltaNs).
     */
    fun radPerSecond(
        previousAzimuthRad: Float?,
        previousTimestampNs: Long?,
        azimuthRad: Float,
        nowNs: Long,
    ): Float? {
        if (previousAzimuthRad == null || previousTimestampNs == null) return null
        val dtSeconds = (nowNs - previousTimestampNs) / 1_000_000_000.0
        if (dtSeconds <= 0.0) return null

        // Unwrap to (-pi, pi] so a wrap from +179deg to -179deg reads as
        // a small step in one direction, not a near-360-degree spin.
        var deltaRad = (azimuthRad - previousAzimuthRad).toDouble()
        while (deltaRad > PI) deltaRad -= 2 * PI
        while (deltaRad <= -PI) deltaRad += 2 * PI

        return (deltaRad / dtSeconds).toFloat()
    }
}
