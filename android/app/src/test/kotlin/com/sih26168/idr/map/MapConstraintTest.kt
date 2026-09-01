package com.sih26168.idr.map

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapConstraintTest {

    private val tolerance = 1e-6

    // A single north-south segment from (0,0) to (0,100), heading = north (0 rad).
    private val northSouthSegment = MapConstraint.Segment(
        startEastM = 0.0,
        startNorthM = 0.0,
        endEastM = 0.0,
        endNorthM = 100.0,
    )

    @Test
    fun `point near the road with a compatible heading snaps onto it`() {
        val result = MapConstraint.snapToRoad(
            eastM = 5.0,
            northM = 50.0,
            headingRad = 0f, // heading north, same as the segment
            segments = listOf(northSouthSegment),
        )
        assertTrue(result.snapped)
        assertEquals(0.0, result.eastM, tolerance)
        assertEquals(50.0, result.northM, tolerance)
        assertEquals(5.0, result.distanceToRoadM, tolerance)
    }

    @Test
    fun `point too far from any road is not snapped`() {
        val result = MapConstraint.snapToRoad(
            eastM = 50.0,
            northM = 50.0,
            headingRad = 0f,
            segments = listOf(northSouthSegment),
            maxSnapDistanceM = 30.0,
        )
        assertFalse(result.snapped)
        assertEquals(50.0, result.eastM, tolerance)
        assertEquals(50.0, result.northM, tolerance)
    }

    @Test
    fun `heading perpendicular to the only nearby road is not snapped`() {
        val result = MapConstraint.snapToRoad(
            eastM = 5.0,
            northM = 50.0,
            headingRad = (PI / 2).toFloat(), // heading east, perpendicular to the north-south road
            segments = listOf(northSouthSegment),
            maxHeadingDeltaRad = PI / 4,
        )
        assertFalse(result.snapped)
    }

    @Test
    fun `heading exactly opposite the road direction still snaps, roads are undirected`() {
        val result = MapConstraint.snapToRoad(
            eastM = 5.0,
            northM = 50.0,
            headingRad = PI.toFloat(), // heading south, 180 degrees from the segment's stored direction
            segments = listOf(northSouthSegment),
            maxHeadingDeltaRad = PI / 4,
        )
        assertTrue(result.snapped)
        assertEquals(0.0, result.eastM, tolerance)
        assertEquals(50.0, result.northM, tolerance)
    }

    @Test
    fun `closest point beyond a segment's endpoint clamps to the endpoint, not the infinite line`() {
        val result = MapConstraint.snapToRoad(
            eastM = 0.0,
            northM = 105.0, // 5m past the segment's north end at (0, 100)
            headingRad = 0f,
            segments = listOf(northSouthSegment),
        )
        assertTrue(result.snapped)
        assertEquals(0.0, result.eastM, tolerance)
        assertEquals(100.0, result.northM, tolerance)
        assertEquals(5.0, result.distanceToRoadM, tolerance)
    }

    @Test
    fun `the nearest eligible segment wins regardless of list order`() {
        val nearSegment = MapConstraint.Segment(20.0, 0.0, 20.0, 100.0)
        val farSegment = northSouthSegment // at east=0

        val resultFarFirst = MapConstraint.snapToRoad(
            eastM = 25.0,
            northM = 50.0,
            headingRad = 0f,
            segments = listOf(farSegment, nearSegment),
        )
        val resultNearFirst = MapConstraint.snapToRoad(
            eastM = 25.0,
            northM = 50.0,
            headingRad = 0f,
            segments = listOf(nearSegment, farSegment),
        )

        assertTrue(resultFarFirst.snapped)
        assertEquals(20.0, resultFarFirst.eastM, tolerance)
        assertTrue(resultNearFirst.snapped)
        assertEquals(20.0, resultNearFirst.eastM, tolerance)
    }

    @Test
    fun `degenerate zero-length segments are skipped without crashing`() {
        val degenerate = MapConstraint.Segment(0.0, 0.0, 0.0, 0.0)
        val result = MapConstraint.snapToRoad(
            eastM = 5.0,
            northM = 50.0,
            headingRad = 0f,
            segments = listOf(degenerate, northSouthSegment),
        )
        assertTrue(result.snapped)
        assertEquals(0.0, result.eastM, tolerance)
        assertEquals(50.0, result.northM, tolerance)
    }

    @Test
    fun `no segments at all leaves the position unchanged`() {
        val result = MapConstraint.snapToRoad(
            eastM = 5.0,
            northM = 50.0,
            headingRad = 0f,
            segments = emptyList(),
        )
        assertFalse(result.snapped)
        assertEquals(5.0, result.eastM, tolerance)
        assertEquals(50.0, result.northM, tolerance)
    }
}
