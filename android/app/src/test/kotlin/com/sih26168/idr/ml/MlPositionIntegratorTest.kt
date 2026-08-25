package com.sih26168.idr.ml

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Test

class MlPositionIntegratorTest {

    private val tolerance = 1e-9

    @Test
    fun `zero or negative dt is a no-op`() {
        val integrator = MlPositionIntegrator()
        val before = integrator.currentState()
        val afterZero = integrator.update(dtSeconds = 0.0, velocityMps = 5f, headingRad = 0f, isStationary = false)
        assertEquals(before, afterZero)
        val afterNegative = integrator.update(dtSeconds = -1.0, velocityMps = 5f, headingRad = 0f, isStationary = false)
        assertEquals(before, afterNegative)
    }

    @Test
    fun `heading north moves purely north`() {
        val integrator = MlPositionIntegrator()
        val result = integrator.update(dtSeconds = 2.0, velocityMps = 3f, headingRad = 0f, isStationary = false)
        assertEquals(0.0, result.positionEastM, tolerance)
        assertEquals(6.0, result.positionNorthM, tolerance) // 3 m/s * 2s
    }

    @Test
    fun `heading east moves purely east`() {
        val integrator = MlPositionIntegrator()
        val headingRad = (PI / 2).toFloat()
        val result = integrator.update(dtSeconds = 2.0, velocityMps = 3f, headingRad = headingRad, isStationary = false)
        assertEquals(6.0, result.positionEastM, 1e-6)
        assertEquals(0.0, result.positionNorthM, 1e-6)
    }

    @Test
    fun `isStationary forces effective velocity to zero regardless of predicted speed`() {
        val integrator = MlPositionIntegrator()
        val result = integrator.update(dtSeconds = 5.0, velocityMps = 10f, headingRad = 0f, isStationary = true)
        assertEquals(0.0, result.positionEastM, tolerance)
        assertEquals(0.0, result.positionNorthM, tolerance)
    }

    @Test
    fun `position accumulates across multiple ticks`() {
        val integrator = MlPositionIntegrator()
        integrator.update(dtSeconds = 1.0, velocityMps = 2f, headingRad = 0f, isStationary = false)
        val result = integrator.update(dtSeconds = 1.0, velocityMps = 2f, headingRad = 0f, isStationary = false)
        assertEquals(4.0, result.positionNorthM, tolerance) // 2m + 2m
    }

    @Test
    fun `negative velocity moves in the opposite direction`() {
        val integrator = MlPositionIntegrator()
        val result = integrator.update(dtSeconds = 1.0, velocityMps = -5f, headingRad = 0f, isStationary = false)
        assertEquals(-5.0, result.positionNorthM, tolerance)
    }

    @Test
    fun `reset clears accumulated position`() {
        val integrator = MlPositionIntegrator()
        integrator.update(dtSeconds = 1.0, velocityMps = 5f, headingRad = 0f, isStationary = false)
        integrator.reset()
        assertEquals(MlDeadReckoningState(), integrator.currentState())
    }
}
