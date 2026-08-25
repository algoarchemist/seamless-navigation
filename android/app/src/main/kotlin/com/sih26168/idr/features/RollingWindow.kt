package com.sih26168.idr.features

import kotlin.math.sqrt

/**
 * A fixed-capacity trailing window over Float samples, computing
 * mean/std/energy the SAME way ml/feature_extraction.py's pandas
 * `.rolling(w, min_periods=1)` does — including using however many
 * samples are actually available before the window fills (not
 * zero-padding), which is what "min_periods=1" means. Pure Kotlin,
 * unit-testable per CLAUDE.md Rule 19.
 *
 * PARITY-CRITICAL DETAIL: [std] is the SAMPLE standard deviation
 * (divides by n-1, "ddof=1"), matching pandas' `.std()` default —
 * NOT population std (divide by n). Getting this wrong would silently
 * feed the trained model systematically-wrong-magnitude features.
 */
class RollingWindow(private val capacity: Int) {

    private val buffer = ArrayDeque<Float>(capacity)

    fun add(value: Float) {
        if (buffer.size == capacity) buffer.removeFirst()
        buffer.addLast(value)
    }

    fun mean(): Float {
        if (buffer.isEmpty()) return 0f
        return buffer.sum() / buffer.size
    }

    /** Sample std (ddof=1) — 0.0 for fewer than 2 samples, matching
     * Python's `.std().fillna(0.0)` on a single-element window (sample
     * variance is undefined for n=1, pandas returns NaN there). */
    fun std(): Float {
        if (buffer.size < 2) return 0f
        val m = mean()
        val sumSquaredDiff = buffer.sumOf { value -> ((value - m) * (value - m)).toDouble() }
        return sqrt(sumSquaredDiff / (buffer.size - 1)).toFloat()
    }

    /** Mean of squares. */
    fun energy(): Float {
        if (buffer.isEmpty()) return 0f
        return (buffer.sumOf { (it * it).toDouble() } / buffer.size).toFloat()
    }
}
