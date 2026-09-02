package com.sih26168.idr.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sih26168.idr.dr.DeadReckoningState
import com.sih26168.idr.fusion.FusedPositionUiState
import com.sih26168.idr.fusion.GeoProjection
import com.sih26168.idr.gnss.GnssMode
import com.sih26168.idr.gnss.GnssModeUiState
import com.sih26168.idr.ml.MlVelocityUiState
import com.sih26168.idr.routing.GeocodeResult
import com.sih26168.idr.routing.OfflineRouteCache
import com.sih26168.idr.routing.RouteProgress
import com.sih26168.idr.routing.RouteResult
import com.sih26168.idr.routing.RoutingRepository
import com.sih26168.idr.ui.components.ActiveRouteCard
import com.sih26168.idr.ui.components.NavigationEtaBar
import com.sih26168.idr.ui.components.NavigationInstructionCard
import com.sih26168.idr.ui.components.VehicleMode
import com.sih26168.idr.ui.map.StreetMapView
import com.sih26168.idr.ui.theme.CtaRed
import com.sih26168.idr.ui.theme.GlassCardRadius
import com.sih26168.idr.ui.theme.GlassSurface
import com.sih26168.idr.ui.theme.TextPrimary
import com.sih26168.idr.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import org.osmdroid.views.MapView

/**
 * The REAL street-map screen — real OpenStreetMap tiles
 * ([StreetMapView]), plus (2026-08-26, user-requested full routing —
 * explicit override of PRD.md Section 7/22's "no search/turn-by-turn
 * chrome" default) real destination search
 * (`routing/GeocodingRepository.kt`, OpenStreetMap Nominatim), real
 * routing (`routing/RoutingRepository.kt`, OSRM), and real offline
 * caching of that one trip's tiles + route data
 * (`routing/OfflineRouteCache.kt`). Uses [StatusOverlayContent] for the
 * GNSS/DR status readout, unaffected by any of this; search/routing is
 * layered on top as its own state machine
 * (idle -> destination selected -> route active).
 *
 * HONEST LIMITATION (CLAUDE.md Rule 13): no live "next turn in X m"
 * banner — this project does not track progress along an active route
 * (matching current position to distance-travelled-along-route), so
 * [ActiveRouteCard] shows the full real step list instead of a live
 * single-instruction banner, rather than fabricate live progress.
 */
