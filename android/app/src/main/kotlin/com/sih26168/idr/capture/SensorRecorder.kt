package com.sih26168.idr.capture

/**
 * One recorded sensor tick — accel, gyro, and orientation captured
 * together. [elapsedMs] is measured from the SAME boot-time-monotonic
 * sensor clock family accel/gyro/orientation timestamps already use
 * (CLAUDE.md Rule 9/14) — NOT wall-clock time, and NOT reconciled against
 * GNSS's wall-clock fixes.
 *
 * [label] (added 2026-09-01, PRD.md Section 24's "self-captured labelled
 * data" for the two motion-classifier classes IO-VNBD doesn't provide
 * ground truth for — Pothole and Phone Moved, per the Phase 4 finding in
 * docs/PROJECT_MAP.md) is whichever of [CaptureLabel] was ACTIVE at the
 * instant this tick was recorded, set by MainActivity's marker buttons —
 * not derived from the sensor values themselves (this class stays a dumb
 * recorder, same as before this field existed). Defaults to
 * [CaptureLabel.NONE] so a capture with no marker taps is still valid
 * (background/negative-class data, useful on its own).
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
    val label: String = CaptureLabel.NONE,
)

/**
 * The only two PRD.md Section 14 motion-classifier classes this project
 * actually needs manually-marked ground truth for (see SensorRecordEntry's
 * own doc) — every other class (Stationary/Moving/Accelerating/Braking/
 * Cruising/Turning) already has a deterministic stand-in or is weak-
 * labelable from GNSS speed/heading, per the Phase 4 dataset-inspection
 * finding in docs/PROJECT_MAP.md. Plain string constants, not a Kotlin
 * enum, so [SensorRecordEntry.label] round-trips through CSV/JSON without
 * a separate (de)serializer (CLAUDE.md Rule 2 — no new tooling for this).
 */
object CaptureLabel {
    const val NONE = "NONE"
    const val POTHOLE = "POTHOLE"
    const val PHONE_MOVED = "PHONE_MOVED"
}

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
        label: String = CaptureLabel.NONE,
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
            label = label,
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
            sb.append("\"azimuthRad\":${e.azimuthRad},\"pitchRad\":${e.pitchRad},\"rollRad\":${e.rollRad},")
            sb.append("\"label\":\"${e.label}\"")
            sb.append("}")
            if (index != entries.lastIndex) sb.append(",")
            sb.append("\n")
        }
        sb.append("]\n")
        return sb.toString()
    }
}
