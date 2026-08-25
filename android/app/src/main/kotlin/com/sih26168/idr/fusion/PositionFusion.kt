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
 * - `GNSS_AIDED`: always (0, 0) — GNSS is trusted directly, no DR delta
 *   has accumulated (by construction of the callers' own reset behavior).
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
 */
class PositionFusion(
    private val reacquisitionBlendMs: Long = DEFAULT_REACQUISITION_BLEND_MS,
) {
    companion object {
        // Deliberately a separate constant from GnssOutageDetector's own
        // reacquisitionDwellMs (also 1000ms by default) rather than reading
        // it directly — avoids coupling this class's constructor to that
        // one's private internals. The two matching by default is
        // intentional (a REACQUISITION-mode blend that outlasts the mode
        // itself would be a visible bug) but not structurally enforced.
        const val DEFAULT_REACQUISITION_BLEND_MS = 1_000L
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
            GnssMode.GNSS_AIDED -> FusedPosition(0.0, 0.0)
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
    }
}