@Composable
fun MapScreen(
    drState: DeadReckoningState,
    gnssState: GnssModeUiState,
    mlState: MlVelocityUiState,
    fusedState: FusedPositionUiState,
    mlModelLoadError: String?,
    isDarkTheme: Boolean,
    isPipelinePaused: Boolean,
    vehicleMode: VehicleMode?,
    onToggleTheme: () -> Unit,
    onRecalibrate: () -> Unit,
    onShowDebugScreen: () -> Unit,
    onTogglePipelinePause: () -> Unit,
    onVehicleModeChange: (VehicleMode) -> Unit,
    /**
     * PRD.md Section 19's MVP map constraint: hands the active route's
     * geometry (or null, once it ends) to `fusion/StateEstimator.kt` so it
     * can road-snap the fused DR position against it during an outage —
     * see [com.sih26168.idr.fusion.StateEstimator.setActiveRouteGeometry]'s
     * own doc for why this crosses from UI state into that lower layer via
     * a plain callback instead of StateEstimator reading UI state directly.
     */
    onActiveRouteGeometryChanged: (List<Pair<Double, Double>>?) -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Moved ahead of currentLatDeg/currentLonDeg below (Round 2,
    // 2026-08-28) so the map-matching correction there can read it — was
    // previously declared further down with the rest of the search/
    // routing state, which is otherwise unrelated to it.
    var activeRoute by remember { mutableStateOf<RouteResult?>(null) }

    // The real lat/lon to center on and mark. Preference order: a live
    // GNSS fix (most accurate, no projection error) while GNSS_AIDED,
    // else the fused local-meter position, projected back through the
    // SAME outage anchor fusion/StateEstimator.kt already tracks
    // (fusion/GeoProjection.kt's exact inverse of the forward projection
    // it already uses). Null (no marker, map still renders) only before
    // any GNSS fix has EVER been seen this run, since there is then no
    // anchor to project against.
    //
    // REAL BUG FOUND AND FIXED (2026-09-02): this used to ALSO run
    // fusion/RoadSnap.kt's own nearest-route-segment projection here, on
    // top of fusedState.fusedEastM/fusedNorthM — but map/MapConstraint.kt
    // (added 2026-08-30, after this screen's original comment was
    // written) already corrects THAT SAME field upstream, inside
    // fusion/StateEstimator.kt, before it ever reaches this screen. Two
    // independent nearest-segment projections were stacking on every
    // tick a route was active outside GNSS_AIDED — with slightly
    // different tolerances (RoadSnap's 25m/45deg vs MapConstraint's
    // 30m/45deg) — wasting a per-tick projection twice and risking a
    // marker position neither implementation alone would have computed.
    // fusion/RoadSnap.kt (and its test) has been deleted; MapConstraint
    // is now the SOLE road-snap implementation, applied once, upstream,
    // matching PRD.md Section 16's framing of map-constraint as "a
    // correction... rather than the primary estimator." This screen now
    // simply projects the already-correct fusedEastM/fusedNorthM back to
    // lat/lon — no second snap.
    //
    // fusion/DriftSummary.kt's measured drift number remains provably
    // unaffected by MapConstraint's correction either way: StateEstimator
    // snapshots it from drEastM/drNorthM and the newly-reacquired GNSS fix
    // BEFORE PositionFusion.update()/MapConstraint ever run that tick —
    // see StateEstimator.kt's own doc.
    val (currentLatDeg, currentLonDeg) = remember(
        gnssState.mode,
        gnssState.latestFix,
        fusedState.anchorLatDeg,
        fusedState.anchorLonDeg,
        fusedState.fusedEastM,
        fusedState.fusedNorthM,
    ) {
        val fix = gnssState.latestFix
        val anchorLat = fusedState.anchorLatDeg
        val anchorLon = fusedState.anchorLonDeg
        when {
            gnssState.mode == GnssMode.GNSS_AIDED && fix != null -> fix.latitudeDeg to fix.longitudeDeg
            anchorLat != null && anchorLon != null -> {
                GeoProjection.toLatLon(
                    eastM = fusedState.fusedEastM,
                    northM = fusedState.fusedNorthM,
                    refLatDeg = anchorLat,
                    refLonDeg = anchorLon,
                )
            }
            else -> null to null
        }
    }

    // A SEPARATE, more lenient origin specifically for routing (not for
    // the marker/map-center logic above, which deliberately stays strict
    // about GNSS_AIDED/anchor quality for drift-measurement honesty).
    // Real on-device finding: indoors, a genuine GNSS fix can arrive
    // (e.g. near a window) that's real but too stale/inaccurate to pass
    // GnssQuality's threshold — gnssState.mode correctly stays
    // DEAD_RECKONING/TRANSITION and the anchor never gets set, so
    // currentLatDeg/currentLonDeg above stay null even though a usable
    // coordinate exists. Routing doesn't need the same precision bar as
    // the fused position estimate — any real fix is good enough to ask
    // OSRM "roughly from here" — so this falls back to the raw fix
    // regardless of mode, rather than refusing to route at all.
    val routingOriginLatDeg: Double?
    val routingOriginLonDeg: Double?
    if (currentLatDeg != null && currentLonDeg != null) {
        routingOriginLatDeg = currentLatDeg
        routingOriginLonDeg = currentLonDeg
    } else {
        routingOriginLatDeg = gnssState.latestFix?.latitudeDeg
        routingOriginLonDeg = gnssState.latestFix?.longitudeDeg
    }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    // Search/routing state — independent of the GNSS/DR state above.
    // 2026-08-28, user-requested "search destination like Google Maps,
    // redirects to map page when searched": the live in-progress query/
    // results/error used to live here and drive a floating dropdown drawn
    // over the map. That's now entirely owned by the full-page
    // ui/screens/SearchScreen.kt instead — this screen only keeps the
    // FINAL chosen destination (searchQuery here becomes display-only:
    // the selected place's name, or the collapsed bar's placeholder) plus
    // whether that full page is currently showing.
    var searchQuery by remember { mutableStateOf("") }
    var showSearchScreen by remember { mutableStateOf(false) }
    var selectedDestination by remember { mutableStateOf<GeocodeResult?>(null) }
    var isRouting by remember { mutableStateOf(false) }
    var routingError by remember { mutableStateOf<String?>(null) }
    var downloadStatus by remember { mutableStateOf<String?>(null) }
    // 2026-08-26, user-requested "turn by turn navigation screen (start
    // mode, Google Maps-like)": a third state past route-active, entered
    // via ActiveRouteCard's new "Go" button.
    var isNavigating by remember { mutableStateOf(false) }

    // Pushes the active route's geometry (or null, once it ends) down to
    // fusion/StateEstimator.kt for road-snapping — see
    // onActiveRouteGeometryChanged's own doc. Converted from osmdroid's
    // GeoPoint to plain lat/lon pairs here, at the UI boundary, so that
    // lower layer stays free of a map-library dependency.
    LaunchedEffect(activeRoute) {
        onActiveRouteGeometryChanged(activeRoute?.geometry?.map { it.latitude to it.longitude })
    }

    // Live route progress — projects the route geometry into the SAME
    // local East/North frame fusion/StateEstimator.kt's anchor already
    // defines, then compares it against the SAME fusedEastM/fusedNorthM
    // position the rest of the app (including the DRIVE tab) already
    // falls back to during a GNSS outage. This is what makes "next turn in
    // X m" keep counting down through an outage using physics/ML dead
    // reckoning, not just live GNSS. Null whenever there's no route or no
    // anchor yet (see fusion/StateEstimator.kt's anchor doc) — NavigationBanner
    // falls back to the route's own static totals in that case.
    val anchorLat = fusedState.anchorLatDeg
    val anchorLon = fusedState.anchorLonDeg
    val routeProgress = remember(activeRoute, anchorLat, anchorLon, fusedState.fusedEastM, fusedState.fusedNorthM) {
        val route = activeRoute
        if (route == null || anchorLat == null || anchorLon == null) {
            null
        } else {
            val routeLocalMeters = route.geometry.map { point ->
                GeoProjection.toLocalMeters(point.latitude, point.longitude, anchorLat, anchorLon)
            }
            RouteProgress.compute(
                routeLocalMeters = routeLocalMeters,
                stepDistancesMeters = route.steps.map { it.distanceMeters },
                currentEastM = fusedState.fusedEastM,
                currentNorthM = fusedState.fusedNorthM,
            )
        }
    }

    // Live heading for the navigation screen's "heading-up" map rotation
    // (ui/map/StreetMapView.kt's headingDeg param) AND the current-position
    // marker's own directional-arrow rotation (that same file's
    // markerHeadingDeg param, STATUS_AND_ROADMAP.md Tier-1 item #1).
    //
    // Round 2 (2026-08-28): this used to be computed HERE with a hard
    // cutover between GNSS bearing and a DR-derived bearing at the
    // GNSS_AIDED/DEAD_RECKONING mode boundary — no interpolation, which
    // produced a visible ~180 degree map-orientation flip on reacquisition
    // during the Round 2 Day 1 live outage test whenever the two
    // disagreed. That computation now lives in
    // fusion/StateEstimator.kt/HeadingFusion.kt (same file/reasoning as
    // fusedEastM/fusedNorthM's own blend), so it's a SINGLE source of
    // truth that's actually blended over REACQUISITION instead of
    // recomputed ad hoc per screen. See HeadingFusion's doc for the fix.
    val headingDeg: Float = fusedState.fusedHeadingDeg

    // Zoom in tighter the instant navigation starts (Google Maps' own
    // "start mode" behavior) — a one-time camera action, not fought with
    // every tick's animateTo(point) in StreetMapView.kt's update block.
    LaunchedEffect(isNavigating) {
        if (isNavigating) {
            mapViewRef?.controller?.setZoom(19.0)
        }
    }

    // Back from the full-page search screen returns to the map, same
    // priority convention MainActivity's own BackHandler already uses for
    // tab switching — this one is added later/deeper in the composition so
    // it wins over MainActivity's while the search page is open.
    BackHandler(enabled = showSearchScreen) { showSearchScreen = false }

    Box(modifier = Modifier.fillMaxSize()) {
        StreetMapView(
            currentLatDeg = currentLatDeg,
            currentLonDeg = currentLonDeg,
            anchorLatDeg = fusedState.anchorLatDeg,
            anchorLonDeg = fusedState.anchorLonDeg,
            mode = gnssState.mode,
            isDarkTheme = isDarkTheme,
            routeGeometry = activeRoute?.geometry,
            headingDeg = if (isNavigating) headingDeg else null,
            // Unlike the map-rotation headingDeg above (gated to
            // isNavigating), the marker's own directional arrow is fed
            // real heading whenever one is available — STATUS_AND_ROADMAP.md
            // Tier-1 item #1, "rotate it with heading" applies generally,
            // not only during turn-by-turn.
            markerHeadingDeg = headingDeg,
            onMapViewReady = { mapViewRef = it },
            modifier = Modifier.fillMaxSize(),
        )
        StatusOverlayContent(
            drState = drState,
            gnssState = gnssState,
            mlState = mlState,
            fusedState = fusedState,
            mlModelLoadError = mlModelLoadError,
            isDarkTheme = isDarkTheme,
            isPipelinePaused = isPipelinePaused,
            vehicleMode = vehicleMode,
            onToggleTheme = onToggleTheme,
            onRecalibrate = onRecalibrate,
            onShowDebugScreen = onShowDebugScreen,
            onTogglePipelinePause = onTogglePipelinePause,
            onVehicleModeChange = onVehicleModeChange,
            // REAL BUG FIX (2026-08-26, found testing "Go" on-device): this
            // section used to always render, visually colliding with
            // ActiveRouteCard/NavigationEtaBar once a route existed.
            showBottomBar = activeRoute == null,
        )

        // Search / routing UI — layered above the status overlay's own
        // top/bottom content, positioned to clear both (top: below the
        // GNSS status chips; bottom: above the vehicle-mode-selector row).
        // REAL BUG FOUND on-device (2026-08-26, testing the "Go" button): the
        // original 230dp fixed top offset was NOT enough clearance below
        // StatusOverlayContent's top chip stack (GNSS mode/speed/motion
        // chips + alignment line) — confirmed on a real S24 FE as visibly
        // overlapping text, not just a theoretical risk. Bumped to 300dp.
        // HONEST LIMITATION: still a fixed constant, not measured against
        // that Column's actual rendered height (which varies — e.g. the
        // "Location permission not granted"/ML-unavailable lines add more
        // height when present) — a real onGloballyPositioned measurement
        // would be more correct but is more than this fix needs right now.
        if (activeRoute == null) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 300.dp, start = 16.dp, end = 16.dp)) {
                // 2026-08-28, user-requested "search destination like Google
                // Maps, redirects to map page when searched": this used to be
                // a live, typeable DestinationSearchBar with its dropdown
                // floating directly over the map. Now it's a collapsed,
                // tappable bar — tapping it (whether idle or to change an
                // already-chosen destination) opens the full ui/screens/
                // SearchScreen.kt page; picking a result there closes that
                // page and lands back here with selectedDestination set,
                // same as Google Maps returning you to the map after search.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(GlassCardRadius))
                        .background(GlassSurface, RoundedCornerShape(GlassCardRadius))
                        .clickable { showSearchScreen = true }
                        .padding(16.dp),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary)
                    Text(
                        text = selectedDestination?.displayName?.takeIf { it.isNotBlank() }
                            ?: "Search destination…",
                        color = if (selectedDestination != null) TextPrimary else TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                if (selectedDestination != null) {
                    Button(
                        onClick = {
                            val destination = selectedDestination ?: return@Button
                            val originLat = routingOriginLatDeg
                            val originLon = routingOriginLonDeg
                            if (originLat == null || originLon == null) {
                                routingError = "No current position yet — need a GNSS fix or an active DR session first."
                                return@Button
                            }
                            isRouting = true
                            routingError = null
                            coroutineScope.launch {
                                val route = RoutingRepository.computeRoute(
                                    originLatDeg = originLat,
                                    originLonDeg = originLon,
                                    destLatDeg = destination.latDeg,
                                    destLonDeg = destination.lonDeg,
                                    destinationName = destination.displayName,
                                )
                                isRouting = false
                                if (route == null) {
                                    routingError = "Could not compute a route (no network, or OSRM found no path)."
                                } else {
                                    activeRoute = route
                                    searchQuery = ""
                                    selectedDestination = null
                                    // User-requested "smoother working"
                                    // (2026-08-29): silently warms the tile
                                    // cache for this route's corridor at the
                                    // live-viewing zoom level, so browsing
                                    // the map along the route is less likely
                                    // to stutter waiting on a live tile
                                    // fetch — separate from, and lighter
                                    // than, the explicit "Download offline"
                                    // button on ActiveRouteCard (see
                                    // OfflineRouteCache.prefetchLiveZoomTiles's
                                    // own doc for the data-usage tradeoff
                                    // this was deliberately scoped against).
                                    // Best-effort: mapViewRef should already
                                    // be set (the user is looking at the map
                                    // to have reached this button), but a
                                    // null skips silently rather than crash —
                                    // this is an optimization, not a
                                    // guarantee, same as the function's own
                                    // silent-failure behavior on a genuine
                                    // network/cache error.
                                    mapViewRef?.let { mapView ->
                                        OfflineRouteCache.prefetchLiveZoomTiles(context, mapView, route.geometry)
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CtaRed),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(text = if (isRouting) "Routing…" else "Start", color = TextPrimary)
                    }
                }
                if (routingError != null) {
                    Text(text = routingError!!, style = MaterialTheme.typography.labelMedium, color = CtaRed)
                }
            }
        } else if (isNavigating) {
            // Live turn-by-turn — top instruction card, bottom ETA/Exit bar,
            // same top/bottom split the idle/route-preview states above use.
            // Same 300dp clearance fix as the idle-state Column above.
            Column(modifier = Modifier.fillMaxWidth().padding(top = 300.dp, start = 16.dp, end = 16.dp)) {
                NavigationInstructionCard(route = activeRoute!!, progress = routeProgress)
            }
            Box(modifier = Modifier.fillMaxSize().padding(bottom = 24.dp, start = 16.dp, end = 16.dp)) {
                NavigationEtaBar(
                    route = activeRoute!!,
                    progress = routeProgress,
                    onExit = {
                        isNavigating = false
                        activeRoute = null
                        downloadStatus = null
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        } else {
            // REAL BUG FOUND on-device (2026-08-26, testing the "Go" button):
            // this Box previously had no height limit at all — ActiveRouteCard
            // is bottom-aligned and wrap-content-sized, so its natural height
            // (destination name + duration/distance row + up-to-160dp step
            // list + Go button + Download/End row) grew tall enough to
            // overlap StatusOverlayContent's top GNSS/speed/motion chips,
            // confirmed visually colliding on a real S24 FE. BoxWithConstraints
            // computes the actual available height so the card's max height
            // is capped to leave the same top clearance the idle-search/
            // navigating states reserve (300dp) — ActiveRouteCard.kt's own
            // Column is made scrollable (see that file) so if a route's step
            // list is long enough to still exceed this, it scrolls internally
            // instead of overflowing past the cap.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val maxCardHeight = (maxHeight - 340.dp - 170.dp).coerceAtLeast(120.dp)
                Box(modifier = Modifier.fillMaxSize().padding(bottom = 170.dp, start = 16.dp, end = 16.dp)) {
                    ActiveRouteCard(
                        route = activeRoute!!,
                        onStartNavigation = { isNavigating = true },
                        downloadStatus = downloadStatus,
                        onDownloadOffline = {
                            val mapView = mapViewRef ?: return@ActiveRouteCard
                            val route = activeRoute ?: return@ActiveRouteCard
                            downloadStatus = "Downloading tiles for this trip…"
                            OfflineRouteCache.saveRoute(context, route)
                            OfflineRouteCache.downloadRouteTiles(
                                context = context,
                                mapView = mapView,
                                routeGeometry = route.geometry,
                                onProgress = { downloaded, total -> downloadStatus = "Downloading… $downloaded/$total tiles" },
                                onComplete = { downloadStatus = "Saved for offline use." },
                                // REAL FINDING (2026-08-29, from the crash
                                // fix in OfflineRouteCache.kt): this will
                                // currently ALWAYS fail, every time, not
                                // just on a bad connection — the live tile
                                // source (osmdroid's MAPNIK) permanently
                                // refuses bulk downloads by policy (honoring
                                // OpenStreetMap's own "no bulk downloading"
                                // tile usage terms), so "check network"
                                // would be a misleading, retriable-sounding
                                // message for a non-retriable cause. Says so
                                // honestly instead (CLAUDE.md Rule 13) —
                                // whether this button should be reworked or
                                // removed given it can't currently succeed
                                // at all is a separate, larger decision.
                                onFailed = { downloadStatus = "Offline download isn't available for this map source right now." },
                            )
                        },
                        onEnd = {
                            activeRoute = null
                            downloadStatus = null
                        },
                        modifier = Modifier.align(Alignment.BottomCenter).heightIn(max = maxCardHeight),
                    )
                }
            }
        }

        // Full-page search — drawn LAST so it covers the entire map screen
        // (tiles, status overlay, everything above), same as Google Maps
        // replacing the whole screen with its search page rather than
        // layering a dropdown on top of the live map.
        if (showSearchScreen) {
            SearchScreen(
                initialQuery = selectedDestination?.displayName ?: "",
                onBack = { showSearchScreen = false },
                onResultSelected = { result ->
                    selectedDestination = result
                    searchQuery = result.displayName
                    routingError = null
                    showSearchScreen = false
                },
            )
        }
    }
}
