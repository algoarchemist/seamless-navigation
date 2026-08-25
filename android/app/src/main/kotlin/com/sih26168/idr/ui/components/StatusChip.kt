package com.sih26168.idr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sih26168.idr.ui.theme.GlassSurface
import com.sih26168.idr.ui.theme.PillShape
import com.sih26168.idr.ui.theme.TextPrimary

/**
 * Pill/chip primitive — Figma's pill-button convention (Simple
 * Components frame's Start/Stop/Go buttons all use a full-pill shape),
 * generalized here into a status indicator: a small colored dot + label,
 * on the same [GlassSurface] frosted background the rest of the screen
 * uses. Used for GNSS mode (FR10) and the motion-state readout.
 */
@Composable
fun StatusChip(
    label: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(GlassSurface, PillShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
    }
}
