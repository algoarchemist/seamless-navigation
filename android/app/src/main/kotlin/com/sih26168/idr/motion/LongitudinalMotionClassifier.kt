package com.sih26168.idr.motion

/**
 * [isAccelerating] and [isBraking] are mutually exclusive (opposite-sign
 * thresholds on the same scalar) and both false for anything in between
 * ("Cruising"/"Moving" territory — see [MotionStateClassifier] and
 * `ui/screens/StatusOverlayContent.kt`'s motion-label priority chain for
 * how those are resolved instead).
 */
data class LongitudinalMotionClassification(
    val isAccelerating: Boolean,
    val isBraking: Boolean,
)

/**
 * A deterministic stand-in for PRD.md Section 14's `Accelerating`/
 * `Braking` classes — same "no labeled classifier data yet" precedent as
 * [MotionStateClassifier]/[PotholeShockDetector]/[TurningDetector]/
 * [PhoneMovedDetector]. A sign/magnitude threshold on vehicle-frame
 * FORWARD acceleration: positive and large enough is Accelerating,
 * negative and large enough (in magnitude) is Braking.
 *
 * ML-path only, same precedent [MotionStateClassifier] already
 * establishes (CLAUDE.md Rule 3: the physics-only baseline stays
 * untouched by any ML-derived signal) — this reads
 * `ml/MlVelocityRepository.kt`'s already-computed, alignment-corrected
 * `accelForwardMps2` (the same vehicle-frame forward-acceleration feature
 * that also feeds the ONNX model), rather than reimplementing a separate
 * vehicle-frame projection for the physics path, which has no alignment-
 * corrected forward/lateral split at all.
 *
 * Pure Kotlin, no Android dependency, unit-testable on the plain JVM
 * (CLAUDE.md Rule 19).
 *
 * UPDATE (2026-09-03, hysteresis — REAL FINDING: on-device shake test
 * showed the Accelerating/Braking display label flipping every ~100ms
 * tick while just hand-vibrating a STATIONARY phone): this class used to
 * be fully stateless — a single sample's forward-accel crossing
 * +-1.0 m/s^2 flipped the label immediately, no dwell at all, so ordinary
 * vibration (which easily produces brief accel spikes past that on this
 * project's noisy real sensor data — see `motion/StopEventClassifier.kt`'s
 * own doc for the measured accel noise floor) made it flicker every tick.
 * This was ORIGINALLY a deliberate choice (see the removed doc note:
 * "only drives a display label, not a correction that would misfire badly
 * on one noisy sample") — but a label that flickers every tick during
 * ordinary vibration is itself a bad user-visible experience, so it now
 * gets the SAME two-threshold hysteresis shape [TurningDetector] and
 * `dr/StationaryDetector.kt` already use: [enterAccelMps2] (unchanged
 * value, 1.0 m/s^2 ~= 0.1g) must sustain for >= [enterDwellMs] before
 * flipping to Accelerating/Braking; once in either state, forward-accel
 * must fall back inside +-[exitAccelMps2] (roughly half of enter,
 * matching [TurningDetector]'s enter/exit ratio) and sustain there for
 * >= [exitDwellMs] before returning to neutral. Both dwell constants are
 * engineering defaults, not yet validated against real labeled
 * Accelerating/Braking data (CLAUDE.md Rule 13) — same disclosed status
 * every other threshold in this codebase already carries.
 */
