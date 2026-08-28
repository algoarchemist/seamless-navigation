package com.sih26168.idr.fusion

import com.sih26168.idr.gnss.GnssMode
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * PRD.md Section 18's REACQUISITION heading/map-orientation blend — the
 * heading-level counterpart to [PositionFusion] (Round 2 addition,
 * 2026-08-28). Round 1 only blended position on reacquisition; heading
 * was a hard cutover between the DR-derived heading and the newly
 * reacquired GNSS bearing (computed ad hoc in
 * `ui/screens/MapScreen.kt`), which produced a visible ~180 degree
 * map-orientation flip during the Round 2 Day 1 live outage test when
 * the two disagreed. Mirrors [PositionFusion]'s per-mode structure
 * exactly, but interpolates CIRCULARLY (via sin/cos, same technique
 * `alignment/AlignmentEstimator.kt` already uses for its circular mean)
 * — a plain linear lerp between e.g. 179 deg and -179 deg would spin the
 * map the LONG way around instead of the correct 2-degree short way.
 */
class HeadingFusion(
    private val reacquisitionBlendMs: Long = PositionFusion.DEFAULT_REACQUISITION_BLEND_MS,
) {
    private var lastMode: GnssMode? = null
    private var modeEnteredAtMs: Long = 0L
    private var frozenHeadingDeg = 0f
    private var reacquisitionStartHeadingDeg = 0f

    /**
     * @param drHeadingDeg the live DR-derived heading this tick (compass
     *   bearing, degrees, 0-360).
     * @param newFixHeadingDeg the newly reacquired GNSS fix's bearing, or
     *   null if unavailable this tick (e.g. Location.getBearing() can be
     *   absent at very low speed).
     */
    fun update(
        nowMs: Long,
        mode: GnssMode,
        drHeadingDeg: Float,
        newFixHeadingDeg: Float?,
    ): Float {
        if (mode != lastMode) {
            modeEnteredAtMs = nowMs
            when (mode) {
                GnssMode.TRANSITION -> frozenHeadingDeg = drHeadingDeg
                GnssMode.REACQUISITION -> reacquisitionStartHeadingDeg = drHeadingDeg
                else -> Unit
            }
            lastMode = mode
        }

        return when (mode) {
            // GNSS bearing is trusted directly while aided; if this particular
            // fix has no bearing (low-speed GPS), hold the DR heading rather
            // than snapping to a stale/undefined value.
            GnssMode.GNSS_AIDED -> newFixHeadingDeg ?: drHeadingDeg
            GnssMode.TRANSITION -> frozenHeadingDeg
            GnssMode.DEAD_RECKONING -> drHeadingDeg
            GnssMode.REACQUISITION -> {
                if (newFixHeadingDeg == null) {
                    drHeadingDeg
                } else {
                    val elapsedMs = nowMs - modeEnteredAtMs
                    val progress = (elapsedMs.toDouble() / reacquisitionBlendMs).coerceIn(0.0, 1.0)
                    lerpDegreesCircular(reacquisitionStartHeadingDeg, newFixHeadingDeg, progress)
                }
            }
        }
    }

    /**
     * Shortest-path circular interpolation between two compass bearings
     * (degrees): treats each as a unit vector, linearly blends the
     * vectors, then recovers the angle via atan2 — the standard technique
     * for interpolating angles without a wraparound discontinuity at
     * +-180 degrees.
     */
    private fun lerpDegreesCircular(fromDeg: Float, toDeg: Float, t: Double): Float {
        val fromRad = Math.toRadians(fromDeg.toDouble())
        val toRad = Math.toRadians(toDeg.toDouble())
        val sinInterp = sin(fromRad) + (sin(toRad) - sin(fromRad)) * t
        val cosInterp = cos(fromRad) + (cos(toRad) - cos(fromRad)) * t
        val resultDeg = Math.toDegrees(atan2(sinInterp, cosInterp)).toFloat()
        return if (resultDeg < 0f) resultDeg + 360f else resultDeg
    }

    fun reset() {
        lastMode = null
        modeEnteredAtMs = 0L
        frozenHeadingDeg = 0f
        reacquisitionStartHeadingDeg = 0f
    }
}
