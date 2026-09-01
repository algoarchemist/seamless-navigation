package com.sih26168.idr.fusion

import com.sih26168.idr.gnss.GnssMode
import org.junit.Assert.assertEquals
import org.junit.Test

class HeadingFusionTest {

    @Test
    fun `GNSS_AIDED with a bearing returns that bearing directly`() {
        val fusion = HeadingFusion()
        val result = fusion.update(nowMs = 0L, mode = GnssMode.GNSS_AIDED, drHeadingDeg = 10f, newFixHeadingDeg = 90f)
        assertEquals(90f, result, 0.001f)
    }

    @Test
    fun `GNSS_AIDED with no bearing falls back to the dr heading`() {
        val fusion = HeadingFusion()
        val result = fusion.update(nowMs = 0L, mode = GnssMode.GNSS_AIDED, drHeadingDeg = 10f, newFixHeadingDeg = null)
        assertEquals(10f, result, 0.001f)
    }

    @Test
    fun `TRANSITION freezes at the dr heading from the instant it was entered`() {
        val fusion = HeadingFusion()
        val first = fusion.update(0L, GnssMode.TRANSITION, drHeadingDeg = 45f, newFixHeadingDeg = null)
        assertEquals(45f, first, 0.001f)

        // Same mode, dr heading keeps changing in the background — frozen output must NOT track it.
        val second = fusion.update(500L, GnssMode.TRANSITION, drHeadingDeg = 200f, newFixHeadingDeg = null)
        assertEquals(45f, second, 0.001f)
    }

    @Test
    fun `DEAD_RECKONING passes the live dr heading straight through`() {
        val fusion = HeadingFusion()
        val first = fusion.update(0L, GnssMode.DEAD_RECKONING, drHeadingDeg = 30f, newFixHeadingDeg = null)
        assertEquals(30f, first, 0.001f)
        val second = fusion.update(100L, GnssMode.DEAD_RECKONING, drHeadingDeg = 60f, newFixHeadingDeg = null)
        assertEquals(60f, second, 0.001f)
    }

    @Test
    fun `REACQUISITION at the instant it is entered returns the dr heading it started from`() {
        val fusion = HeadingFusion(reacquisitionBlendMs = 1000L)
        val result = fusion.update(
            nowMs = 0L,
            mode = GnssMode.REACQUISITION,
            drHeadingDeg = 10f,
            newFixHeadingDeg = 90f,
        )
        assertEquals(10f, result, 0.001f)
    }

    @Test
    fun `REACQUISITION at or past the blend window returns exactly the new fix heading`() {
        val fusion = HeadingFusion(reacquisitionBlendMs = 1000L)
        fusion.update(0L, GnssMode.REACQUISITION, drHeadingDeg = 10f, newFixHeadingDeg = 90f)
        val done = fusion.update(1000L, GnssMode.REACQUISITION, drHeadingDeg = 10f, newFixHeadingDeg = 90f)
        assertEquals(90f, done, 0.01f)

        val pastWindow = fusion.update(5000L, GnssMode.REACQUISITION, drHeadingDeg = 10f, newFixHeadingDeg = 90f)
        assertEquals(90f, pastWindow, 0.01f)
    }

    @Test
    fun `REACQUISITION interpolates the SHORT way across the 0-360 wrap boundary`() {
        // The exact scenario this class exists for: dr heading 350 deg,
        // new fix 10 deg — the shortest path is +20 deg (through 360/0),
        // NOT a naive linear lerp from 350 down to 10 (-340 deg, the long
        // way around, which is what produced the visible map spin bug).
        val fusion = HeadingFusion(reacquisitionBlendMs = 1000L)
        fusion.update(0L, GnssMode.REACQUISITION, drHeadingDeg = 350f, newFixHeadingDeg = 10f)
        val midpoint = fusion.update(500L, GnssMode.REACQUISITION, drHeadingDeg = 350f, newFixHeadingDeg = 10f)
        // Halfway along the short 20-degree arc from 350 -> 360/0 -> 10 is 0 deg.
        val normalized = if (midpoint > 180f) midpoint - 360f else midpoint
        assertEquals(0f, normalized, 1f)
    }

    @Test
    fun `REACQUISITION with no fix yet falls back to raw dr passthrough`() {
        val fusion = HeadingFusion()
        val result = fusion.update(nowMs = 0L, mode = GnssMode.REACQUISITION, drHeadingDeg = 77f, newFixHeadingDeg = null)
        assertEquals(77f, result, 0.001f)
    }

    @Test
    fun `reset clears mode tracking so the next mode change re-anchors correctly`() {
        val fusion = HeadingFusion()
        fusion.update(0L, GnssMode.TRANSITION, drHeadingDeg = 45f, newFixHeadingDeg = null)
        fusion.reset()
        val afterReset = fusion.update(0L, GnssMode.TRANSITION, drHeadingDeg = 200f, newFixHeadingDeg = null)
        assertEquals(200f, afterReset, 0.001f)
    }
}
