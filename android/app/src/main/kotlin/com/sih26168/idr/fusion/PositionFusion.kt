package com.sih26168.idr.fusion

import com.sih26168.idr.gnss.GnssMode

/** WORLD-frame (East, North) fused position, meters, in the SAME local frame the DR input is expressed in. */
data class FusedPosition(val eastM: Double, val northM: Double)

/**
 * PRD.md Section 18's TRANSITION "freeze/average" and REACQUISITION "blend
 * DR position toward new GNSS fix" behavior — the position-level
 * counterpart to [com.sih26168.idr.gnss.GnssOutageDetector]'s mode-level
 * state machine. Pure Kotlin, no Android dependency, driven by repeated
 * [update] calls (CLAUDE.md Rule 19), same pattern as GnssOutageDetector's
 * `evaluate()`.
 *
 * [update]'s `drEastM`/`drNorthM` is assumed to already be "meters since
 * GNSS was last good" — exactly what both
 * [com.sih26168.idr.dr.BaselineDeadReckoningRepository] and
 * [com.sih26168.idr.ml.MlVelocityRepository]'s position integrators
 * already produce (both reset to (0,0) the instant `mode == GNSS_AIDED`).
 * This class does NOT duplicate that reset logic — it only decides how
 * much of that DR delta to trust/show per GNSS mode, and how to reconcile
 * it with a newly reacquired fix.
 *
 * Per-mode behavior:
 * - `GNSS_AIDED`: [gnssJitterOffsetEastM]/[gnssJitterOffsetNorthM] (both
 *   default 0.0) — GNSS is trusted directly (by construction of the
 *   callers' own DR-integrator reset behavior, `drEastM`/`drNorthM` are
 *   never used for this mode), but PRD.md Section 17's "smooth short
 *   GNSS gaps/jitter" means the position isn't necessarily the raw fix
 *   UNMODIFIED either — see [com.sih26168.idr.fusion.GnssJitterFilter]
 *   and [com.sih26168.idr.fusion.StateEstimator]'s own doc for how that
 *   small complementary-filtered offset is computed. Passing 0.0 (the
 *   default, and every pre-existing call site) reproduces the exact
 *   original "always (0,0)" behavior.
 * - `TRANSITION`: frozen at whatever the DR position was the INSTANT
 *   TRANSITION was entered. This is the "freeze" half of Section 18's
 *   "freeze/average" — chosen over averaging because TRANSITION's default
 *   dwell is short (~1s), so the two are nearly indistinguishable, and
 *   freeze is simpler to reason about and test.
 * - `DEAD_RECKONING`: the live DR delta, passed straight through.
 * - `REACQUISITION`: linearly interpolates from the DR position at the
 *   instant REACQUISITION was entered toward the newly reacquired fix's
 *   position (in the same local frame, via [GeoProjection]) over
 *   [reacquisitionBlendMs]. Falls back to the raw DR passthrough if no fix
 *   is available yet that tick (honest degrade, not a crash or a stale
 *   guess).
 *
 * UPDATE (PRD.md Section 17's "AI-based" GNSS+INS fusion — previously
 * entirely classical, per this class's own doc and STATUS_AND_ROADMAP.md's
 * flagged decision point): [reacquisitionBlendMs] is now a `var`, settable
 * via [setReacquisitionBlendMs] BEFORE the tick that first enters
 * REACQUISITION — [com.sih26168.idr.fusion.StateEstimator] calls it with
 * [blendDurationForDriftMs]'s output, fed by
 * [com.sih26168.idr.ml.ReacquisitionDriftModel]'s predicted along-track
 * drift for this specific outage. Deliberately NOT a Kalman/EKF state
 * update (CLAUDE.md's "What Not To Build" / PRD.md Section 7) — this
 * remains the same simple, transparent linear-interpolation blend as
 * before, just with a DATA-INFORMED duration instead of a fixed constant.
 * If the model is unavailable ([com.sih26168.idr.ml.ReacquisitionDriftModel]
 * failed to load, same resilience pattern as [com.sih26168.idr.ml.VelocityModel]),
 * [reacquisitionBlendMs] simply stays at [DEFAULT_REACQUISITION_BLEND_MS]
 * — the exact previous, classical behavior.
 */
