package com.sih26168.idr.motion

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A deterministic stand-in for PRD.md Section 14's `Phone Moved` class —
 * same "no labeled classifier data yet" precedent as
 * [MotionStateClassifier]/[PotholeShockDetector]/[TurningDetector]. PRD.md
 * Section 15's own basis for phone-to-vehicle alignment is the phone's
 * fixed mounting orientation (pitch/roll from gravity, yaw from GNSS
 * course) — if that mounting changes (picked up, refitted, knocked loose),
 * the previously-established alignment is no longer valid. This detects
 * exactly that: a SUSTAINED change in the device's own WORLD-frame
 * pitch/roll (from [com.sih26168.idr.sensors.OrientationSample], already
 * gravity-referenced by the rotation-vector sensor) relative to a
 * remembered reference orientation.
 *
 * Deliberately requires the deviation to persist for
 * [minSustainedDeviationMs] (same hysteresis principle CLAUDE.md Rule 16
 * already requires for the GNSS state machine, applied here so a single
 * pothole jolt or road-vibration spike doesn't falsely read as a
 * remounted phone) rather than firing on one noisy sample.
 *
 * Pure Kotlin, no Android dependency, unit-testable on the plain JVM
 * (CLAUDE.md Rule 19).
 */
class PhoneMovedDetector(
    private val pitchRollChangeThresholdRad: Float = DEFAULT_PITCH_ROLL_CHANGE_THRESHOLD_RAD,
    private val minSustainedDeviationMs: Long = DEFAULT_MIN_SUSTAINED_DEVIATION_MS,
) {
    companion object {
        // Engineering defaults, unvalidated against real "phone picked up
        // mid-drive" data (CLAUDE.md Rule 13) — ~15 degrees is meant to
        // clear normal road vibration/pothole jolts (which perturb the
        // rotation-vector estimate only briefly and slightly) while still
        // catching an actual remount (typically a large, deliberate
        // reorientation).
        const val DEFAULT_PITCH_ROLL_CHANGE_THRESHOLD_RAD = 0.26f // ~15 degrees
        const val DEFAULT_MIN_SUSTAINED_DEVIATION_MS = 1000L
    }

    private var referencePitchRad: Float? = null
    private var referenceRollRad: Float? = null
    private var deviatingStreakStartMs: Long? = null

    /**
     * Call once per orientation tick.
     *
     * @return true on the tick a sustained pitch/roll deviation from the
     *   remembered reference first crosses [minSustainedDeviationMs] — a
     *   one-shot edge, not held true on every subsequent tick, since the
     *   reference is immediately updated to this new orientation so the
     *   NEXT comparison starts fresh from "wherever the phone is now."
     *   Also false on the very first call ever (nothing to compare
     *   against yet — that call just establishes the initial reference).
     */
    fun evaluate(nowMs: Long, pitchRad: Float, rollRad: Float): Boolean {
        val refPitch = referencePitchRad
        val refRoll = referenceRollRad
        if (refPitch == null || refRoll == null) {
            referencePitchRad = pitchRad
            referenceRollRad = rollRad
            return false
        }

        // Pitch is bounded to [-pi/2, pi/2] (OrientationAngles' own
        // convention) so a plain difference never wraps. Roll ranges over
        // the full (-pi, pi], so its difference is taken circularly
        // (atan2(sin(delta), cos(delta))) the same way YawRate.kt unwraps
        // azimuth deltas, so a phone resting near the +-180 degree roll
        // boundary doesn't read as a huge false deviation.
        val pitchDeltaRad = abs(pitchRad - refPitch)
        val rawRollDeltaRad = (rollRad - refRoll).toDouble()
        val rollDeltaRad = abs(atan2(sin(rawRollDeltaRad), cos(rawRollDeltaRad))).toFloat()
        val deviating = pitchDeltaRad > pitchRollChangeThresholdRad || rollDeltaRad > pitchRollChangeThresholdRad

        if (!deviating) {
            deviatingStreakStartMs = null
            return false
        }

        val streakStart = deviatingStreakStartMs ?: nowMs.also { deviatingStreakStartMs = it }
        val moved = nowMs - streakStart >= minSustainedDeviationMs
        if (moved) {
            // The phone has settled into ITS NEW orientation — that becomes
            // the new reference, not the old one, so this doesn't keep
            // firing every tick for as long as the phone stays in its new
            // (equally legitimate, just different) resting position.
            referencePitchRad = pitchRad
            referenceRollRad = rollRad
            deviatingStreakStartMs = null
        }
        return moved
    }

    /** Discards the remembered reference orientation — used when (re)starting alignment tracking. */
    fun reset() {
        referencePitchRad = null
        referenceRollRad = null
        deviatingStreakStartMs = null
    }
}
