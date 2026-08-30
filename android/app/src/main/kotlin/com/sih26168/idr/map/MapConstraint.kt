package com.sih26168.idr.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * PRD.md Section 19's MVP-level map constraint: nearest-road-snap plus a
 * heading-compatibility check against real road geometry — explicitly NOT
 * a Hidden Markov Model or general-purpose map-matching engine (PRD.md
 * Section 19/Section 7 rule this out as out of scope for this project's
 * timebox; see CLAUDE.md's "What Not To Build").
 *
 * Scope reduction from PRD.md Section 19's two listed options: this
 * project already fetches real, road-following OSM geometry for whatever
 * route is currently active (`routing/RoutingRepository.kt`'s OSRM call,
 * used for turn-by-turn) — that's real road geometry, just scoped to one
 * route instead of the whole demo area. Reusing it here needs no new
 * network call, no new library, and no separately-fetched offline road
 * dataset (CLAUDE.md Rule 2). The honest tradeoff: this constraint only
 * does anything while a route is active, matching this MVP's one demo
 * scenario (driving an intended route through a GNSS-denied stretch), not
 * "snap to the nearest road anywhere."
 *
 * Pure Kotlin, no Android dependency, unit-testable on the plain JVM
 * (CLAUDE.md Rule 19). All inputs/outputs are WORLD-frame local East/North
 * meters (CLAUDE.md Rule 14) — the same local frame
 * `fusion/PositionFusion.kt` already publishes into, via
 * `fusion/GeoProjection.kt`.
 */
object MapConstraint {

    /** One road-geometry edge (two consecutive route points), WORLD-frame local East/North meters. */
    data class Segment(
        val startEastM: Double,
        val startNorthM: Double,
        val endEastM: Double,
        val endNorthM: Double,
    )

    data class SnapResult(
        val eastM: Double,
        val northM: Double,
        val snapped: Boolean,
        /** Distance from the pre-snap point to the road, meters. Double.MAX_VALUE if [snapped] is false. */
        val distanceToRoadM: Double,
    )

    // Engineering defaults, unvalidated against a real outdoor test drive
    // (CLAUDE.md Rule 13) — chosen to tolerate the raw physics/ML DR drift
    // already measured elsewhere in this project (several meters of error
    // over tens of seconds is normal for the physics baseline), while
    // still refusing to snap the estimate onto a clearly unrelated road.
    const val DEFAULT_MAX_SNAP_DISTANCE_M = 30.0
    const val DEFAULT_MAX_HEADING_DELTA_RAD = (PI / 4) // 45 degrees

    /**
     * Snaps ([eastM], [northM]) onto the nearest point of the nearest
     * [segments] entry that is BOTH within [maxSnapDistanceM] AND whose own
     * direction is within [maxHeadingDeltaRad] of [headingRad] (Android
     * azimuth convention: 0 = north, positive = clockwise, same as
     * `dr/NonHolonomicConstraint.kt`).
     *
     * The heading check is done modulo PI (180 degrees) — a route
     * polyline's point order says nothing about which way traffic on that
     * road actually flows, so a vehicle heading either along or exactly
     * against a segment's stored direction both count as compatible.
     *
     * Returns the ORIGINAL ([eastM], [northM]), unsnapped, if no segment
     * qualifies (too far away, or no nearby segment's heading looks
     * related) — this constraint must never move the estimate somewhere
     * the vehicle plausibly is not.
     */
    fun snapToRoad(
        eastM: Double,
        northM: Double,
        headingRad: Float,
        segments: List<Segment>,
        maxSnapDistanceM: Double = DEFAULT_MAX_SNAP_DISTANCE_M,
        maxHeadingDeltaRad: Double = DEFAULT_MAX_HEADING_DELTA_RAD,
    ): SnapResult {
        var bestEastM = eastM
        var bestNorthM = northM
        var bestDistanceM = Double.MAX_VALUE
        var found = false

        for (segment in segments) {
            val dx = segment.endEastM - segment.startEastM
            val dy = segment.endNorthM - segment.startNorthM
            val lengthSq = dx * dx + dy * dy
            if (lengthSq == 0.0) continue // degenerate (duplicate consecutive route points)

            // Project the point onto the segment, clamped to [0, 1] so the
            // closest point stays ON the segment, not its infinite line.
            val t = (((eastM - segment.startEastM) * dx + (northM - segment.startNorthM) * dy) / lengthSq)
                .coerceIn(0.0, 1.0)
            val projEastM = segment.startEastM + t * dx
            val projNorthM = segment.startNorthM + t * dy
            val distanceM = hypot(eastM - projEastM, northM - projNorthM)
            if (distanceM > maxSnapDistanceM || distanceM >= bestDistanceM) continue

            // East/North -> compass-style heading (0 = north, clockwise) —
            // same convention atan2(east, north) already uses in
            // MapScreen.kt's own DR-velocity-vector heading fallback.
            val segmentHeadingRad = atan2(dx, dy)
            var headingDeltaRad = abs(headingRad.toDouble() - segmentHeadingRad)
            while (headingDeltaRad > PI) headingDeltaRad -= 2 * PI
            headingDeltaRad = abs(headingDeltaRad)
            val headingCompatible = headingDeltaRad <= maxHeadingDeltaRad ||
                abs(headingDeltaRad - PI) <= maxHeadingDeltaRad

            if (!headingCompatible) continue

            bestEastM = projEastM
            bestNorthM = projNorthM
            bestDistanceM = distanceM
            found = true
        }

        return if (found) {
            SnapResult(eastM = bestEastM, northM = bestNorthM, snapped = true, distanceToRoadM = bestDistanceM)
        } else {
            SnapResult(eastM = eastM, northM = northM, snapped = false, distanceToRoadM = Double.MAX_VALUE)
        }
    }
}
