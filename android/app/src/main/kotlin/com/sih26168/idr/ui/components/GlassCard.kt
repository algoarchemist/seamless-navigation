package com.sih26168.idr.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sih26168.idr.ui.theme.GlassBorder
import com.sih26168.idr.ui.theme.GlassCardRadius
import com.sih26168.idr.ui.theme.GlassSurface

/**
 * The frosted/glass card primitive — Figma's "Modal Sheet" component,
 * inspected directly via Figma's Design-tab inspector. Two real instances
 * of this component exist in the source file with different values
 * (see ui/theme/Color.kt): the default [fill]/[border] here are the
 * Presentation-page instance (45% flat fill, flat 1px stroke); pass
 * `fill = BottomSheetFill` / a gradient `border` built from
 * `BottomSheetBorderStart`/`BottomSheetBorderEnd` for the Drag-Gesture-page
 * instance DriveScreen.kt's live bottom sheet uses instead. `cornerRadius`
 * defaults to the smaller [GlassCardRadius] (40dp reads oversized on a
 * compact status chip/card — see Shape.kt's note), with the real 40dp
 * available via `LargeGlassCardRadius` for bottom-sheet-scale cards.
 *
 * No real backdrop blur is applied (Compose has no first-class
 * blur-what's-behind-me primitive; `Modifier.blur()` only blurs this
 * composable's own content, and is a no-op below API 31 besides) — the
 * translucent fill + gradient stroke alone approximate the frosted look.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GlassCardRadius,
    fill: Color = GlassSurface,
    border: Brush = SolidColor(GlassBorder),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Column(
        modifier = modifier
            .clip(shape)
            .background(fill, shape)
            .border(BorderStroke(1.dp, border), shape)
            .padding(16.dp),
    ) {
        content()
    }
}
