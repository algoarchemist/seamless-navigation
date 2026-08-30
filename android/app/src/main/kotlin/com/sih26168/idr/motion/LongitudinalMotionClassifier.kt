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
 * [PhoneMovedDetector]. A simple sign/magnitude threshold on vehicle-frame
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
 * (CLAUDE.md Rule 19). Stateless — no dwell/hysteresis, unlike
 * [TurningDetector]/[PhoneMovedDetector], since this only drives a
 * display label (PRD.md Section 14: "context for the state machine...
 * and non-holonomic constraint"), not a correction that would misfire
 * badly on one noisy sample.
 */
class LongitudinalMotionClassifier(
    private val minLongitudinalAccelMps2: Float = DEFAULT_MIN_LONGITUDINAL_ACCEL_MPS2,
) {
    companion object {
        // Engineering default, unvalidated against real labeled
        // Accelerating/Braking data (CLAUDE.md Rule 13) — roughly 0.1g,
        // meant to clear ordinary road-noise-scale forward-accel jitter
        // while still catching a deliberate accelerator/brake input.
        const val DEFAULT_MIN_LONGITUDINAL_ACCEL_MPS2 = 1.0f
    }

    fun classify(accelForwardMps2: Float): LongitudinalMotionClassification = LongitudinalMotionClassification(
        isAccelerating = accelForwardMps2 >= minLongitudinalAccelMps2,
        isBraking = accelForwardMps2 <= -minLongitudinalAccelMps2,
    )
}
