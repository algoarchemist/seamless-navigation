package com.sih26168.idr.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.LineString
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.LineLayer
import com.mapbox.maps.extension.style.layers.getLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.getSource
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.navigation.ui.components.maneuver.view.MapboxManeuverView
import com.sih26168.idr.BuildConfig
import com.sih26168.idr.R
import com.sih26168.idr.dr.DeadReckoningState
import com.sih26168.idr.fusion.FusedPositionUiState
import com.sih26168.idr.fusion.GeoProjection
import com.sih26168.idr.gnss.GnssMode
import com.sih26168.idr.gnss.GnssModeUiState
import com.sih26168.idr.ml.MlVelocityUiState
import com.sih26168.idr.nav.NavigationSessionRepository
import com.sih26168.idr.ui.components.FloatingIconButton
import com.sih26168.idr.ui.theme.CtaRed
import com.sih26168.idr.ui.theme.DeadReckoningColor
import com.sih26168.idr.ui.theme.GlassSurface
import com.sih26168.idr.ui.theme.GnssAidedColor
import com.sih26168.idr.ui.theme.PillShape
import com.sih26168.idr.ui.theme.ReacquisitionColor
import com.sih26168.idr.ui.theme.TextPrimary
import com.sih26168.idr.ui.theme.TextSecondary
import com.sih26168.idr.ui.theme.TransitionColor
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

private const val SOURCE_ID_ACTIVE_ROUTE_LINE = "idr-active-guidance-route-line-source"
private const val LAYER_ID_ACTIVE_ROUTE_LINE = "idr-active-guidance-route-line-layer"

/**
 * Mapbox Navigation SDK active-guidance / free-drive screen (PRD.md
 * Section 7 2026-09-05 amendment, developer-requested). Simpler than the
 * idle/route-preview map (`ui/map/StreetMapView.kt`) in one respect —
 * the current-position marker is the Maps SDK's own built-in location
 * puck (via `mapView.location`), fed by
 * [NavigationSessionRepository.locationProvider], not a custom
 * bitmap/annotation — but the route line IS drawn here (same
 * GeoJsonSource+LineLayer pattern StreetMapView.kt uses for its own
 * route line, [CtaRed] to match), using
 * [NavigationSessionRepository.NavUiState.routeGeometryPoints].
 *
 * REAL BUG FOUND (2026-09-05, user report: "the orange line path to
 * destination is not coming... just a free drive window with the blue
 * circle"): this screen originally drew no route line at all — see
 * [NavigationSessionRepository.NavUiState.routeGeometryPoints]'s own doc
 * for the fix. [MapboxManeuverView] (banner text + lane guidance
 * combined, Mapbox's own component) handles the turn-by-turn instruction
 * UI. Voice announcements play automatically via
 * [NavigationSessionRepository]'s observers — nothing in this file
 * triggers audio directly.
 *
 * @param isFreeDrive true hides the maneuver/ETA UI and route line
 *   (free-drive has no route) and shows only the live map-matched
 *   position.
 *
 * User-requested (2026-09-05): the GNSS-aided/transition/dead-reckoning/
 * reacquisition state chip plus speed/motion chips — [StatusOverlayContent]'s
 * own FR10 readout on the idle map screen — went missing the moment
 * turn-by-turn guidance starts, since this screen is a completely separate
 * full-screen overlay (its own MapView, drawn last over MapScreen) that
 * previously read only [NavigationSessionRepository]'s Mapbox-side state
 * and never received the underlying GNSS/DR pipeline state at all. The
 * four extra params below thread that same state MapScreen already has in
 * from the app-level pipeline, and [estimateSpeedMps]/[estimateMotionLabel]
 * (both `internal` in this package, already shared with
 * [StatusOverlayContent]) compute the identical values so the two screens
 * can never silently disagree.
 */
