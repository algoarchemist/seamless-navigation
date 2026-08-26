package com.sih26168.idr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sih26168.idr.fusion.DriftSummaryResult
import com.sih26168.idr.ui.theme.DeadReckoningColor
import com.sih26168.idr.ui.theme.GlassSurface
import com.sih26168.idr.ui.theme.PillShape
import com.sih26168.idr.ui.theme.TextPrimary

/**
 * PRD.md Section 30 WOW-factor #4 — a REAL measured drift event just
 * happened ([com.sih26168.idr.fusion.DriftSummary], not a marketing claim),
 * but user-requested (2026-08-26: "so annoying... reduce the size... just
 * 'drift detected'") this be a small, dismissible status chip instead of a
 * full attention-grabbing card — same [StatusChip] pill language the rest
 * of the status overlay already uses, not a second visual style. The exact
 * numeric value ([driftSummary]'s own `driftMeters`/`distanceTravelledMeters`)
 * isn't thrown away — it's still real data available in full on
 * `ui/screens/HistoryScreen.kt`'s drift log; this chip is just the
 * transient, low-friction "something happened" notice.
 */
@Composable
fun DriftSummaryCard(
    driftSummary: DriftSummaryResult,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The real measured number isn't shown visually anymore (that's the
    // whole point of this compaction), but it's not thrown away either —
    // exposed via semantics so accessibility services still read the real
    // value, and so this parameter has a real purpose rather than sitting
    // unused now that the on-screen text is fixed to "Drift detected".
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(GlassSurface, PillShape)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .semantics {
                contentDescription = "Drift detected: %.1f m drift over %.1f m travelled".format(
                    driftSummary.driftMeters,
                    driftSummary.distanceTravelledMeters,
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(DeadReckoningColor))
        Spacer(modifier = Modifier.size(8.dp))
        Text(text = "Drift detected", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
        Spacer(modifier = Modifier.size(4.dp))
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Dismiss",
            tint = TextPrimary,
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .clickable(onClick = onDismiss)
                .padding(2.dp),
        )
    }
}
