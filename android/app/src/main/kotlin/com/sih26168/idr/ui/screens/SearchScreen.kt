package com.sih26168.idr.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sih26168.idr.R
import com.sih26168.idr.routing.GeocodeResult
import com.sih26168.idr.routing.GeocodeSearchOutcome
import com.sih26168.idr.routing.GeocodingRepository
import com.sih26168.idr.routing.RecentSearchRepository
import com.sih26168.idr.routing.SavedPlaceSlot
import com.sih26168.idr.routing.SavedPlacesRepository
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
 * Google Maps, redirects to map page when searched".
 *
 * RESTYLED 2026-08-29 to match a user-supplied Google Maps screenshot of
 * its own search screen: a rounded search pill (back arrow + field + mic/
 * clear button), a Home/Work quick-access row, and a "Recent" list shown
 * while the query is empty. Two pieces of that reference needed REAL data
 * behind them rather than being copied as static decoration (CLAUDE.md
 * Rule 13):
 *  - [SavedPlacesRepository] — Home/Work are genuinely user-set locations
 *    (SharedPreferences-backed), not placeholder addresses. Tapping an
 *    unset slot switches this screen into "pick a location to save as
 *    Home/Work" mode (`settingSlot`) instead of pretending one exists.
 *  - [RecentSearchRepository] — "Recent" is this device's REAL past picks,
 *    not sample data. The reference screenshot's per-row business-hours
 *    line ("Open · Closes 22:00") is deliberately left out: Nominatim (the
 *    only geocoding source this app has) doesn't return opening-hours
 *    data, so there is nothing real to show there.
 * The reference's third shortcut ("… More", revealing additional saved
 * lists) is left out entirely rather than built as a non-functional
 * button — this app has no additional saved-place concept for it to open.
 *
 * No navigation library added (CLAUDE.md Rule 2) — this follows the SAME
 * manual boolean-state screen-swap pattern MainActivity already uses for
 * the debug screen / tab switching, just one level down inside MapScreen.
 * The mic button uses Android's own `android.speech` API (platform-
 * provided, not a new dependency) to launch the system speech-recognition
 * UI and fill in the REAL transcribed text — not a decorative icon that
 * does nothing when tapped.
 */
