package com.sih26168.idr.fusion

import org.junit.Assert.assertEquals
import org.junit.Test

class DriftSummaryTest {

    @Test
    fun `identical dr and gnss positions have zero drift`() {
        val result = DriftSummary.compute(drEastM = 10.0, drNorthM = 5.0, gnssEastM = 10.0, gnssNorthM = 5.0)
        assertEquals(0.0, result.driftMeters, 0.0001)
    }

    @Test
    fun `drift is the straight-line distance between dr and gnss positions`() {
        // 3-4-5 right triangle — hand-derived, not just "some positive number".
        val result = DriftSummary.compute(drEastM = 3.0, drNorthM = 0.0, gnssEastM = 0.0, gnssNorthM = 4.0)
        assertEquals(5.0, result.driftMeters, 0.0001)
    }

    @Test
    fun `distanceTravelledMeters is the straight-line distance from the anchor to the dr position`() {
        val result = DriftSummary.compute(drEastM = 6.0, drNorthM = 8.0, gnssEastM = 6.0, gnssNorthM = 8.0)
        assertEquals(10.0, result.distanceTravelledMeters, 0.0001)
    }

    @Test
    fun `drift is symmetric regardless of which position is dr vs gnss`() {
        val a = DriftSummary.compute(drEastM = 1.0, drNorthM = 2.0, gnssEastM = 4.0, gnssNorthM = 6.0)
        val b = DriftSummary.compute(drEastM = 4.0, drNorthM = 6.0, gnssEastM = 1.0, gnssNorthM = 2.0)
        assertEquals(a.driftMeters, b.driftMeters, 0.0001)
    }

    @Test
    fun `negative coordinates are handled correctly`() {
        val result = DriftSummary.compute(drEastM = -3.0, drNorthM = -4.0, gnssEastM = 0.0, gnssNorthM = 0.0)
        assertEquals(5.0, result.driftMeters, 0.0001)
        assertEquals(5.0, result.distanceTravelledMeters, 0.0001)
    }
}
