package com.sih26168.idr.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// 40dp is a real, directly-inspected value (the Figma Modal Sheet
// component's corner radius). The others are reasonable derived steps
// down from it, not separately measured — Figma's chip/pill components
// use a full-pill radius (50% of height), which RoundedCornerShape(50)
// approximates generically regardless of the element's actual height.
val GlassCardRadius = 24.dp // compact cards (status overlay); 40dp read large on a phone-sized chip/card
val LargeGlassCardRadius = 40.dp // Modal Sheet's own directly-inspected value (bottom sheets)
val PillShape = RoundedCornerShape(percent = 50) // chips, buttons — Figma's own pill-button convention
