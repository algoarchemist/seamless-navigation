package com.sih26168.idr.fusion

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * PRD.md Section 19's MVP map-constraint layer (Round 2, 2026-08-28):
 * nearest-road snapping, explicitly NOT a full HMM-based map matcher
 * (Section 19/34 rule that out as Future Work). Pure Kotlin, no Android
 * dependency, unit-testable (CLAUDE.md Rule 19).
 *
 * Scope decision (per PRD.md Section 19's 2026-08-28 amendment): rather
 * than fetching a general road-network graph (which would need a new
 * service — e.g. Overpass API — CLAUDE.md Rule 2's "no new dependency
 * without discussing it first"), this snaps onto the geometry of the
 * user's OWN already-computed active route (`routing/RoutingRepository.kt`,
 * OSRM) — the road the demo scenario is actually driving. That reuses
 * infrastructure Slice 8b already built and needs no new network call.
 * When there is no active route, there is nothing to snap to — this
 * degrades to "no correction," not a crash or a guess.
 *
 * Method: for each route segment, finds the nearest point on that
 * segment to the estimated position, then rejects any segment whose
 * OWN bearing disagrees with the estimated heading by more than
 * [maxHeadingDeltaDeg] (a road running roughly perpendicular or opposite
 * to the direction of travel is not a plausible match, even if
 * geometrically close — e.g. a parallel service road or the SAME road on
 * the return leg) — "simple nearest-segment + heading-compatibility
 * check, not an HMM," exactly PRD.md Section 19's own wording. Among the
 * remaining heading-compatible segments, returns the closest point,
 * provided it's within [maxSnapDistanceM].
 */
object RoadSnap {

    // Engineering defaults (CLAUDE.md Rule 13), not yet validated against
    // a real test drive:
    // - 25m: comparable to GnssQuality's own max-accuracy threshold —
    //   wide enough to absorb ordinary DR drift over a short outage
    //   without snapping onto a genuinely different, nearby road.
    // - 45 degrees: generous enough to allow a road's own gentle
    //   curvature and normal heading noise, tight enough to reject a
    //   clearly wrong (perpendicular/opposite-direction) segment.
    const val DEFAULT_MAX_SNAP_DISTANCE_M = 25.0
    const val DEFAULT_MAX_HEADING_DELTA_DEG = 45.0f

    data class SnapResult(
        val eastM: Double,
        val northM: Double,
        /** How far the ORIGINAL (unsnapped) position was from this result, meters — for callers that want to show/log the correction magnitude. */
        val correctionDistanceM: Double,
    )

    /**
     * @param positionEastM/[positionNorthM] the estimated position to
     *   correct, in local East/North meters (same frame the route
     *   geometry below must already be projected into — callers use
     *   [GeoProjection.toLocalMeters] against the SAME reference point
     *   for both).
     * @param headingDeg the estimated compass heading (0-360, e.g.
     *   [com.sih26168.idr.fusion.FusedPositionUiState.fusedHeadingDeg]),
     *   or null to skip the heading-compatibility check entirely (snaps
     *   to the nearest segment regardless of direction — a looser,
     *   honest fallback when no heading estimate exists yet, not a
     *   silent assumption of straight-ahead travel).
     * @param routeGeometryLocalMeters the active route's geometry,
     *   already projected into the SAME local East/North frame as the
     *   position above (at least 2 points to form a segment).
     * @return the nearest heading-compatible point on the route within
     *   [maxSnapDistanceM], or null if there's no active route (fewer
     *   than 2 points), no heading-compatible segment nearby, or nothing
     *   within range — an honest "no correction available," not a guess.
     */
    fun snap(
        positionEastM: Double,
        positionNorthM: Double,
        headingDeg: Float?,
        routeGeometryLocalMeters: List<Pair<Double, Double>>,
        maxSnapDistanceM: Double = DEFAULT_MAX_SNAP_DISTANCE_M,
        maxHeadingDeltaDeg: Float = DEFAULT_MAX_HEADING_DELTA_DEG,
    ): SnapResult? {
        if (routeGeometryLocalMeters.size < 2) return null

        var best: SnapResult? = null
        for (i in 0 until routeGeometryLocalMeters.size - 1) {
            val (ax, ay) = routeGeometryLocalMeters[i]
            val (bx, by) = routeGeometryLocalMeters[i + 1]
            if (ax == bx && ay == by) continue // zero-length segment (duplicate point) — nothing to project onto

            if (headingDeg != null) {
                val segmentHeadingDeg = segmentBearingDeg(ax, ay, bx, by)
                if (circularDeltaDeg(headingDeg, segmentHeadingDeg) > maxHeadingDeltaDeg) continue
            }

            val (nearestX, nearestY) = nearestPointOnSegment(positionEastM, positionNorthM, ax, ay, bx, by)
            val distance = hypot(positionEastM - nearestX, positionNorthM - nearestY)
            if (distance <= maxSnapDistanceM && (best == null || distance < best.correctionDistanceM)) {
                best = SnapResult(eastM = nearestX, northM = nearestY, correctionDistanceM = distance)
            }
        }
        return best
    }

    /** Compass bearing (degrees, 0-360) of the direction from (ax,ay) to (bx,by) in an East/North local frame. */
    private fun segmentBearingDeg(ax: Double, ay: Double, bx: Double, by: Double): Float {
        val bearingRad = atan2(bx - ax, by - ay) // atan2(east, north) — this codebase's standard bearing convention (see fusion/StateEstimator.kt)
        val bearingDeg = Math.toDegrees(bearingRad).toFloat()
        return if (bearingDeg < 0f) bearingDeg + 360f else bearingDeg
    }

    /** Shortest angular distance between two compass bearings, degrees, always >= 0 — safe across the 0/360 wrap. */
    private fun circularDeltaDeg(aDeg: Float, bDeg: Float): Float {
        var diff = (aDeg - bDeg) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return kotlin.math.abs(diff)
    }

    /** Standard point-to-segment projection, clamped to the segment (not the infinite line). */
    private fun nearestPointOnSegment(
        px: Double,
        py: Double,
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
    ): Pair<Double, Double> {
        val abx = bx - ax
        val aby = by - ay
        val lengthSquared = abx * abx + aby * aby
        val t = (((px - ax) * abx + (py - ay) * aby) / lengthSquared).coerceIn(0.0, 1.0)
        return (ax + t * abx) to (ay + t * aby)
    }
}
