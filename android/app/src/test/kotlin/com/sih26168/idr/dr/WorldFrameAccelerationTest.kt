package com.sih26168.idr.dr

import org.junit.Assert.assertEquals
import org.junit.Test

class WorldFrameAccelerationTest {

    private val tolerance = 1e-4f
    private val identity = listOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    @Test
    fun `identity rotation leaves the vector unchanged`() {
        val world = WorldFrameAcceleration.rotateDeviceToWorld(1f, 2f, 3f, identity)
        assertEquals(1f, world[0], tolerance)
        assertEquals(2f, world[1], tolerance)
        assertEquals(3f, world[2], tolerance)
    }

    @Test
    fun `a non-identity rotation matrix routes device axes to the matrix-defined world axes`() {
        // Not a physically-meaningful device orientation — just verifies
        // rotateDeviceToWorld does a real row-major matrix-vector multiply
        // (device Y here only has a nonzero coefficient on the world-Up
        // row) rather than e.g. silently passing components through.
        val matrix = listOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f,
        )
        val world = WorldFrameAcceleration.rotateDeviceToWorld(0f, 1f, 0f, matrix)
        assertEquals(0f, world[0], tolerance)
        assertEquals(0f, world[1], tolerance)
        assertEquals(1f, world[2], tolerance)
    }

    @Test
    fun `stationary device reading is near-zero after removing standard gravity`() {
        // A phone lying flat at rest reads ~+9.81 on device Z, which with
        // identity rotation is also world Up — this is the whole-pipeline
        // "at rest" case removeGravity exists to cancel out.
        val world = WorldFrameAcceleration.rotateDeviceToWorld(
            0f, 0f, WorldFrameAcceleration.STANDARD_GRAVITY_MPS2, identity,
        )
        val linear = WorldFrameAcceleration.removeGravity(world)
        assertEquals(0f, linear[0], tolerance)
        assertEquals(0f, linear[1], tolerance)
        assertEquals(0f, linear[2], tolerance)
    }

    @Test
    fun `removeGravity only touches the up component`() {
        val world = floatArrayOf(2f, 3f, WorldFrameAcceleration.STANDARD_GRAVITY_MPS2 + 1f)
        val linear = WorldFrameAcceleration.removeGravity(world)
        assertEquals(2f, linear[0], tolerance)
        assertEquals(3f, linear[1], tolerance)
        assertEquals(1f, linear[2], tolerance)
    }
}
