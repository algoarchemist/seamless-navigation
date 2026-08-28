package com.sih26168.idr.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoadSnapTest {

    // A single segment running due NORTH from (0,0) to (0,100) — bearing 0 deg.
    private val northSegment = listOf(0.0 to 0.0, 0.0 to 100.0)

    @Test
    fun `fewer than two geometry points returns null`() {
        assertNull(RoadSnap.snap(0.0, 0.0, headingDeg = 0f, routeGeometryLocalMeters = emptyList()))
        assertNull(RoadSnap.snap(0.0, 0.0, headingDeg = 0f, routeGeometryLocalMeters = listOf(0.0 to 0.0)))
    }

    @Test
    fun `a position already on the segment snaps to itself with zero correction`() {
        val result = RoadSnap.snap(
            positionEastM = 0.0,
            positionNorthM = 50.0,
            headingDeg = 0f,
            routeGeometryLocalMeters = northSegment,
        )
        assertEquals(0.0, result!!.eastM, 0.0001)
        assertEquals(50.0, result.northM, 0.0001)
        assertEquals(0.0, result.correctionDistanceM, 0.0001)
    }

    @Test
    fun `a position offset to the side snaps perpendicular onto the segment`() {
        // 10m east of the midpoint of a north-running segment.
        val result = RoadSnap.snap(
            positionEastM = 10.0,
            positionNorthM = 50.0,
            headingDeg = 0f,
            routeGeometryLocalMeters = northSegment,
        )
        assertEquals(0.0, result!!.eastM, 0.0001)
        assertEquals(50.0, result.northM, 0.0001)
        assertEquals(10.0, result.correctionDistanceM, 0.0001)
    }

    @Test
    fun `a position beyond the segment's endpoint clamps to the endpoint, not the infinite line`() {
        val result = RoadSnap.snap(
            positionEastM = 0.0,
            positionNorthM = 150.0, // 50m past the north end
            headingDeg = 0f,
            routeGeometryLocalMeters = northSegment,
            maxSnapDistanceM = 60.0, // wide enough that the distance check itself doesn't interfere with this test
        )
        assertEquals(0.0, result!!.eastM, 0.0001)
        assertEquals(100.0, result.northM, 0.0001)
        assertEquals(50.0, result.correctionDistanceM, 0.0001)
    }

    @Test
    fun `a heading-incompatible segment is rejected even when geometrically close`() {
        // Travelling EAST (90 deg) near a NORTH-running (0 deg) segment —
        // 90 degree delta exceeds the default 45-degree tolerance.
        val result = RoadSnap.snap(
            positionEastM = 1.0,
            positionNorthM = 50.0,
            headingDeg = 90f,
            routeGeometryLocalMeters = northSegment,
        )
        assertNull(result)
    }

    @Test
    fun `a null heading skips the compatibility check entirely`() {
        val result = RoadSnap.snap(
            positionEastM = 1.0,
            positionNorthM = 50.0,
            headingDeg = null,
            routeGeometryLocalMeters = northSegment,
        )
        assertEquals(0.0, result!!.eastM, 0.0001)
    }

    @Test
    fun `a position beyond maxSnapDistanceM returns null even if heading-compatible`() {
        val result = RoadSnap.snap(
            positionEastM = 30.0,
            positionNorthM = 50.0,
            headingDeg = 0f,
            routeGeometryLocalMeters = northSegment,
            maxSnapDistanceM = 25.0,
        )
        assertNull(result)
    }

    @Test
    fun `among multiple segments, rejects a heading-incompatible closer one for a farther compatible one`() {
        // An EAST-running segment passes directly THROUGH the position
        // itself (distance 0) but is heading-incompatible (90 deg vs the
        // 0 deg travel direction) — must be rejected entirely, even though
        // geometrically nothing could be closer. The NORTH-running segment
        // (compatible, but farther) must be the one actually returned.
        val eastSegmentThroughPosition = listOf(-50.0 to 50.0, 50.0 to 50.0)
        val geometry = eastSegmentThroughPosition + northSegment
        val result = RoadSnap.snap(
            positionEastM = 2.0,
            positionNorthM = 50.0,
            headingDeg = 0f,
            routeGeometryLocalMeters = geometry,
        )
        assertEquals(0.0, result!!.eastM, 0.0001)
        assertEquals(50.0, result.northM, 0.0001)
    }

    @Test
    fun `heading compatibility is circular-safe across the 0-360 wrap boundary`() {
        // Travelling at 359 deg (nearly due north, just west of it) against
        // a segment bearing exactly 0 deg — true delta is 1 degree, NOT the
        // naive 359-degree difference a non-circular subtraction would give.
        val result = RoadSnap.snap(
            positionEastM = 1.0,
            positionNorthM = 50.0,
            headingDeg = 359f,
            routeGeometryLocalMeters = northSegment,
        )
        assertEquals(0.0, result!!.eastM, 0.0001)
    }

    @Test
    fun `a duplicate consecutive point (zero-length segment) is skipped without error`() {
        val geometryWithDuplicate = listOf(0.0 to 0.0, 0.0 to 0.0, 0.0 to 100.0)
        val result = RoadSnap.snap(
            positionEastM = 0.0,
            positionNorthM = 50.0,
            headingDeg = 0f,
            routeGeometryLocalMeters = geometryWithDuplicate,
        )
        assertEquals(50.0, result!!.northM, 0.0001)
    }
}
