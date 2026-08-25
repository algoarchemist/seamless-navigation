package com.sih26168.idr.dr

import org.junit.Assert.assertEquals
import org.junit.Test

class BaselinePhysicsIntegratorTest {

    private val tolerance = 1e-9

    @Test
    fun `zero or negative dt is a no-op`() {
        val integrator = BaselinePhysicsIntegrator()
        val before = integrator.currentState()
        val after = integrator.update(dtSeconds = 0.0, linearAccelEastMps2 = 5.0, linearAccelNorthMps2 = 5.0)
        assertEquals(before, after)
        val afterNegative = integrator.update(dtSeconds = -1.0, linearAccelEastMps2 = 5.0, linearAccelNorthMps2 = 5.0)
        assertEquals(before, afterNegative)
    }

    @Test
    fun `constant acceleration for ten 0point1s ticks matches the semi-implicit Euler analytic result`() {
        val integrator = BaselinePhysicsIntegrator()
        val dtSeconds = 0.1
        val accelEastMps2 = 1.0
        var last = integrator.currentState()
        repeat(10) {
            last = integrator.update(dtSeconds, accelEastMps2, linearAccelNorthMps2 = 0.0)
        }
        // Semi-implicit Euler: v_n = a*dt*n; pos_n = a*dt^2 * sum(1..n).
        // n=10, dt=0.1, a=1: v_10 = 1*0.1*10 = 1.0 m/s.
        // pos_10 = 1 * 0.01 * (10*11/2) = 0.01 * 55 = 0.55 m.
        assertEquals(1.0, last.velocityEastMps, tolerance)
        assertEquals(0.55, last.positionEastM, tolerance)
        assertEquals(0.0, last.velocityNorthMps, tolerance)
        assertEquals(0.0, last.positionNorthM, tolerance)
    }

    @Test
    fun `reset zeroes position and velocity`() {
        val integrator = BaselinePhysicsIntegrator()
        integrator.update(dtSeconds = 1.0, linearAccelEastMps2 = 3.0, linearAccelNorthMps2 = 4.0)
        integrator.reset()
        assertEquals(DeadReckoningState(), integrator.currentState())
    }

    @Test
    fun `east and north integrate independently`() {
        val integrator = BaselinePhysicsIntegrator()
        val result = integrator.update(dtSeconds = 2.0, linearAccelEastMps2 = 1.0, linearAccelNorthMps2 = -2.0)
        assertEquals(2.0, result.velocityEastMps, tolerance)
        assertEquals(-4.0, result.velocityNorthMps, tolerance)
        assertEquals(4.0, result.positionEastM, tolerance)
        assertEquals(-8.0, result.positionNorthM, tolerance)
    }
}
