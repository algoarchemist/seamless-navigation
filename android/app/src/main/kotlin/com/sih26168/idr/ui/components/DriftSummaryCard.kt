package com.sih26168.idr.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sih26168.idr.fusion.DriftSummaryResult
import com.sih26168.idr.ui.theme.LargeGlassCardRadius
import com.sih26168.idr.ui.theme.TextPrimary
import com.sih26168.idr.ui.theme.TextSecondary

/**
 * PRD.md Section 30 WOW-factor #4 — shows the REAL measured drift number
 * [com.sih26168.idr.fusion.DriftSummary] computed (not a marketing
 * claim), on the bottom-sheet-scale [GlassCard] (Figma's own Modal Sheet
 * corner radius, 40dp). Dismissible — [onDismiss] is the caller's job to
 * wire (e.g. clearing local "shown" state), this component has no
 * internal visibility logic of its own.
 */
@Composable
fun DriftSummaryCard(
    driftSummary: DriftSummaryResult,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier.fillMaxWidth(), cornerRadius = LargeGlassCardRadius) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "%.1f m drift over %.1f m travelled".format(
                    driftSummary.driftMeters,
                    driftSummary.distanceTravelledMeters,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
            )
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Dismiss", tint = TextSecondary)
            }
        }
        Text(
            text = "Measured at GNSS reacquisition — straight-line distance, not integrated path length.",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
        )
    }
}
