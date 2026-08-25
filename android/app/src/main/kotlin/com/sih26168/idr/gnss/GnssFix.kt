package com.sih26168.idr.gnss

/**
 * A single GNSS fix, reduced to what the outage detector / (later) state
 * estimator actually need from android.location.Location.
 *
 * timeMs is WALL-CLOCK (System.currentTimeMillis()-based, from
 * Location.getTime()) — unlike sensor timestamps (SensorEvent.timestamp,
 * boot-time monotonic; see sensors/SensorSample.kt), which are NOT
 * reconciled against this yet (CLAUDE.md Rule 9/14). That reconciliation
 * (aligning boot-time IMU samples to wall-clock GNSS fixes) is deferred
 * to the fusion slice (Slice 7) — this slice only reads and classifies
 * GNSS fixes on their own wall-clock timeline.
 */
data class GnssFix(
    val timeMs: Long,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val accuracyM: Float,
    val speedMps: Float?,
    val bearingDeg: Float?,
)
