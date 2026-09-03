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
 *
 * UPDATE (2026-09-03, hysteresis — REAL FINDING: on-device shake test
 * showed the display label flipping Turning/Moving every ~100ms tick
 * while just hand-vibrating a STATIONARY phone): the original single
 * fixed-threshold check had NO dwell at all — one noisy yaw-rate sample
 * above [enterYawRateRadPerSec] flipped `isTurning` true immediately, one
 * sample below flipped it false immediately, so ordinary vibration (which
 * easily produces brief gyro/azimuth-derived yaw-rate spikes past 0.15
 * rad/s) made it flicker every tick. This affects more than the display
 * label — the SAME `isTurning` gates
 * `dr/BaselineDeadReckoningRepository.kt`'s non-holonomic constraint
 * (CLAUDE.md Rule 16 requires hysteresis — separate enter/exit
 * thresholds, minimum dwell time — for exactly this kind of state, which
 * this class had never actually had). Now a proper two-threshold
 * hysteresis band, same shape [dr/StationaryDetector.kt] and
 * `gnss/GnssOutageDetector.kt` already use elsewhere in this codebase:
 * [enterYawRateRadPerSec] (unchanged value, 0.15 rad/s) must sustain for
 * >= [enterDwellMs] before flipping Turning true; once true, the yaw rate
 * must drop to/below the LOWER [exitYawRateRadPerSec] (roughly half of
 * enter, opening a dead zone between the two so noise sitting right at
 * the boundary can't flap the flag) and sustain there for >= [exitDwellMs]
 * before flipping back to false. Both dwell constants are short relative
 * to a real turn's duration (which comfortably exceeds them) but long
 * enough that a single ~100ms noisy tick (this project's ~10 Hz sensor
 * rate) can never flip the result alone — same engineering-default,
 * not-yet-validated-against-a-real-drive status every other threshold in
 * this codebase already discloses (CLAUDE.md Rule 13).
 */
class TurningDetector(
    private val enterYawRateRadPerSec: Float = DEFAULT_ENTER_YAW_RATE_RADPS,
    private val exitYawRateRadPerSec: Float = DEFAULT_EXIT_YAW_RATE_RADPS,
    private val enterDwellMs: Long = DEFAULT_ENTER_DWELL_MS,
    private val exitDwellMs: Long = DEFAULT_EXIT_DWELL_MS,
) {
    companion object {
        // Engineering default, unvalidated against a real outdoor test drive
        // (CLAUDE.md Rule 13). Deliberately set ABOVE
        // AlignmentEstimator.DEFAULT_MAX_YAW_RATE_RADPS (0.1 rad/s, its own
        // "reasonably straight" ceiling) so there is a small dead zone
        // between "straight" and "turning" rather than the two thresholds
        // touching exactly at the same value. Unchanged from the original,
        // pre-hysteresis threshold.
        const val DEFAULT_ENTER_YAW_RATE_RADPS = 0.15f // ~8.6 deg/s

        /** Roughly half of [DEFAULT_ENTER_YAW_RATE_RADPS] — must drop THIS
         * far, not merely back below the enter threshold, before a turn is
         * considered over. The gap between the two is what stops yaw-rate
         * noise sitting near 0.15 rad/s from flapping the flag on its own. */
        const val DEFAULT_EXIT_YAW_RATE_RADPS = 0.08f

        /** How long yaw rate must sustain >= [DEFAULT_ENTER_YAW_RATE_RADPS]
         * before Turning flips true. Short relative to a real turn (which
         * lasts far longer) but long enough that one noisy ~100ms tick
         * (this project's ~10 Hz sensor rate) can't trigger it alone. */
        const val DEFAULT_ENTER_DWELL_MS = 200L

        /** How long yaw rate must sustain <= [DEFAULT_EXIT_YAW_RATE_RADPS]
         * before Turning flips back to false — matches
         * [StationaryDetector.DEFAULT_MIN_STATIONARY_DWELL_MS] (300ms) for
         * consistency with this codebase's other dwell-gated detectors. */
        const val DEFAULT_EXIT_DWELL_MS = 300L
    }

    private var lastAzimuthRad: Float? = null
    private var lastTimestampNs: Long? = null
    private var isTurning: Boolean = false
    private var aboveEnterStreakStartMs: Long? = null
    private var belowExitStreakStartMs: Long? = null

    /**
     * Call once per orientation tick, WORLD-frame device azimuth (see
     * [com.sih26168.idr.sensors.OrientationSample.azimuthRad]).
     *
     * @return the CURRENT hysteresis-confirmed Turning state (not simply
     *   "was this one sample above threshold") — false on the very first
     *   call (no previous sample to diff against — same "not enough
     *   information yet" convention [YawRate] itself already uses) and
     *   whenever neither the enter nor exit dwell has completed yet.
     */
    fun evaluate(nowNs: Long, azimuthRad: Float): Boolean {
        val yawRateRadPerSec = YawRate.radPerSecond(lastAzimuthRad, lastTimestampNs, azimuthRad, nowNs)
        lastAzimuthRad = azimuthRad
        lastTimestampNs = nowNs
        if (yawRateRadPerSec == null) return isTurning // first sample / clock reset -- keep prior state (starts false)

        // Boot-time ms, same clock family as every other dwell-tracking
        // detector in this codebase (CLAUDE.md Rule 9/14).
        val nowMs = nowNs / 1_000_000L
        val magnitudeRadPerSec = abs(yawRateRadPerSec)

        if (!isTurning) {
            if (magnitudeRadPerSec >= enterYawRateRadPerSec) {
                val streakStart = aboveEnterStreakStartMs ?: nowMs.also { aboveEnterStreakStartMs = it }
                if (nowMs - streakStart >= enterDwellMs) {
                    isTurning = true
                    belowExitStreakStartMs = null
                }
            } else {
                aboveEnterStreakStartMs = null
            }
        } else {
            if (magnitudeRadPerSec <= exitYawRateRadPerSec) {
                val streakStart = belowExitStreakStartMs ?: nowMs.also { belowExitStreakStartMs = it }
                if (nowMs - streakStart >= exitDwellMs) {
                    isTurning = false
                    aboveEnterStreakStartMs = null
                }
            } else {
                belowExitStreakStartMs = null
            }
        }
        return isTurning
    }

    /** Discards tracked azimuth/timestamp/dwell history — used when
     * (re)starting a DR run. */
    fun reset() {
        lastAzimuthRad = null
        lastTimestampNs = null
        isTurning = false
        aboveEnterStreakStartMs = null
        belowExitStreakStartMs = null
    }
}