@Composable
fun ActiveGuidanceScreen(
    isFreeDrive: Boolean,
    drState: DeadReckoningState,
    gnssState: GnssModeUiState,
    mlState: MlVelocityUiState,
    fusedState: FusedPositionUiState,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val navState by NavigationSessionRepository.state.collectAsState()

    val mapView = remember {
        MapboxOptions.accessToken = BuildConfig.MAPBOX_PUBLIC_TOKEN
        MapView(context)
    }

    // Follow/recenter (developer-requested, 2026-09-05) — same
    // "follow me" vs "user is browsing" pattern ui/map/StreetMapView.kt
    // uses: a real user pan gesture (OnMoveListener below) turns
    // auto-follow off; the recenter button turns it back on.
    // [isProgrammaticMove] guards EVERY programmatic setCamera call
    // (both the recenter button's and the auto-follow effect's) — REAL
    // BUG StreetMapView.kt already found and fixed once (2026-09-04):
    // Mapbox's OnMoveListener fires for ANY camera change, not just
    // genuine touch gestures, so an unguarded per-tick setCamera call
    // (this screen's own auto-follow effect below, which runs on every
    // location update) would otherwise immediately re-trigger
    // `onMoveBegin` and fight the recenter button. Learned once, applied
    // here from the start rather than re-discovering it.
    var isFollowingLocation by remember { mutableStateOf(true) }
    val isProgrammaticMove = remember { mutableStateOf(false) }

    DisposableEffect(mapView) {
        onDispose {
            NavigationSessionRepository.stop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.mapboxMap.loadStyle(Style.DARK) { style ->
            style.addSource(
                GeoJsonSource.Builder(SOURCE_ID_ACTIVE_ROUTE_LINE)
                    // REAL BUG FOUND (2026-09-05, same class of race
                    // ui/map/StreetMapView.kt's style-load bug already
                    // was): startActiveGuidance sets routeGeometryPoints
                    // BEFORE this screen even composes, so the reactive
                    // LaunchedEffect(navState.routeGeometryPoints) below
                    // can run and find `getSource(...)` still null (style
                    // loading is async) BEFORE this callback fires — and
                    // since that value never changes again afterward,
                    // nothing would re-set it. Seeding the source's
                    // initial geometry directly from the live repository
                    // state (not the possibly-stale captured `navState`)
                    // here guarantees a route present at screen-open time
                    // is drawn regardless of which finishes first.
                    .geometry(LineString.fromLngLats(NavigationSessionRepository.state.value.routeGeometryPoints))
                    .build(),
            )
            style.addLayer(
                LineLayer(LAYER_ID_ACTIVE_ROUTE_LINE, SOURCE_ID_ACTIVE_ROUTE_LINE).apply {
                    lineColor(CtaRed.toArgb())
                    lineWidth(5.0)
                    lineCap(LineCap.ROUND)
                    lineJoin(LineJoin.ROUND)
                },
            )
        }
        mapView.mapboxMap.setCamera(CameraOptions.Builder().zoom(17.0).build())
        mapView.location.setLocationProvider(NavigationSessionRepository.locationProvider)
        mapView.location.enabled = true
        mapView.location.puckBearingEnabled = true
        mapView.location.puckBearing = PuckBearing.HEADING
        // REAL BUG FOUND (2026-09-05, user report: "current location is
        // shown by a blue circle, make it an arrow"): `puckBearingEnabled`
        // + `puckBearing` alone only control WHICH heading source rotates
        // the puck — they don't change its SHAPE. The plugin's actual
        // default `locationPuck` is a plain non-directional dot regardless;
        // a puck that visually shows direction needs an explicit
        // `createDefault2DPuck(withBearing = true)`, which swaps in
        // Mapbox's own arrow/chevron bearing image. `PuckBearing.HEADING`
        // (not `.COURSE`) is what was already set above — device compass
        // heading ("front of the phone"), not GPS direction of travel,
        // matching the request exactly.
        mapView.location.locationPuck = createDefault2DPuck(withBearing = true)

        mapView.gestures.addOnMoveListener(object : OnMoveListener {
            override fun onMoveBegin(detector: com.mapbox.android.gestures.MoveGestureDetector) {
                if (!isProgrammaticMove.value) {
                    isFollowingLocation = false
                }
            }
            override fun onMove(detector: com.mapbox.android.gestures.MoveGestureDetector): Boolean = false
            override fun onMoveEnd(detector: com.mapbox.android.gestures.MoveGestureDetector) = Unit
        })
    }

    // REAL BUG FIX (2026-09-05, user report: "the dead reckoning system is
    // not working in the app... the marker freezes completely, doesn't move
    // at all during outage") — see
    // [NavigationSessionRepository.setDeadReckonedPosition]'s own doc for the
    // full root cause. The location puck on this screen was fed ONLY by the
    // Mapbox Navigation SDK's own GPS-backed matcher, which stops delivering
    // updates entirely once GNSS is denied; this app's own dead-reckoned
    // estimate was never wired to it at all.
    //
    // Projects [fusedState]'s local East/North meters back to WORLD-frame
    // lat/lon (CLAUDE.md Rule 14) through the SAME outage anchor
    // fusion/StateEstimator.kt accumulated them against — fusion/GeoProjection.kt's
    // exact inverse of its own forward projection, identical to what
    // ui/screens/MapScreen.kt already does for its own marker (that screen's
    // marker never froze, which is precisely why this one's freezing pointed
    // at the position SOURCE rather than at the DR pipeline itself).
    //
    // Null anchor means no GNSS fix has EVER been seen this run, so there is
    // no real-world point to project against — hand control back to the SDK
    // rather than invent a position (CLAUDE.md Rule 8/13).
    val isGnssTrusted = gnssState.mode == GnssMode.GNSS_AIDED
    LaunchedEffect(
        isGnssTrusted,
        fusedState.fusedEastM,
        fusedState.fusedNorthM,
        fusedState.anchorLatDeg,
        fusedState.anchorLonDeg,
        fusedState.fusedHeadingDeg,
    ) {
        val anchorLat = fusedState.anchorLatDeg
        val anchorLon = fusedState.anchorLonDeg
        if (isGnssTrusted || anchorLat == null || anchorLon == null) {
            NavigationSessionRepository.setDeadReckonedPosition(null, null, null)
        } else {
            val (drLatDeg, drLonDeg) = GeoProjection.toLatLon(
                eastM = fusedState.fusedEastM,
                northM = fusedState.fusedNorthM,
                refLatDeg = anchorLat,
                refLonDeg = anchorLon,
            )
            NavigationSessionRepository.setDeadReckonedPosition(
                latDeg = drLatDeg,
                lonDeg = drLonDeg,
                headingDeg = fusedState.fusedHeadingDeg,
            )
        }
    }

    // Updates whenever the active route's geometry changes (set once per
    // startActiveGuidance call — see NavigationSessionRepository) or once
    // the style finishes loading, whichever comes later; style loading is
    // async so the FIRST time this runs, `getSource` may still return null
    // if the route arrived before the style did — this LaunchedEffect
    // re-runs on the geometry key regardless, so a real route always ends
    // up drawn once both are ready.
    LaunchedEffect(navState.routeGeometryPoints) {
        val source = mapView.mapboxMap.style?.getSource(SOURCE_ID_ACTIVE_ROUTE_LINE) as? GeoJsonSource
        source?.geometry(LineString.fromLngLats(navState.routeGeometryPoints))
    }

    // Keep the camera on the live map-matched position — same "follow"
    // idea ui/map/StreetMapView.kt uses, now with the same manual-pan/
    // recenter-button affordance (developer-requested, 2026-09-05):
    // only recenters while isFollowingLocation is true, and the
    // programmatic-move guard (see its own doc above) keeps this from
    // fighting the recenter button.
    LaunchedEffect(navState.currentLatDeg, navState.currentLonDeg) {
        val lat = navState.currentLatDeg
        val lon = navState.currentLonDeg
        if (lat != null && lon != null && isFollowingLocation) {
            isProgrammaticMove.value = true
            mapView.mapboxMap.setCamera(
                CameraOptions.Builder()
                    .center(com.mapbox.geojson.Point.fromLngLat(lon, lat))
                    .bearing(navState.currentHeadingDeg?.toDouble())
                    .build(),
            )
            isProgrammaticMove.value = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { mapView })

        if (!isFreeDrive) {
            val maneuvers = navState.maneuvers
            if (maneuvers != null) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopCenter),
                    factory = { ctx -> MapboxManeuverView(ctx) },
                    update = { view -> view.renderManeuvers(maneuvers) },
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = if (isFreeDrive) "Free drive" else "Active guidance")
                    if (!isFreeDrive) {
                        val distance = navState.distanceRemainingMeters
                        val duration = navState.durationRemainingSeconds
                        if (distance != null && duration != null) {
                            Text(text = "%.0f m remaining, %.0f min".format(distance, duration / 60.0))
                        }
                        if (navState.isRerouting) {
                            Text(text = "Rerouting…")
                        }
                    }
                }
            }
            // GNSS state-machine chip (GNSS_AIDED -> TRANSITION ->
            // DEAD_RECKONING -> REACQUISITION -> GNSS_AIDED, see
            // gnss/GnssOutageDetector.kt's own state diagram) + speed +
            // motion — same three values [StatusOverlayContent] shows on
            // the idle map. Originally placed top-start, but user reported
            // "cant see those cards" during ACTUAL guided navigation
            // (not free-drive): [MapboxManeuverView] just below is an
            // AndroidView with its own opaque background spanning the full
            // width at TopCenter, and being added later in this Box it
            // draws on top, visually burying anything under its bounds —
            // TopStart wasn't actually clear of it despite the alignment
            // difference. Moved down next to the Exit button instead (a
            // region MapboxManeuverView never reaches) and shrunk
            // ([MiniStatusChip], not the larger [StatusChip]) per the
            // follow-up "make sure those cards are small and right to the
            // end trip button" request.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Button(onClick = onExit) {
                    Text(text = "Exit")
                }
                val speedMps = estimateSpeedMps(drState, mlState, gnssState, fusedState)
                val motionLabel = estimateMotionLabel(drState, mlState, speedMps)
                val gnssColor = when (gnssState.mode) {
                    GnssMode.GNSS_AIDED -> GnssAidedColor
                    GnssMode.TRANSITION -> TransitionColor
                    GnssMode.DEAD_RECKONING -> DeadReckoningColor
                    GnssMode.REACQUISITION -> ReacquisitionColor
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MiniStatusChip(label = gnssState.mode.name, dotColor = gnssColor)
                    MiniStatusChip(label = "%.1f m/s".format(speedMps), dotColor = TextSecondary)
                    MiniStatusChip(label = motionLabel, dotColor = TextSecondary)
                }
            }
        }

        if (!isFollowingLocation) {
            FloatingIconButton(
                icon = painterResource(R.drawable.ic_recenter),
                contentDescription = "Resume following current location",
                onClick = {
                    isFollowingLocation = true
                    val lat = navState.currentLatDeg
                    val lon = navState.currentLonDeg
                    if (lat != null && lon != null) {
                        isProgrammaticMove.value = true
                        mapView.mapboxMap.setCamera(
                            CameraOptions.Builder()
                                .center(com.mapbox.geojson.Point.fromLngLat(lon, lat))
                                .bearing(navState.currentHeadingDeg?.toDouble())
                                .build(),
                        )
                        isProgrammaticMove.value = false
                    }
                },
                // BottomEnd, same convention ui/map/StreetMapView.kt's own
                // recenter button uses (not TopEnd — Mapbox's own compass
                // control already renders there by default, would overlap).
                // Fixed bottom padding to clear the status card + Exit
                // button/mini status chip stack below, same fixed-offset
                // approach StreetMapView.kt uses to clear ITS OWN bottom
                // row — bumped from 140dp to 190dp once the mini chip
                // column (below the Exit button) made that bottom row
                // taller than before.
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 190.dp),
            )
        }
    }
}

/**
 * Compact variant of [com.sih26168.idr.ui.components.StatusChip] — same
 * dot + label on a [GlassSurface] pill, but with tighter padding and a
 * smaller dot/text so three of them can stack next to the Exit button
 * without dominating the screen (user-requested, 2026-09-05: "make sure
 * those cards are small and right to the end trip button", after the
 * original top-start placement turned out to render UNDER
 * [MapboxManeuverView]'s full-width banner during real guided navigation
 * — see this file's header doc).
 */
@Composable
private fun MiniStatusChip(label: String, dotColor: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(GlassSurface, PillShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
    }
}
