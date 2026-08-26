package com.sih26168.idr.fusion

import kotlin.math.cos

// WGS84 mean meters-per-degree-latitude — a good approximation everywhere,
// since latitude-degree spacing barely varies with latitude (unlike
// longitude-degree spacing, which shrinks toward the poles by cos(lat)).
private const val METERS_PER_DEG_LAT = 111_320.0

/**
 * Converts a GNSS lat/lon fix into local East/North meters relative to a
 * reference lat/lon, via an equirectangular (flat-earth tangent-plane)
 * approximation.
 *
 * This is NOT a geodesically exact projection — it ignores WGS84 ellipsoid
 * curvature entirely, so error grows with distance from the reference
 * point. It is accurate to a small fraction of a percent at the few-km
 * scale this demo operates at (CLAUDE.md Rule 13: this limitation is
 * stated explicitly, not silently assumed away), which is more than
 * sufficient for its one caller: fusion/StateEstimator.kt needs to express
 * a newly-reacquired GNSS fix in the same local frame the dead-reckoned
 * position has already been accumulating in, so fusion/PositionFusion.kt
 * can blend the two.
 */
object GeoProjection {

    /**
     * @return (eastMeters, northMeters) of (latDeg, lonDeg) relative to
     *   (refLatDeg, refLonDeg) — positive east/north.
     */
    fun toLocalMeters(latDeg: Double, lonDeg: Double, refLatDeg: Double, refLonDeg: Double): Pair<Double, Double> {
        val metersPerDegLon = METERS_PER_DEG_LAT * cos(Math.toRadians(refLatDeg))
        val eastM = (lonDeg - refLonDeg) * metersPerDegLon
        val northM = (latDeg - refLatDeg) * METERS_PER_DEG_LAT
        return eastM to northM
    }

    /**
     * Exact algebraic inverse of [toLocalMeters] (same flat-earth tangent-
     * plane approximation, same accuracy caveat) — added so a real street
     * map (`ui/map/StreetMapView.kt`) can place the already-computed fused
     * East/North position back onto real-world lat/lon tiles. Round-trips
     * with [toLocalMeters] to within floating-point precision (see
     * GeoProjectionTest).
     *
     * @return (latDeg, lonDeg) of (eastM, northM) relative to (refLatDeg, refLonDeg).
     */
    fun toLatLon(eastM: Double, northM: Double, refLatDeg: Double, refLonDeg: Double): Pair<Double, Double> {
        val metersPerDegLon = METERS_PER_DEG_LAT * cos(Math.toRadians(refLatDeg))
        val lonDeg = refLonDeg + eastM / metersPerDegLon
        val latDeg = refLatDeg + northM / METERS_PER_DEG_LAT
        return latDeg to lonDeg
    }
}
