package com.sih26168.idr.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sih26168.idr.R
import com.sih26168.idr.gnss.GnssMode
import com.sih26168.idr.ui.components.FloatingIconButton
import com.sih26168.idr.ui.theme.AccentBlue
import com.sih26168.idr.ui.theme.AccentBlueLight
import com.sih26168.idr.ui.theme.CtaRed
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

/**
 * The real street-map base layer (Slice 8b — added when the user
 * explicitly asked to bring in a real map dependency, CLAUDE.md Rule 2
 * discussed and overridden for this decision). osmdroid, not Google Maps
 * Compose/Mapbox, specifically because it needs no API key or billing
 * account to show real tiles — nothing here blocks on a credential this
 * project doesn't have.
 *
 * This file draws real OpenStreetMap street geometry underneath the
 * current-position marker language (halo/ring/dot,
 * [com.sih26168.idr.ui.theme.AccentBlue]) and outage-anchor dashed line
 * established from the Figma extraction — an abstract local-East/North-
 * meter grid base layer (ui/map/TrackCanvas.kt) originally established
 * this same marker styling before it was removed as redundant once this
 * real map existed.
 *
 * HONEST GAP (CLAUDE.md Rule 13): the standard OSM tile server is
 * rate-limited and meant for light/demo traffic, not production load —
 * acceptable for this MVP demo, not a claim this scales.
 *
 * UPDATE (2026-08-26, real bug found + fixed): the CARTO dark_all/light_all
 * tile source this used to point at (basemaps.cartocdn.com) started
 * returning an HTTP 200 "API KEY REQUIRED" WATERMARK IMAGE in place of
 * real tiles — confirmed by fetching a tile directly with curl and
 * inspecting the PNG, not assumed from a log message, since a 200 status
 * gives osmdroid no error to detect or log. CARTO's anonymous free-tier
 * basemap access was evidently locked down after this file was first
 * written (see the removed DARK/LIGHT XYTileSource entries this replaced).
 * Rather than replace one paid/key-gated provider with another, this now
 * points at osmdroid's own [TileSourceFactory.MAPNIK] — the standard
 * openstreetmap.org tile server, free with no account/key, the same
 * "no new credential" principle this file's doc comment already commits
 * to. Since Mapnik ships only one (light) style, dark mode is now
 * simulated with osmdroid's built-in [TilesOverlay.INVERT_COLORS] color
 * filter instead of a second tile source — this also sidesteps the
 * tile-interruption bug documented below (`setTileSource` mid-fetch),
 * since toggling a color filter doesn't rebuild the tile-provider modules
 * the way switching tile sources does.
 */
private val STREET_TILE_SOURCE = TileSourceFactory.MAPNIK

// Engineering default (CLAUDE.md Rule 13) — not measured against a
// specific tile-loading benchmark, just picked well under one tile's
// real-world width at the zoom levels this screen uses (a zoom-18 tile
// is roughly 150m wide near the equator, smaller nearer the poles) so a
// genuine several-meter walk still re-centers promptly.
private const val MIN_RECENTER_DISTANCE_M = 3.0

/**
 * One-time osmdroid setup (tile cache location + required user-agent —
 * OSM's tile usage policy blocks requests with no/default user agent).
 * Idempotent; safe to call from every StreetMapView composition.
 */
private fun configureOsmdroid(context: Context) {
    val config = Configuration.getInstance()
    config.userAgentValue = context.packageName
    // Explicit base/tile-cache paths derived directly from the CONTEXT WE
    // WERE GIVEN — osmdroid's own default-path auto-resolution
    // (DefaultConfigurationProvider.getOsmdroidBasePath) reaches for a
    // separate internal Context reference that is only populated by
    // Configuration.getInstance().load(context, prefs), which this app
    // never calls. Left unset, that internal reference is null and
    // MapView's constructor throws (caught internally by osmdroid, logged
    // as "Unable to create base path at null") before ever reaching the
    // network tile fetch — setting both paths explicitly here sidesteps
    // that auto-resolution entirely rather than papering over its NPE.
    val basePath = context.filesDir.resolve("osmdroid")
    config.osmdroidBasePath = basePath
    config.osmdroidTileCache = basePath.resolve("tiles")
}

