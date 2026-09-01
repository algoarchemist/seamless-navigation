package com.sih26168.idr.motion

import kotlin.math.pow

/**
 * PRD.md FR12 (Round 2 addition, 2026-08-28): barometer-based floor/
 * level-change detection, directly supporting the multi-level-parking
 * scenario named in the SIH problem statement (Section 2/3) — nothing in
 * this codebase used altitude at all before this. Pure Kotlin, no
 * Android dependency, unit-testable on the plain JVM (CLAUDE.md Rule 19).
 *
 * Deterministic threshold detector, same "physics-only stand-in"
 * precedent as [com.sih26168.idr.dr.StationaryDetector]/
 * [PotholeShockDetector] — there is no PRD Section 14 class for this, it
 * satisfies its own FR directly, not a motion-classifier effect.
 *
 * Method: converts absolute pressure to RELATIVE altitude against a
 * tracked baseline pressure, via the international barometric formula
 * (a standard atmospheric-physics approximation, not a measured/invented
 * figure — CLAUDE.md Rule 13's "no invented numbers" concerns claimed
 * BENCHMARKS, not textbook physics constants):
 *
 *   h = 44330.0 * (1.0 - (P / P0)^(1/5.255))
 *
 * where P0 is the baseline pressure and P the current reading (any
 * consistent pressure unit works — the ratio is unit-independent, this
 * codebase uses hPa throughout). Only accurate for modest altitude
 * changes near the baseline (assumes the ICAO standard atmosphere's
 * fixed temperature/lapse-rate model) — entirely adequate for a few
 * floors of a parking structure (tens of meters), not claimed accurate
 * at kilometre scale.
 *
 * Sustained-crossing hysteresis (same dwell principle as
 * StationaryDetector/GnssOutageDetector, CLAUDE.md Rule 16's spirit): a
 * floor change is only signaled once the relative altitude has been
 * beyond [floorHeightThresholdM] continuously for [minDwellMs] — a brief
 * pressure blip (e.g. a car door slamming, momentarily changing cabin
 * pressure) must not flip this. Once signaled, the baseline RE-ANCHORS
 * to the current pressure, so the NEXT floor change (e.g. descending
 * multiple levels in a row) can also be detected, not just the first.
 */
class FloorChangeDetector(
    private val floorHeightThresholdM: Float = DEFAULT_FLOOR_HEIGHT_THRESHOLD_M,
    private val minDwellMs: Long = DEFAULT_MIN_DWELL_MS,
) {
    companion object {
        // Typical parking-structure floor-to-floor height is roughly 3m;
        // set somewhat below that so a genuine floor change reliably
        // crosses it, while staying comfortably above raw consumer-MEMS
        // barometric noise. Engineering default, not yet validated
        // against a real multi-level-parking test drive (CLAUDE.md
        // Rule 13).
        const val DEFAULT_FLOOR_HEIGHT_THRESHOLD_M = 2.5f
        const val DEFAULT_MIN_DWELL_MS = 2_000L

        /** The barometric formula itself — exposed as a pure function so it can be tested independently of the dwell/hysteresis logic above. */
        fun relativeAltitudeMeters(baselinePressureHpa: Float, currentPressureHpa: Float): Float =
            (44330.0 * (1.0 - (currentPressureHpa / baselinePressureHpa).toDouble().pow(1.0 / 5.255))).toFloat()
    }

    /**
     * @property relativeAltitudeM live altitude relative to the current baseline, meters (positive = up).
     * @property floorChangeDetected true only on the tick a sustained crossing is confirmed.
     * @property floorDelta +1 = went up a floor, -1 = went down a floor, 0 = no change signaled this tick.
     */
    data class Result(
        val relativeAltitudeM: Float,
        val floorChangeDetected: Boolean,
        val floorDelta: Int,
    )

    private var baselinePressureHpa: Float? = null
    private var streakStartMs: Long? = null
    private var streakDirection: Int = 0

    /**
     * @param nowMs a monotonic clock in milliseconds — callers use sensor
     *   boot-time timestamps (converted to ms), same convention as
     *   StationaryDetector, since only relative durations matter here.
     */
    fun evaluate(nowMs: Long, pressureHpa: Float): Result {
        val baseline = baselinePressureHpa
        if (baseline == null) {
            // First-ever reading this run establishes the baseline —
            // nothing to compare against yet.
            baselinePressureHpa = pressureHpa
            return Result(relativeAltitudeM = 0f, floorChangeDetected = false, floorDelta = 0)
        }

        val relativeAltitudeM = relativeAltitudeMeters(baseline, pressureHpa)
        val direction = when {
            relativeAltitudeM >= floorHeightThresholdM -> 1
            relativeAltitudeM <= -floorHeightThresholdM -> -1
            else -> 0
        }

        if (direction == 0 || direction != streakDirection) {
            streakStartMs = if (direction != 0) nowMs else null
            streakDirection = direction
            return Result(relativeAltitudeM = relativeAltitudeM, floorChangeDetected = false, floorDelta = 0)
        }

        val streakStart = streakStartMs ?: nowMs.also { streakStartMs = it }
        if (nowMs - streakStart < minDwellMs) {
            return Result(relativeAltitudeM = relativeAltitudeM, floorChangeDetected = false, floorDelta = 0)
        }

        // Sustained crossing confirmed — re-anchor the baseline to THIS
        // pressure so a subsequent floor change can also be detected, and
        // reset the streak tracking.
        baselinePressureHpa = pressureHpa
        streakStartMs = null
        streakDirection = 0
        return Result(relativeAltitudeM = 0f, floorChangeDetected = true, floorDelta = direction)
    }

    fun reset() {
        baselinePressureHpa = null
        streakStartMs = null
        streakDirection = 0
    }
}
