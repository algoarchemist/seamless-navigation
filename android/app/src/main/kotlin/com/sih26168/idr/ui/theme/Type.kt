package com.sih26168.idr.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The Figma file's Text Styles frame names an iOS-HIG-style scale
// (Regular + Bold x Caption2/Caption1/Footnote/Subheadline/Callout/Body/
// Headline/Title3/Title2/Title1/LargeTitle). HONEST GAP (CLAUDE.md
// Rule 13): I read the STYLE NAMES directly from Figma's inspector, but
// did not drill into every nested text node for exact px/line-height —
// the sizes below are the standard values those iOS HIG names denote,
// mapped onto Material3's Typography roles. This is a reasoned
// adaptation, not a pixel-exact extraction like Color.kt's hex values.
val IdrTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp), // LargeTitle
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp), // Title1
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp), // Title2
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp), // Title3
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp), // Headline
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 17.sp), // Body
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp), // Callout
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp), // Subheadline
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp), // Footnote
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp), // Caption1
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp), // Caption2
)
