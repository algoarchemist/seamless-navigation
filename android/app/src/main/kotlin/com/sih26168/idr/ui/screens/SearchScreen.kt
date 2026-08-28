package com.sih26168.idr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sih26168.idr.routing.GeocodeResult
import com.sih26168.idr.routing.GeocodeSearchOutcome
import com.sih26168.idr.routing.GeocodingRepository
import com.sih26168.idr.ui.theme.CtaRed
import com.sih26168.idr.ui.theme.NeutralIconButtonBg
import com.sih26168.idr.ui.theme.ScreenGradientBottom
import com.sih26168.idr.ui.theme.ScreenGradientTop
import com.sih26168.idr.ui.theme.TextPrimary
import com.sih26168.idr.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * Full-page destination search — Google Maps' own pattern: tapping the
 * collapsed search bar on [MapScreen] opens THIS screen (covers the whole
 * app, not a small floating dropdown drawn on top of the live map), the
 * user searches in isolation here, then [onResultSelected] hands the
 * chosen place back and the caller returns to [MapScreen] to start
 * routing. Added 2026-08-28, user-requested "search destination like
 * Google Maps, redirects to map page when searched" — MapScreen used to
 * overlay live search results directly over the map tiles (a fixed
 * 300dp-offset floating box); this replaces that with a dedicated screen.
 *
 * No navigation library added (CLAUDE.md Rule 2) — this follows the SAME
 * manual boolean-state screen-swap pattern MainActivity already uses for
 * the debug screen / tab switching, just one level down inside MapScreen.
 *
 * Owns its own debounced Nominatim search (moved here from MapScreen,
 * which used to run this same LaunchedEffect inline against its floating
 * dropdown) — MapScreen now only holds the FINAL selected result, not the
 * live in-progress query/results/error state.
 */
@Composable
fun SearchScreen(
    initialQuery: String,
    onBack: () -> Unit,
    onResultSelected: (GeocodeResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<GeocodeResult>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Debounced real Nominatim search — fires ~500ms after typing stops,
    // not on every keystroke (Nominatim's usage policy caps ~1 req/sec).
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            error = null
            return@LaunchedEffect
        }
        delay(500)
        isSearching = true
        when (val outcome = GeocodingRepository.search(query)) {
            is GeocodeSearchOutcome.Success -> {
                results = outcome.results
                error = if (outcome.results.isEmpty()) "No matches for \"$query\"" else null
            }
            is GeocodeSearchOutcome.Failure -> {
                results = emptyList()
                error = outcome.reason
            }
        }
        isSearching = false
    }

    // Auto-focus + open the keyboard the instant this page appears, same
    // as Google Maps' own search page — the user came here specifically to
    // type, so they shouldn't need an extra tap on the field first.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ScreenGradientTop, ScreenGradientBottom)))
            .statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(44.dp).clip(CircleShape).background(NeutralIconButtonBg),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to map", tint = TextPrimary)
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search destination…", color = TextSecondary) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
                    .focusRequester(focusRequester),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = CtaRed,
                ),
            )
        }
        if (isSearching) {
            CircularProgressIndicator(modifier = Modifier.padding(start = 16.dp, top = 8.dp), color = CtaRed)
        }
        if (error != null) {
            Text(
                text = error!!,
                style = MaterialTheme.typography.labelMedium,
                color = CtaRed,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(results) { result ->
                Text(
                    text = result.displayName,
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onResultSelected(result) }
                        .padding(vertical = 14.dp, horizontal = 16.dp),
                )
            }
        }
    }
}
