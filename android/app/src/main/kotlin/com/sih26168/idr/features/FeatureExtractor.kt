package com.sih26168.idr.features

import kotlin.math.sign

/** ~1.0s trailing window at ~10 Hz — MUST match ml/train_velocity_model.py's WINDOW_SAMPLES exactly. */
private const val WINDOW_SAMPLES = 10

/**
 * Kotlin mirror of ml/feature_extraction.py's windowed feature
 * computation (PRD.md Section 11/23) — turns a stream of already
 * vehicle-frame-rotated accel/gyro ticks into the same 13-element
 * feature vector the Python training pipeline produced, in the exact
 * order ml/train_velocity_model.py's FEATURE_COLUMNS expects (that
 * order is load-bearing for VelocityModel.kt's ONNX inference and must
 * never be changed independently of the Python list).
 *
 * Deliberately does NOT do the device-frame -> vehicle-frame rotation
 * itself — that is the caller's job (see the still-PLANNED wiring in
 * ml/MlVelocityRepository.kt), using WorldFrameAcceleration.kt +
 * AlignmentEstimator.kt's yaw offset. This class starts from already-
 * rotated forward/lateral/up components, matching CLAUDE.md Rule 5's
 * one-responsibility-per-file.
 *
 * KNOWN, DOCUMENTED PARITY GAP (not silently glossed over — CLAUDE.md
 * Rule 13): the Python pipeline built vehicle-frame axes via
 * device-frame Gram-Schmidt against a FIXED, KNOWN mounting (the
 * offline dataset's phone never moved relative to the car). This class
 * instead receives forward/lateral/up computed by projecting WORLD-frame
 * linear acceleration onto an alignment-corrected heading (see
 * AlignmentEstimator.kt) — mathematically similar under the "phone
 * yaw tracks vehicle yaw once aligned" assumption, but NOT bit-identical
 * to what training saw. A true cross-language output-parity test
 * (CLAUDE.md Rule 20) is not yet possible and is tracked as a real gap,
 * not assumed away.
 */
class FeatureExtractor {

    private val accelForwardWindow = RollingWindow(WINDOW_SAMPLES)
    private val accelLateralWindow = RollingWindow(WINDOW_SAMPLES)
    private val accelUpWindow = RollingWindow(WINDOW_SAMPLES)
    private val gyroYawRateWindow = RollingWindow(WINDOW_SAMPLES)
    private val jerkForwardWindow = RollingWindow(WINDOW_SAMPLES)
    private val signChangeWindow = RollingWindow(WINDOW_SAMPLES) // holds 0f/1f "did the sign change this tick" values

    private var previousAccelForwardMps2: Float? = null
    private var previousTimestampNs: Long? = null

    /**
     * Call once per ~10 Hz tick with already vehicle-frame-rotated
     * inputs. elapsedSinceLastGnssFixS should come from
     * GnssModeUiState.fixAgeMs / 1000f — the fix's own reported
     * staleness — not a re-derived "time since the value last changed"
     * (that was a Python-side proxy shaped by the offline dataset's
     * specific ~9s GPS-hold artifact; live Android GPS updates don't
     * share that artifact, so the direct staleness is the more honest
     * on-device equivalent of what this feature is meant to represent).
     *
     * @return 13-element feature vector, ordered to exactly match
     *   ml/train_velocity_model.py's FEATURE_COLUMNS:
     *   [accel_forward_mean, accel_forward_std, accel_forward_energy,
     *    accel_lateral_mean, accel_lateral_std, accel_up_mean, accel_up_std,
     *    gyro_yaw_rate_mean, gyro_yaw_rate_std, jerk_forward_mean,
     *    jerk_forward_std, accel_forward_zero_crossing_rate,
     *    elapsed_since_last_gnss_fix_s]
     */
    fun update(
        timestampNs: Long,
        accelForwardMps2: Float,
        accelLateralMps2: Float,
        accelUpMps2: Float,
        gyroYawRateRadPerSec: Float,
        elapsedSinceLastGnssFixS: Float,
    ): FloatArray {
        accelForwardWindow.add(accelForwardMps2)
        accelLateralWindow.add(accelLateralMps2)
        accelUpWindow.add(accelUpMps2)
        gyroYawRateWindow.add(gyroYawRateRadPerSec)

        val previousAccel = previousAccelForwardMps2
        val previousTime = previousTimestampNs

        val jerk = if (previousAccel != null && previousTime != null) {
            val dtSeconds = (timestampNs - previousTime) / 1_000_000_000.0
            if (dtSeconds > 0.0) ((accelForwardMps2 - previousAccel) / dtSeconds).toFloat() else 0f
        } else {
            0f
        }
        jerkForwardWindow.add(jerk)

        // Matches ml/feature_extraction.py's `np.sign(accel).diff() != 0`:
        // pandas treats the very first-ever row's diff() (NaN) as "!= 0"
        // -> True, so the very first sample counts as a "sign change" —
        // an inherited pandas NaN-comparison quirk, not a deliberate
        // design choice, but replicated here deliberately for exact
        // parity rather than silently "fixed" to something different.
        val signChanged = if (previousAccel == null) true else sign(accelForwardMps2) != sign(previousAccel)
        signChangeWindow.add(if (signChanged) 1f else 0f)

        previousAccelForwardMps2 = accelForwardMps2
        previousTimestampNs = timestampNs

        return floatArrayOf(
            accelForwardWindow.mean(),
            accelForwardWindow.std(),
            accelForwardWindow.energy(),
            accelLateralWindow.mean(),
            accelLateralWindow.std(),
            accelUpWindow.mean(),
            accelUpWindow.std(),
            gyroYawRateWindow.mean(),
            gyroYawRateWindow.std(),
            jerkForwardWindow.mean(),
            jerkForwardWindow.std(),
            signChangeWindow.mean(),
            elapsedSinceLastGnssFixS,
        )
    }
}