// Verified once with Configuration.isDebugMode/isDebugTileProviders (both
// temporarily set true) + a direct curl check against
// a.basemaps.cartocdn.com (200 OK) while diagnosing an initial blank-map
// appearance — that appearance turned out to be the CORRECT dark-tile
// rendering for open ocean near (0,0) (no GNSS fix yet = default map
// center), not a broken fetch. Re-add those two Configuration lines
// temporarily if a future tile issue needs re-diagnosing.

/**
 * Draws the halo/ring/dot current-position marker at a real [GeoPoint]
 * on osmdroid's tile canvas, per this file's own doc comment.
 *
 * Plain mutable fields rather than `View.setTag(int, Any)` — osmdroid's
 * `MapView` (like any Android `View`) requires int tag keys to be real
 * app resource IDs, not arbitrary constants; a private mutable holder
 * the [AndroidView] `update` block writes into avoids that pitfall
 * entirely.
 */
private class CurrentPositionOverlay : Overlay() {
    var position: GeoPoint? = null
    var anchor: GeoPoint? = null
    var mode: GnssMode = GnssMode.GNSS_AIDED
    /** (Round 2 addition, 2026-08-28, user report: "line terminating vaguely") The active route's destination — null clears the pin. */
    var destination: GeoPoint? = null

    /**
     * Screen-space clockwise-degrees rotation to draw the directional
     * arrow marker at, or null to fall back to the plain (non-directional)
     * dot. This is NOT the same number as device heading — it's already
     * had the map's own current rotation (`MapView.setMapOrientation`)
     * subtracted out by the caller (see StreetMapView's `update` block,
     * where it's computed), because osmdroid pre-rotates the canvas this
     * `draw()` receives by that same amount before invoking any overlay
     * (CLAUDE.md Rule 14 — stating the frame at the boundary): drawing the
     * arrow at the raw device heading on an ALREADY-rotated canvas would
     * double-apply the map's rotation. UNVERIFIED ON A REAL DEVICE
     * (CLAUDE.md Rule 13), same caveat StreetMapView's own headingDeg
     * param already carries for the map-rotation half of this.
     */
    var iconRotationDeg: Float? = null

    private val haloPaint = Paint().apply { color = AccentBlue.copy(alpha = 0.18f).toArgb(); isAntiAlias = true }
    private val ringPaint = Paint().apply {
        color = AccentBlue.toArgb()
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val dotPaint = Paint().apply { color = AccentBlueLight.toArgb(); isAntiAlias = true }
    private val arrowFillPaint = Paint().apply { color = AccentBlueLight.toArgb(); isAntiAlias = true; style = Paint.Style.FILL }
    private val arrowOutlinePaint = Paint().apply {
        color = android.graphics.Color.WHITE
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val anchorPaint = Paint().apply { color = CtaRed.toArgb(); isAntiAlias = true }
    private val linePaint = Paint().apply {
        color = CtaRed.toArgb()
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 6f
        pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f)
    }

    // Destination pin paints — same CtaRed accent the route polyline and
    // outage-anchor marker already use (Figma's own route-related color),
    // so the pin reads as "belongs to the route," not a competing accent.
    private val pinFillPaint = Paint().apply { color = CtaRed.toArgb(); isAntiAlias = true; style = Paint.Style.FILL }
    private val pinOutlinePaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val pinHolePaint = Paint().apply { color = Color.WHITE; isAntiAlias = true }

    override fun draw(canvas: Canvas, projection: Projection) {
        val currentPosition = position ?: return
        val point = projection.toPixels(currentPosition, null)

        if (mode == GnssMode.DEAD_RECKONING || mode == GnssMode.REACQUISITION) {
            anchor?.let { anchorPoint ->
                val anchorPixel = projection.toPixels(anchorPoint, null)
                canvas.drawLine(
                    anchorPixel.x.toFloat(),
                    anchorPixel.y.toFloat(),
                    point.x.toFloat(),
                    point.y.toFloat(),
                    linePaint,
                )
                canvas.drawCircle(anchorPixel.x.toFloat(), anchorPixel.y.toFloat(), 10f, anchorPaint)
            }
        }

        destination?.let { destinationPoint ->
            val destPixel = projection.toPixels(destinationPoint, null)
            drawPin(canvas, destPixel.x.toFloat(), destPixel.y.toFloat())
        }

        canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 44f, haloPaint)
        canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 16f, ringPaint)

