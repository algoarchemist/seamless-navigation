package com.sih26168.idr.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable

/**
 * Wraps Slice 8's Figma-derived design tokens (Color.kt/Type.kt/Shape.kt)
 * into a MaterialTheme. [darkTheme] (added 2026-08-26, user-requested
 * light mode) selects between [DarkIdrPalette] (the real Figma-extracted
 * values) and [LightIdrPalette] (a reasoned, non-Figma light counterpart —
 * see that val's own doc comment) via [LocalIdrPalette], which every
 * chrome-color token in Color.kt (TextPrimary, GlassSurface, etc.) reads
 * from. Defaults to dark so every existing call site keeps its original
 * appearance unless a caller explicitly opts into light mode.
 */
@Composable
fun IdrTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val palette = if (darkTheme) DarkIdrPalette else LightIdrPalette
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            background = palette.screenGradientBottom,
            surface = palette.panelBackground,
            primary = AccentBlueLight,
            secondary = AccentBlue,
            error = CtaRed,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
        )
    } else {
        lightColorScheme(
            background = palette.screenGradientBottom,
            surface = palette.panelBackground,
            primary = AccentBlue,
            secondary = AccentBlueLight,
            error = CtaRed,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
        )
    }

    CompositionLocalProvider(LocalIdrPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = IdrTypography,
            content = content,
        )
    }
}
