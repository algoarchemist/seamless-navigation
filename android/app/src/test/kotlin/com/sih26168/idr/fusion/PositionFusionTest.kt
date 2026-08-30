package com.sih26168.idr.fusion

import com.sih26168.idr.gnss.GnssMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PositionFusionTest {

    @Test
    fun `GNSS_AIDED always returns zero regardless of dr input`() {
        val fusion = PositionFusion()
        val result = fusion.update(
            nowMs = 1000L,
            mode = GnssMode.GNSS_AIDED,
            drEastM = 50.0,
            drNorthM = -20.0,
            newFixEastM = null,
            newFixNorthM = null,
        )
        assertEquals(0.0, result.eastM, 0.0001)
        assertEquals(0.0, result.northM, 0.0001)
    }

    @Test
    fun `TRANSITION freezes at the dr position from the instant it was entered`() {
        val fusion = PositionFusion()
        val first = fusion.update(0L, GnssMode.TRANSITION, drEastM = 1.0, drNorthM = 1.0, null, null)
        assertEquals(1.0, first.eastM, 0.0001)
        assertEquals(1.0, first.northM, 0.0001)

        // Same mode, dr keeps growing in the background — frozen output must NOT track it.
        val second = fusion.update(500L, GnssMode.TRANSITION, drEastM = 5.0, drNorthM = 5.0, null, null)
        assertEquals(1.0, second.eastM, 0.0001)
        assertEquals(1.0, second.northM, 0.0001)
    }

    @Test
    fun `re-entering TRANSITION re-freezes at the new value, not the old one`() {
        val fusion = PositionFusion()
        fusion.update(0L, GnssMode.TRANSITION, drEastM = 1.0, drNorthM = 1.0, null, null)
        fusion.update(500L, GnssMode.GNSS_AIDED, drEastM = 0.0, drNorthM = 0.0, null, null)
        val reentered = fusion.update(600L, GnssMode.TRANSITION, drEastM = 9.0, drNorthM = 9.0, null, null)
        assertEquals(9.0, reentered.eastM, 0.0001)
        assertEquals(9.0, reentered.northM, 0.0001)
    }

    @Test
    fun `DEAD_RECKONING passes the live dr delta straight through`() {
        val fusion = PositionFusion()
        val first = fusion.update(0L, GnssMode.DEAD_RECKONING, drEastM = 3.0, drNorthM = 4.0, null, null)
        assertEquals(3.0, first.eastM, 0.0001)
        assertEquals(4.0, first.northM, 0.0001)

        val second = fusion.update(100L, GnssMode.DEAD_RECKONING, drEastM = 10.0, drNorthM = -2.0, null, null)
        assertEquals(10.0, second.eastM, 0.0001)
        assertEquals(-2.0, second.northM, 0.0001)
    }

    @Test
    fun `REACQUISITION at the instant it is entered returns the dr position it started from`() {
        val fusion = PositionFusion(reacquisitionBlendMs = 1000L)
        val result = fusion.update(
            nowMs = 0L,
            mode = GnssMode.REACQUISITION,
            drEastM = 20.0,
            drNorthM = 10.0,
            newFixEastM = 24.0,
            newFixNorthM = 14.0,
        )
        assertEquals(20.0, result.eastM, 0.0001)
        assertEquals(10.0, result.northM, 0.0001)
    }

    @Test
    fun `REACQUISITION at the midpoint linearly interpolates`() {
        val fusion = PositionFusion(reacquisitionBlendMs = 1000L)
        fusion.update(0L, GnssMode.REACQUISITION, drEastM = 20.0, drNorthM = 10.0, newFixEastM = 24.0, newFixNorthM = 14.0)
        val midpoint = fusion.update(500L, GnssMode.REACQUISITION, drEastM = 20.0, drNorthM = 10.0, newFixEastM = 24.0, newFixNorthM = 14.0)
        // 20 -> 24 at t=0.5 is 22.0; 10 -> 14 at t=0.5 is 12.0 — hand-derived.
        assertEquals(22.0, midpoint.eastM, 0.0001)
        assertEquals(12.0, midpoint.northM, 0.0001)
    }

    @Test
    fun `REACQUISITION at or past the blend window returns exactly the new fix position`() {
        val fusion = PositionFusion(reacquisitionBlendMs = 1000L)
        fusion.update(0L, GnssMode.REACQUISITION, drEastM = 20.0, drNorthM = 10.0, newFixEastM = 24.0, newFixNorthM = 14.0)
        val done = fusion.update(1000L, GnssMode.REACQUISITION, drEastM = 20.0, drNorthM = 10.0, newFixEastM = 24.0, newFixNorthM = 14.0)
        assertEquals(24.0, done.eastM, 0.0001)
        assertEquals(14.0, done.northM, 0.0001)

        val pastWindow = fusion.update(5000L, GnssMode.REACQUISITION, drEastM = 20.0, drNorthM = 10.0, newFixEastM = 24.0, newFixNorthM = 14.0)
        assertEquals(24.0, pastWindow.eastM, 0.0001)
        assertEquals(14.0, pastWindow.northM, 0.0001)
    }

    @Test
    fun `REACQUISITION with no fix yet falls back to raw dr passthrough`() {
        val fusion = PositionFusion()
        val result = fusion.update(
            nowMs = 0L,
            mode = GnssMode.REACQUISITION,
            drEastM = 7.0,
            drNorthM = 8.0,
            newFixEastM = null,
            newFixNorthM = null,
        )
        assertEquals(7.0, result.eastM, 0.0001)
        assertEquals(8.0, result.northM, 0.0001)
    }

    @Test
    fun `reset clears mode tracking so the next mode change re-anchors correctly`() {
        val fusion = PositionFusion()
        fusion.update(0L, GnssMode.TRANSITION, drEastM = 1.0, drNorthM = 1.0, null, null)
        fusion.reset()
        val afterReset = fusion.update(0L, GnssMode.TRANSITION, drEastM = 5.0, drNorthM = 5.0, null, null)
        assertEquals(5.0, afterReset.eastM, 0.0001)
        assertEquals(5.0, afterReset.northM, 0.0001)
    }

    @Test
    fun `setReacquisitionBlendMs changes how fast REACQUISITION converges`() {
        val fusion = PositionFusion()
        fusion.setReacquisitionBlendMs(2000L)
        fusion.update(0L, GnssMode.REACQUISITION, drEastM = 0.0, drNorthM = 0.0, newFixEastM = 10.0, newFixNorthM = 0.0)
        // At the OLD 1000ms default this would already be fully converged;
        // at the new 2000ms blend, t=1000ms is only the midpoint.
        val midpoint = fusion.update(1000L, GnssMode.REACQUISITION, drEastM = 0.0, drNorthM = 0.0, newFixEastM = 10.0, newFixNorthM = 0.0)
        assertEquals(5.0, midpoint.eastM, 0.0001)
    }

    @Test
    fun `reset restores the default reacquisition blend duration`() {
        val fusion = PositionFusion()
        fusion.setReacquisitionBlendMs(3000L)
        fusion.reset()
        fusion.update(0L, GnssMode.REACQUISITION, drEastM = 0.0, drNorthM = 0.0, newFixEastM = 10.0, newFixNorthM = 0.0)
        // Back to the 1000ms default — fully converged by t=1000ms, not still blending.
        val atOneSecond = fusion.update(1000L, GnssMode.REACQUISITION, drEastM = 0.0, drNorthM = 0.0, newFixEastM = 10.0, newFixNorthM = 0.0)
        assertEquals(10.0, atOneSecond.eastM, 0.0001)
    }

    @Test
    fun `blendDurationForDriftMs returns the minimum for zero predicted drift`() {
        assertEquals(
            PositionFusion.MIN_ADAPTIVE_REACQUISITION_BLEND_MS,
            PositionFusion.blendDurationForDriftMs(0f),
        )
    }

    @Test
    fun `blendDurationForDriftMs scales linearly with predicted drift below the clamp`() {
        // 10m at 30ms/m = 300ms on top of the 500ms floor = 800ms.
        assertEquals(800L, PositionFusion.blendDurationForDriftMs(10f))
    }

    @Test
    fun `blendDurationForDriftMs clamps to the maximum for a large predicted drift`() {
        assertEquals(
            PositionFusion.MAX_ADAPTIVE_REACQUISITION_BLEND_MS,
            PositionFusion.blendDurationForDriftMs(500f),
        )
    }
}
