package com.sih26168.idr.motion

import com.sih26168.idr.dr.StationaryDetector

/**
 * The four motion contexts this classifier distinguishes for ZUPT gating.
 * [MOVING] covers both "clearly still moving" AND "readings ambiguous/
 * noisy but not yet confirmed stationary" — both must NOT be ZUPT'd, so
 * they share one context rather than inventing a fifth state with
 * identical downstream behavior.
 */
enum class StationaryContext {
    /** Not stationary — includes noisy/ambiguous near-zero readings that
     * haven't been corroborated by either accel/gyro or a sustained
     * speed drop. Real motion must never be ZUPT'd on a guess. */
    MOVING,

    /** Speed was recently meaningful and has now sustained near-zero for
     * [StopEventClassifier.nearZeroConfirmMs] — a genuine post-motion
     * stop (traffic light, congestion). ZUPT applies immediately, without
     * waiting out the slower accel/gyro dwell below. */
    SUDDEN_STOP,

    /** Accel/gyro have been confirmed quiet (StationaryDetector's own
     * dwell) but for less than [StopEventClassifier.longIdleDurationMs] —
     * a short pause with no strong prior-speed evidence either way. */
    BRIEF_STOP,

    /** Accel/gyro confirmed quiet for at least
     * [StopEventClassifier.longIdleDurationMs] — parked/idle. */
    LONG_IDLE,
}

/** One tick's classification result — everything a caller needs to both
 * apply ZUPT and explain why (CLAUDE.md Rule 17-style traceability). */
data class StopClassification(
    val context: StationaryContext,
    /** The actual ZUPT gate — true for every context except [StationaryContext.MOVING]. */
    val shouldApplyZupt: Boolean,
    /** How long [context] has held (0 while MOVING). */
    val stationaryDurationMs: Long,
    /** The highest reference speed seen within the lookback window — the
     * "was this vehicle really moving a moment ago" evidence. */
    val recentPeakSpeedMps: Float,
    /** The raw current-tick speed estimate this call was given, echoed
     * back for logging (CLAUDE.md Rule 17). */
    val currentSpeedEstimateMps: Float,
    /** [StationaryDetector]'s own accel/gyro-only dwell-confirmed result
     * for THIS tick — exposed so a caller that still needs the plain
     * "physically still" signal (e.g. [MotionStateClassifier]'s existing
     * contract) doesn't have to run a second, redundant detector. */
    val dwellConfirmedStationary: Boolean,
    /** Human-readable reason for [context] — logged by callers per this
     * task's own logging requirement, not parsed by anything. */
    val reason: String,
)

/**
 * Context-aware replacement for gating ZUPT on "accel/gyro have been
 * quiet for N ms" alone. Composes [StationaryDetector] UNCHANGED (still
 * the sole signal for [StationaryContext.BRIEF_STOP]/[StationaryContext.LONG_IDLE]
 * — no existing behavior there is touched, no existing test of that class
 * needed to change) and adds a SECOND, independent signal: a sustained
 * drop from a meaningful reference speed to near-zero, corroborated by
 * GNSS speed when it's actually trustworthy.
 *
 * REAL FINDING this class exists to fix (2026-09-01 real outdoor drive,
 * see [StationaryDetector]'s own doc and `scripts/analyze_drive_log.py`'s
 * `report_zupt_threshold_sweep`): cross-checked against GNSS speed as
 * independent ground truth, accel/gyro-only stationary detection was
 * 100% false-negative on real urban-traffic stops (280/280 truly-stopped
 * rows missed) — a grid search over every threshold combination found
 * none that separates the classes, because real engine-idle/road
 * vibration keeps accel/gyro magnitude elevated even while genuinely
 * stopped in traffic (measured stationary accel p50=1.517 m/s^2, barely
 * below moving accel p50=1.656 m/s^2). That means the fast path here
 * DELIBERATELY does not gate [StationaryContext.SUDDEN_STOP] on the same
 * strict accel/gyro threshold StationaryDetector uses — that threshold is
 * exactly the signal measured unreliable for this case. Velocity crossing
 * from meaningful to near-zero is a physically different, independent
 * signal that doesn't share that failure mode.
 *
 * Flow: Motion history (rolling speed samples) -> stop-event detection
 * (sustained near-zero after a meaningful peak) -> context classification
 * -> ZUPT decision, per this task's own required shape — rather than the
 * old `velocity ~= 0 -> ZUPT` (StationaryDetector alone).
 *
 * Deliberately a rule-based baseline, not an ML classifier: no labeled
 * stop-event data exists in this project (`train_motion_classifier.py`
 * is PLANNED, blocked on self-captured labels per
 * `docs/PROJECT_MAP.md`) and CLAUDE.md Rule 3 requires a measured reason
 * to reach for ML over a simple deterministic solution, not availability
 * of the word "AI" in the task description. Structured so a future
 * trained classifier could slot in behind the same [StopClassification]
 * output shape without changing any caller.
 *
 * HONEST LIMITATION (CLAUDE.md Rule 13): when no GNSS speed is available
 * (mid-outage, exactly when ZUPT matters most), the "reference speed" is
 * this app's own DR/ML velocity estimate — not independent ground truth.
 * A sustained (>= [nearZeroConfirmMs]) speed-estimate glitch while the
 * vehicle is genuinely still moving could misfire into [StationaryContext.SUDDEN_STOP].
 * Preferring GNSS speed whenever it's actually trustworthy (see callers)
 * minimizes this, since GNSS speed doesn't share the DR/ML path's own
 * error modes — but it is not eliminated. Not yet validated against a
 * real labeled stop-event drive (none has been captured).
 */
