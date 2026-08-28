package com.sih26168.idr.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.sih26168.idr.gnss.GnssMode
import com.sih26168.idr.ui.theme.AccentBlue
import com.sih26168.idr.ui.theme.AccentBlueLight
import com.sih26168.idr.ui.theme.CtaRed
import com.sih26168.idr.ui.theme.ScreenGradientBottom
import com.sih26168.idr.ui.theme.ScreenGradientTop

// Engineering default (CLAUDE.md Rule 13) — how many screen pixels
// represent one meter of local East/North displacement. Not derived
// from any measured screen-density calculation; picked so a ~30-50m
// outage drift is visible without the dot running off-canvas on a
// typical phone screen.
private const val METERS_TO_PIXELS = 8f

/**
 * The map layer — a Compose `Canvas`, not a real map SDK (decision made
 * with the user: zero new dependencies, works fully offline, plots
 * directly in the local East/North meters [com.sih26168.idr.fusion.StateEstimator]
 * already produces). Styling borrows Google Maps' LAYOUT pattern
 * (dot-with-ring current position, accuracy halo, polyline route) but
 * renders it entirely in the Figma-extracted dark palette — no separate
 * light "Google palette" (per user correction during Slice 8 planning).
 *
 * The current-position dot is always drawn at canvas center (a
 * "follow-me" navigation view, like a real turn-by-turn app) — the
 * OUTAGE ANCHOR (where GNSS was last trustworthy, local (0,0)) is what
 * moves in screen space as [fusedEastM]/[fusedNorthM] grow.
 *
 * HONEST SIMPLIFICATION (CLAUDE.md Rule 13): this draws a single
 * STRAIGHT line from the outage anchor to the current fused position
 * when [mode] is `DEAD_RECKONING`/`REACQUISITION` — NOT a true curved
 * path, since nothing in this codebase accumulates a full position-
 * history polyline (StateEstimator only ever publishes the CURRENT
 * position, per tick, not a history list). This shows the NET
 * divergence since the outage began, not the literal path shape. The
 * actual GNSS-vs-DR comparison at the moment of reacquisition is a real,
 * exact measurement — see [com.sih26168.idr.ui.components.DriftSummaryCard]
 * / [com.sih26168.idr.fusion.DriftSummary].
 */
@Composable
fun TrackCanvas(
    fusedEastM: Double,
    fusedNorthM: Double,
    mode: GnssMode,
    modifier: Modifier = Modifier,
) {
    // Read OUTSIDE the Canvas draw lambda — that lambda is a DrawScope
    // closure, not a @Composable context, so the theme-aware
    // ScreenGradientTop/Bottom getters (light/dark mode, 2026-08-26) can't
    // be called from inside it directly; captured into plain local vals
    // here instead.
    val gradientTop = ScreenGradientTop
    val gradientBottom = ScreenGradientBottom

    // Round 2 UI smoothness pass (2026-08-28): fusedEastM/fusedNorthM
    // update at the ~5-10Hz GNSS/DR tick rate — drawing the anchor
    // directly from them each recomposition made the dashed anchor line
    // visibly step in small jumps rather than glide, the same issue
    // ui/map/StreetMapView.kt's real-map marker had (see
    // PositionSmoother's doc for why this needs a display-frame-rate loop,
    // not just drawing the latest tick). Reuses that SAME generic 2D
    // exponential smoother (units-agnostic — local meters here, lat/lon
    // there) rather than duplicating the chase math a third time.
    // targetEastM/targetNorthM mirror the incoming parameters into a
    // MutableState the loop below can poll every frame — a plain closure
    // over the parameters would go stale (LaunchedEffect(Unit) launches
    // once, it would never see later recompositions' new parameter
    // values), so this indirection is required, not optional.
    val smoother = remember { PositionSmoother() }
    val targetEastM = remember { mutableStateOf(fusedEastM) }
    val targetNorthM = remember { mutableStateOf(fusedNorthM) }
    targetEastM.value = fusedEastM
    targetNorthM.value = fusedNorthM
    var smoothedEastM by remember { mutableStateOf(fusedEastM) }
    var smoothedNorthM by remember { mutableStateOf(fusedNorthM) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            val smoothed = smoother.stepPosition(targetEastM.value, targetNorthM.value) ?: continue
            smoothedEastM = smoothed.first
            smoothedNorthM = smoothed.second
        }
    }

    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f

        // Root frame fill, re-inspected 2026-08-25 against the exact
        // frame the user pointed at (see ui/theme/Color.kt's note on
        // ScreenGradientTop/Bottom) — a top-to-bottom linear gradient,
        // not the flat DarkBackground this canvas used before.
        drawRect(Brush.verticalGradient(listOf(gradientTop, gradientBottom)))

        // Screen Y grows downward; local North should read "up" on screen,
        // so it's subtracted rather than added (CLAUDE.md Rule 9/14 — this
        // is a DEVICE-SCREEN-frame transform, distinct from the sensor
        // frame transforms elsewhere in this codebase, named here explicitly).
        val anchorScreenX = centerX - (smoothedEastM.toFloat() * METERS_TO_PIXELS)
        val anchorScreenY = centerY + (smoothedNorthM.toFloat() * METERS_TO_PIXELS)

        // Subtle grid — Google Maps' LAYOUT language (a map-like surface),
        // not real road geometry.
        val gridSpacingPx = METERS_TO_PIXELS * 20f
        var gx = centerX % gridSpacingPx
        while (gx < size.width) {
            drawLine(AccentBlue.copy(alpha = 0.08f), Offset(gx, 0f), Offset(gx, size.height))
            gx += gridSpacingPx
        }
        var gy = centerY % gridSpacingPx
        while (gy < size.height) {
            drawLine(AccentBlue.copy(alpha = 0.08f), Offset(0f, gy), Offset(size.width, gy))
            gy += gridSpacingPx
        }

        if (mode == GnssMode.DEAD_RECKONING || mode == GnssMode.REACQUISITION) {
            drawLine(
                color = CtaRed,
                start = Offset(anchorScreenX, anchorScreenY),
                end = Offset(centerX, centerY),
                strokeWidth = 5f,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f)),
            )
            drawCircle(color = CtaRed, radius = 6f, center = Offset(anchorScreenX, anchorScreenY))
        }

        // Accuracy halo (translucent), then ring, then filled dot — Google
        // Maps' own current-position marker LAYOUT, Figma's accent-blue palette.
        drawCircle(color = AccentBlue.copy(alpha = 0.18f), radius = 40f, center = Offset(centerX, centerY))
        drawCircle(
            color = AccentBlue,
            radius = 12f,
            center = Offset(centerX, centerY),
            style = Stroke(width = 3f),
        )
        drawCircle(color = AccentBlueLight, radius = 9f, center = Offset(centerX, centerY))
    }
}