        val rotationDeg = iconRotationDeg
        if (rotationDeg != null) {
            // Directional chevron in place of the plain dot — only drawn
            // once a real heading exists (see StreetMapView's
            // markerHeadingDeg param doc for when that is/isn't the case).
            canvas.save()
            canvas.rotate(rotationDeg, point.x.toFloat(), point.y.toFloat())
            val arrowPath = buildArrowPath(point.x.toFloat(), point.y.toFloat())
            canvas.drawPath(arrowPath, arrowFillPaint)
            canvas.drawPath(arrowPath, arrowOutlinePaint)
            canvas.restore()
        } else {
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 11f, dotPaint)
        }
    }

    /** A small "up"-pointing chevron centered at ([cx],[cy]) — rotated by the caller, not here. */
    private fun buildArrowPath(cx: Float, cy: Float): Path = Path().apply {
        moveTo(cx, cy - 12f)
        lineTo(cx - 9f, cy + 8f)
        lineTo(cx, cy + 3f)
        lineTo(cx + 9f, cy + 8f)
        close()
    }

    /**
     * Classic map-pin silhouette (circular head + a triangular tail
     * pointing down) — drawn with Canvas primitives, same approach the
     * halo/ring/dot marker above already uses, no new drawable asset
     * needed. The tail's point (NOT the head's center) lands exactly on
     * [tipX]/[tipY] — the actual destination coordinate — matching how
     * every real map app anchors a pin at its pointed tip, not its head.
     */
    private fun drawPin(canvas: Canvas, tipX: Float, tipY: Float) {
        val headRadius = 22f
        val headCenterY = tipY - headRadius * 2.2f

        val tail = Path().apply {
            moveTo(tipX - headRadius * 0.55f, headCenterY + headRadius * 0.85f)
            lineTo(tipX + headRadius * 0.55f, headCenterY + headRadius * 0.85f)
            lineTo(tipX, tipY)
            close()
        }
        canvas.drawPath(tail, pinFillPaint)
        canvas.drawCircle(tipX, headCenterY, headRadius, pinFillPaint)
        canvas.drawCircle(tipX, headCenterY, headRadius, pinOutlinePaint)
        canvas.drawCircle(tipX, headCenterY, headRadius * 0.32f, pinHolePaint)
    }
}

/**
 * @param currentLatDeg/[currentLonDeg] the real-world position to center
 *   on and mark — null while no lat/lon is derivable yet (no GNSS fix this
 *   run and no anchor to project DR meters against), in which case the map
 *   still renders (last-known/default world view) but with no marker.
 * @param anchorLatDeg/[anchorLonDeg] the outage-anchor point the dashed
 *   drift line is drawn back to during DEAD_RECKONING/REACQUISITION.
 * @param isDarkTheme toggles [TilesOverlay.INVERT_COLORS] over the one real
 *   [STREET_TILE_SOURCE] (user-requested light mode, 2026-08-26) — switched
 *   live via a [LaunchedEffect] below if the app's theme toggle changes
 *   while this screen is showing.
 * @param routeGeometry a REAL computed route's geometry (from
 *   `routing/RoutingRepository.kt`'s OSRM call), drawn as a solid CtaRed
 *   polyline — Figma's own route-line color. Null clears it (no active
 *   route). Added 2026-08-26 alongside real destination search/routing.
 * @param onMapViewReady hands back the underlying osmdroid [MapView] once
 *   created, so a caller (MapScreen's offline-download button) can pass
 *   it to `routing/OfflineRouteCache.kt`'s [org.osmdroid.tileprovider.cachemanager.CacheManager]
 *   — the SAME MapView instance, not a second one, so downloaded tiles
 *   land in the cache this view already reads from.
 */
