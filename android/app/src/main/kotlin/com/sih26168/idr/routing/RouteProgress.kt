package com.sih26168.idr.routing

import kotlin.math.sqrt

/**
 * Live turn-by-turn progress along an active [RouteResult], added
 * 2026-08-26 for the user-requested "turn by turn navigation screen
 * (start mode, Google Maps-like)". Pure Kotlin (no android import,
 * CLAUDE.md Rule 19) so it's unit-testable on the plain JVM, same pattern
 * as [com.sih26168.idr.fusion.GeoProjection].
 *
 * Deliberately works in local East/North METERS, not lat/lon — the caller
 * (`ui/screens/MapScreen.kt`) projects both the route geometry and the
 * live position through the SAME [com.sih26168.idr.fusion.GeoProjection]
 * anchor `fusion/StateEstimator.kt` already maintains, so "how far along
 * the route am I" keeps working from the SAME physics/ML dead-reckoned
 * position the rest of the app falls back to during a GNSS outage — not a
 * second, GNSS-only position source.
 */
data class RouteProgressResult(
    /** Index into the route's `RouteStep` list this position currently belongs to. */
    val currentStepIndex: Int,
    /** Remaining distance (m) to the maneuver that ends the current step. */
    val distanceRemainingInStepMeters: Double,
    /** Remaining distance (m) to the destination, along the route. */
    val distanceRemainingTotalMeters: Double,
    /** Perpendicular distance (m) from the current position to the nearest point on the route polyline. */
    val distanceOffRouteMeters: Double,
)

object RouteProgress {

    /**
     * @param routeLocalMeters the route geometry, each vertex already
     *   projected to (eastM, northM) in the caller's chosen local frame, in
     *   route order (start to destination). Needs at least 2 points.
     * @param stepDistancesMeters each `RouteStep.distanceMeters`, in the
     *   SAME order as the steps OSRM returned — the length (m) of route
     *   consumed by that step, so cumulative sums mark step BOUNDARIES
     *   along the route (this is exactly what OSRM's own per-step
     *   `distance` field means: distance from this step's start to the
     *   next maneuver).
     * @param currentEastM/[currentNorthM] the live position, in the SAME
     *   local frame as [routeLocalMeters].
     * @return null if the route has fewer than 2 points (nothing to
     *   project against).
     */
    fun compute(
        routeLocalMeters: List<Pair<Double, Double>>,
        stepDistancesMeters: List<Double>,
        currentEastM: Double,
        currentNorthM: Double,
    ): RouteProgressResult? {
        if (routeLocalMeters.size < 2) return null

        // Walk every segment of the polyline, projecting the current
        // position onto each one (clamped to the segment, not the infinite
        // line) and keeping the closest — standard "nearest point on a
        // polyline" via per-segment parametric projection. Track cumulative
        // route length as we go so the winning projection's cumulative
        // distance is known without a second pass.
        var bestDistSq = Double.MAX_VALUE
        var bestCumulativeM = 0.0
        var cumulativeM = 0.0
        for (i in 0 until routeLocalMeters.size - 1) {
            val (ax, ay) = routeLocalMeters[i]
            val (bx, by) = routeLocalMeters[i + 1]
            val segDx = bx - ax
            val segDy = by - ay
            val segLenSq = segDx * segDx + segDy * segDy
            val segLen = sqrt(segLenSq)

            val t = if (segLenSq > 0.0) {
                (((currentEastM - ax) * segDx + (currentNorthM - ay) * segDy) / segLenSq)
                    .coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val projEastM = ax + t * segDx
            val projNorthM = ay + t * segDy
            val dx = currentEastM - projEastM
            val dy = currentNorthM - projNorthM
            val distSq = dx * dx + dy * dy

            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestCumulativeM = cumulativeM + t * segLen
            }
            cumulativeM += segLen
        }
        val totalRouteLengthM = cumulativeM
        val distanceOffRouteMeters = sqrt(bestDistSq)

        // stepDistancesMeters[i] is how much route length step i itself
        // consumes, so its cumulative sum up to and including step i is
        // that step's END boundary along the route. The first step whose
        // end boundary is still ahead of bestCumulativeM is the CURRENT step.
        var stepEndBoundaryM = 0.0
        var currentStepIndex = stepDistancesMeters.lastIndex.coerceAtLeast(0)
        for (i in stepDistancesMeters.indices) {
            stepEndBoundaryM += stepDistancesMeters[i]
            if (bestCumulativeM < stepEndBoundaryM || i == stepDistancesMeters.lastIndex) {
                currentStepIndex = i
                break
            }
        }
        val stepStartBoundaryM = stepDistancesMeters.take(currentStepIndex).sum()
        val currentStepEndBoundaryM = stepStartBoundaryM + stepDistancesMeters.getOrElse(currentStepIndex) { 0.0 }
        val distanceRemainingInStepMeters = (currentStepEndBoundaryM - bestCumulativeM).coerceAtLeast(0.0)
        val distanceRemainingTotalMeters = (totalRouteLengthM - bestCumulativeM).coerceAtLeast(0.0)

        return RouteProgressResult(
            currentStepIndex = currentStepIndex,
            distanceRemainingInStepMeters = distanceRemainingInStepMeters,
            distanceRemainingTotalMeters = distanceRemainingTotalMeters,
            distanceOffRouteMeters = distanceOffRouteMeters,
        )
    }
}
