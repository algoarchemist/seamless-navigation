package com.sih26168.idr.routing

import org.osmdroid.util.GeoPoint

/** One real OSRM route step, converted from its maneuver type/modifier into a plain-English instruction. */
data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
)

/**
 * A REAL computed route from [RoutingRepository] — geometry and steps come
 * directly from OSRM's response (CLAUDE.md Rule 13: nothing here is
 * fabricated the way a hardcoded "turn left in 200m" would be, since this
 * project has no routing engine of its own to back that claim with real
 * data — OSRM's public routing API IS that real data source).
 */
data class RouteResult(
    val geometry: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<RouteStep>,
    val destinationName: String,
)

/** One real geocoded place from [GeocodingRepository] (OpenStreetMap Nominatim). */
data class GeocodeResult(
    val displayName: String,
    val latDeg: Double,
    val lonDeg: Double,
)

/**
 * [GeocodingRepository.search]'s result — distinguishes a real "no matches"
 * from a real failure (network/HTTP/parse error), added 2026-08-26 after a
 * user report where every failure mode was silently indistinguishable from
 * "nothing matched" (CLAUDE.md Rule 13 — a failure should say so, not look
 * like an empty, honest result).
 */
sealed class GeocodeSearchOutcome {
    data class Success(val results: List<GeocodeResult>) : GeocodeSearchOutcome()
    data class Failure(val reason: String) : GeocodeSearchOutcome()
}
