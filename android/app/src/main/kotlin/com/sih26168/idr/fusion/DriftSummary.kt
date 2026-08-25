package com.sih26168.idr.fusion

import kotlin.math.hypot

/**
 * PRD.md Section 30 WOW-factor #4: "an honest, on-screen drift
 * measurement at the end of the outage segment (e.g. 'X m drift over
 * Y m travelled')."
 */
data class DriftSummaryResult(
    val driftMeters: Double,
    val distanceTravelledMeters: Double,
)

/**
 * Pure Kotlin, no Android dependency (CLAUDE.md Rule 19) — computes a
 * REAL drift measurement from two already-computed local-meter
 * positions, the same two values fusion/PositionFusion.kt's REACQUISITION
 * blend already uses: where dead reckoning THOUGHT the phone was
 * (`drEastM`/`drNorthM`) versus where the newly-reacquired GNSS fix says
 * it actually is (`gnssEastM`/`gnssNorthM`, both relative to the SAME
 * outage anchor, via fusion/GeoProjection.kt). No new sensor data or
 * geodesy needed — this is a small reduction over data already flowing
 * through fusion/StateEstimator.kt.
 *
 * HONEST LIMITATION (CLAUDE.md Rule 13): [DriftSummaryResult.distanceTravelledMeters]
 * is the STRAIGHT-LINE distance from the outage anchor to the DR
 * position, not an integrated path length — nothing in this codebase
 * separately accumulates true path length (the DR integrators only ever
 * track current position/velocity, not cumulative distance). For a
 * mostly-forward drive this is a close approximation; for a route with
 * sharp turns or backtracking it understates actual distance travelled.
 * Documented here rather than silently presented as exact.
 */
object DriftSummary {
    fun compute(drEastM: Double, drNorthM: Double, gnssEastM: Double, gnssNorthM: Double): DriftSummaryResult {
        val driftMeters = hypot(drEastM - gnssEastM, drNorthM - gnssNorthM)
        val distanceTravelledMeters = hypot(drEastM, drNorthM)
        return DriftSummaryResult(driftMeters, distanceTravelledMeters)
    }
}
