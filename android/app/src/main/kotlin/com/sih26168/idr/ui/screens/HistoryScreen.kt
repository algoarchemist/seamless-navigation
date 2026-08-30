package com.sih26168.idr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sih26168.idr.fusion.DriftSummaryResult
import com.sih26168.idr.ui.components.GlassCard
import com.sih26168.idr.ui.theme.ScreenGradientBottom
import com.sih26168.idr.ui.theme.ScreenGradientTop
import com.sih26168.idr.ui.theme.TextPrimary
import com.sih26168.idr.ui.theme.TextSecondary
import androidx.compose.ui.graphics.Brush

/**
 * The "other window" companion to [MapScreen] (Slice 8b) — inspired by
 * the Figma template's "Timeline" screen (a scrollable list of
 * past trip events on the same dark/glass visual language). HONEST
 * DEPARTURE from that screen (CLAUDE.md Rule 13): Figma's Timeline shows a
 * fabricated per-day mileage bar chart with no data source behind it. This
 * screen instead lists [FusedPositionUiState.driftHistory] —
 * [fusion.StateEstimator]'s real, already-measured drift-at-reacquisition
 * log (PRD.md Section 30 WOW-factor #4's number, kept as a running list
 * instead of a single overwritten field). Real content over visual
 * parity: an empty, honest list beats a chart with numbers nobody measured.
 */
@Composable
fun HistoryScreen(driftHistory: List<DriftSummaryResult>) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ScreenGradientTop, ScreenGradientBottom)))
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "History", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
            Text(
                text = "Real, measured drift at each GNSS reacquisition this run — " +
                    "not a fabricated trip log.",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
            if (driftHistory.isEmpty()) {
                Text(
                    text = "No GNSS outage has been reacquired yet this run.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(driftHistory.withIndex().toList().reversed()) { (index, entry) ->
                        HistoryEntryCard(index = index + 1, entry = entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryEntryCard(index: Int, entry: DriftSummaryResult) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Outage #$index",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        Text(
            text = "%.1f m drift over %.1f m travelled".format(entry.driftMeters, entry.distanceTravelledMeters),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
}
