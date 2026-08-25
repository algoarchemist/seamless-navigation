package com.sih26168.idr.dr

import kotlin.math.cos
import kotlin.math.sin

/**
 * PRD.md Section 20's non-holonomic constraint: a road vehicle cannot
 * move sideways relative to its own heading, so the component of
 * velocity perpendicular to heading is suppressed toward zero.
 *
 * PRD.md Section 20 specifies this in VEHICLE frame with a `Turning`
 * exemption from the ML motion classifier. Neither exists yet — phone-
 * to-vehicle alignment (PRD.md Section 15) needs a GNSS-aided
 * initialization window (not built), and the motion classifier is
 * Slice 6. This is a deliberately simplified WORLD-frame stand-in for
 * Slice 5: it uses the device's own WORLD-frame heading (azimuth, from
 * OrientationMath/OrientationSample) as a proxy for vehicle heading,
 * under the explicit assumption that the phone's yaw tracks the
 * vehicle's yaw (true for a phone mounted rigidly to the vehicle body;
 * false if it's loose, e.g. free-sliding in a cup holder). It also has
 * no `Turning` exemption, so a genuine turn's real lateral velocity gets
 * suppressed too — an accepted, honestly-documented over-constraint for
 * this slice, to be relaxed once Slice 6's motion classifier can flag
 * `Turning` windows.
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
