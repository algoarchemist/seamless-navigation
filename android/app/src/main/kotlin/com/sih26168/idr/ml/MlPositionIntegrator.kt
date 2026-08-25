package com.sih26168.idr.ml

import kotlin.math.cos
import kotlin.math.sin

/** WORLD-frame (East, North) position, meters, relative to wherever integration started. */
data class MlDeadReckoningState(
    val positionEastM: Double = 0.0,
    val positionNorthM: Double = 0.0,
)

/**
 * Integrates the ML-predicted forward speed directly into a 2D
 * WORLD-frame position estimate, per PRD.md Section 16's
 * `dx[t] = v[t]*cos(heading[t])*dt, dy[t] = v[t]*sin(heading[t])*dt`
 * — using ML velocity (`v[t] = VelocityModel(features[t])`) instead of
 * the physics-fallback path dr/BaselinePhysicsIntegrator.kt implements.
 *
 * Unlike BaselinePhysicsIntegrator, there is no acceleration-integration
 * state/momentum here: each tick's position delta depends only on THAT
 * tick's model-predicted speed and heading, not on velocity carried
 * over from the previous tick — the model predicts speed directly
 * rather than us inferring it by double-integrating acceleration.
 *
 * Non-holonomic constraint (PRD.md Section 16 "Gated by: ... non-
 * holonomic lateral suppression") is satisfied BY CONSTRUCTION here,
 * not as a separate correction step: the model only ever predicts a
 * scalar FORWARD speed (no lateral component is ever computed), so
 * there is no lateral velocity to suppress in the first place — unlike
 * BaselinePhysicsIntegrator, which integrates a full 2D acceleration
 * vector and needs dr/NonHolonomicConstraint.kt to remove spurious
 * lateral drift after the fact.
 *
 * ZUPT (PRD.md Section 16 "Gated by: ZUPT when Stationary") is still
 * applied explicitly by the caller via [update]'s isStationary
 * parameter (from dr/StationaryDetector.kt) — the model's own
 * predictions during genuine stillness are close to, but not exactly,
 * zero (observed live: 0.11-0.74 m/s while stationary), which would
 * otherwise accumulate slow but real position drift over time.
 */
class MlPositionIntegrator {

    private var state = MlDeadReckoningState()

    /**
     * @param headingRad vehicle heading (device azimuth corrected by
     *   AlignmentEstimator's yaw offset — the SAME heading
     *   MlVelocityRepository used to compute the forward-axis features
     *   this velocity was predicted from; using a different heading
     *   here would silently misdirect the position update relative to
     *   what the model actually predicted).
     */
    fun update(dtSeconds: Double, velocityMps: Float, headingRad: Float, isStationary: Boolean): MlDeadReckoningState {
        if (dtSeconds <= 0.0) return state

        val effectiveVelocityMps = if (isStationary) 0.0 else velocityMps.toDouble()
        val deltaEastM = effectiveVelocityMps * sin(headingRad.toDouble()) * dtSeconds
        val deltaNorthM = effectiveVelocityMps * cos(headingRad.toDouble()) * dtSeconds

        state = MlDeadReckoningState(
            positionEastM = state.positionEastM + deltaEastM,
            positionNorthM = state.positionNorthM + deltaNorthM,
        )
        return state
    }

    fun currentState(): MlDeadReckoningState = state

    fun reset() {
        state = MlDeadReckoningState()
    }
}
