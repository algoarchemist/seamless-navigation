package com.sih26168.idr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.sih26168.idr.R
import com.sih26168.idr.ui.theme.AccentBlue
import com.sih26168.idr.ui.theme.GlassSurface
import com.sih26168.idr.ui.theme.PillShape
import com.sih26168.idr.ui.theme.TextPrimary
import com.sih26168.idr.ui.theme.TextSecondary

/** PRD.md Section 6 (Scope, "User-selected Car / Motorcycle mode") / Section 22. */
enum class VehicleMode { CAR, MOTORCYCLE }

/**
 * Two-option segmented control, styled as two adjacent pill buttons on
 * the [com.sih26168.idr.ui.theme.GlassSurface] background (Figma's own
 * pill-button convention). `Car` uses res/drawable/ic_car.xml (exported
 * from the Figma file's tab-bar icon set); `Motorcycle` uses
 * res/drawable/ic_motorcycle.xml, a small hand-drawn glyph since no
 * motorcycle icon exists in the Figma file (see that drawable's own doc
 * comment).
 *
 * LOCAL UI STATE ONLY (CLAUDE.md Rule 8): nothing in the pipeline
 * currently branches on vehicle type — PRD Section 6 explicitly excludes
 * automatic car-vs-motorcycle classification, and no manual-selection
 * consumer exists downstream yet either. This is a real, working
 * control that stores a selection; it does not yet change any
 * physics/ML behavior, and is not pretending to.
 */
@Composable
fun VehicleModeSelector(
    selected: VehicleMode,
    onSelect: (VehicleMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        VehicleModeOption(
            label = "Car",
            iconRes = R.drawable.ic_car,
            isSelected = selected == VehicleMode.CAR,
            onClick = { onSelect(VehicleMode.CAR) },
        )
        Spacer(modifier = Modifier.size(8.dp))
        VehicleModeOption(
            label = "Motorcycle",
            iconRes = R.drawable.ic_motorcycle,
            isSelected = selected == VehicleMode.MOTORCYCLE,
            onClick = { onSelect(VehicleMode.MOTORCYCLE) },
        )
    }
}

@Composable
private fun VehicleModeOption(
    label: String,
    iconRes: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (isSelected) AccentBlue else GlassSurface
    val textColor = if (isSelected) TextPrimary else TextSecondary
    Row(
        modifier = Modifier
            .clip(PillShape)
            .background(background, PillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = label, tint = textColor)
        Spacer(modifier = Modifier.size(6.dp))
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = textColor)
    }
}
