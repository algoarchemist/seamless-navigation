package com.sih26168.idr.dr

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Test

class NonHolonomicConstraintTest {

    private val tolerance = 1e-6

    @Test
    fun `heading north keeps the north component and drops the east component`() {
        val (east, north) = NonHolonomicConstraint.suppressLateralVelocity(
            velocityEastMps = 3.0,
            velocityNorthMps = 5.0,
            headingRad = 0f,
        )
        assertEquals(0.0, east, tolerance)
        assertEquals(5.0, north, tolerance)
    }

    @Test
    fun `heading east keeps the east component and drops the north component`() {
        val (east, north) = NonHolonomicConstraint.suppressLateralVelocity(
            velocityEastMps = 3.0,
            velocityNorthMps = 5.0,
            headingRad = (PI / 2).toFloat(),
        )
        assertEquals(3.0, east, tolerance)
        assertEquals(0.0, north, tolerance)
    }

    @Test
    fun `velocity purely along heading is unchanged`() {
        val headingRad = (PI / 4).toFloat()
        val forwardEast = kotlin.math.sin(headingRad.toDouble())
        val forwardNorth = kotlin.math.cos(headingRad.toDouble())
        val speed = 5.0
        val (east, north) = NonHolonomicConstraint.suppressLateralVelocity(
            velocityEastMps = speed * forwardEast,
            velocityNorthMps = speed * forwardNorth,
            headingRad = headingRad,
        )
        assertEquals(speed * forwardEast, east, tolerance)
        assertEquals(speed * forwardNorth, north, tolerance)
    }

    @Test
    fun `velocity purely perpendicular to heading is fully suppressed`() {
        val headingRad = (PI / 4).toFloat()
        // Perpendicular to (sin45, cos45) is (cos45, -sin45).
        val lateralEast = kotlin.math.cos(headingRad.toDouble())
        val lateralNorth = -kotlin.math.sin(headingRad.toDouble())
        val (east, north) = NonHolonomicConstraint.suppressLateralVelocity(
            velocityEastMps = 5.0 * lateralEast,
            velocityNorthMps = 5.0 * lateralNorth,
            headingRad = headingRad,
        )
        assertEquals(0.0, east, tolerance)
        assertEquals(0.0, north, tolerance)
    }

    @Test
    fun `reverse motion along heading is preserved with correct sign`() {
        val (east, north) = NonHolonomicConstraint.suppressLateralVelocity(
            velocityEastMps = 0.0,
            velocityNorthMps = -5.0,
            headingRad = 0f,
        )
        assertEquals(0.0, east, tolerance)
        assertEquals(-5.0, north, tolerance)
    }

    @Test
    fun `zero velocity stays zero`() {
        val (east, north) = NonHolonomicConstraint.suppressLateralVelocity(
            velocityEastMps = 0.0,
            velocityNorthMps = 0.0,
            headingRad = 1.23f,
        )
        assertEquals(0.0, east, tolerance)
        assertEquals(0.0, north, tolerance)
    }
}
