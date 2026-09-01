package com.sih26168.idr.sensors

/**
 * A single accelerometer reading in DEVICE frame (CLAUDE.md Rule 9/14 —
 * the frame must be named explicitly at every boundary that touches
 * motion). Units: m/s^2, per Android's SensorEvent convention for
 * TYPE_ACCELEROMETER — gravity is included; this is raw, not
 * gravity-compensated. Gravity removal / vehicle-frame rotation is
 * Slice 2, not here.
 *
 * timestampNs is SensorEvent.timestamp: nanoseconds since an arbitrary
 * device boot-time epoch. It is NOT wall-clock time and must not be
 * compared against System.currentTimeMillis() without explicit
 * reconciliation (PRD.md Section 11).
 */
data class AccelSample(
    val timestampNs: Long,
    val xMps2: Float,
    val yMps2: Float,
    val zMps2: Float,
)

/**
 * A single gyroscope reading in DEVICE frame. Units: rad/s, per
 * Android's SensorEvent convention for TYPE_GYROSCOPE (angular velocity
 * about each device axis, right-hand rule).
 */
data class GyroSample(
    val timestampNs: Long,
    val xRadPerSec: Float,
    val yRadPerSec: Float,
    val zRadPerSec: Float,
)

/**
 * A single orientation reading: DEVICE orientation relative to the
 * WORLD/EARTH frame (East-North-Up), NOT vehicle frame (CLAUDE.md
 * Rule 9/14). Derived from Android's TYPE_ROTATION_VECTOR sensor fusion
 * output via [OrientationMath]. Units: rad, per PRD.md Section 11's
 * "device rotation vector as the base orientation source" — see
 * [OrientationAngles] for the azimuth/pitch/roll convention.
 *
 * Phone-to-vehicle alignment (PRD.md Section 15) is NOT applied here —
 * that requires a GNSS-aided initialization window and is a later slice.
 */
data class OrientationSample(
    val timestampNs: Long,
    val azimuthRad: Float,
    val pitchRad: Float,
    val rollRad: Float,
    // Row-major 3x3 rotation matrix, DEVICE frame -> WORLD frame (the same
    // matrix azimuth/pitch/roll above were extracted from). Kept alongside
    // the human-readable angles because Slice 3 (baseline physics) needs
    // to rotate a 3D accelerometer vector into world frame directly —
    // reconstructing a rotation matrix back out of azimuth/pitch/roll
    // afterward would be lossier and more error-prone than keeping the
    // matrix OrientationMath already computed. List<Float>, not
    // FloatArray, so this data class gets correct structural equals()
    // (Kotlin data classes compare FloatArray fields by reference, which
    // is a well-known footgun).
    val rotationMatrixDeviceToWorld: List<Float>,
)

/**
 * A single barometer reading (Round 2 addition, 2026-08-28 — PRD.md
 * FR12). Units: hPa, per Android's SensorEvent convention for
 * TYPE_PRESSURE. Not all devices have a barometer — see
 * SensorRepository.hasBarometer(); consumers must treat its absence as a
 * normal, honest case, not an error.
 */
data class PressureSample(
    val timestampNs: Long,
    val pressureHpa: Float,
)
