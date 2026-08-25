package com.sih26168.idr.fusion

import org.junit.Assert.assertEquals
import org.junit.Test

private const val DELTA = 0.5 // meters — this is a flat-earth approximation, not exact geodesy

class GeoProjectionTest {

    @Test
    fun `same point as reference is zero, zero`() {
        val (east, north) = GeoProjection.toLocalMeters(12.9716, 77.5946, 12.9716, 77.5946)
        assertEquals(0.0, east, DELTA)
        assertEquals(0.0, north, DELTA)
    }

    @Test
    fun `one degree north of reference is about 111320 meters north`() {
        val (east, north) = GeoProjection.toLocalMeters(13.9716, 77.5946, 12.9716, 77.5946)
        assertEquals(0.0, east, DELTA)
        assertEquals(111_320.0, north, DELTA)
    }

    @Test
    fun `one degree of longitude at the equator is about 111320 meters east`() {
        val (east, north) = GeoProjection.toLocalMeters(0.0, 1.0, 0.0, 0.0)
        assertEquals(111_320.0, east, DELTA)
        assertEquals(0.0, north, DELTA)
    }

    @Test
    fun `longitude degree spacing shrinks away from the equator by cos of latitude`() {
        // At 60 degrees latitude, cos(60 deg) = 0.5, so a degree of longitude
        // should span about half as many meters as it does at the equator —
        // exactly why toLocalMeters scales metersPerDegLon by cos(refLatDeg)
        // instead of reusing the latitude constant directly.
        val (east, _) = GeoProjection.toLocalMeters(60.0, 1.0, 60.0, 0.0)
        assertEquals(111_320.0 * 0.5, east, DELTA)
    }

    @Test
    fun `south and west of reference are negative`() {
        val (east, north) = GeoProjection.toLocalMeters(11.9716, 76.5946, 12.9716, 77.5946)
        assertEquals(true, east < 0.0)
        assertEquals(true, north < 0.0)
    }
}
