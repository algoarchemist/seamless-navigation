package com.sih26168.idr.dr

import kotlin.math.cos
import kotlin.math.sin

/**
 * PRD.md Section 20's non-holonomic constraint: a road vehicle cannot
 * move sideways relative to its own heading, so the component of
 * velocity perpendicular to heading is suppressed toward zero.
 *
 * PRD.md Section 20 specifies this in VEHICLE frame with a `Turning`
 * exemption from the ML motion classifier. The VEHICLE-frame half still
 * doesn't exist — phone-to-vehicle yaw alignment
 * (`alignment/AlignmentEstimator.kt`, PRD.md Section 15) is wired into
 * the ML feature path only, not this physics path, so this remains a
 * deliberately simplified WORLD-frame stand-in: it uses the device's own
 * WORLD-frame heading (azimuth, from OrientationMath/OrientationSample)
 * as a proxy for vehicle heading, under the explicit assumption that the
 * phone's yaw tracks the vehicle's yaw (true for a phone mounted rigidly
 * to the vehicle body; false if it's loose, e.g. free-sliding in a cup
 * holder).
 *
 * The `Turning` exemption DOES now exist —
 * `motion/TurningDetector.kt`'s deterministic yaw-rate stand-in (same
 * "no labeled classifier data yet" precedent as
 * `motion/MotionStateClassifier.kt`) — but is applied by this class's one
 * caller (`dr/BaselineDeadReckoningRepository.kt`), which simply skips
 * calling [suppressLateralVelocity] on a tick flagged as turning, rather
 * than being threaded through this function's own signature. Keeps this
 * object a pure "always project onto heading" function, matching the
 * gating style already used for ZUPT/`walkingModeEnabled`-style
 * exemptions at that same call site.
 *
 * Pure Kotlin, no Android dependency, unit-testable on the plain JVM
 * (CLAUDE.md Rule 19).
 */
object NonHolonomicConstraint {

    /**
     * Projects (velocityEastMps, velocityNorthMps) onto the forward
     * direction implied by headingRad (Android azimuth convention: 0 =
     * north, positive = clockwise, see OrientationAngles), discarding
     * the perpendicular (lateral) component. Reverse motion (negative
     * forward speed) is preserved, not clamped to zero.
     */
    fun suppressLateralVelocity(
        velocityEastMps: Double,
        velocityNorthMps: Double,
        headingRad: Float,
    ): Pair<Double, Double> {
        val forwardEast = sin(headingRad.toDouble())
        val forwardNorth = cos(headingRad.toDouble())
        val forwardSpeedMps = velocityEastMps * forwardEast + velocityNorthMps * forwardNorth
        return Pair(forwardSpeedMps * forwardEast, forwardSpeedMps * forwardNorth)
    }
}
