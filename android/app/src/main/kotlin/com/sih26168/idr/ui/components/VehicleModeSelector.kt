package com.sih26168.idr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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

/**
 * PRD.md Section 6 originally scoped this to Car/Motorcycle only. WALKING
 * was added 2026-08-26 on explicit user request, overriding that scope
 * (same "explicit override" pattern already used for Slice 8b's map
 * dependency and the full-routing addition — see docs/PROJECT_MAP.md).
 * Unlike CAR/MOTORCYCLE, WALKING is NOT local-UI-state-only: selecting it
 * actually changes dr/BaselineDeadReckoningRepository's behavior (see
 * that file's `walkingModeEnabled` — the vehicle-only non-holonomic
 * constraint is skipped for a pedestrian, who can strafe/turn in place in
 * a way a car physically cannot).
 */
enum class VehicleMode { CAR, MOTORCYCLE, WALKING }

/**
 * Three-option segmented control, styled as adjacent pill buttons on the
 * [com.sih26168.idr.ui.theme.GlassSurface] background (Figma's own
 * pill-button convention). `Car` uses res/drawable/ic_car.xml (exported
 * from the Figma file's tab-bar icon set); `Motorcycle`/`Walking` use
 * res/drawable/ic_motorcycle.xml / ic_walk.xml, small hand-drawn glyphs
 * since neither icon exists in the Figma file (see each drawable's own
 * doc comment).
 */
@Composable
fun VehicleModeSelector(
    selected: VehicleMode,
    onSelect: (VehicleMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    // REAL BUG FIX (2026-08-26, found testing on a real S24 FE): three pills
    // (Car/Motorcycle/Walk) are wider than the phone's screen once the
    // recenter FloatingIconButton's reserved space (see
    // StatusOverlayContent.kt's Row, changed in the same fix) is subtracted
    // — this used to clip/overlap the last pill's label. Scrolls instead of
    // clipping now, so it stays readable regardless of how many modes this
    // ever grows to.
    Row(modifier = modifier.horizontalScroll(rememberScrollState())) {
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
        Spacer(modifier = Modifier.size(8.dp))
        VehicleModeOption(
            label = "Walk",
            iconRes = R.drawable.ic_walk,
            isSelected = selected == VehicleMode.WALKING,
            onClick = { onSelect(VehicleMode.WALKING) },
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
