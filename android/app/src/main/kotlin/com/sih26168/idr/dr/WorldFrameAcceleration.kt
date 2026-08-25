package com.sih26168.idr.dr

/**
 * Pure math (no android.* import, unit-testable on the plain JVM per
 * CLAUDE.md Rule 19) for turning a raw DEVICE-frame accelerometer
 * reading into WORLD-frame (East, North, Up) linear acceleration —
 * i.e. motion-caused acceleration with gravity's reaction force
 * subtracted out.
 *
 * This is explicitly WORLD frame, NOT vehicle frame (CLAUDE.md
 * Rule 9/14): it only needs the device's own orientation relative to
 * Earth (Slice 2's rotation matrix), not phone-to-vehicle alignment
 * (PRD.md Section 15, which needs GNSS and isn't built yet). A
 * world-frame baseline works regardless of how the phone is mounted in
 * the vehicle, which is exactly why it's the right scope for Slice 3.
 */
object WorldFrameAcceleration {

    /** Standard gravity, m/s^2 — used as a fixed approximation, not a
     * per-location measured value; local gravity varies by a few parts
     * in 10,000, negligible next to raw MEMS accelerometer bias/noise. */
    const val STANDARD_GRAVITY_MPS2 = 9.80665f

    /**
     * Rotates a DEVICE-frame vector into WORLD frame (East, North, Up)
     * using the row-major 3x3 device->world rotation matrix from
     * [com.sih26168.idr.sensors.OrientationMath.quaternionToRotationMatrix]
     * (worldVector = R * deviceVector).
     */
    fun rotateDeviceToWorld(
        deviceX: Float,
        deviceY: Float,
        deviceZ: Float,
        rotationMatrixDeviceToWorld: List<Float>,
    ): FloatArray {
        require(rotationMatrixDeviceToWorld.size == 9) {
            "rotation matrix must have 9 elements, got ${rotationMatrixDeviceToWorld.size}"
        }
        val r = rotationMatrixDeviceToWorld
        val worldEast = r[0] * deviceX + r[1] * deviceY + r[2] * deviceZ
        val worldNorth = r[3] * deviceX + r[4] * deviceY + r[5] * deviceZ
        val worldUp = r[6] * deviceX + r[7] * deviceY + r[8] * deviceZ
        return floatArrayOf(worldEast, worldNorth, worldUp)
    }

    /**
     * Subtracts gravity's reaction force from a WORLD-frame accelerometer
     * reading, leaving motion-caused linear acceleration. A stationary
     * device's accelerometer reads ~+g on world Up (per Android's
     * accelerometer convention — it measures the upward reaction force,
     * not true free-fall acceleration), so this is a plain subtraction
     * on the Up component only.
     */
    fun removeGravity(
        worldAccel: FloatArray,
        gravityMps2: Float = STANDARD_GRAVITY_MPS2,
    ): FloatArray {
        require(worldAccel.size == 3) { "world accel must have 3 elements, got ${worldAccel.size}" }
        return floatArrayOf(worldAccel[0], worldAccel[1], worldAccel[2] - gravityMps2)
    }
}
