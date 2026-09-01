package com.sih26168.idr.dr

import kotlin.math.PI

/**
 * A single-pole (exponential moving average) low-pass filter — PRD.md
 * Section 11's "low-pass filtering... to remove high-frequency vibration
 * noise before feature extraction."
 *
 * SCOPE DECISION (CLAUDE.md Rule 4/20, documented here since it narrows
 * PRD.md Section 11's literal "before feature extraction" wording): this
 * filter is wired into `dr/BaselineDeadReckoningRepository.kt` (the
 * physics baseline path) ONLY, deliberately NOT into
 * `ml/MlVelocityRepository.kt`/`features/FeatureExtractor.kt`'s input.
 * The already-trained, exported, and measured ONNX velocity model
 * (`ml/VelocityModel.kt` — MAE 1.244 m/s, a REAL number from
 * `ml/train_velocity_model.py`) was trained on windowed statistics
 * (mean/std/energy) computed over RAW, unfiltered accel/gyro
 * (`ml/feature_extraction.py` applies no filtering). Smoothing that
 * signal now, on-device only, without retraining and re-validating the
 * model against a matched Python-side filter, would silently shift the
 * live feature distribution (reduced noise/variance) away from what the
 * model was actually trained on — exactly the kind of undetected
 * train/inference mismatch CLAUDE.md Rule 20 exists to prevent, and could
 * quietly regress the already-measured 4.2x accuracy win with no new
 * measurement to catch it (CLAUDE.md Rule 13). Retraining on filtered
 * features is legitimate future work, not something to do silently as a
 * side effect of adding this class.
 *
 * The physics baseline has no such constraint — it is a from-scratch
 * naive double-integrator (`BaselinePhysicsIntegrator.kt`) with no
 * trained parameters to keep in sync with, so improving its own raw
 * signal quality here is safe and self-contained. (The already-published
 * physics+ZUPT baseline MAE/RMSE figures in `ml/train_velocity_model.py`
 * come from an independent PYTHON re-implementation evaluated over
 * IO-VNBD, not from this Kotlin class, so adding this filter here does
 * not retroactively change or invalidate that number — it simply means
 * this on-device path no longer matches that Python mirror exactly,
 * which is now the honest, disclosed state of things.)
 *
 * Standard RC low-pass discretization: `alpha = dt / (rc + dt)`, where
 * `rc = 1 / (2*pi*cutoffHz)` — the same math a passive analog RC
 * low-pass filter implements, computed per-sample so it stays correct
 * even though this project's real sensor delivery isn't a perfectly
 * constant rate (see `sensors/SampleRate.kt`) — a fixed alpha would
 * silently assume a constant dt it doesn't have (CLAUDE.md Rule 10).
 *
 * Pure Kotlin, no Android dependency, unit-testable on the plain JVM
 * (CLAUDE.md Rule 19). O(1) per sample — trivially real-time-safe at
 * live ~10 Hz sensor rates (CLAUDE.md Rule 10).
 */
class LowPassFilter(private val cutoffHz: Double) {

    private var filteredValue: Double? = null

    /**
     * @param dtSeconds elapsed time since the previous sample. `<= 0.0`
     *   (first sample / clock reset — same guard convention as
     *   [BaselinePhysicsIntegrator.update]) returns [value] unfiltered
     *   and adopts it as the initial state, since there is no prior
     *   filtered value to blend with yet.
     */
    fun filter(value: Double, dtSeconds: Double): Double {
        val previous = filteredValue
        if (previous == null || dtSeconds <= 0.0) {
            filteredValue = value
            return value
        }
        val rc = 1.0 / (2.0 * PI * cutoffHz)
        val alpha = dtSeconds / (rc + dtSeconds)
        val newValue = previous + alpha * (value - previous)
        filteredValue = newValue
        return newValue
    }

    /** Discards the filter's internal state — used when (re)starting a DR run. */
    fun reset() {
        filteredValue = null
    }
}
