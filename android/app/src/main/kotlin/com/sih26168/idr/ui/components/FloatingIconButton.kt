package com.sih26168.idr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.sih26168.idr.ui.theme.NeutralIconButtonBg
import com.sih26168.idr.ui.theme.TextPrimary

/**
 * Circular floating icon button — Figma's "Navigation Button" component
 * (Simple Components frame's search/recenter/settings cluster on the map
 * screen), inspected directly: solid `#383E42` circle, 44dp per the
 * "Current Location" icon's own frame size. Used once in this app, for
 * the manual recalibrate action (PRD Section 15/31/32) with
 * res/drawable/ic_recenter.xml (exported from that same Figma icon).
 */
@Composable
fun FloatingIconButton(
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(NeutralIconButtonBg),
    ) {
        Icon(painter = icon, contentDescription = contentDescription, tint = TextPrimary)
    }
}
