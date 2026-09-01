package com.sih26168.idr.capture

/**
 * One logged tick — every raw input the hysteresis/ZUPT thresholds below
 * actually react to, plus their own real-time decision, so a real drive's
 * log can be checked against what SHOULD have happened, not just what did:
 *  - `gnss/GnssQuality.DEFAULT_MAX_FIX_AGE_MS`/`DEFAULT_MAX_ACCURACY_M`
 *  - `gnss/GnssOutageDetector`'s four dwell constants
 *  - `dr/StationaryDetector`'s accel/gyro/dwell thresholds
 * All three are documented in their own files as "engineering defaults,
 * not yet validated against a real test drive" (CLAUDE.md Rule 13) — this
 * is the tooling that closes that gap, per Rule 18 ("build a small
 * prototype/test first rather than a large abstraction around an
 * unverified assumption").
 */
data class DriveLogEntry(
    val elapsedMs: Long,
    val gnssMode: String,
    val gnssFixAccuracyM: Float?,
    val gnssFixAgeMs: Long,
    val gnssSpeedMps: Float?,
    val drVelocityEastMps: Double,
    val drVelocityNorthMps: Double,
    /** UPDATE (2026-08-30): low-pass FILTERED, not raw — see DeadReckoningState's own doc. */
    val linearAccelMagnitudeMps2: Double,
    /** UPDATE (2026-08-30): low-pass FILTERED, not raw — see DeadReckoningState's own doc. */
    val gyroMagnitudeRadPerSec: Double,
    val isStationary: Boolean,
    /**
     * UPDATE (2026-09-01): PRE-filter magnitude, added after the real
     * outdoor drive found ZUPT 100% false-negative against filtered-only
     * logging — lets `scripts/analyze_drive_log.py` compare raw vs.
     * filtered separability and tune `LowPassFilter`'s cutoffHz itself,
     * not just the StationaryDetector threshold. See DeadReckoningState's
     * own doc.
     */
    val rawLinearAccelMagnitudeMps2: Double,
    val rawGyroMagnitudeRadPerSec: Double,
)

/**
 * Minimal one-off data-capture tool (CLAUDE.md Rule 18), same spirit as
 * [SensorRecorder] but for a REAL TEST DRIVE rather than a stationary
 * motion-classifier capture: one row per DR tick (~10 Hz) combining
 * dr/BaselineDeadReckoningRepository's [com.sih26168.idr.dr.DeadReckoningState]
 * (which now also carries its ZUPT inputs/decision, see that class's doc)
 * with gnss/GnssModeRepository's [com.sih26168.idr.gnss.GnssModeUiState]
 * at the same instant. NOT part of the shipped demo's state machine or
 * position estimate (CLAUDE.md Rule 8's "clearly separated test tooling"
 * — this only READS already-real values, same as [SensorRecorder]) —
 * MainActivity's Start/Stop button decides when rows are appended, this
 * class only accumulates and serializes them.
 *
 * CSV (not JSON like [SensorRecorder]) specifically so `scripts/
 * analyze_drive_log.py` can load it with `pandas.read_csv` directly —
 * pandas is already a dependency of `ml/inspect_dataset.py`, so this adds
 * no new tooling dependency (CLAUDE.md Rule 2).
 *
 * Pure in-memory accumulation, no Android/file IO here — keeps the
 * elapsed-ms math and CSV formatting plain-JVM unit-testable (Rule 19).
 * Writing [toCsv]'s output to disk is the caller's (MainActivity's) job.
 */
class DriveDataLogger {
    private val entries = mutableListOf<DriveLogEntry>()
    private var startTimestampNs: Long? = null

    val recordedCount: Int get() = entries.size

    fun record(
        timestampNs: Long,
        gnssMode: String,
        gnssFixAccuracyM: Float?,
        gnssFixAgeMs: Long,
        gnssSpeedMps: Float?,
        drVelocityEastMps: Double,
        drVelocityNorthMps: Double,
        linearAccelMagnitudeMps2: Double,
        gyroMagnitudeRadPerSec: Double,
        isStationary: Boolean,
        rawLinearAccelMagnitudeMps2: Double,
        rawGyroMagnitudeRadPerSec: Double,
    ) {
        val start = startTimestampNs ?: timestampNs.also { startTimestampNs = it }
        val elapsedMs = (timestampNs - start) / 1_000_000L
        entries += DriveLogEntry(
            elapsedMs = elapsedMs,
            gnssMode = gnssMode,
            gnssFixAccuracyM = gnssFixAccuracyM,
            gnssFixAgeMs = gnssFixAgeMs,
            gnssSpeedMps = gnssSpeedMps,
            drVelocityEastMps = drVelocityEastMps,
            drVelocityNorthMps = drVelocityNorthMps,
            linearAccelMagnitudeMps2 = linearAccelMagnitudeMps2,
            gyroMagnitudeRadPerSec = gyroMagnitudeRadPerSec,
            isStationary = isStationary,
            rawLinearAccelMagnitudeMps2 = rawLinearAccelMagnitudeMps2,
            rawGyroMagnitudeRadPerSec = rawGyroMagnitudeRadPerSec,
        )
    }

    fun reset() {
        entries.clear()
        startTimestampNs = null
    }

    fun toCsv(): String {
        val sb = StringBuilder()
        sb.append("elapsedMs,gnssMode,gnssFixAccuracyM,gnssFixAgeMs,gnssSpeedMps,")
        sb.append("drVelocityEastMps,drVelocityNorthMps,linearAccelMagnitudeMps2,gyroMagnitudeRadPerSec,isStationary,")
        sb.append("rawLinearAccelMagnitudeMps2,rawGyroMagnitudeRadPerSec\n")
        entries.forEach { e ->
            sb.append(e.elapsedMs).append(',')
            sb.append(e.gnssMode).append(',')
            sb.append(e.gnssFixAccuracyM?.toString() ?: "").append(',')
            sb.append(e.gnssFixAgeMs).append(',')
            sb.append(e.gnssSpeedMps?.toString() ?: "").append(',')
            sb.append(e.drVelocityEastMps).append(',')
            sb.append(e.drVelocityNorthMps).append(',')
            sb.append(e.linearAccelMagnitudeMps2).append(',')
            sb.append(e.gyroMagnitudeRadPerSec).append(',')
            sb.append(e.isStationary).append(',')
            sb.append(e.rawLinearAccelMagnitudeMps2).append(',')
            sb.append(e.rawGyroMagnitudeRadPerSec)
            sb.append('\n')
        }
        return sb.toString()
    }
}
