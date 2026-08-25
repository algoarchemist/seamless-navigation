package com.sih26168.idr.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val IdrColorScheme = darkColorScheme(
    background = DarkBackground,
    surface = PanelBackground,
    primary = AccentBlueLight,
    secondary = AccentBlue,
    error = CtaRed,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
)

/** Wraps Slice 8's Figma-derived design tokens (Color.kt/Type.kt/Shape.kt) into a MaterialTheme. */
@Composable
fun IdrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IdrColorScheme,
        typography = IdrTypography,
        content = content,
    )
}