class StopEventClassifier(
    private val stationaryDetector: StationaryDetector = StationaryDetector(),
    private val suddenStopPriorSpeedMps: Float = DEFAULT_SUDDEN_STOP_PRIOR_SPEED_MPS,
    private val suddenStopLookbackMs: Long = DEFAULT_SUDDEN_STOP_LOOKBACK_MS,
    private val nearZeroSpeedMps: Float = DEFAULT_NEAR_ZERO_SPEED_MPS,
    private val nearZeroConfirmMs: Long = DEFAULT_NEAR_ZERO_CONFIRM_MS,
    private val longIdleDurationMs: Long = DEFAULT_LONG_IDLE_DURATION_MS,
) {
    companion object {
        // Engineering defaults, not yet validated against a real labeled
        // stop-event drive (CLAUDE.md Rule 13) — same disclosed-guess
        // status every other threshold in this codebase carries until a
        // real drive measures it.

        /** A prior speed at or above this, within the lookback window,
         * counts as "was really moving" evidence for a sudden stop. */
        const val DEFAULT_SUDDEN_STOP_PRIOR_SPEED_MPS = 1.5f

        /** How far back to look for that meaningful prior speed. A real
         * traffic-speed-to-stop deceleration typically completes within a
         * few seconds; kept short so SUDDEN_STOP stays tied to an actual
         * recent stop event, not stale history. */
        const val DEFAULT_SUDDEN_STOP_LOOKBACK_MS = 2_000L

        /** "Essentially stopped" by our own speed estimate — matches the
         * 0.3 m/s bound `scripts/analyze_drive_log.py`'s own
         * `report_zupt_validation` already uses as GNSS-speed ground
         * truth for "was it really stationary," not a new, independent guess. */
        const val DEFAULT_NEAR_ZERO_SPEED_MPS = 0.3f

        /** How long the near-zero reading must sustain before
         * [StationaryContext.SUDDEN_STOP] fires. Deliberately shorter than
         * [StationaryDetector.DEFAULT_MIN_STATIONARY_DWELL_MS] (300ms) —
         * the prior-speed evidence already substantially raises
         * confidence this is a real stop, not sensor noise, so less
         * confirmation time is needed; still non-zero, so a single ~100ms
         * tick's glitch can't trigger it alone. */
        const val DEFAULT_NEAR_ZERO_CONFIRM_MS = 150L

        /** Stationary (accel/gyro-dwell-confirmed) beyond this duration is
         * [StationaryContext.LONG_IDLE] rather than [StationaryContext.BRIEF_STOP]. */
        const val DEFAULT_LONG_IDLE_DURATION_MS = 8_000L
    }

    private data class SpeedSample(val atMs: Long, val speedMps: Float)

    private val speedHistory = ArrayDeque<SpeedSample>()
    private var nearZeroStreakStartMs: Long? = null
    private var stationaryEnteredAtMs: Long? = null

    /**
     * @param nowMs monotonic clock, ms — same boot-time family callers
     *   already use for [StationaryDetector] (CLAUDE.md Rule 9/14).
     * @param linearAccelMagnitudeMps2 / @param gyroMagnitudeRadPerSec fed
     *   straight through to the wrapped [StationaryDetector] — unchanged
     *   inputs, unchanged meaning.
     * @param currentSpeedEstimateMps this system's own best current speed
     *   estimate (m/s) — the physics integrator's pre-ZUPT speed, or the
     *   ML model's damped prediction, depending on caller.
     * @param gnssSpeedMps GNSS-reported speed for THIS tick, only when the
     *   caller has already verified it's trustworthy (GNSS_AIDED +
     *   [com.sih26168.idr.gnss.GnssQuality.isGood]) — null otherwise. When
     *   present, this is preferred over [currentSpeedEstimateMps] as the
     *   reference speed (see class doc's honest-limitation note).
     */
    fun evaluate(
        nowMs: Long,
        linearAccelMagnitudeMps2: Float,
        gyroMagnitudeRadPerSec: Float,
        currentSpeedEstimateMps: Float,
        gnssSpeedMps: Float? = null,
    ): StopClassification {
        val referenceSpeedMps = gnssSpeedMps ?: currentSpeedEstimateMps

        speedHistory.addLast(SpeedSample(nowMs, referenceSpeedMps))
        while (speedHistory.isNotEmpty() && nowMs - speedHistory.first().atMs > suddenStopLookbackMs) {
            speedHistory.removeFirst()
        }
        val recentPeakSpeedMps = speedHistory.maxOf { it.speedMps }

        val nowNearZero = referenceSpeedMps <= nearZeroSpeedMps
        if (nowNearZero) {
            if (nearZeroStreakStartMs == null) nearZeroStreakStartMs = nowMs
        } else {
            nearZeroStreakStartMs = null
        }
        val nearZeroConfirmed = nearZeroStreakStartMs != null &&
            nowMs - nearZeroStreakStartMs!! >= nearZeroConfirmMs
        val cameFromMeaningfulSpeed = recentPeakSpeedMps >= suddenStopPriorSpeedMps

        // Always run — BRIEF_STOP/LONG_IDLE's own signal, and the sole
        // fallback when there's no meaningful prior-speed evidence to
        // lean on (e.g. right after launch, already stationary).
        val dwellConfirmedStationary = stationaryDetector.evaluate(nowMs, linearAccelMagnitudeMps2, gyroMagnitudeRadPerSec)

        val context: StationaryContext = when {
            cameFromMeaningfulSpeed && nearZeroConfirmed -> {
                if (stationaryEnteredAtMs == null) stationaryEnteredAtMs = nowMs
                StationaryContext.SUDDEN_STOP
            }
            dwellConfirmedStationary -> {
                if (stationaryEnteredAtMs == null) stationaryEnteredAtMs = nowMs
                val durationMs = nowMs - stationaryEnteredAtMs!!
                if (durationMs >= longIdleDurationMs) StationaryContext.LONG_IDLE else StationaryContext.BRIEF_STOP
            }
            else -> {
                stationaryEnteredAtMs = null
                StationaryContext.MOVING
            }
        }

        val stationaryDurationMs = stationaryEnteredAtMs?.let { nowMs - it } ?: 0L
        return StopClassification(
            context = context,
            shouldApplyZupt = context != StationaryContext.MOVING,
            stationaryDurationMs = stationaryDurationMs,
            recentPeakSpeedMps = recentPeakSpeedMps,
            currentSpeedEstimateMps = currentSpeedEstimateMps,
            dwellConfirmedStationary = dwellConfirmedStationary,
            reason = reasonFor(context, recentPeakSpeedMps, referenceSpeedMps, stationaryDurationMs),
        )
    }

    private fun reasonFor(
        context: StationaryContext,
        recentPeakSpeedMps: Float,
        referenceSpeedMps: Float,
        stationaryDurationMs: Long,
    ): String = when (context) {
        StationaryContext.SUDDEN_STOP ->
            "sudden stop: recent peak %.2f m/s -> now %.2f m/s, fast-path ZUPT".format(recentPeakSpeedMps, referenceSpeedMps)
        StationaryContext.BRIEF_STOP ->
            "brief stop: accel/gyro quiet %dms (< %dms long-idle bound)".format(stationaryDurationMs, longIdleDurationMs)
        StationaryContext.LONG_IDLE ->
            "long idle: accel/gyro quiet %dms".format(stationaryDurationMs)
        StationaryContext.MOVING ->
            "moving: no confirmed near-zero speed streak and accel/gyro not dwell-quiet"
    }

    /** Clears all history/state — called when a DR session (re)starts, same
     * convention as every other stateful detector's `reset()` in this
     * codebase (e.g. [TurningDetector.reset]). Deliberately does NOT reset
     * the wrapped [StationaryDetector] beyond what its own `evaluate()`
     * calls naturally do — that class has no `reset()` of its own today. */
    fun reset() {
        speedHistory.clear()
        nearZeroStreakStartMs = null
        stationaryEnteredAtMs = null
    }
}
