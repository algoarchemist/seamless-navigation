package com.sih26168.idr.ui.map

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Frame-rate marker/heading smoothing for StreetMapView.kt (Round 2 UI
 * smoothness pass, 2026-08-28). GNSS/DR position updates arrive at
 * ~5-10 Hz (gnss/GnssModeRepository's own tick cadence, PRD.md
 * Section 8/11); rendering the marker/map-rotation directly at that rate
 * makes it visibly step/teleport in small discrete increments rather
 * than glide, since the display itself refreshes at ~60 Hz. This applies
 * simple exponential ("chase") smoothing once per DISPLAY frame,
 * independent of the underlying data-tick rate.
 *
 * Deliberately NOT a Kalman filter or any predictive smoothing — this is
 * a cosmetic display concern (how the marker LOOKS moving), not a
 * position-ESTIMATION one (CLAUDE.md Rule 2's "smallest practical
 * stack": a one-line-per-step chase is all this problem needs; the real
 * position-fusion math lives in fusion/PositionFusion.kt and is
 * untouched by this class).
 *
 * Pure Kotlin, no Android/Compose dependency, unit-testable (CLAUDE.md
 * Rule 19 — a filter, even a cosmetic one, gets a test before being
 * relied on).
 *
 * HONEST LIMITATION: after a long gap with no target updates (e.g. the
 * app was paused, PRD.md's Pause button), the next real target can be far
 * from the last displayed one, and this will glide quickly toward it
 * rather than snapping — a brief fast slide across the map, not a
 * teleport, but not instant either. Not mitigated (no "big jump, skip
 * smoothing" special case) since the common tab-switch/restart path
 * already gets a fresh instance (see StreetMapView.kt, `remember`-scoped)
 * and a rare pause/resume glide is a minor cosmetic edge case, not a
 * correctness one.
 */
class PositionSmoother(
    private val smoothingFactor: Double = DEFAULT_SMOOTHING_FACTOR,
) {
    companion object {
        // Fraction of the remaining gap closed each display frame — tuned
        // by feel (CLAUDE.md Rule 13: unvalidated, cosmetic default), not
        // against a specific target Hz: high enough to keep up with the
        // ~5-10Hz real tick rate without visibly lagging behind it (94% of
        // any gap closed within ~10 frames / ~166ms at 60fps), low enough
        // to actually read as a glide rather than an instant snap.
        const val DEFAULT_SMOOTHING_FACTOR = 0.25
    }

    private var currentLatDeg: Double? = null
    private var currentLonDeg: Double? = null
    private var currentHeadingDeg: Float? = null

    /**
     * Advances the smoothed position one step toward (targetLatDeg,
     * targetLonDeg) and returns the new smoothed value, or null if no
     * target has ever been provided. The FIRST-EVER target snaps directly
     * (there is nothing to glide FROM yet).
     */
    fun stepPosition(targetLatDeg: Double?, targetLonDeg: Double?): Pair<Double, Double>? {
        if (targetLatDeg == null || targetLonDeg == null) return null
        val prevLat = currentLatDeg
        val prevLon = currentLonDeg
        val newLat: Double
        val newLon: Double
        if (prevLat == null || prevLon == null) {
            newLat = targetLatDeg
            newLon = targetLonDeg
        } else {
            newLat = prevLat + (targetLatDeg - prevLat) * smoothingFactor
            newLon = prevLon + (targetLonDeg - prevLon) * smoothingFactor
        }
        currentLatDeg = newLat
        currentLonDeg = newLon
        return newLat to newLon
    }

    /**
     * Same idea as [stepPosition], for a compass heading (degrees,
     * 0-360). Interpolates CIRCULARLY (via sin/cos, same technique
     * fusion/HeadingFusion.kt uses for its own, unrelated, REACQUISITION
     * blend — this is a separate per-frame cosmetic smoother, not that
     * class) — a plain linear step between e.g. 359 deg and 1 deg would
     * visibly spin the map the LONG way around instead of the correct
     * 2-degree short way.
     */
    fun stepHeading(targetHeadingDeg: Float): Float {
        val prev = currentHeadingDeg
        val new = if (prev == null) {
            targetHeadingDeg
        } else {
            lerpDegreesCircular(prev, targetHeadingDeg, smoothingFactor)
        }
        currentHeadingDeg = new
        return new
    }

    private fun lerpDegreesCircular(fromDeg: Float, toDeg: Float, t: Double): Float {
        val fromRad = Math.toRadians(fromDeg.toDouble())
        val toRad = Math.toRadians(toDeg.toDouble())
        val sinInterp = sin(fromRad) + (sin(toRad) - sin(fromRad)) * t
        val cosInterp = cos(fromRad) + (cos(toRad) - cos(fromRad)) * t
        val resultDeg = Math.toDegrees(atan2(sinInterp, cosInterp)).toFloat()
        return if (resultDeg < 0f) resultDeg + 360f else resultDeg
    }

    fun reset() {
        currentLatDeg = null
        currentLonDeg = null
        currentHeadingDeg = null
    }
}