@Composable
fun StreetMapView(
    currentLatDeg: Double?,
    currentLonDeg: Double?,
    anchorLatDeg: Double?,
    anchorLonDeg: Double?,
    mode: GnssMode,
    isDarkTheme: Boolean,
    routeGeometry: List<GeoPoint>? = null,
    /**
     * Real device heading (degrees clockwise from north) to rotate the map
     * to "heading-up" — Google Maps navigation-mode convention — while
     * turn-by-turn is active. Null means north-up (the normal Drive/idle-Map
     * behavior, unchanged). UNVERIFIED ON A REAL DEVICE (CLAUDE.md Rule 13):
     * osmdroid's `setMapOrientation` rotation direction/sign was set from
     * its documented convention, not confirmed against a live compass
     * heading outdoors yet.
     */
    headingDeg: Float? = null,
    /**
     * Real device/travel heading (degrees clockwise from north — same
     * WORLD-frame convention as [headingDeg], CLAUDE.md Rule 14), used to
     * rotate the current-position MARKER icon into a directional arrow.
     * Unlike [headingDeg] (which only rotates the whole MAP, and only
     * while navigating — see MapScreen's `isNavigating` gating), this is
     * meant to be fed whenever a real heading is available at all, so the
     * marker itself points the right way even when the map stays
     * north-up. Null falls back to the previous plain (non-directional)
     * dot marker — STATUS_AND_ROADMAP.md Tier-1 item #1.
     */
    markerHeadingDeg: Float? = null,
    onMapViewReady: (MapView) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val overlay = remember { CurrentPositionOverlay() }
    val routePolyline = remember { Polyline().apply { color = CtaRed.toArgb(); width = 12f } }
    // REAL BUG FIX (2026-08-26, on-device test — map stuck showing mostly
    // osmdroid's "tile unavailable" placeholder): `update` below re-runs on
    // EVERY recomposition, i.e. every ~10Hz live sensor/GNSS tick (the
    // exact hazard this file's Slice 8b "black tiles" bug, above, already
    // named) — not just when currentLatDeg/currentLonDeg actually change.
    // Unconditionally calling `view.controller.animateTo(point)` every
    // single tick was re-centering the viewport dozens of times a second
    // for near-zero real movement, each call abandoning whatever tiles
    // were still mid-download for the previous center. logcat confirmed
    // this: a genuine mode flip (GNSS_AIDED -> TRANSITION -> DEAD_RECKONING,
    // switching which position source MapScreen.kt feeds in — see that
    // file's currentLatDeg/currentLonDeg selection) jumped the requested
    // tiles across 5 unrelated map areas within 34 seconds, so almost none
    // of them ever finished downloading. Gated on [MIN_RECENTER_DISTANCE_M]
    // below so only a REAL move re-centers the map; this does not fix the
    // underlying GNSS_AIDED-vs-drifted-DR position source jump itself
    // (a separate, already-flagged issue), only the redundant/wasteful
    // re-centering that was making every jump worse for tile loading.
    val lastCenteredPoint = remember { mutableStateOf<GeoPoint?>(null) }
    // REAL BUG FIX (2026-08-26, user report: "can't scroll through the
    // map, it just resurfaces at the same location"): panning itself was
    // never disabled (setMultiTouchControls(true) below already gives real
    // one/two-finger drag-to-pan and pinch-to-zoom) — the live position
    // update above was simply re-centering the viewport out from under any
    // manual pan on the very next tick, since it never knew the user had
    // just moved the map by hand. [isFollowingLocation] tracks whether the
    // map should keep auto-centering on the live position; a real user
    // gesture (detected via the MapListener below) turns it off, and the
    // small recenter button that then appears (bottom-end of the map)
    // turns it back on — the same "follow me" vs. "user is browsing"
    // pattern every turn-by-turn map app uses. [isProgrammaticMove]
    // distinguishes OUR OWN recentering calls from a real user gesture in
    // that same onScroll callback, since osmdroid fires it for both.
    var isFollowingLocation by remember { mutableStateOf(true) }
    val isProgrammaticMove = remember { mutableStateOf(false) }
    val mapView = remember {
        configureOsmdroid(context)
        MapView(context).apply {
            setTileSource(STREET_TILE_SOURCE)
            overlayManager.tilesOverlay.setColorFilter(if (isDarkTheme) TilesOverlay.INVERT_COLORS else null)
            setMultiTouchControls(true)
            // User-reported bug (2026-08-26): osmdroid's default on-screen
            // +/- zoom buttons render bottom-center over this screen's own
            // bottom content (vehicle-mode selector / drift card /
            // ActiveRouteCard, all in StatusOverlayContent.kt / MapScreen.kt),
            // and there's no osmdroid API to reposition them independently of
            // that fixed placement. Disabled outright rather than fought over
            // z-order — setMultiTouchControls(true) above already gives real
            // pinch-to-zoom, so the on-screen buttons were redundant anyway.
            setBuiltInZoomControls(false)
            controller.setZoom(18.0)
            overlays.add(routePolyline)
            overlays.add(overlay)
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    if (!isProgrammaticMove.value) {
                        isFollowingLocation = false
                    }
                    return false
                }

                override fun onZoom(event: ZoomEvent?): Boolean = false
            })
        }.also(onMapViewReady)
    }

    DisposableEffect(mapView) {
        onDispose { mapView.onDetach() }
    }

    // Round 2 UI smoothness pass (2026-08-28): the marker position and
    // map rotation used to be set directly from currentLatDeg/currentLonDeg/
    // headingDeg inside the `update` block below, which only re-runs on a
    // real GNSS/DR tick (~5-10Hz) — visibly stepping/teleporting in small
    // discrete jumps rather than gliding, since the display itself
    // refreshes at ~60Hz. `targetPosition`/`targetHeadingDeg` below are
    // updated at that same ~5-10Hz tick rate (from `update`); THIS loop
    // runs independently at display frame rate, chasing them smoothly via
    // PositionSmoother. Keyed on `mapView` (stable for this composable's
    // lifetime) so it starts once and is cancelled automatically when this
    // screen leaves composition (standard LaunchedEffect behavior) — no
    // manual cleanup needed. An always-on 60fps loop is an accepted cost
    // for a foreground live-navigation screen (every real turn-by-turn map
    // app redraws continuously while active), not a battery concern this
    // MVP needs to optimize away.
    val positionSmoother = remember { PositionSmoother() }
    val targetPosition = remember { mutableStateOf<GeoPoint?>(null) }
    val targetHeadingDeg = remember { mutableStateOf(0f) }
    // Origin's directional-arrow marker feature (STATUS_AND_ROADMAP.md
    // Tier-1 item #1) — same "feed a target, let this loop chase it"
    // treatment as targetPosition/targetHeadingDeg above, rather than
    // setting overlay.iconRotationDeg directly from the `update` block:
    // the icon-rotation math below has to subtract out the map's own
    // CURRENT rotation (see its own comment), which is the SMOOTHED
    // mapOrientationDeg this loop computes each frame, not the raw,
    // possibly-stale value `update` last saw.
    val targetMarkerHeadingDeg = remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(mapView) {
        while (true) {
            withFrameNanos { }
            val target = targetPosition.value
            if (target != null) {
                val smoothed = positionSmoother.stepPosition(target.latitude, target.longitude)
                if (smoothed != null) {
                    overlay.position = GeoPoint(smoothed.first, smoothed.second)
                }
            }
            val smoothedHeadingDeg = positionSmoother.stepHeading(targetHeadingDeg.value)
            // osmdroid rotates the MAP clockwise by the given degrees, so to
            // make the device's own heading point "up" the map must be
            // rotated by the OPPOSITE (negative) amount — same convention
            // the old per-tick call used, just now fed a smoothed value.
            val mapOrientationDeg = -smoothedHeadingDeg
            mapView.setMapOrientation(mapOrientationDeg)
            // Subtract the map's own rotation back out so the directional
            // arrow always points at the REAL travel direction on screen —
            // see CurrentPositionOverlay.iconRotationDeg's own doc for why
            // this subtraction is needed (osmdroid pre-rotates the canvas
            // this overlay draws into by mapOrientationDeg). UNVERIFIED ON
            // A REAL DEVICE (CLAUDE.md Rule 13), same caveat as the map's
            // own rotation direction above.
            overlay.iconRotationDeg = targetMarkerHeadingDeg.value?.let { it - mapOrientationDeg }
            mapView.invalidate()
        }
    }

    // BUG FIX (2026-08-26, real on-device test): setTileSource() rebuilds
    // osmdroid's internal tile-provider modules (closes/reopens caches),
    // which interrupts any tile download already in flight — a real problem
    // when this toggled the tile SOURCE on every theme change. Now dark mode
    // is a color filter over the one real tile source (see the doc comment
    // above), so there is no tile-provider rebuild to worry about; this
    // LaunchedEffect only flips the filter and repaints already-cached
    // tiles in place. Still keyed on [isDarkTheme] alone, not every
    // recomposition, to keep that guarantee explicit.
    LaunchedEffect(isDarkTheme) {
        mapView.overlayManager.tilesOverlay.setColorFilter(if (isDarkTheme) TilesOverlay.INVERT_COLORS else null)
        mapView.invalidate()
    }

    // REAL on-device finding: a newly-computed route can be geographically
    // far from wherever the map camera currently happens to be centered
    // (e.g. still parked near (0,0) if no GNSS-quality fix has ever set
    // the marker position this run, even though `routing/RoutingRepository.kt`
    // successfully used a looser fallback origin for the ROUTE calculation
    // itself) — the route would be computed and drawn correctly but
    // invisible off-screen. Zoom-to-fit the route's own bounding box once
    // per new route, independent of the marker-centering logic above.
    LaunchedEffect(routeGeometry) {
        val geometry = routeGeometry
        if (!geometry.isNullOrEmpty()) {
            mapView.post {
                // BUG FIX (Round 2, 2026-08-28, user report: "glitchy
                // buffer... large pixel tiles of some random places" after
                // pressing Go, needing a manual recenter tap to fix): this
                // call used to fire WITHOUT the isProgrammaticMove guard
                // the marker-recenter logic below already uses, so
                // osmdroid's own onScroll/onZoom callbacks (fired BY
                // zoomToBoundingBox itself) were misclassified as a REAL
                // user gesture by the MapListener below, permanently
                // flipping isFollowingLocation off. Later, when navigation
                // started and the map zoomed in tight
                // (MapScreen.kt's isNavigating effect, setZoom(19.0)), it
                // zoomed in at the STALE route-preview center instead of
                // recentering on the live position — a real but far-off,
                // sparsely-tile-cached area, which is exactly what reads
                // as "random places" made of large placeholder tiles.
                // animated=false (was true) for the same reason setCenter
                // (not animateTo) is used for marker-following below: an
                // ANIMATED call fires its scroll/zoom callbacks
                // asynchronously over several frames, after this flag
                // would already be reset — only an instant jump lets the
                // guard actually cover every callback it causes.
                isProgrammaticMove.value = true
                mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(geometry), false, 100)
                isProgrammaticMove.value = false
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { view ->
                routePolyline.setPoints(routeGeometry ?: emptyList())
                overlay.mode = mode
                // Round 2 addition (2026-08-28, user report: "line
                // terminating vaguely") — the route's own last geometry
                // point IS the destination; no separate geocode lookup
                // needed, RoutingRepository's OSRM response already ends
                // exactly there.
                overlay.destination = routeGeometry?.lastOrNull()
                overlay.anchor = if (anchorLatDeg != null && anchorLonDeg != null) {
                    GeoPoint(anchorLatDeg, anchorLonDeg)
                } else {
                    null
                }
                // Heading-up rotation while navigating (headingDeg != null),
                // north-up (0 degrees) otherwise. Feeds the target the
                // per-frame smoothing loop above chases toward — does NOT
                // set the map's rotation directly (Round 2 UI smoothness
                // pass, 2026-08-28, see that loop's doc). Same treatment
                // for markerHeadingDeg (the directional-arrow icon heading)
                // — see targetMarkerHeadingDeg's own doc for why the icon-
                // rotation subtraction has to happen in that loop, not here.
                targetHeadingDeg.value = headingDeg ?: 0f
                targetMarkerHeadingDeg.value = markerHeadingDeg
                if (currentLatDeg != null && currentLonDeg != null) {
                    val point = GeoPoint(currentLatDeg, currentLonDeg)
                    // Feeds the target the per-frame smoothing loop above
                    // chases toward — does NOT set the marker position
                    // directly (same Round 2 change as headingDeg above).
                    targetPosition.value = point
                    val previousCenter = lastCenteredPoint.value
                    val shouldRecenter = isFollowingLocation &&
                        (previousCenter == null || previousCenter.distanceToAsDouble(point) >= MIN_RECENTER_DISTANCE_M)
                    if (shouldRecenter) {
                        // setCenter (instant), not animateTo (animated over
                        // several frames) — the animated version would fire
                        // onScroll callbacks asynchronously AFTER this flag
                        // is reset below, so the MapListener above couldn't
                        // tell those apart from a real user gesture
                        // mid-animation. Trades away the smooth pan for a
                        // reliable follow/user-pan distinction, an honest
                        // simplification given this map already jumps
                        // around from real GNSS/DR position-source changes
                        // (see the fix above).
                        isProgrammaticMove.value = true
                        view.controller.setCenter(point)
                        isProgrammaticMove.value = false
                        lastCenteredPoint.value = point
                    }
                }
                view.invalidate()
            },
        )

        if (!isFollowingLocation) {
            FloatingIconButton(
                icon = painterResource(R.drawable.ic_recenter),
                contentDescription = "Resume following current location",
                onClick = {
                    isFollowingLocation = true
                    // Prefer the TRUE target position over overlay.position
                    // (Round 2: the latter is now a smoothed, slightly-
                    // lagged cosmetic value, see the smoothing loop above)
                    // — recentering should snap to where the phone actually
                    // is, not to wherever the marker's glide animation
                    // happens to be mid-frame.
                    val point = targetPosition.value ?: overlay.position
                    if (point != null) {
                        isProgrammaticMove.value = true
                        mapView.controller.setCenter(point)
                        isProgrammaticMove.value = false
                        lastCenteredPoint.value = point
                    } else {
                        // No live position yet to snap to — just let the
                        // next real position update (the `update` block
                        // above) recenter as soon as one arrives.
                        lastCenteredPoint.value = null
                    }
                },
                // Bottom-end, like most map apps' recenter button, but
                // pushed up well clear of MapScreen.kt's own bottom row
                // (StatusOverlayContent's vehicle-mode selector + its
                // recalibrate button, drawn ON TOP of this view in that
                // screen's Box z-order) — REAL on-device finding: at the
                // default 16dp padding this button was fully hidden behind
                // that existing bottom row, not just visually close to it.
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 96.dp),
            )
        }
    }
}
