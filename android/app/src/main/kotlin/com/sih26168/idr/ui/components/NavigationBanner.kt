package com.sih26168.idr.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.sih26168.idr.routing.RouteResult
import com.sih26168.idr.routing.RouteProgressResult
import com.sih26168.idr.ui.theme.CtaRed
import com.sih26168.idr.ui.theme.LargeGlassCardRadius
import com.sih26168.idr.ui.theme.TextPrimary
import com.sih26168.idr.ui.theme.TextSecondary
import kotlin.math.roundToInt

/**
 * The Google-Maps-"start navigation"-style live turn-by-turn screen, added
 * 2026-08-26 on explicit user request. Split into two pieces placed at the
 * top and bottom of the map (`ui/screens/MapScreen.kt`), the same
 * top/bottom split Google Maps and most nav apps use (and the same split
 * this screen already used for the idle search bar/`ActiveRouteCard`):
 *
 * - [NavigationInstructionCard] (top): the CURRENT instruction
 *   (`route.steps[currentStepIndex]`) in large text + live
 *   distance-to-that-maneuver, from `routing/RouteProgress.kt` — a REAL
 *   computed value (nearest-point-on-the-real-OSRM-polyline projection of
 *   the live fused position), not a static readout. Shows an "off route"
 *   warning if `distanceOffRouteMeters` exceeds [OFF_ROUTE_THRESHOLD_METERS]
 *   (CLAUDE.md Rule 13 — this project has no rerouting engine, so rather
 *   than silently keep showing stale directions, it honestly flags it).
 * - [NavigationEtaBar] (bottom): total remaining distance/ETA (recomputed
 *   live from the SAME progress, not the original route-computation-time
 *   totals) + Exit.
 *
 * [progress] is nullable in both — null means "no live position to project
 * yet" (e.g. no GNSS/DR anchor established this run), in which case these
 * fall back to the route's own static totals/first step rather than
 * crashing or showing nothing (same "real-but-degraded beats missing"
 * pattern as the rest of this app's honest-limitation callouts).
 */
private const val OFF_ROUTE_THRESHOLD_METERS = 50.0
private const val ARRIVED_THRESHOLD_METERS = 20.0

@Composable
fun NavigationInstructionCard(
    route: RouteResult,
    progress: RouteProgressResult?,
    modifier: Modifier = Modifier,
) {
    val stepIndex = progress?.currentStepIndex ?: 0
    val currentStep = route.steps.getOrNull(stepIndex)
    val distanceInStep = progress?.distanceRemainingInStepMeters ?: currentStep?.distanceMeters ?: 0.0
    val distanceTotal = progress?.distanceRemainingTotalMeters ?: route.distanceMeters
    val isOffRoute = (progress?.distanceOffRouteMeters ?: 0.0) > OFF_ROUTE_THRESHOLD_METERS
    val hasArrived = progress != null && distanceTotal <= ARRIVED_THRESHOLD_METERS

    GlassCard(modifier = modifier.fillMaxWidth(), cornerRadius = LargeGlassCardRadius) {
        if (hasArrived) {
            Text(
                text = "You have arrived",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
        } else {
            if (isOffRoute) {
                Text(
                    text = "Off route — no live rerouting yet, showing the original route's next step",
                    style = MaterialTheme.typography.labelMedium,
                    color = CtaRed,
                )
            }
            Text(
                text = formatDistance(distanceInStep),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Text(
                text = currentStep?.instruction ?: "Continue",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
        }
    }
}

@Composable
fun NavigationEtaBar(
    route: RouteResult,
    progress: RouteProgressResult?,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val distanceTotal = progress?.distanceRemainingTotalMeters ?: route.distanceMeters

    GlassCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = formatEtaMinutes(distanceTotal, route.distanceMeters, route.durationSeconds),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                Text(
                    text = "${formatDistance(distanceTotal)} remaining",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
            }
            Button(
                onClick = onExit,
                colors = ButtonDefaults.buttonColors(containerColor = CtaRed),
                shape = RoundedCornerShape(percent = 50),
            ) {
                Text(text = "Exit", color = TextPrimary)
            }
        }
    }
}

private fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.roundToInt()} m" else "%.1f km".format(meters / 1000.0)

/**
 * Scales the route's original OSRM-estimated duration by how much distance
 * actually remains, rather than showing the fixed original ETA the whole
 * trip — a real (if approximate) live re-estimate, not a static number
 * pretending to be live. Falls back to the original duration if the route
 * has essentially zero length (avoids a divide-by-zero).
 */
private fun formatEtaMinutes(remainingMeters: Double, totalMeters: Double, totalDurationSeconds: Double): String {
    val fractionRemaining = if (totalMeters > 1.0) (remainingMeters / totalMeters).coerceIn(0.0, 1.0) else 1.0
    val minutes = (totalDurationSeconds * fractionRemaining / 60.0).roundToInt()
    return if (minutes < 60) "$minutes min" else "${minutes / 60}h ${minutes % 60}m"
}
