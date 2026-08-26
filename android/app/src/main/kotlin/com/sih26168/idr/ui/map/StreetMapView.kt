package com.sih26168.idr.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.sih26168.idr.gnss.GnssMode
import com.sih26168.idr.ui.theme.AccentBlue
import com.sih26168.idr.ui.theme.AccentBlueLight
import com.sih26168.idr.ui.theme.CtaRed
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

/**
 * The REAL street-map counterpart to [TrackCanvas]'s abstract local-meter
 * grid (Slice 8b — added when the user explicitly asked to bring in a real
 * map dependency, CLAUDE.md Rule 2 discussed and overridden for this
 * decision). osmdroid, not Google Maps Compose/Mapbox, specifically
 * because it needs no API key or billing account to show real tiles —
 * nothing here blocks on a credential this project doesn't have.
 *
 * This file draws real OpenStreetMap street geometry underneath the SAME
 * current-position marker language (halo/ring/dot,
 * [com.sih26168.idr.ui.theme.AccentBlue]) and outage-anchor dashed line
 * [TrackCanvas] already established from the Figma extraction — it is a
 * different BASE LAYER over identical marker styling, not a competing
 * visual language.
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
 * Draws the exact same halo/ring/dot current-position marker
 * [TrackCanvas] uses, at a real [GeoPoint] on osmdroid's tile canvas
 * instead of at a fixed local-meter screen offset — the one visual
 * language, two different position sources (local meters vs real
 * lat/lon), per this file's own doc comment.
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

    private val haloPaint = Paint().apply { color = AccentBlue.copy(alpha = 0.18f).toArgb(); isAntiAlias = true }
    private val ringPaint = Paint().apply {
        color = AccentBlue.toArgb()
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val dotPaint = Paint().apply { color = AccentBlueLight.toArgb(); isAntiAlias = true }
    private val anchorPaint = Paint().apply { color = CtaRed.toArgb(); isAntiAlias = true }
    private val linePaint = Paint().apply {
        color = CtaRed.toArgb()
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 6f
        pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f)
    }

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

        canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 44f, haloPaint)
        canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 16f, ringPaint)
        canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 11f, dotPaint)
    }
}

/**
 * @param currentLatDeg/[currentLonDeg] the real-world position to center
 *   on and mark — null while no lat/lon is derivable yet (no GNSS fix this
 *   run and no anchor to project DR meters against), in which case the map
 *   still renders (last-known/default world view) but with no marker.
 * @param anchorLatDeg/[anchorLonDeg] the outage-anchor point the dashed
 *   drift line is drawn back to during DEAD_RECKONING/REACQUISITION —
 *   mirrors [TrackCanvas]'s own anchor-line behavior.
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
    onMapViewReady: (MapView) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val overlay = remember { CurrentPositionOverlay() }
    val routePolyline = remember { Polyline().apply { color = CtaRed.toArgb(); width = 12f } }
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
        }.also(onMapViewReady)
    }

    DisposableEffect(mapView) {
        onDispose { mapView.onDetach() }
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
                mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(geometry), true, 100)
            }
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { mapView },
        update = { view ->
            routePolyline.setPoints(routeGeometry ?: emptyList())
            overlay.mode = mode
            overlay.anchor = if (anchorLatDeg != null && anchorLonDeg != null) {
                GeoPoint(anchorLatDeg, anchorLonDeg)
            } else {
                null
            }
            // Heading-up rotation while navigating (headingDeg != null),
            // north-up (0 degrees) otherwise. osmdroid rotates the MAP
            // clockwise by the given degrees, so to make the device's own
            // heading point "up" the map must be rotated by the OPPOSITE
            // (negative) amount.
            view.setMapOrientation(if (headingDeg != null) -headingDeg else 0f)
            if (currentLatDeg != null && currentLonDeg != null) {
                val point = GeoPoint(currentLatDeg, currentLonDeg)
                overlay.position = point
                view.controller.animateTo(point)
            }
            view.invalidate()
        },
    )
}
