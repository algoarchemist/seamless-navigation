package com.sih26168.idr.motion

import kotlin.math.abs

/**
 * PRD.md Section 14's `Pothole` class downstream effect, stated
 * precisely: "discount the acceleration sample(s) so a vertical/shock
 * spike doesn't get misread as forward acceleration." Implemented as a
 * DETERMINISTIC threshold on the magnitude of the WORLD-frame vertical
 * (Up) linear-acceleration component (dr/WorldFrameAcceleration.kt's
 * existing output, index 2) — NOT the trained PRD Section 14 classifier
 * (same "physics-only stand-in" precedent as
 * [com.sih26168.idr.dr.StationaryDetector] and
 * [com.sih26168.idr.motion.MotionStateClassifier]).
 *
 * HONEST LIMITATION (CLAUDE.md Rule 13): this has not been validated
 * against real pothole data — none exists yet (same gap blocking
 * train_motion_classifier.py). It proves the DISCOUNTING MECHANISM works
 * on a large-enough vertical spike; it does NOT prove the threshold
 * correctly distinguishes a real pothole from e.g. a speed bump, a curb,
 * or the phone being dropped/bumped. That validation needs real,
 * labeled drive data.
 *
 * UPDATE (2026-08-26, real outdoor walking test): the original 4.0 m/s^2
 * default false-positived constantly on ordinary walking gait -- a phone
 * carried by hand while walking routinely produces 5-10+ m/s^2 vertical
 * bounce per step, well above what should be needed to flag a genuine
 * shock. Raised to 15.0 m/s^2 so casual walking motion no longer trips
 * it. STILL an unvalidated engineering default (CLAUDE.md Rule 13): this
 * is now tuned to reject one real observed false-positive source
 * (walking), not confirmed against a real in-vehicle pothole strike,
 * which remains blocked on real labeled drive data same as before.
 */
class PotholeShockDetector(
    private val verticalShockThresholdMps2: Float = DEFAULT_VERTICAL_SHOCK_THRESHOLD_MPS2,
) {
    companion object {
        // Engineering default, unvalidated (CLAUDE.md Rule 13). Normal
        // driving vertical noise after gravity removal is typically well
        // under 2 m/s^2 — set well above that to only catch a genuine
        // shock, not routine road texture.
        const val DEFAULT_VERTICAL_SHOCK_THRESHOLD_MPS2 = 15.0f
    }

    /** Magnitude-based — a shock can push the Up component either direction (into or out of the seat). */
    fun isShock(verticalLinearAccelMps2: Float): Boolean =
        abs(verticalLinearAccelMps2) >= verticalShockThresholdMps2
}
