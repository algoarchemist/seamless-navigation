package com.sih26168.idr.ui.theme

import androidx.compose.ui.graphics.Color

// Extracted directly from the Figma "Navigation app (Community)" file
// (https://www.figma.com/design/UUJcsjOJOo9rhJjKT4gMiz) via its own
// Design-tab inspector — Color Styles frame (dark-theme text) and "Map
// app Color" frame (map-app-specific palette), NOT eyeballed from a
// screenshot. See docs/PROJECT_MAP.md's ui/theme/Color.kt entry for the
// per-value source note.

// Color Styles frame, "Dark" section (this app is dark-themed).
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFEBEBF5)

// "Map app Color" frame.
val DarkBackground = Color(0xFF0B0C0D) // "Tab bar" darker stop
val PanelBackground = Color(0xFF2D3443) // "Tab bar" lighter stop
val CtaRed = Color(0xFFB13025) // "Button"
val CtaRedPressed = Color(0xFF281000) // "Button" pressed-shade stop
val AccentBlue = Color(0xFF2B64A8) // "Location icon" / "Blue Chart" darker stop
val AccentBlueLight = Color(0xFF499FEF) // "Location icon" / "Blue Chart" lighter stop
val NeutralIconButtonBg = Color(0xFF383E42) // "Navigation Button"
val AlertRed = Color(0xFFFF0000) // "Line Stroke" (100% -> 0% alpha in Figma)

// The Modal Sheet component (Presentation page, map screen's instruction
// card / bottom metrics sheet), inspected directly: corner radius 40,
// fill #FFFFFF at 45% opacity over the dark background — a frosted/glass
// card. GlassCard.kt is built around this exact value.
val GlassSurface = Color(0x73FFFFFF) // #FFFFFF at 45% alpha (0x73 = 115/255 ~= 45%)
val GlassBorder = Color(0x1AFFFFFF) // subtle 1px light stroke, simplified from Figma's gradient stroke

// GNSS-state status colors — Figma has no 4-state status palette to
// import (its map screen only shows a single "on route" state), so
// these are a reasoned traffic-light-style EXTENSION built from colors
// already in the extracted palette, not a direct Figma import. Kept
// here (not buried in a component file) so this honest distinction is
// visible at the token definition site.
val GnssAidedColor = AccentBlueLight // trustworthy / normal
val TransitionColor = Color(0xFFE8A33D) // caution — not in Figma's palette, a standard amber
val DeadReckoningColor = CtaRed // degraded — reuses the CTA red already in the palette
val ReacquisitionColor = AccentBlue // blending back to trustworthy

// Re-inspected 2026-08-25 directly against the specific frame the user
// pointed at (figma.com/design/UUJcsjOJOo9rhJjKT4gMiz, node-id=135-3047,
// "Drag Gesture" page's "Timeline" screen) via Figma's Design-tab
// inspector — the root frame and its bottom Modal Sheet turn out to use
// DIFFERENT values than the earlier "Presentation" page instance
// GlassSurface/GlassBorder above came from. Both are real, directly-read
// values from two different instances of the same component in the same
// file — not a contradiction. These are the values DriveScreen.kt's
// bottom sheet now uses, since that IS the frame the user asked for.
val ScreenGradientTop = Color(0xFF2A2D32) // root frame fill, linear gradient stop at 3%
val ScreenGradientBottom = Color(0xFF030303) // root frame fill, linear gradient stop at 100%
val BottomSheetFill = Color(0x14FFFFFF) // Modal Sheet fill: #FFFFFF at 8% alpha (0x14 = 20/255 ~= 8%)
val BottomSheetBorderStart = Color(0x99FFFFFF) // Modal Sheet stroke gradient start: #FFFFFF at 60%
val BottomSheetBorderEnd = Color(0x00000000) // Modal Sheet stroke gradient end: #000000 at 0%
