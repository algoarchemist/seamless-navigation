package com.sih26168.idr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sih26168.idr.ui.theme.DeadReckoningColor
import com.sih26168.idr.ui.theme.GlassCardRadius
import com.sih26168.idr.ui.theme.GnssAidedColor
import kotlinx.coroutines.delay

/**
 * A brief, non-blocking banner shown the moment the app actually starts
 * dead-reckoning — user-requested "create a popup when switching from
 * gnss aided to dead reckoning mode" (2026-08-29). Deliberately NOT a
 * blocking AlertDialog: this app's whole point is honest, UNINTERRUPTED
 * navigation through a GNSS outage (see CLAUDE.md's Mission), so a modal
 * the driver has to dismiss at the exact moment GNSS drops would work
 * against that. Auto-dismisses after [autoDismissMs] like a toast, with a
 * manual dismiss (X) for anyone who wants it gone sooner — same
 * transient-surface convention [DriftSummaryCard] already uses, just
 * sized/colored to stand out from the persistent GNSS-mode [StatusChip]
 * next to it rather than blend in with it.
 *
 * [DeadReckoningColor] is used as a SOLID background here (not the usual
 * translucent-dot-on-glass chip look) specifically so this reads as a
 * momentary alert, not another status chip. Text is hardcoded white
 * rather than the theme-aware TextPrimary — same reasoning
 * ui/theme/Color.kt's own header comment gives for keeping
 * [DeadReckoningColor] itself constant across light/dark theme: this is a
 * fixed-color alert surface, and TextPrimary would lose contrast against
 * it in light mode (where TextPrimary is near-black).
 *
 * Shares its layout/dismiss behavior with [GnssReacquiredBanner] (the
 * symmetric "back to GNSS" counterpart, STATUS_AND_ROADMAP.md Tier-1
 * item #2) via the private [ModeChangeBanner] both now call — pulled out
 * once a second real caller existed, rather than speculatively before
 * one did.
 */
@Composable
fun GnssModeChangeBanner(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    autoDismissMs: Long = 4_000L,
) {
    ModeChangeBanner(
        title = "GNSS signal lost",
        message = "Switched to dead reckoning — position is now estimated from motion sensors.",
        backgroundColor = DeadReckoningColor,
        onDismiss = onDismiss,
        modifier = modifier,
        autoDismissMs = autoDismissMs,
    )
}

/**
 * The symmetric counterpart to [GnssModeChangeBanner]: shown the moment
 * the state machine confirms a real GNSS fix again (REACQUISITION ->
 * GNSS_AIDED — see `ui/screens/StatusOverlayContent.kt`'s
 * `showReacquiredBanner`, mirroring that file's own `showModeChangeBanner`
 * gating). [GnssAidedColor] (the same color [com.sih26168.idr.ui.screens.StatusOverlayContent]
 * already uses for the GNSS_AIDED status chip) marks this as the "good
 * news" counterpart to [DeadReckoningColor]'s outage alert, same
 * solid-background-for-a-momentary-alert reasoning as that banner.
 */
@Composable
fun GnssReacquiredBanner(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    autoDismissMs: Long = 4_000L,
) {
    ModeChangeBanner(
        title = "GNSS signal reacquired",
        message = "Back on live GPS — dead-reckoning estimate has been corrected.",
        backgroundColor = GnssAidedColor,
        onDismiss = onDismiss,
        modifier = modifier,
        autoDismissMs = autoDismissMs,
    )
}

@Composable
private fun ModeChangeBanner(
    title: String,
    message: String,
    backgroundColor: Color,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    autoDismissMs: Long = 4_000L,
) {
    LaunchedEffect(Unit) {
        delay(autoDismissMs)
        onDismiss()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GlassCardRadius))
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.9f),
            )
        }
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Dismiss",
            tint = Color.White,
            modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp),
        )
    }
}
