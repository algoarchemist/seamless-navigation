package com.sih26168.idr.fusion

import kotlin.math.sqrt

/**
 * A running (online, Welford's algorithm) mean/population-standard-
 * deviation accumulator over a stream of scalar samples — built
 * specifically to feed `ml/ReacquisitionDriftModel.kt`'s
 * `avgPredictedSpeedMps`/`predictedSpeedStdMps` features with "the
 * mean/std of DR speed samples seen so far during the CURRENT GNSS
 * outage," without storing the full sample history (an outage of
 * unknown/unbounded length must not need unbounded memory).
 *
 * Welford's algorithm (Welford 1962, the standard numerically-stable
 * streaming-variance method) computes the SAME population variance
 * `numpy.std()`'s default (`ddof=0`) computes in
 * `ml/train_reacquisition_model.py` — this class exists specifically so
 * `fusion/StateEstimator.kt`'s live accumulation matches that training-
 * time statistic, not a different one (an unmatched statistic would
 * silently feed the model input outside its training distribution).
 *
 * Pure Kotlin, no Android dependency, unit-testable on the plain JVM
 * (CLAUDE.md Rule 19).
 */
class RunningStats {
    private var count = 0
    private var runningMean = 0.0
    private var m2 = 0.0 // sum of squared differences from the current running mean

    fun accumulate(value: Double) {
        count++
        val delta = value - runningMean
        runningMean += delta / count
        val delta2 = value - runningMean
        m2 += delta * delta2
    }

    fun mean(): Double = runningMean

    /** Population standard deviation (ddof=0, matching numpy.std()'s default) — 0.0 if no samples yet. */
    fun populationStdDev(): Double = if (count > 0) sqrt(m2 / count) else 0.0

    val sampleCount: Int get() = count

    fun reset() {
        count = 0
        runningMean = 0.0
        m2 = 0.0
    }
}
