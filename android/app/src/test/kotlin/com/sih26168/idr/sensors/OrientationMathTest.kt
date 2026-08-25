package com.sih26168.idr.sensors

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationMathTest {

    private val tolerance = 1e-4f

    @Test
    fun `identity quaternion is zero azimuth, pitch, and roll`() {
        val angles = OrientationMath.orientationFromQuaternion(x = 0f, y = 0f, z = 0f, w = 1f)
        assertEquals(0f, angles.azimuthRad, tolerance)
        assertEquals(0f, angles.pitchRad, tolerance)
        assertEquals(0f, angles.rollRad, tolerance)
    }

    @Test
    fun `90 degree counterclockwise yaw quaternion reads as minus 90 degree azimuth`() {
        // Quaternion for a mathematically-positive (counterclockwise,
        // right-hand rule) 90-degree rotation about world Z:
        // (x, y, z, w) = (0, 0, sin(45deg), cos(45deg)). Android's azimuth
        // is clockwise-positive (compass-bearing convention, see
        // OrientationAngles doc), so this reads as -90 degrees, not +90.
        val halfAngle = (PI / 4).toFloat()
        val q = OrientationMath.orientationFromQuaternion(
            x = 0f,
            y = 0f,
            z = sin(halfAngle),
            w = cos(halfAngle),
        )
        assertEquals((-PI / 2).toFloat(), q.azimuthRad, tolerance)
        assertEquals(0f, q.pitchRad, tolerance)
        assertEquals(0f, q.rollRad, tolerance)
    }

    @Test
    fun `scalar derived from vector part matches an explicit w for a unit quaternion`() {
        val halfAngle = (PI / 6).toFloat() // 30 degree rotation
        val z = sin(halfAngle)
        val expectedW = cos(halfAngle)
        val derivedW = OrientationMath.scalarFromVectorPart(x = 0f, y = 0f, z = z)
        assertEquals(expectedW, derivedW, tolerance)
    }

    @Test
    fun `scalar derivation clamps instead of producing NaN when floating point pushes norm above 1`() {
        // A should-be-unit vector part nudged fractionally over 1.0 total
        // norm by floating-point error must not yield NaN via sqrt(negative).
        val derivedW = OrientationMath.scalarFromVectorPart(x = 0.8f, y = 0.6f, z = 0.001f)
        assertEquals(0f, derivedW, tolerance)
    }

    @Test
    fun `rotation matrix from identity quaternion is the identity matrix`() {
        val r = OrientationMath.quaternionToRotationMatrix(x = 0f, y = 0f, z = 0f, w = 1f)
        val expected = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
        )
        for (i in expected.indices) {
            assertEquals(expected[i], r[i], tolerance)
        }
    }
}
