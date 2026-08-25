package com.sih26168.idr.sensors

import kotlin.math.asin
import kotlin.math.atan2

/**
 * Device orientation relative to the WORLD/EARTH frame (East-North-Up),
 * in radians, using Android's own azimuth/pitch/roll convention:
 * - azimuthRad: angle around Z axis, 0 = magnetic north, +ve = clockwise
 *   when viewed from above. Range (-pi, pi].
 * - pitchRad: angle around X axis. Range [-pi/2, pi/2].
 * - rollRad: angle around Y axis. Range (-pi, pi].
 *
 * This is DEVICE-frame-relative-to-WORLD orientation only (CLAUDE.md
 * Rule 9/14) — it says nothing about how the phone is mounted in the
 * vehicle. Phone-to-vehicle alignment (PRD.md Section 15) is a separate,
 * not-yet-built step that needs a GNSS-aided initialization window, so
 * it cannot exist before GNSS is wired in (later slice).
 */
data class OrientationAngles(
    val azimuthRad: Float,
    val pitchRad: Float,
    val rollRad: Float,
)

/**
 * Pure-Kotlin (no android.* import) conversion from the rotation-vector
 * sensor's quaternion output to azimuth/pitch/roll, so this math is
 * unit-testable on the plain JVM (CLAUDE.md Rule 19) instead of only
 * verifiable on-device. The algorithm deliberately mirrors AOSP's
 * SensorManager.getRotationMatrixFromVector + getOrientation exactly
 * (same intermediate rotation-matrix convention) so on-device output is
 * expected to numerically match this, not just be "close."
 */
object OrientationMath {

    /**
     * Android's TYPE_ROTATION_VECTOR reports the vector part (x, y, z) of
     * a unit quaternion describing device-frame -> world-frame rotation;
     * the scalar part w is reported in values[3] on modern devices, or
     * must be derived as sqrt(1 - x^2 - y^2 - z^2) when absent.
     */
    fun scalarFromVectorPart(x: Float, y: Float, z: Float): Float {
        val sumOfSquares = x * x + y * y + z * z
        // Clamp: floating-point error can push sumOfSquares fractionally
        // above 1.0 for a should-be-unit quaternion, which would make
        // sqrt(negative) = NaN.
        return if (sumOfSquares > 1f) 0f else Math.sqrt((1f - sumOfSquares).toDouble()).toFloat()
    }

    /**
     * Quaternion (x, y, z, w), device-frame -> world-frame, to a
     * row-major 3x3 rotation matrix (9 floats): index [row*3 + col].
     * Matches AOSP SensorManager.getRotationMatrixFromVector.
     */
    fun quaternionToRotationMatrix(x: Float, y: Float, z: Float, w: Float): FloatArray {
        val sqX2 = 2 * x * x
        val sqY2 = 2 * y * y
        val sqZ2 = 2 * z * z
        val xy2 = 2 * x * y
        val zw2 = 2 * z * w
        val xz2 = 2 * x * z
        val yw2 = 2 * y * w
        val yz2 = 2 * y * z
        val xw2 = 2 * x * w

        return floatArrayOf(
            1 - sqY2 - sqZ2, xy2 - zw2, xz2 + yw2,
            xy2 + zw2, 1 - sqX2 - sqZ2, yz2 - xw2,
            xz2 - yw2, yz2 + xw2, 1 - sqX2 - sqY2,
        )
    }

    /**
     * Row-major 3x3 rotation matrix (9 floats, as produced by
     * [quaternionToRotationMatrix]) to azimuth/pitch/roll radians.
     * Matches AOSP SensorManager.getOrientation.
     */
    fun rotationMatrixToOrientation(r: FloatArray): OrientationAngles {
        require(r.size == 9) { "rotation matrix must have 9 elements, got ${r.size}" }
        val azimuthRad = atan2(r[1], r[4])
        val pitchRad = asin(-r[7].coerceIn(-1f, 1f))
        val rollRad = atan2(-r[6], r[8])
        return OrientationAngles(azimuthRad, pitchRad, rollRad)
    }

    /** Convenience: rotation-vector quaternion straight to azimuth/pitch/roll. */
    fun orientationFromQuaternion(x: Float, y: Float, z: Float, w: Float): OrientationAngles =
        rotationMatrixToOrientation(quaternionToRotationMatrix(x, y, z, w))
}
