package com.sih26168.idr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sih26168.idr.routing.GeocodeResult
import com.sih26168.idr.ui.theme.CtaRed
import com.sih26168.idr.ui.theme.GlassCardRadius
import com.sih26168.idr.ui.theme.GlassSurface
import com.sih26168.idr.ui.theme.TextPrimary
import com.sih26168.idr.ui.theme.TextSecondary

/**
 * Figma Home screen's destination-input concept ("Where are you going
 * today?" -> "Your Current Location" / "Work" fields) — REAL here rather
 * than static mock text: [query] drives a live `routing/GeocodingRepository`
 * search (Nominatim), [results] are real matching places to pick from, not
 * placeholder rows. Added 2026-08-26 alongside real routing, per the
 * user's explicit request to build full destination search + routing.
 */
@Composable
fun DestinationSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<GeocodeResult>,
    isSearching: Boolean,
    onSelectResult: (GeocodeResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GlassCardRadius))
            .background(GlassSurface, RoundedCornerShape(GlassCardRadius))
            .padding(12.dp),
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search destination…", color = TextSecondary) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = CtaRed,
            ),
        )
        if (isSearching) {
            CircularProgressIndicator(modifier = Modifier.padding(8.dp), color = CtaRed)
        }
        results.forEach { result ->
            Text(
                text = result.displayName,
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectResult(result) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
            )
        }
    }
}
