package com.sih26168.idr.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PotholeShockDetectorTest {

    @Test
    fun `below the threshold is not a shock`() {
        val detector = PotholeShockDetector(verticalShockThresholdMps2 = 4.0f)
        assertFalse(detector.isShock(3.9f))
    }

    @Test
    fun `at the threshold is a shock`() {
        val detector = PotholeShockDetector(verticalShockThresholdMps2 = 4.0f)
        assertTrue(detector.isShock(4.0f))
    }

    @Test
    fun `well above the threshold is a shock`() {
        val detector = PotholeShockDetector(verticalShockThresholdMps2 = 4.0f)
        assertTrue(detector.isShock(12.5f))
    }

    @Test
    fun `a large negative spike is a shock too, via magnitude not raw sign`() {
        val detector = PotholeShockDetector(verticalShockThresholdMps2 = 4.0f)
        assertTrue(detector.isShock(-6.0f))
    }

    @Test
    fun `a small negative value is not a shock`() {
        val detector = PotholeShockDetector(verticalShockThresholdMps2 = 4.0f)
        assertFalse(detector.isShock(-0.5f))
    }

    @Test
    fun `zero is never a shock`() {
        val detector = PotholeShockDetector(verticalShockThresholdMps2 = 4.0f)
        assertFalse(detector.isShock(0.0f))
    }
}
