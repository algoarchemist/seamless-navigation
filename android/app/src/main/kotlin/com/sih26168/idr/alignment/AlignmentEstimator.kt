package com.sih26168.idr.alignment

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Phone-to-vehicle YAW alignment (PRD.md Section 15). Pure Kotlin, no
 * Android dependency, unit-testable per CLAUDE.md Rule 19.
 *
 * Scope note: PRD.md Section 15 asks for pitch/roll (from gravity while
 * stationary) AND yaw (from GNSS course vs. device heading while
 * moving). Pitch/roll are deliberately NOT separately estimated here —
 * Android's rotation-vector sensor already fuses gravity into its own
 * azimuth/pitch/roll output (sensors/OrientationMath.kt), so device
 * orientation is already gravity-referenced before this class ever
 * sees it. The genuinely missing piece — which nothing else in the
 * pipeline already computes — is the YAW offset between the device's
 * own compass-referenced azimuth and the vehicle's true direction of
 * travel (GNSS course-over-ground), since gravity alone can never
 * resolve that (PRD.md Section 15's own stated reason for using GNSS
 * course here).
 *
 * Method: while the vehicle is moving above a minimum speed AND not
 * currently turning (low yaw rate, from [YawRate]), accumulate
 * (azimuth - GNSS course) as a CIRCULAR mean — plain arithmetic
 * averaging of angles is wrong near the +-180 degree wrap boundary, so
 * this sums sin/cos components and recovers the mean via atan2, the
 * standard circular-mean technique.
 *
 * Explicit limitations (matching PRD.md Section 15's own stated ones):
 * assumes at least one clean straight-line moving segment with GNSS
 * near trip start; does not re-estimate while GNSS is unavailable;
 * does NOT yet re-trigger on a "Phone Moved" classification — no
 * motion classifier exists yet (Slice 6 continues) — [reset] is
 * exposed for that future caller to use, not invoked automatically.
 */
data class AlignmentEstimate(
    /** Device azimuth minus true vehicle heading, radians. Null until [isAligned]. */
    val yawOffsetRad: Float?,
    val sampleCount: Int,
    val isAligned: Boolean,
)

class AlignmentEstimator(
    private val minSpeedForAlignmentMps: Float = DEFAULT_MIN_SPEED_MPS,
    private val maxYawRateForStraightRadPerSec: Float = DEFAULT_MAX_YAW_RATE_RADPS,
    private val minSamplesForAligned: Int = DEFAULT_MIN_SAMPLES,
) {
    companion object {
        // Engineering defaults, not yet validated against a real test
        // drive (CLAUDE.md Rule 13) — GNSS course-over-ground is derived
        // from consecutive position fixes and gets noisy at low speed,
        // hence a deliberately conservative minimum here.
        const val DEFAULT_MIN_SPEED_MPS = 5.0f // ~18 km/h
        const val DEFAULT_MAX_YAW_RATE_RADPS = 0.1f // ~5.7 deg/s — "reasonably straight"
        const val DEFAULT_MIN_SAMPLES = 20
    }

    private var sumSin = 0.0
    private var sumCos = 0.0
    private var sampleCount = 0

    private var lastAzimuthRad: Float? = null
    private var lastAzimuthTimestampNs: Long? = null

    /**
     * Call once per orientation tick. gnssBearingDeg/gnssSpeedMps may be
     * null (no fix yet) — such ticks still update the internal yaw-rate
     * tracker (so the NEXT valid tick has a correct rate) but don't
     * contribute to the alignment estimate itself.
     */
    fun evaluate(
        nowNs: Long,
        azimuthRad: Float,
        gnssBearingDeg: Float?,
        gnssSpeedMps: Float?,
    ): AlignmentEstimate {
        val yawRateRadPerSec = YawRate.radPerSecond(lastAzimuthRad, lastAzimuthTimestampNs, azimuthRad, nowNs)
        lastAzimuthRad = azimuthRad
        lastAzimuthTimestampNs = nowNs

        val movingStraightAndFastEnough = gnssSpeedMps != null &&
            gnssSpeedMps >= minSpeedForAlignmentMps &&
            yawRateRadPerSec != null &&
            abs(yawRateRadPerSec) <= maxYawRateForStraightRadPerSec

        if (movingStraightAndFastEnough && gnssBearingDeg != null) {
            val gnssBearingRad = Math.toRadians(gnssBearingDeg.toDouble())
            val diff = azimuthRad.toDouble() - gnssBearingRad
            sumSin += sin(diff)
            sumCos += cos(diff)
            sampleCount++
        }

        val yawOffsetRad = if (sampleCount > 0) atan2(sumSin, sumCos).toFloat() else null
        return AlignmentEstimate(
            yawOffsetRad = yawOffsetRad,
            sampleCount = sampleCount,
            isAligned = sampleCount >= minSamplesForAligned,
        )
    }

    /** Discards the accumulated estimate — intended for a future "Phone Moved" re-trigger. */
    fun reset() {
        sumSin = 0.0
        sumCos = 0.0
        sampleCount = 0
        lastAzimuthRad = null
        lastAzimuthTimestampNs = null
    }
}
