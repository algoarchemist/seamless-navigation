package com.sih26168.idr.capture

/**
 * One recorded sensor tick — accel, gyro, and orientation captured
 * together. [elapsedMs] is measured from the SAME boot-time-monotonic
 * sensor clock family accel/gyro/orientation timestamps already use
 * (CLAUDE.md Rule 9/14) — NOT wall-clock time, and NOT reconciled against
 * GNSS's wall-clock fixes.
 */
data class SensorRecordEntry(
    val elapsedMs: Long,
    val accelXMps2: Float,
    val accelYMps2: Float,
    val accelZMps2: Float,
    val gyroXRadPerSec: Float,
    val gyroYRadPerSec: Float,
    val gyroZRadPerSec: Float,
    val azimuthRad: Float,
    val pitchRad: Float,
    val rollRad: Float,
)

/**
 * Minimal one-off data-capture tool (CLAUDE.md Rule 18: a small prototype
 * before a large abstraction) for gathering real, physically-moved-phone
 * sensor data — the "self-captured Pothole/Phone-Moved data"
 * docs/PROJECT_MAP.md already lists as blocking train_motion_classifier.py.
 * NOT part of the shipped demo's state machine or position estimate
 * (same "clearly separated test tooling" spirit as CLAUDE.md Rule 8,
 * even though this records real motion rather than faking anything) —
 * purely a logger a caller starts/stops and then reads back.
 *
 * Pure in-memory accumulation, no Android/file IO here — keeps the
 * elapsed-ms math plain-JVM unit-testable per Rule 19. Writing
 * [toJsonArray]'s output to disk is the caller's job.
 */
class SensorRecorder {
    private val entries = mutableListOf<SensorRecordEntry>()
    private var startTimestampNs: Long? = null

    val recordedCount: Int get() = entries.size

    fun record(
        timestampNs: Long,
        accelXMps2: Float,
        accelYMps2: Float,
        accelZMps2: Float,
        gyroXRadPerSec: Float,
        gyroYRadPerSec: Float,
        gyroZRadPerSec: Float,
        azimuthRad: Float,
        pitchRad: Float,
        rollRad: Float,
    ) {
        val start = startTimestampNs ?: timestampNs.also { startTimestampNs = it }
        val elapsedMs = (timestampNs - start) / 1_000_000L
        entries += SensorRecordEntry(
            elapsedMs = elapsedMs,
            accelXMps2 = accelXMps2,
            accelYMps2 = accelYMps2,
            accelZMps2 = accelZMps2,
            gyroXRadPerSec = gyroXRadPerSec,
            gyroYRadPerSec = gyroYRadPerSec,
            gyroZRadPerSec = gyroZRadPerSec,
            azimuthRad = azimuthRad,
            pitchRad = pitchRad,
            rollRad = rollRad,
        )
    }

    fun reset() {
        entries.clear()
        startTimestampNs = null
    }

    /**
     * Hand-written JSON, not a library (CLAUDE.md Rule 2 — no new
     * dependency for a one-off tool) — a flat array of flat objects, one
     * per recorded tick.
     */
    fun toJsonArray(): String {
        val sb = StringBuilder("[\n")
        entries.forEachIndexed { index, e ->
            sb.append("  {")
            sb.append("\"elapsedMs\":${e.elapsedMs},")
            sb.append("\"accelXMps2\":${e.accelXMps2},\"accelYMps2\":${e.accelYMps2},\"accelZMps2\":${e.accelZMps2},")
            sb.append("\"gyroXRadPerSec\":${e.gyroXRadPerSec},\"gyroYRadPerSec\":${e.gyroYRadPerSec},\"gyroZRadPerSec\":${e.gyroZRadPerSec},")
            sb.append("\"azimuthRad\":${e.azimuthRad},\"pitchRad\":${e.pitchRad},\"rollRad\":${e.rollRad}")
            sb.append("}")
            if (index != entries.lastIndex) sb.append(",")
            sb.append("\n")
        }
        sb.append("]\n")
        return sb.toString()
    }
}