@Composable
fun SearchScreen(
    initialQuery: String,
    onBack: () -> Unit,
    onResultSelected: (GeocodeResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<GeocodeResult>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Loaded once up front and kept in local state (not re-read from
    // SharedPreferences on every recomposition) — updated in place by
    // handleResultTap() below whenever a pick changes them.
    var recentSearches by remember { mutableStateOf(RecentSearchRepository.getRecent(context)) }
    var homePlace by remember { mutableStateOf(SavedPlacesRepository.get(context, SavedPlaceSlot.HOME)) }
    var workPlace by remember { mutableStateOf(SavedPlacesRepository.get(context, SavedPlaceSlot.WORK)) }
    // Non-null while the user is picking a location to SAVE into a
    // Home/Work slot (tapped an unset shortcut below) rather than picking
    // a one-off destination — see the shortcuts Row and handleResultTap().
    var settingSlot by remember { mutableStateOf<SavedPlaceSlot?>(null) }

    fun handleResultTap(result: GeocodeResult) {
        val slot = settingSlot
        if (slot != null) {
            SavedPlacesRepository.save(context, slot, result)
            if (slot == SavedPlaceSlot.HOME) homePlace = result else workPlace = result
            settingSlot = null
        } else {
            RecentSearchRepository.add(context, result)
            recentSearches = RecentSearchRepository.getRecent(context)
        }
        onResultSelected(result)
    }

    // Debounced real Nominatim search — fires ~500ms after typing stops,
    // not on every keystroke (Nominatim's usage policy caps ~1 req/sec).
    // Runs the same whether picking a one-off destination or a Home/Work
    // location — only what happens on tap (handleResultTap) differs.
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

    // Voice search — Android's own speech-recognition activity (the same
    // one Google's dialer/assistant use), launched via a plain implicit
    // intent. No RECORD_AUDIO permission needed here: the recognition
    // activity itself owns the microphone, this app only reads back its
    // transcribed text result.
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { activityResult ->
        val spoken = activityResult.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) query = spoken
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
                onClick = {
                    // Back cancels "picking a Home/Work location" first,
                    // same as the on-screen Cancel text below — only exits
                    // the whole screen once no sub-mode is active.
                    if (settingSlot != null) settingSlot = null else onBack()
                },
                modifier = Modifier.size(44.dp).clip(CircleShape).background(NeutralIconButtonBg),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
                    .clip(CircleShape)
                    .background(NeutralIconButtonBg, CircleShape),
            ) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = {
                        val hint = settingSlot?.let { "Search for ${it.label.lowercase()} location…" }
                            ?: "Search destination…"
                        Text(hint, color = TextSecondary)
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
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
                if (query.isBlank()) {
                    IconButton(
                        onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your destination")
                            }
                            try {
                                voiceLauncher.launch(intent)
                            } catch (e: ActivityNotFoundException) {
                                // Real, honest failure — some devices ship
                                // with no speech-recognition app at all
                                // (CLAUDE.md Rule 13: say so, don't just do
                                // nothing when tapped).
                                Toast.makeText(context, "Voice search isn't available on this device", Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) {
                        Icon(painter = painterResource(R.drawable.ic_mic), contentDescription = "Voice search", tint = TextSecondary)
                    }
                } else {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = TextSecondary)
                    }
                }
            }
        }
        if (settingSlot != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "Choose a location for ${settingSlot!!.label}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelMedium,
                    color = CtaRed,
                    modifier = Modifier.clickable { settingSlot = null },
                )
            }
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

        if (query.isBlank() && settingSlot == null) {
            // Idle state — Home/Work shortcuts + real recent-search
            // history, same as Google Maps' own search screen shows
            // before you start typing.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShortcutItem(
                    iconRes = R.drawable.ic_home,
                    label = "Home",
                    subLabel = homePlace?.let { it.splitDisplayName().first } ?: "Set location",
                    onClick = {
                        val home = homePlace
                        if (home != null) handleResultTap(home) else settingSlot = SavedPlaceSlot.HOME
                    },
                )
                VerticalDivider()
                ShortcutItem(
                    iconRes = R.drawable.ic_work,
                    label = "Work",
                    subLabel = workPlace?.let { it.splitDisplayName().first } ?: "Set location",
                    onClick = {
                        val work = workPlace
                        if (work != null) handleResultTap(work) else settingSlot = SavedPlaceSlot.WORK
                    },
                )
            }
            Text(
                text = "Recent",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(recentSearches) { result ->
                    PlaceRow(iconRes = R.drawable.ic_recent, result = result, onClick = { handleResultTap(result) })
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results) { result ->
                    PlaceRow(iconRes = R.drawable.ic_place, result = result, onClick = { handleResultTap(result) })
                }
            }
        }
    }
}

/** One Home/Work quick-access entry — circular icon + bold label + a real (or "Set location") subtitle. */
@Composable
private fun ShortcutItem(iconRes: Int, label: String, subLabel: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(NeutralIconButtonBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(iconRes), contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.padding(start = 8.dp).width(96.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = subLabel, style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun VerticalDivider() {
    Box(modifier = Modifier.padding(horizontal = 4.dp).size(width = 1.dp, height = 32.dp).background(TextSecondary.copy(alpha = 0.25f)))
}

/** One place row — recent history or a live search result, sharing the same circular-icon + name/address layout. */
@Composable
private fun PlaceRow(iconRes: Int, result: GeocodeResult, onClick: () -> Unit) {
    val (primary, secondary) = result.splitDisplayName()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 16.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(NeutralIconButtonBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(iconRes), contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(text = primary, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (secondary.isNotBlank()) {
                Text(text = secondary, style = MaterialTheme.typography.labelMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Nominatim's `display_name` is one long "Name, Street, Area, City, State,
 * Postcode, Country" string with no separate name/address fields — this
 * splits it on the first comma into a bold primary line + a secondary
 * address line (falls back to the whole string as primary if there's no
 * comma), the same real string Nominatim returned, just laid out in two
 * lines instead of one (not a fabricated name/address split).
 */
private fun GeocodeResult.splitDisplayName(): Pair<String, String> {
    val commaIndex = displayName.indexOf(',')
    return if (commaIndex < 0) {
        displayName to ""
    } else {
        displayName.substring(0, commaIndex).trim() to displayName.substring(commaIndex + 1).trim()
    }
}
