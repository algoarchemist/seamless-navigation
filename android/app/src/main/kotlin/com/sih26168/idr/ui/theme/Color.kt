package com.sih26168.idr.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Extracted directly from the Figma "Navigation app (Community)" file
// (https://www.figma.com/design/UUJcsjOJOo9rhJjKT4gMiz) via its own
// Design-tab inspector — Color Styles frame (dark-theme text) and "Map
// app Color" frame (map-app-specific palette), NOT eyeballed from a
// screenshot. See docs/PROJECT_MAP.md's ui/theme/Color.kt entry for the
// per-value source note.

// Map-marker / status-accent colors — DELIBERATELY constant across both
// light and dark theme (2026-08-26 light-mode addition): real map apps
// (Google Maps included) keep their current-position dot and route/status
// accent colors the same regardless of theme, only the surrounding chrome
// changes. These are used from BOTH Composable and non-Composable contexts
// (e.g. ui/map/StreetMapView.kt's Overlay.draw(), which is not a
// Composable function) so they stay plain constants, not theme-aware
// getters, unlike the CHROME tokens below.
val DarkBackground = Color(0xFF0B0C0D) // "Tab bar" darker stop
val CtaRed = Color(0xFFB13025) // "Button"
val CtaRedPressed = Color(0xFF281000) // "Button" pressed-shade stop
val AccentBlue = Color(0xFF2B64A8) // "Location icon" / "Blue Chart" darker stop
val AccentBlueLight = Color(0xFF499FEF) // "Location icon" / "Blue Chart" lighter stop
val AlertRed = Color(0xFFFF0000) // "Line Stroke" (100% -> 0% alpha in Figma)

// GNSS-state status colors — Figma has no 4-state status palette to
// import (its map screen only shows a single "on route" state), so
// these are a reasoned traffic-light-style EXTENSION built from colors
// already in the extracted palette, not a direct Figma import.
val GnssAidedColor = AccentBlueLight // trustworthy / normal
val TransitionColor = Color(0xFFE8A33D) // caution — not in Figma's palette, a standard amber
val DeadReckoningColor = CtaRed // degraded — reuses the CTA red already in the palette
val ReacquisitionColor = AccentBlue // blending back to trustworthy

// Unused so far (Slice 8 simplified to one GlassCard instance across the
// app) — kept as plain constants, not part of the light/dark palette
// below, since nothing reads them yet. See GlassCard.kt's doc comment.
val BottomSheetFill = Color(0x14FFFFFF)
val BottomSheetBorderStart = Color(0x99FFFFFF)
val BottomSheetBorderEnd = Color(0x00000000)

/**
 * The CHROME tokens — text, glass surfaces, panel/nav-bar backgrounds,
 * screen gradient — that DO change between light and dark mode (added
 * 2026-08-26, user-requested light mode). Grouped into one data class
 * rather than individually theme-aware `val`s so [IdrTheme] has exactly
 * one place to swap the whole set.
 */
data class IdrPalette(
    val textPrimary: Color,
    val textSecondary: Color,
    val screenGradientTop: Color,
    val screenGradientBottom: Color,
    val glassSurface: Color,
    val glassBorder: Color,
    val panelBackground: Color,
    val neutralIconButtonBg: Color,
)

// The ORIGINAL Figma-extracted dark palette (unchanged values, same
// sourcing as this file's header comment describes) — Color Styles
// frame's "Dark" text section, "Map app Color" frame's Tab bar/Navigation
// Button values, and the Drag-Gesture-page Modal Sheet instance's
// gradient stops (see docs/PROJECT_MAP.md for the full per-value trail).
val DarkIdrPalette = IdrPalette(
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFEBEBF5),
    screenGradientTop = Color(0xFF2A2D32),
    screenGradientBottom = Color(0xFF030303),
    glassSurface = Color(0x73FFFFFF), // #FFFFFF at 45% alpha
    glassBorder = Color(0x1AFFFFFF),
    panelBackground = Color(0xFF2D3443), // "Tab bar" lighter stop
    neutralIconButtonBg = Color(0xFF383E42), // "Navigation Button"
)

// REASONED EXTENSION, NOT a Figma import (CLAUDE.md Rule 13) — the source
// Figma file is dark-theme only, so there is nothing to directly inspect
// for a light variant. Built by inverting the SAME structure the dark
// palette uses (dark text on light surfaces, translucent BLACK glass
// instead of translucent white) rather than picking arbitrary new colors,
// so the two themes stay visually consistent in everything but polarity.
val LightIdrPalette = IdrPalette(
    textPrimary = Color(0xFF1C1C1E),
    textSecondary = Color(0xFF48484A),
    screenGradientTop = Color(0xFFF2F3F5),
    screenGradientBottom = Color(0xFFFFFFFF),
    glassSurface = Color(0xB3FFFFFF), // white glass, higher opacity for legibility on a light bg
    glassBorder = Color(0x1A000000),
    panelBackground = Color(0xFFE4E6EA),
    neutralIconButtonBg = Color(0xFFD8DBDF),
)

/** Provided by [IdrTheme]; defaults to dark so any preview/test context outside it still resolves. */
val LocalIdrPalette = staticCompositionLocalOf { DarkIdrPalette }

// Composable-getter properties, NOT plain constants — every existing call
// site (`color = TextPrimary`, etc.) keeps compiling unchanged; the value
// now resolves against whichever palette IdrTheme currently provides
// instead of being fixed at declaration time. Restricted to Composable
// contexts only (CLAUDE.md Rule 9/14-style explicitness: these must never
// be read from a non-Composable class like StreetMapView's Overlay.draw()
// — that file intentionally keeps using the plain constants above instead).
val TextPrimary: Color @Composable get() = LocalIdrPalette.current.textPrimary
val TextSecondary: Color @Composable get() = LocalIdrPalette.current.textSecondary
val ScreenGradientTop: Color @Composable get() = LocalIdrPalette.current.screenGradientTop
val ScreenGradientBottom: Color @Composable get() = LocalIdrPalette.current.screenGradientBottom
val GlassSurface: Color @Composable get() = LocalIdrPalette.current.glassSurface
val GlassBorder: Color @Composable get() = LocalIdrPalette.current.glassBorder
val PanelBackground: Color @Composable get() = LocalIdrPalette.current.panelBackground
val NeutralIconButtonBg: Color @Composable get() = LocalIdrPalette.current.neutralIconButtonBg
