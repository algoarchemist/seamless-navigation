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
