package com.sih26168.idr.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DELTA = 0.5 // meters

/**
 * JUnit4 unit tests for RouteProgress — CLAUDE.md Rule 19 (deterministic
 * math gets a unit test) before it's trusted to drive the live
 * turn-by-turn banner in `ui/screens/MapScreen.kt`.
 *
 * Uses a simple straight route (0,0) -> (0,100) -> (0,200) north, split
 * into two 100m steps, so expected distances are easy to reason about by
 * hand.
 */
class RouteProgressTest {

    private val straightRoute = listOf(0.0 to 0.0, 0.0 to 100.0, 0.0 to 200.0)
    private val twoSteps = listOf(100.0, 100.0)

    @Test
    fun `fewer than two route points returns null`() {
        assertNull(RouteProgress.compute(listOf(0.0 to 0.0), twoSteps, 0.0, 0.0))
    }

    @Test
    fun `at the very start, full route remains and step index is zero`() {
        val result = RouteProgress.compute(straightRoute, twoSteps, currentEastM = 0.0, currentNorthM = 0.0)!!
        assertEquals(0, result.currentStepIndex)
        assertEquals(100.0, result.distanceRemainingInStepMeters, DELTA)
        assertEquals(200.0, result.distanceRemainingTotalMeters, DELTA)
        assertEquals(0.0, result.distanceOffRouteMeters, DELTA)
    }

    @Test
    fun `halfway through the first step`() {
        val result = RouteProgress.compute(straightRoute, twoSteps, currentEastM = 0.0, currentNorthM = 50.0)!!
        assertEquals(0, result.currentStepIndex)
        assertEquals(50.0, result.distanceRemainingInStepMeters, DELTA)
        assertEquals(150.0, result.distanceRemainingTotalMeters, DELTA)
    }

    @Test
    fun `just past the first step boundary moves into step two`() {
        val result = RouteProgress.compute(straightRoute, twoSteps, currentEastM = 0.0, currentNorthM = 110.0)!!
        assertEquals(1, result.currentStepIndex)
        assertEquals(90.0, result.distanceRemainingInStepMeters, DELTA)
        assertEquals(90.0, result.distanceRemainingTotalMeters, DELTA)
    }

    @Test
    fun `at the destination, nothing remains`() {
        val result = RouteProgress.compute(straightRoute, twoSteps, currentEastM = 0.0, currentNorthM = 200.0)!!
        assertEquals(1, result.currentStepIndex)
        assertEquals(0.0, result.distanceRemainingInStepMeters, DELTA)
        assertEquals(0.0, result.distanceRemainingTotalMeters, DELTA)
    }

    @Test
    fun `off to the side reports real perpendicular distance, not zero`() {
        val result = RouteProgress.compute(straightRoute, twoSteps, currentEastM = 30.0, currentNorthM = 50.0)!!
        assertEquals(30.0, result.distanceOffRouteMeters, DELTA)
        // still projects onto the same point along the route as directly-on-route
        assertEquals(150.0, result.distanceRemainingTotalMeters, DELTA)
    }

    @Test
    fun `past the destination clamps to the final segment, not extrapolated`() {
        val result = RouteProgress.compute(straightRoute, twoSteps, currentEastM = 0.0, currentNorthM = 500.0)!!
        assertEquals(1, result.currentStepIndex)
        assertEquals(0.0, result.distanceRemainingTotalMeters, DELTA)
        assertTrue("should report real distance off route, not silently clamp to 0", result.distanceOffRouteMeters > 250.0)
    }
}
