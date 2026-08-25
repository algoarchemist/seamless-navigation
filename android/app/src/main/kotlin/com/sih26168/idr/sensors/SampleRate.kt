package com.sih26168.idr.sensors

/**
 * Pure timestamp-delta -> Hz conversion, kept free of any Android
 * dependency so it can run under a plain JVM unit test (CLAUDE.md
 * Rule 19 — deterministic math gets a test before anything downstream
 * relies on it). Used to surface the *observed* sensor delivery rate
 * live, so a demo-time sensor stall is visible instead of silently
 * assumed away (CLAUDE.md Rule 10).
 */
object SampleRate {
    fun hzFromDeltaNs(deltaNs: Long): Double {
        if (deltaNs <= 0L) return 0.0
        return 1_000_000_000.0 / deltaNs
    }

    /**
     * Timestamp delta (ns) -> elapsed seconds, for physics integration
     * (Slice 3's BaselinePhysicsIntegrator). Returns 0.0 for zero/negative
     * deltas, same clock-reset guard as [hzFromDeltaNs] — a caller must
     * treat 0.0 as "skip this integration step," never divide by it.
     */
    fun secondsFromDeltaNs(deltaNs: Long): Double {
        if (deltaNs <= 0L) return 0.0
        return deltaNs / 1_000_000_000.0
    }
}
