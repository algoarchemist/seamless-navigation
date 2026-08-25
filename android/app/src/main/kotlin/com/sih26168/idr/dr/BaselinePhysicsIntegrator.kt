package com.sih26168.idr.dr

/**
 * A WORLD-frame (East, North) 2D dead-reckoning position/velocity
 * estimate, in meters and m/s respectively, relative to wherever
 * integration started (there is no GNSS fix tying this to a real
 * lat/lon yet — that fusion is a later slice).
 */
data class DeadReckoningState(
    val positionEastM: Double = 0.0,
    val positionNorthM: Double = 0.0,
    val velocityEastMps: Double = 0.0,
    val velocityNorthMps: Double = 0.0,
)

/**
 * Double-integrates WORLD-frame linear acceleration into velocity and
 * position via semi-implicit ("symplectic") Euler: velocity is updated
 * from acceleration first, then position is updated using the *new*
 * velocity — more numerically stable than naive explicit Euler while
 * still being a one-line-per-step, unit-testable, no-library method
 * appropriate for a 36-hour MVP (CLAUDE.md Rule 18).
 *
 * This is the PRD.md Section 32 "physics baseline" fallback path — a
 * deliberately naive reference, not a production estimator. Raw MEMS
 * accelerometer bias and noise are never corrected here (no ZUPT, no
 * bias estimation, no non-holonomic constraint — those belong to
 * Slice 5's Dead Reckoning state machine), so position error is
 * expected to grow rapidly and unboundedly over more than a few
 * seconds. That is the intended, honest behavior of a physics-only
 * baseline (CLAUDE.md Rule 3: ML is only justified once compared
 * against a real measurement of how bad this baseline actually is).
 */
class BaselinePhysicsIntegrator {

    private var state = DeadReckoningState()

    /**
     * Advances the estimate by one tick given the elapsed time and
     * WORLD-frame linear (gravity-removed) acceleration for that tick.
     * `dtSeconds <= 0.0` (first sample / clock reset, see
     * [com.sih26168.idr.sensors.SampleRate.secondsFromDeltaNs]) is a
     * no-op that returns the unchanged state, matching the same guard
     * convention used for Hz calculation in Slice 1.
     */
    fun update(dtSeconds: Double, linearAccelEastMps2: Double, linearAccelNorthMps2: Double): DeadReckoningState {
        if (dtSeconds <= 0.0) return state

        val newVelocityEastMps = state.velocityEastMps + linearAccelEastMps2 * dtSeconds
        val newVelocityNorthMps = state.velocityNorthMps + linearAccelNorthMps2 * dtSeconds
        val newPositionEastM = state.positionEastM + newVelocityEastMps * dtSeconds
        val newPositionNorthM = state.positionNorthM + newVelocityNorthMps * dtSeconds

        state = DeadReckoningState(
            positionEastM = newPositionEastM,
            positionNorthM = newPositionNorthM,
            velocityEastMps = newVelocityEastMps,
            velocityNorthMps = newVelocityNorthMps,
        )
        return state
    }

    fun currentState(): DeadReckoningState = state

    /** Zeroes position and velocity — used when (re)starting a DR run. */
    fun reset() {
        state = DeadReckoningState()
    }
}
