package com.sih26168.idr.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionSmootherTest {

    @Test
    fun `stepPosition with a null target returns null`() {
        val smoother = PositionSmoother()
        assertNull(smoother.stepPosition(null, null))
        assertNull(smoother.stepPosition(10.0, null))
        assertNull(smoother.stepPosition(null, 10.0))
    }

    @Test
    fun `the first-ever target snaps directly, nothing to glide from yet`() {
        val smoother = PositionSmoother()
        val result = smoother.stepPosition(12.5, 77.5)
        assertEquals(12.5, result!!.first, 0.0001)
        assertEquals(77.5, result.second, 0.0001)
    }

    @Test
    fun `a second step closes the gap by exactly smoothingFactor`() {
        val smoother = PositionSmoother(smoothingFactor = 0.25)
        smoother.stepPosition(0.0, 0.0)
        val result = smoother.stepPosition(4.0, -8.0)
        // 0 + 0.25*(4-0) = 1.0; 0 + 0.25*(-8-0) = -2.0 — hand-derived, not
        // just "moved somewhat toward the target".
        assertEquals(1.0, result!!.first, 0.0001)
        assertEquals(-2.0, result.second, 0.0001)
    }

    @Test
    fun `repeated identical-target steps converge toward the target`() {
        val smoother = PositionSmoother(smoothingFactor = 0.5)
        smoother.stepPosition(0.0, 0.0)
        var last: Pair<Double, Double>? = null
        repeat(20) { last = smoother.stepPosition(100.0, 100.0) }
        assertEquals(100.0, last!!.first, 0.01)
        assertEquals(100.0, last!!.second, 0.01)
    }

    @Test
    fun `stepHeading first-ever target snaps directly`() {
        val smoother = PositionSmoother()
        assertEquals(200f, smoother.stepHeading(200f), 0.001f)
    }

    @Test
    fun `stepHeading interpolates the SHORT way across the 0-360 wrap boundary`() {
        // The exact scenario this exists for: heading 350 deg -> target 10
        // deg — the short way is +20 deg through 360/0, NOT a naive linear
        // step from 350 down toward 10 (-340 deg, the long way around).
        val smoother = PositionSmoother(smoothingFactor = 0.5)
        smoother.stepHeading(350f)
        val result = smoother.stepHeading(10f)
        // Halfway along the short 20-degree arc from 350 -> 360/0 -> 10 is 0 deg.
        val normalized = if (result > 180f) result - 360f else result
        assertEquals(0f, normalized, 1f)
    }

    @Test
    fun `stepHeading closes an ordinary gap by exactly smoothingFactor`() {
        val smoother = PositionSmoother(smoothingFactor = 0.5)
        smoother.stepHeading(0f)
        val result = smoother.stepHeading(90f)
        assertEquals(45f, result, 0.5f)
    }

    @Test
    fun `reset clears both position and heading state so the next step snaps again`() {
        val smoother = PositionSmoother()
        smoother.stepPosition(0.0, 0.0)
        smoother.stepHeading(0f)
        smoother.reset()

        val positionResult = smoother.stepPosition(50.0, 60.0)
        assertEquals(50.0, positionResult!!.first, 0.0001)
        assertEquals(60.0, positionResult.second, 0.0001)

        val headingResult = smoother.stepHeading(270f)
        assertEquals(270f, headingResult, 0.001f)
    }
}
