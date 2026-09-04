package com.sih26168.idr.motion

/**
 * A deterministic answer to exactly one question: given that
 * [dr.StationaryDetector] says the phone LOOKS physically still (low
 * accel/gyro magnitude), is it actually stationary, or smoothly cruising
 * at constant velocity? [isStationary] and [isCruising] are mutually
 * exclusive; both are false if the phone isn't even physically still by
 * StationaryDetector's own definition (no ambiguity to resolve there).
 */
data class MotionClassification(
    val isStationary: Boolean,
    val isCruising: Boolean,
)

/**
 * PRD.md Section 14's Motion Classification names `Stationary` and
 * `Cruising` as two of its 8 classes, meant to come from a trained
 * classifier with a reported confusion matrix (Section 14/28). No
 * labeled training data exists yet (docs/PROJECT_MAP.md tracks this as
 * blocking train_motion_classifier.py), so per CLAUDE.md Rule 13 no
 * trained-classifier accuracy claim can be made. This is a DETERMINISTIC
 * STAND-IN for exactly the Stationary-vs-Cruising distinction — same
 * precedent as [com.sih26168.idr.dr.StationaryDetector] itself standing
 * in for the Stationary class's ZUPT effect before any ML classifier
 * existed.
 *
 * The problem this solves: StationaryDetector's own doc already names
 * its honest limitation — "constant-velocity straight-line motion also
 * produces near-zero acceleration/gyro... cannot distinguish 'truly at
 * rest' from 'smoothly coasting.'" That ambiguity is a genuine
 * accelerometer/gyro-only sensor-physics limit (zero net force feels the
 * same whether the phone is parked or gliding at a constant speed) — no
 * threshold on accel/gyro alone can ever resolve it. It CAN be resolved
 * with an independent signal: the ML velocity model's RAW prediction
 * (ml/VelocityModel.kt), already computed every tick regardless of GNSS
 * mode. If accel/gyro look quiet BUT the model still predicts meaningful
 * forward speed, that's real corroborating evidence of cruising, not
 * true rest.
 *
 * Explicitly NOT the PRD Section 14 classifier: this reuses an
 * already-trained REGRESSION model's raw output as a corroborating
 * signal for one binary decision, not a trained classifier over the
 * full 8-class taxonomy, and has not been validated against any
 * real cruising ground truth (no labeled data exists yet).
 */
class MotionStateClassifier(
    private val minCruisingSpeedMps: Float = DEFAULT_MIN_CRUISING_SPEED_MPS,
) {
    companion object {
        // Engineering default, unvalidated (CLAUDE.md Rule 13). Deliberately
        // low — this only needs to catch "the model thinks we're clearly
        // still moving," not distinguish speeds precisely; StationaryDetector
        // has already ruled out any real acceleration/gyro activity by the
        // time this is consulted.
        const val DEFAULT_MIN_CRUISING_SPEED_MPS = 1.0f
    }

    fun classify(physicallyStill: Boolean, rawPredictedVelocityMps: Float): MotionClassification {
        if (!physicallyStill) {
            return MotionClassification(isStationary = false, isCruising = false)
        }
        val cruising = rawPredictedVelocityMps >= minCruisingSpeedMps
        return MotionClassification(isStationary = !cruising, isCruising = cruising)
    }

    /**
     * Unconditional "does the raw model think we're still really moving"
     * check — the SAME threshold [classify] uses, but WITHOUT its
     * `physicallyStill` gate.
     *
     * REAL BUG FIX (2026-09-04, bugs.jpeg code review,
     * ml/MlVelocityRepository.kt's ZUPT gate): [classify] is deliberately
     * scoped to answer "given that StationaryDetector says the phone LOOKS
     * physically still, is it actually stationary or cruising" — a
     * narrower question than what the ZUPT override at that call site
     * actually needs. `motion/StopEventClassifier.kt`'s SUDDEN_STOP and
     * HARDWARE_CONFIRMED_IDLE fast paths can both set `shouldApplyZupt =
     * true` precisely when `dwellConfirmedStationary` (the `physicallyStill`
     * input) is FALSE — that's the whole point of those paths, catching
     * real stops the plain accel/gyro dwell check misses. But
     * [classify]`(physicallyStill = false, ...)` unconditionally returns
     * `isCruising = false`, regardless of what the raw model predicts —
     * so the ZUPT gate's "don't ZUPT if the model still predicts real
     * speed" safety net silently never engaged in exactly the two contexts
     * StopEventClassifier was built to widen ZUPT into. This method
     * restores that check, independent of dwell confirmation, so the
     * override can still fire during a fast-path ZUPT decision.
     */
    fun predictsRealMotion(rawPredictedVelocityMps: Float): Boolean =
        rawPredictedVelocityMps >= minCruisingSpeedMps
}
