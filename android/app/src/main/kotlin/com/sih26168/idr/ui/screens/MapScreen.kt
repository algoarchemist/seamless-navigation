package com.sih26168.idr.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sih26168.idr.dr.DeadReckoningState
import com.sih26168.idr.fusion.FusedPositionUiState
import com.sih26168.idr.fusion.GeoProjection
import com.sih26168.idr.gnss.GnssMode
import com.sih26168.idr.gnss.GnssModeUiState
import com.sih26168.idr.ml.MlVelocityUiState
import com.sih26168.idr.routing.GeocodeResult
import com.sih26168.idr.routing.GeocodeSearchOutcome
import com.sih26168.idr.routing.GeocodingRepository
import com.sih26168.idr.routing.OfflineRouteCache
import com.sih26168.idr.routing.RouteProgress
import com.sih26168.idr.routing.RouteResult
import com.sih26168.idr.routing.RoutingRepository
import com.sih26168.idr.ui.components.ActiveRouteCard
import com.sih26168.idr.ui.components.DestinationSearchBar
import com.sih26168.idr.ui.components.NavigationEtaBar
import com.sih26168.idr.ui.components.NavigationInstructionCard
import com.sih26168.idr.ui.components.VehicleMode
import com.sih26168.idr.ui.map.StreetMapView
import com.sih26168.idr.ui.theme.CtaRed
import com.sih26168.idr.ui.theme.TextPrimary
import kotlin.math.atan2
import kotlin.math.hypot
import kotlinx.coroutines.delay
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
 * (`routing/OfflineRouteCache.kt`). Same [StatusOverlayContent] as
 * [DriveScreen] underneath — the GNSS/DR status readout is unaffected by
 * any of this; search/routing is layered on top as its own state machine
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
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // The real lat/lon to center on and mark. Preference order: a live
    // GNSS fix (most accurate, no projection error) while GNSS_AIDED,
    // else the fused local-meter position projected back through the
    // SAME outage anchor fusion/StateEstimator.kt already tracks (via
    // fusion/GeoProjection.kt's exact inverse of the forward projection
    // it already uses) — null (no marker, map still renders) only before
    // any GNSS fix has EVER been seen this run, since there is then no
    // anchor to project against.
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
            anchorLat != null && anchorLon != null -> GeoProjection.toLatLon(
                eastM = fusedState.fusedEastM,
                northM = fusedState.fusedNorthM,
                refLatDeg = anchorLat,
                refLonDeg = anchorLon,
            )
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
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<GeocodeResult>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var selectedDestination by remember { mutableStateOf<GeocodeResult?>(null) }
    var isRouting by remember { mutableStateOf(false) }
    var routingError by remember { mutableStateOf<String?>(null) }
    var activeRoute by remember { mutableStateOf<RouteResult?>(null) }
    var downloadStatus by remember { mutableStateOf<String?>(null) }
    // 2026-08-26, user-requested "turn by turn navigation screen (start
    // mode, Google Maps-like)": a third state past route-active, entered
    // via ActiveRouteCard's new "Go" button.
    var isNavigating by remember { mutableStateOf(false) }

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
    // (ui/map/StreetMapView.kt's headingDeg param) — prefers a live GNSS
    // bearing (most accurate while GNSS_AIDED and actually moving), else
    // falls back to the physics DR velocity vector's own heading (works
    // through a GNSS outage, same physics/ML fallback theme as the rest of
    // this app), else holds the last confident heading rather than
    // spinning the map at near-zero speed (bearing is meaningless when
    // barely moving — same principle StationaryDetector already applies
    // to ZUPT).
    var lastConfidentHeadingDeg by remember { mutableStateOf(0f) }
    val gnssBearing = gnssState.latestFix?.bearingDeg
    val speedForHeadingMps = hypot(drState.velocityEastMps, drState.velocityNorthMps)
    val headingDeg: Float = when {
        gnssState.mode == GnssMode.GNSS_AIDED && gnssBearing != null -> gnssBearing.also { lastConfidentHeadingDeg = it }
        speedForHeadingMps > 0.5 -> {
            val bearingDeg = Math.toDegrees(atan2(drState.velocityEastMps, drState.velocityNorthMps)).toFloat()
            (if (bearingDeg < 0f) bearingDeg + 360f else bearingDeg).also { lastConfidentHeadingDeg = it }
        }
        else -> lastConfidentHeadingDeg
    }

    // Zoom in tighter the instant navigation starts (Google Maps' own
    // "start mode" behavior) — a one-time camera action, not fought with
    // every tick's animateTo(point) in StreetMapView.kt's update block.
    LaunchedEffect(isNavigating) {
        if (isNavigating) {
            mapViewRef?.controller?.setZoom(19.0)
        }
    }

    // Debounced real Nominatim search — fires ~500ms after typing stops,
    // not on every keystroke (Nominatim's usage policy caps ~1 req/sec).
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            searchResults = emptyList()
            searchError = null
            return@LaunchedEffect
        }
        delay(500)
        isSearching = true
        // BUG FIX (2026-08-26, user report: "SRM Ramapuram not showing" /
        // "most Chennai places not visible") — GeocodingRepository.search
        // used to collapse every failure into an empty list, identical to a
        // real "no matches." Now it reports which one actually happened, so
        // a real cause (bad network, Nominatim rate-limit/403 on this
        // device's IP, etc.) is visible instead of looking like a typo.
        when (val outcome = GeocodingRepository.search(searchQuery)) {
            is GeocodeSearchOutcome.Success -> {
                searchResults = outcome.results
                searchError = if (outcome.results.isEmpty()) "No matches for \"$searchQuery\"" else null
            }
            is GeocodeSearchOutcome.Failure -> {
                searchResults = emptyList()
                searchError = outcome.reason
            }
        }
        isSearching = false
    }

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
                DestinationSearchBar(
                    query = searchQuery,
                    onQueryChange = {
                        searchQuery = it
                        selectedDestination = null
                        routingError = null
                        searchError = null
                    },
                    results = if (selectedDestination == null) searchResults else emptyList(),
                    isSearching = isSearching,
                    onSelectResult = { result ->
                        selectedDestination = result
                        searchQuery = result.displayName
                        searchResults = emptyList()
                        searchError = null
                    },
                )
                // BUG FIX (2026-08-26): previously a search that matched
                // nothing AND a search that failed outright (bad network,
                // Nominatim rate-limiting this device's IP, etc.) both
                // rendered as "no dropdown appeared," with no way to tell
                // which one happened. Now the real reason is shown.
                if (searchError != null && selectedDestination == null) {
                    Text(
                        text = searchError!!,
                        style = MaterialTheme.typography.labelMedium,
                        color = CtaRed,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
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
                                onFailed = { downloadStatus = "Offline download failed — check network." },
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
    }
}
