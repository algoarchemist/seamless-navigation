package com.sih26168.idr.alignment

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YawRateTest {

    private val tolerance = 1e-4f

    @Test
    fun `first sample with no previous returns null`() {
        val rate = YawRate.radPerSecond(null, null, azimuthRad = 0f, nowNs = 1_000_000_000L)
        assertNull(rate)
    }

    @Test
    fun `quarter turn over one second is pi over 4 rad per sec`() {
        val rate = YawRate.radPerSecond(
            previousAzimuthRad = 0f,
            previousTimestampNs = 0L,
            azimuthRad = (PI / 4).toFloat(),
            nowNs = 1_000_000_000L,
        )
        assertEquals((PI / 4).toFloat(), rate!!, tolerance)
    }

    @Test
    fun `wrap around the plus-minus 180 boundary reads as a small step, not a huge jump`() {
        // 170 degrees -> -170 degrees is a 20-degree step forward
        // (170 -> 180/-180 -> -170), not a ~340-degree jump backward.
        val previousAzimuthRad = Math.toRadians(170.0).toFloat()
        val azimuthRad = Math.toRadians(-170.0).toFloat()
        val rate = YawRate.radPerSecond(previousAzimuthRad, 0L, azimuthRad, 1_000_000_000L)
        val expectedRadPerSec = Math.toRadians(20.0).toFloat()
        assertEquals(expectedRadPerSec, rate!!, tolerance)
    }

    @Test
    fun `zero or negative dt returns null`() {
        assertNull(YawRate.radPerSecond(0f, 1000L, 0.1f, 1000L))
        assertNull(YawRate.radPerSecond(0f, 1000L, 0.1f, 500L))
    }

    @Test
    fun `negative turn direction is preserved`() {
        val rate = YawRate.radPerSecond(
            previousAzimuthRad = (PI / 4).toFloat(),
            previousTimestampNs = 0L,
            azimuthRad = 0f,
            nowNs = 1_000_000_000L,
        )
        assertEquals(-(PI / 4).toFloat(), rate!!, tolerance)
    }
}
