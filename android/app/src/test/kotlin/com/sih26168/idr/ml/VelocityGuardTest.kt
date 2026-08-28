package com.sih26168.idr.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VelocityGuardTest {

    @Test
    fun `first plausible sample is accepted as-is, no prior average to blend with`() {
        val guard = VelocityGuard()
        val result = guard.apply(10.0f)
        assertEquals(10.0f, result.velocityMps, 0.0001f)
        assertFalse(result.wasOutOfDistribution)
    }

    @Test
    fun `subsequent samples are exponentially smoothed toward the new value`() {
        val guard = VelocityGuard(emaAlpha = 0.5f)
        guard.apply(10.0f)
        val second = guard.apply(20.0f)
        // 10 + 0.5 * (20 - 10) = 15 — hand-derived, not just "some change happened".
        assertEquals(15.0f, second.velocityMps, 0.0001f)
        assertFalse(second.wasOutOfDistribution)
    }

    @Test
    fun `a value beyond the plausible bound is rejected and the last accepted value is held`() {
        val guard = VelocityGuard(maxPlausibleSpeedMps = 55.0f)
        guard.apply(10.0f)
        val result = guard.apply(1000.0f)
        assertEquals(10.0f, result.velocityMps, 0.0001f)
        assertTrue(result.wasOutOfDistribution)
    }

    @Test
    fun `a negative value beyond the bound in magnitude is also rejected`() {
        val guard = VelocityGuard(maxPlausibleSpeedMps = 55.0f)
        guard.apply(5.0f)
        val result = guard.apply(-1000.0f)
        assertEquals(5.0f, result.velocityMps, 0.0001f)
        assertTrue(result.wasOutOfDistribution)
    }

    @Test
    fun `NaN and infinite predictions are rejected`() {
        val guard = VelocityGuard()
        guard.apply(3.0f)
        val nanResult = guard.apply(Float.NaN)
        assertEquals(3.0f, nanResult.velocityMps, 0.0001f)
        assertTrue(nanResult.wasOutOfDistribution)

        val infResult = guard.apply(Float.POSITIVE_INFINITY)
        assertEquals(3.0f, infResult.velocityMps, 0.0001f)
        assertTrue(infResult.wasOutOfDistribution)
    }

    @Test
    fun `rejecting a sample before any accepted sample holds zero`() {
        val guard = VelocityGuard(maxPlausibleSpeedMps = 55.0f)
        val result = guard.apply(1000.0f)
        assertEquals(0f, result.velocityMps, 0.0001f)
        assertTrue(result.wasOutOfDistribution)
    }

    @Test
    fun `a value exactly at the bound is accepted`() {
        val guard = VelocityGuard(maxPlausibleSpeedMps = 55.0f)
        val result = guard.apply(55.0f)
        assertEquals(55.0f, result.velocityMps, 0.0001f)
        assertFalse(result.wasOutOfDistribution)
    }

    @Test
    fun `reset clears the smoothed value and accepted-sample state`() {
        val guard = VelocityGuard()
        guard.apply(10.0f)
        guard.reset()
        val result = guard.apply(3.0f)
        // Post-reset, the next accepted sample is taken as-is again (no
        // blending with the pre-reset value of 10.0).
        assertEquals(3.0f, result.velocityMps, 0.0001f)
    }
}