class PositionFusion(
    private var reacquisitionBlendMs: Long = DEFAULT_REACQUISITION_BLEND_MS,
) {
    companion object {
        // Deliberately a separate constant from GnssOutageDetector's own
        // reacquisitionDwellMs (also 1000ms by default) rather than reading
        // it directly — avoids coupling this class's constructor to that
        // one's private internals. The two matching by default is
        // intentional (a REACQUISITION-mode blend that outlasts the mode
        // itself would be a visible bug) but not structurally enforced.
        const val DEFAULT_REACQUISITION_BLEND_MS = 1_000L

        // Engineering defaults, unvalidated against a real outdoor test
        // drive (CLAUDE.md Rule 13) — bounds chosen to stay within the
        // same rough order of magnitude as the previous fixed 1-second
        // default (never a runaway multi-minute blend), while still
        // letting a genuinely large predicted drift smooth its correction
        // out over meaningfully longer than a near-zero-drift outage.
        const val MIN_ADAPTIVE_REACQUISITION_BLEND_MS = 500L
        const val MAX_ADAPTIVE_REACQUISITION_BLEND_MS = 3_000L
        const val BLEND_MS_PER_METER_OF_PREDICTED_DRIFT = 30.0

        /**
         * Maps [com.sih26168.idr.ml.ReacquisitionDriftModel]'s predicted
         * along-track drift (meters) to a REACQUISITION blend duration —
         * a simple, transparent linear formula (larger predicted drift =
         * longer blend, spreading the correction out to reduce a visibly
         * jarring "jump" to the reacquired fix; near-zero predicted drift
         * snaps back almost immediately, since there is little error to
         * hide), clamped to [MIN_ADAPTIVE_REACQUISITION_BLEND_MS]..[MAX_ADAPTIVE_REACQUISITION_BLEND_MS].
         */
        fun blendDurationForDriftMs(predictedDriftMeters: Float): Long {
            val rawMs = MIN_ADAPTIVE_REACQUISITION_BLEND_MS + BLEND_MS_PER_METER_OF_PREDICTED_DRIFT * predictedDriftMeters
            return rawMs.toLong().coerceIn(MIN_ADAPTIVE_REACQUISITION_BLEND_MS, MAX_ADAPTIVE_REACQUISITION_BLEND_MS)
        }
    }

    /**
     * Sets the REACQUISITION blend duration for the NEXT outage this
     * instance handles — see this class's own doc for the full reasoning
     * and calling convention (must be called before the first
     * REACQUISITION-mode [update] call for that outage).
     */
    fun setReacquisitionBlendMs(blendMs: Long) {
        reacquisitionBlendMs = blendMs
    }

    private var lastMode: GnssMode? = null
    private var modeEnteredAtMs: Long = 0L
    private var frozenPosition = FusedPosition(0.0, 0.0)
    private var reacquisitionStartPosition = FusedPosition(0.0, 0.0)

    fun update(
        nowMs: Long,
        mode: GnssMode,
        drEastM: Double,
        drNorthM: Double,
        newFixEastM: Double?,
        newFixNorthM: Double?,
        gnssJitterOffsetEastM: Double = 0.0,
        gnssJitterOffsetNorthM: Double = 0.0,
    ): FusedPosition {
        if (mode != lastMode) {
            modeEnteredAtMs = nowMs
            when (mode) {
                GnssMode.TRANSITION -> frozenPosition = FusedPosition(drEastM, drNorthM)
                GnssMode.REACQUISITION -> reacquisitionStartPosition = FusedPosition(drEastM, drNorthM)
                else -> Unit
            }
            lastMode = mode
        }

        return when (mode) {
            GnssMode.GNSS_AIDED -> FusedPosition(gnssJitterOffsetEastM, gnssJitterOffsetNorthM)
            GnssMode.TRANSITION -> frozenPosition
            GnssMode.DEAD_RECKONING -> FusedPosition(drEastM, drNorthM)
            GnssMode.REACQUISITION -> {
                if (newFixEastM == null || newFixNorthM == null) {
                    FusedPosition(drEastM, drNorthM)
                } else {
                    val elapsedMs = nowMs - modeEnteredAtMs
                    val progress = (elapsedMs.toDouble() / reacquisitionBlendMs).coerceIn(0.0, 1.0)
                    FusedPosition(
                        eastM = lerp(reacquisitionStartPosition.eastM, newFixEastM, progress),
                        northM = lerp(reacquisitionStartPosition.northM, newFixNorthM, progress),
                    )
                }
            }
        }
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    fun reset() {
        lastMode = null
        modeEnteredAtMs = 0L
        frozenPosition = FusedPosition(0.0, 0.0)
        reacquisitionStartPosition = FusedPosition(0.0, 0.0)
        reacquisitionBlendMs = DEFAULT_REACQUISITION_BLEND_MS
    }
}
