package com.sih26168.idr.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sih26168.idr.routing.RouteResult
import com.sih26168.idr.ui.theme.CtaRed
import com.sih26168.idr.ui.theme.LargeGlassCardRadius
import com.sih26168.idr.ui.theme.TextPrimary
import com.sih26168.idr.ui.theme.TextSecondary
import kotlin.math.roundToInt

/**
 * Figma's navigation-screen bottom sheet (real "6:32 arrival / 16 min /
 * 6.5 Km" style summary row, red "End" button) — REAL numbers here, from
 * [route] (`routing/RoutingRepository.kt`'s OSRM result), not mock text.
 * The step list below it is the honest substitute for Figma's live
 * "next turn in X m" banner: this project doesn't track live progress
 * along the route (matching current position to route distance
 * travelled), so rather than fake a live instruction, the full real step
 * list is shown instead (CLAUDE.md Rule 13 — real-but-static beats
 * fabricated-but-live).
 */
@Composable
fun ActiveRouteCard(
    route: RouteResult,
    onStartNavigation: () -> Unit,
    onDownloadOffline: () -> Unit,
    onEnd: () -> Unit,
    downloadStatus: String?,
    modifier: Modifier = androidx.compose.ui.Modifier,
) {
    // REAL BUG FIX (2026-08-26, found testing "Go" on-device): the caller
    // (MapScreen.kt) now passes a `heightIn(max = ...)` on [modifier] so this
    // card can never grow tall enough to overlap StatusOverlayContent's top
    // chip stack — verticalScroll here is what makes that cap safe: without
    // it, a route with a long destination name or several steps would just
    // overflow past the max-height boundary instead of respecting it. The
    // nested LazyColumn below stays fine inside this outer scroll since it
    // already has its own bounded `heightIn(max = 160.dp)`.
    GlassCard(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        cornerRadius = LargeGlassCardRadius,
    ) {
        Text(text = route.destinationName, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SummaryStat(value = formatDuration(route.durationSeconds), label = "duration")
            SummaryStat(value = formatDistance(route.distanceMeters), label = "distance")
        }
        if (downloadStatus != null) {
            Text(text = downloadStatus, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        }
        LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
            items(route.steps) { step ->
                Text(
                    text = "${step.instruction} (${formatDistance(step.distanceMeters)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // 2026-08-26, user-requested "Google Maps-like start mode": the
        // primary CTA now enters ui/components/NavigationBanner.kt's live
        // turn-by-turn view instead of this static step list being the only
        // option — same red CTA color Figma's own Start/Go buttons use.
        Button(
            onClick = onStartNavigation,
            colors = ButtonDefaults.buttonColors(containerColor = CtaRed),
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "Go", color = TextPrimary)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onDownloadOffline,
                colors = ButtonDefaults.buttonColors(containerColor = TextSecondary.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(percent = 50),
                modifier = Modifier.weight(1f),
            ) {
                Text(text = "Download offline", color = TextPrimary)
            }
            Button(
                onClick = onEnd,
                colors = ButtonDefaults.buttonColors(containerColor = CtaRed),
                shape = RoundedCornerShape(percent = 50),
                modifier = Modifier.weight(1f),
            ) {
                Text(text = "End", color = TextPrimary)
            }
        }
    }
}

@Composable
private fun SummaryStat(value: String, label: String) {
    androidx.compose.foundation.layout.Column {
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

private fun formatDuration(seconds: Double): String {
    val minutes = (seconds / 60.0).roundToInt()
    return if (minutes < 60) "$minutes min" else "${minutes / 60}h ${minutes % 60}m"
}

private fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.roundToInt()} m" else "%.1f km".format(meters / 1000.0)
