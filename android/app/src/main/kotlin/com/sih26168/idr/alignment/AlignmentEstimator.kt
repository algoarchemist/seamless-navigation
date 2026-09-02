package com.sih26168.idr.alignment

import kotlin.math.PI
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
 * moving). A full device-to-vehicle 3-axis rotation matrix from
 * pitch/roll is still deliberately NOT built — Android's rotation-vector
 * sensor already fuses gravity into its own azimuth/pitch/roll output
 * (sensors/OrientationMath.kt), so device orientation is already
 * gravity-referenced before this class ever sees it, and this project's
 * 2D horizontal navigation only ever needs a HEADING (yaw), which pitch/
 * roll mounting tilt doesn't change. What IS now estimated (2026-09-02)
 * is the narrower, still-open piece PRD.md Section 15 explicitly calls
 * for: "does not model motorcycle lean beyond flagging it as reduced
 * confidence during large roll excursions." That requires knowing the
 * device's MOUNTING roll/pitch baseline (established from gravity while
 * the vehicle is stationary, since a parked vehicle's own roll/pitch is
 * ~0) so a later excursion can be measured relative to it, not relative
 * to an assumed-zero device roll that would false-positive on any phone
 * mounted at a tilt.
 *
 * Method: while the vehicle is moving above a minimum speed AND not
 * currently turning (low yaw rate, from [YawRate]), accumulate
 * (azimuth - GNSS course) as a CIRCULAR mean for yaw — plain arithmetic
 * averaging of angles is wrong near the +-180 degree wrap boundary, so
 * this sums sin/cos components and recovers the mean via atan2, the
 * standard circular-mean technique. Pitch/roll baselines use the SAME
 * circular-mean technique (reused for consistency, even though pitch/
 * roll rarely approach the wrap boundary in practice) while the vehicle
 * is near-stationary, gated on GNSS speed — the real accelerometer-
 * magnitude-based stationary check (dr/StationaryDetector.kt) lives
 * downstream in the DR pipeline and isn't plumbed into this class, to
 * keep it dependency-free of the DR path (same reasoning
 * AlignmentRepository.kt's own doc gives for depending on nothing but
 * SensorRepository + GnssModeRepository).
 *
 * Explicit limitations (matching PRD.md Section 15's own stated ones):
 * assumes at least one clean straight-line moving segment with GNSS
 * near trip start, plus at least one near-stationary segment with a GNSS
 * fix for the roll/pitch baseline (e.g. before pulling away); does not
 * re-estimate while GNSS is unavailable; [reset] is invoked automatically
 * on a detected "Phone Moved" event by AlignmentRepository.kt.
 */
data class AlignmentEstimate(
    /** Device azimuth minus true vehicle heading, radians. Null until [isAligned]. */
    val yawOffsetRad: Float?,
    val sampleCount: Int,
    val isAligned: Boolean,
    /** Device roll minus vehicle (mounting) roll baseline, radians. Null until [isPitchRollAligned]. */
    val rollOffsetRad: Float? = null,
    /** Device pitch minus vehicle (mounting) pitch baseline, radians. Null until [isPitchRollAligned]. */
    val pitchOffsetRad: Float? = null,
    val pitchRollSampleCount: Int = 0,
    val isPitchRollAligned: Boolean = false,
    /**
     * PRD.md Section 15's motorcycle-lean carve-out: true when a roll
     * baseline exists AND the CURRENT roll deviates from it by more than
     * [DEFAULT_MAX_ROLL_EXCURSION_RAD] — e.g. a real lean, or the phone
     * having slipped in its mount. This is a FLAG only, not a lean-
     * dynamics correction (PRD.md explicitly excludes modeling the lean
     * itself) — downstream consumers should treat the current alignment
     * (and anything derived from vehicle-frame heading) as lower
     * confidence while this is true.
     */
    val reducedConfidenceDueToRoll: Boolean = false,
)

class AlignmentEstimator(
    private val minSpeedForAlignmentMps: Float = DEFAULT_MIN_SPEED_MPS,
    private val maxYawRateForStraightRadPerSec: Float = DEFAULT_MAX_YAW_RATE_RADPS,
    private val minSamplesForAligned: Int = DEFAULT_MIN_SAMPLES,
    private val maxSpeedForStationaryMps: Float = DEFAULT_MAX_SPEED_FOR_STATIONARY_MPS,
    private val minSamplesForPitchRollAligned: Int = DEFAULT_MIN_SAMPLES,
    private val maxRollExcursionRad: Float = DEFAULT_MAX_ROLL_EXCURSION_RAD,
) {
    companion object {
        // Engineering defaults, not yet validated against a real test
        // drive (CLAUDE.md Rule 13) — GNSS course-over-ground is derived
        // from consecutive position fixes and gets noisy at low speed,
        // hence a deliberately conservative minimum here.
        const val DEFAULT_MIN_SPEED_MPS = 5.0f // ~18 km/h
        const val DEFAULT_MAX_YAW_RATE_RADPS = 0.1f // ~5.7 deg/s — "reasonably straight"
        const val DEFAULT_MIN_SAMPLES = 20
        // "Near-stationary" per PRD.md Section 15 — a parked/idling
        // vehicle's own roll/pitch relative to gravity is ~0, so the
        // device's roll/pitch at that moment IS the mounting offset.
        // Deliberately conservative (well below walking pace) since a
        // moving vehicle's own suspension/road-camber roll would
        // otherwise contaminate the baseline. Engineering default,
        // unvalidated against real "phone mounted at a tilt" data
        // (CLAUDE.md Rule 13).
        const val DEFAULT_MAX_SPEED_FOR_STATIONARY_MPS = 1.0f // ~3.6 km/h
        // "Large roll excursion" threshold for the motorcycle-lean flag —
        // an engineering default (CLAUDE.md Rule 13), not validated
        // against a real lean/pothole/remount data set.
        const val DEFAULT_MAX_ROLL_EXCURSION_RAD = 0.349f // ~20 degrees
    }

    private var sumSin = 0.0
    private var sumCos = 0.0
    private var sampleCount = 0

    private var sumSinRoll = 0.0
    private var sumCosRoll = 0.0
    private var sumSinPitch = 0.0
    private var sumCosPitch = 0.0
    private var pitchRollSampleCount = 0

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
        pitchRad: Float = 0f,
        rollRad: Float = 0f,
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

        // Roll/pitch mounting baseline: only accumulated while a real GNSS
        // fix confirms the vehicle is near-stationary (see
        // DEFAULT_MAX_SPEED_FOR_STATIONARY_MPS's doc for why a null fix
        // doesn't count — "no fix" isn't proof of "not moving").
        val nearStationaryWithFix = gnssSpeedMps != null && gnssSpeedMps <= maxSpeedForStationaryMps
        if (nearStationaryWithFix) {
            sumSinRoll += sin(rollRad.toDouble())
            sumCosRoll += cos(rollRad.toDouble())
            sumSinPitch += sin(pitchRad.toDouble())
            sumCosPitch += cos(pitchRad.toDouble())
            pitchRollSampleCount++
        }

        val rollOffsetRad = if (pitchRollSampleCount > 0) atan2(sumSinRoll, sumCosRoll).toFloat() else null
        val pitchOffsetRad = if (pitchRollSampleCount > 0) atan2(sumSinPitch, sumCosPitch).toFloat() else null
        val isPitchRollAligned = pitchRollSampleCount >= minSamplesForPitchRollAligned

        val reducedConfidenceDueToRoll = isPitchRollAligned && rollOffsetRad != null &&
            abs(wrapToPi(rollRad - rollOffsetRad)) > maxRollExcursionRad

        val yawOffsetRad = if (sampleCount > 0) atan2(sumSin, sumCos).toFloat() else null
        return AlignmentEstimate(
            yawOffsetRad = yawOffsetRad,
            sampleCount = sampleCount,
            isAligned = sampleCount >= minSamplesForAligned,
            rollOffsetRad = rollOffsetRad,
            pitchOffsetRad = pitchOffsetRad,
            pitchRollSampleCount = pitchRollSampleCount,
            isPitchRollAligned = isPitchRollAligned,
            reducedConfidenceDueToRoll = reducedConfidenceDueToRoll,
        )
    }

    /** Discards the accumulated estimate — invoked automatically by AlignmentRepository.kt on a detected "Phone Moved" event. */
    fun reset() {
        sumSin = 0.0
        sumCos = 0.0
        sampleCount = 0
        sumSinRoll = 0.0
        sumCosRoll = 0.0
        sumSinPitch = 0.0
        sumCosPitch = 0.0
        pitchRollSampleCount = 0
        lastAzimuthRad = null
        lastAzimuthTimestampNs = null
    }
}

/** Wraps a radian angle difference to (-pi, pi] — same wrap-boundary problem [YawRate] solves, needed again here for the roll-excursion diff. */
private fun wrapToPi(deltaRad: Float): Float {
    var d = deltaRad.toDouble()
    while (d > PI) d -= 2 * PI
    while (d <= -PI) d += 2 * PI
    return d.toFloat()
}