class LongitudinalMotionClassifier(
    private val enterAccelMps2: Float = DEFAULT_ENTER_ACCEL_MPS2,
    private val exitAccelMps2: Float = DEFAULT_EXIT_ACCEL_MPS2,
    private val enterDwellMs: Long = DEFAULT_ENTER_DWELL_MS,
    private val exitDwellMs: Long = DEFAULT_EXIT_DWELL_MS,
) {
    companion object {
        // Engineering default, unvalidated against real labeled
        // Accelerating/Braking data (CLAUDE.md Rule 13) — roughly 0.1g,
        // meant to clear ordinary road-noise-scale forward-accel jitter
        // while still catching a deliberate accelerator/brake input.
        // Unchanged from the original, pre-hysteresis threshold.
        const val DEFAULT_ENTER_ACCEL_MPS2 = 1.0f

        /** Roughly half of [DEFAULT_ENTER_ACCEL_MPS2] — forward-accel must
         * fall back THIS far toward zero, not merely inside +-1.0 m/s^2,
         * before Accelerating/Braking is considered over. Same
         * enter/exit-ratio idea as [TurningDetector]'s hysteresis band. */
        const val DEFAULT_EXIT_ACCEL_MPS2 = 0.5f

        /** How long forward-accel must sustain past [DEFAULT_ENTER_ACCEL_MPS2]
         * before Accelerating/Braking flips true. Short relative to a real
         * accelerator/brake input (which lasts far longer) but long enough
         * that one noisy ~100ms tick (this project's ~10 Hz sensor rate)
         * can't trigger it alone. */
        const val DEFAULT_ENTER_DWELL_MS = 200L

        /** How long forward-accel must sustain back inside
         * +-[DEFAULT_EXIT_ACCEL_MPS2] before returning to neutral —
         * matches [TurningDetector.DEFAULT_EXIT_DWELL_MS] for consistency. */
        const val DEFAULT_EXIT_DWELL_MS = 300L
    }

    private enum class State { NEUTRAL, ACCELERATING, BRAKING }

    private var state = State.NEUTRAL
    private var enterAccelStreakStartMs: Long? = null
    private var enterBrakeStreakStartMs: Long? = null
    private var exitStreakStartMs: Long? = null

    /**
     * @param nowMs monotonic clock, ms — same boot-time family every other
     *   dwell-tracking detector in this codebase uses (CLAUDE.md Rule 9/14).
     * @param accelForwardMps2 vehicle-frame forward acceleration for this
     *   tick (unchanged input/meaning from before this class was stateful).
     */
    fun classify(nowMs: Long, accelForwardMps2: Float): LongitudinalMotionClassification {
        when (state) {
            State.NEUTRAL -> {
                if (accelForwardMps2 >= enterAccelMps2) {
                    val streakStart = enterAccelStreakStartMs ?: nowMs.also { enterAccelStreakStartMs = it }
                    if (nowMs - streakStart >= enterDwellMs) {
                        state = State.ACCELERATING
                        enterAccelStreakStartMs = null
                    }
                } else {
                    enterAccelStreakStartMs = null
                }
                if (accelForwardMps2 <= -enterAccelMps2) {
                    val streakStart = enterBrakeStreakStartMs ?: nowMs.also { enterBrakeStreakStartMs = it }
                    if (nowMs - streakStart >= enterDwellMs) {
                        state = State.BRAKING
                        enterBrakeStreakStartMs = null
                    }
                } else {
                    enterBrakeStreakStartMs = null
                }
            }
            State.ACCELERATING -> {
                if (accelForwardMps2 < exitAccelMps2) {
                    val streakStart = exitStreakStartMs ?: nowMs.also { exitStreakStartMs = it }
                    if (nowMs - streakStart >= exitDwellMs) {
                        state = State.NEUTRAL
                        exitStreakStartMs = null
                    }
                } else {
                    exitStreakStartMs = null
                }
            }
            State.BRAKING -> {
                if (accelForwardMps2 > -exitAccelMps2) {
                    val streakStart = exitStreakStartMs ?: nowMs.also { exitStreakStartMs = it }
                    if (nowMs - streakStart >= exitDwellMs) {
                        state = State.NEUTRAL
                        exitStreakStartMs = null
                    }
                } else {
                    exitStreakStartMs = null
                }
            }
        }
        return LongitudinalMotionClassification(
            isAccelerating = state == State.ACCELERATING,
            isBraking = state == State.BRAKING,
        )
    }

    /** Discards dwell/state history — used when (re)starting a DR run,
     * same convention as every other stateful detector's `reset()` in
     * this codebase (e.g. [TurningDetector.reset]). */
    fun reset() {
        state = State.NEUTRAL
        enterAccelStreakStartMs = null
        enterBrakeStreakStartMs = null
        exitStreakStartMs = null
    }
}
