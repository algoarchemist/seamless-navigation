package com.sih26168.idr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sih26168.idr.ui.theme.AccentBlue
import com.sih26168.idr.ui.theme.PanelBackground
import com.sih26168.idr.ui.theme.TextSecondary

/** The three real screens this app has (Slice 8b) — one tab each. */
enum class AppTab(val label: String) {
    DRIVE("Drive"),
    MAP("Map"),
    HISTORY("History"),
}

/**
 * Bottom tab bar — Figma's "Timeline" screen bottom icon row (Km/
 * Following/Map/Saved) established the LAYOUT convention (evenly spaced,
 * icon/label per item, one active item) this reuses. HONEST SIMPLIFICATION
 * (CLAUDE.md Rule 13, same spirit as `ic_motorcycle.xml`'s own note):
 * text-only pills rather than per-tab icon exports — this project's own
 * "Drive"/"Map"/"History" concepts don't map 1:1 onto the Figma icon set
 * (Km/Following/Saved are trip-tracking concepts this app doesn't
 * implement), so labels were chosen for honesty over forcing a mismatched
 * icon. Still uses the same [PanelBackground] panel fill + [AccentBlue]
 * active-state color the rest of the Figma-derived palette uses.
 */
@Composable
fun BottomNavBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(PanelBackground)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Text(
                text = tab.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) AccentBlue else TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}
