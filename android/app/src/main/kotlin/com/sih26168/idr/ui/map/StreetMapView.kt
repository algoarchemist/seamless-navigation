package com.sih26168.idr.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.common.MapboxOptions
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.LineLayer
import com.mapbox.maps.extension.style.layers.getLayer
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.getSource
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotation
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.gestures
import com.sih26168.idr.BuildConfig
import com.sih26168.idr.R
import com.sih26168.idr.gnss.GnssMode
import com.sih26168.idr.ui.components.FloatingIconButton
import com.sih26168.idr.ui.theme.AccentBlue
import com.sih26168.idr.ui.theme.AccentBlueLight
import com.sih26168.idr.ui.theme.CtaRed
import org.osmdroid.util.GeoPoint

/**
 * The real street-map base layer. Migrated from osmdroid to the Mapbox Maps
 * SDK (PRD.md Section 7's 2026-09-04 amendment; see that amendment and
 * CLAUDE.md Rule 2 for the reasoning — Mapbox's basemap is OSM-derived,
 * unlike Google's own proprietary road graph, so it stays geometrically
 * consistent with the OSM/OSRM route geometry `routing/RoutingRepository.kt`
 * already produces and this file already renders). The public API
 * (composable name, every parameter) is UNCHANGED from the osmdroid version
 * on purpose, so every caller (`ui/screens/MapScreen.kt`) needed no changes
 * beyond the two spots that touch the raw platform [MapView] type directly
 * (camera zoom, offline tile download — see MapScreen.kt's own comments).
 *
 * COORDINATE-FRAME NOTE (CLAUDE.md Rule 9/14): every incoming coordinate
 * here is WORLD-frame WGS84 lat/lon degrees. [org.osmdroid.util.GeoPoint]
 * (still used as the shared waypoint type across the routing layer — see
 * `routing/RouteModels.kt` — since swapping it out repo-wide was out of
 * scope for this UI-only migration) stores `(lat, lon)`; Mapbox's
 * [com.mapbox.geojson.Point] stores `(lon, lat)` — [toMapboxPoint] below is
 * the one, explicitly-named place that reordering happens.
 *
 * WHAT DIDN'T CARRY OVER (scoped out of this migration, not silently
 * dropped — CLAUDE.md Rule 13): `routing/OfflineRouteCache.kt`'s bulk tile
 * pre-fetch/download (`downloadRouteTiles`/`prefetchLiveZoomTiles`) was
 * built against osmdroid's `CacheManager` and was ALREADY a permanent,
 * documented no-op there (MAPNIK's `FLAG_NO_BULK` — see that file's own
 * dated comment) before this migration. Mapbox has its own, genuinely
 * different offline system (`OfflineManager`/`TileStore`) that would be a
 * real, separately-scoped feature to build, not a drop-in swap — MapScreen.kt
 * now shows an honest "not available in this build" status instead of
 * silently doing nothing. `saveRoute`/`loadSavedRoute` (the route
 * geometry/steps JSON, no tiles involved) still work unchanged.
 */
private const val MIN_RECENTER_DISTANCE_M = 3.0

// Bitmap canvas size for the generated marker icons below — picked to give
// the halo/ring/dot geometry (same radii the old osmdroid Overlay drew)
// enough padding on every side once Mapbox rotates/scales the icon.
private const val MARKER_BITMAP_SIZE_PX = 140
private const val PIN_BITMAP_SIZE_PX = 100

private const val SOURCE_ID_ANCHOR_LINE = "idr-anchor-line-source"
private const val LAYER_ID_ANCHOR_LINE = "idr-anchor-line-layer"
private const val SOURCE_ID_ROUTE_LINE = "idr-route-line-source"
private const val LAYER_ID_ROUTE_LINE = "idr-route-line-layer"

/** `GeoPoint` is `(lat, lon)`; Mapbox's `Point.fromLngLat` wants `(lon, lat)` — see this file's header doc. */
private fun GeoPoint.toMapboxPoint(): Point = Point.fromLngLat(longitude, latitude)

/**
 * One-time Mapbox SDK setup (public access token). Idempotent (re-assigning
 * the same value is harmless); safe to call from every StreetMapView
 * composition, same convention the osmdroid version's `configureOsmdroid`
 * used.
 */
private fun configureMapbox() {
    MapboxOptions.accessToken = BuildConfig.MAPBOX_PUBLIC_TOKEN
}

/**
 * Renders the halo+ring+dot current-position marker (non-directional
 * fallback) into a bitmap. Mapbox's annotation/style layers need a raster
 * icon rather than the arbitrary `Canvas.draw*` calls the old osmdroid
 * `Overlay.draw()` used directly — same Paint objects/radii as that
 * version, just drawn once into an offscreen bitmap instead of every
 * frame, since Mapbox's renderer composites/positions/rotates the bitmap
 * on the GPU rather than needing it redrawn per frame.
 */
private fun renderDotMarkerBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(MARKER_BITMAP_SIZE_PX, MARKER_BITMAP_SIZE_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = MARKER_BITMAP_SIZE_PX / 2f
    val cy = MARKER_BITMAP_SIZE_PX / 2f
    val haloPaint = Paint().apply { color = AccentBlue.copy(alpha = 0.18f).toArgb(); isAntiAlias = true }
    val ringPaint = Paint().apply {
        color = AccentBlue.toArgb()
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    val dotPaint = Paint().apply { color = AccentBlueLight.toArgb(); isAntiAlias = true }
    canvas.drawCircle(cx, cy, 44f, haloPaint)
    canvas.drawCircle(cx, cy, 16f, ringPaint)
    canvas.drawCircle(cx, cy, 11f, dotPaint)
    return bitmap
}

/**
 * Same halo+ring, but with the directional chevron in place of the plain
 * dot — drawn pointing "up" (0 degrees) at generation time; Mapbox's
 * `iconRotate` layout property rotates the whole bitmap live from there,
 * so unlike the osmdroid version this does NOT need to be redrawn per
 * frame to change heading — a real simplification the GPU-composited
 * annotation model gives for free.
 */
private fun renderArrowMarkerBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(MARKER_BITMAP_SIZE_PX, MARKER_BITMAP_SIZE_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = MARKER_BITMAP_SIZE_PX / 2f
    val cy = MARKER_BITMAP_SIZE_PX / 2f
    val haloPaint = Paint().apply { color = AccentBlue.copy(alpha = 0.18f).toArgb(); isAntiAlias = true }
    val ringPaint = Paint().apply {
        color = AccentBlue.toArgb()
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    val arrowFillPaint = Paint().apply { color = AccentBlueLight.toArgb(); isAntiAlias = true; style = Paint.Style.FILL }
    val arrowOutlinePaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    canvas.drawCircle(cx, cy, 44f, haloPaint)
    canvas.drawCircle(cx, cy, 16f, ringPaint)
    val arrowPath = Path().apply {
        moveTo(cx, cy - 12f)
        lineTo(cx - 9f, cy + 8f)
        lineTo(cx, cy + 3f)
        lineTo(cx + 9f, cy + 8f)
        close()
    }
    canvas.drawPath(arrowPath, arrowFillPaint)
    canvas.drawPath(arrowPath, arrowOutlinePaint)
    return bitmap
}

/** Small filled circle for the outage-anchor point — same [CtaRed] accent as the dashed line drawn back to it. */
private fun renderAnchorDotBitmap(): Bitmap {
    val size = 32
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val anchorPaint = Paint().apply { color = CtaRed.toArgb(); isAntiAlias = true }
    canvas.drawCircle(size / 2f, size / 2f, 10f, anchorPaint)
    return bitmap
}

/**
 * Classic map-pin silhouette (circular head + triangular tail), same shape
 * the osmdroid version's `drawPin` drew with Canvas primitives — the tail's
 * point, not the head's center, is the bitmap's own anchor point (set via
 * [PointAnnotationOptions.withIconAnchor] at the call site), matching how
 * every real map app anchors a pin at its pointed tip.
 */
private fun renderDestinationPinBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(PIN_BITMAP_SIZE_PX, PIN_BITMAP_SIZE_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val pinFillPaint = Paint().apply { color = CtaRed.toArgb(); isAntiAlias = true; style = Paint.Style.FILL }
    val pinOutlinePaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    val pinHolePaint = Paint().apply { color = Color.WHITE; isAntiAlias = true }

    val tipX = PIN_BITMAP_SIZE_PX / 2f
    val tipY = PIN_BITMAP_SIZE_PX - 4f
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
    return bitmap
}

/**
 * @param currentLatDeg/[currentLonDeg] the real-world position to center
 *   on and mark — null while no lat/lon is derivable yet (no GNSS fix this
 *   run and no anchor to project DR meters against), in which case the map
 *   still renders (last-known/default world view) but with no marker.
 * @param anchorLatDeg/[anchorLonDeg] the outage-anchor point the dashed
 *   drift line is drawn back to during DEAD_RECKONING/REACQUISITION.
 * @param isDarkTheme selects Mapbox's own [Style.DARK] vs [Style.MAPBOX_STREETS]
 *   — a genuine dark cartography style, replacing the osmdroid version's
 *   `INVERT_COLORS` filter hack over its one light-only tile source.
 * @param routeGeometry a REAL computed route's geometry (from
 *   `routing/RoutingRepository.kt`'s OSRM call), drawn as a solid CtaRed
 *   line. Null clears it (no active route). Still `List<GeoPoint>` — see
 *   this file's header doc for why that type wasn't migrated too.
 * @param onMapViewReady hands back the underlying Mapbox [MapView] once
 *   created, so a caller (MapScreen.kt) can drive the camera directly
 *   (e.g. the navigation-start zoom-in) — same pattern the osmdroid version
 *   used, now with Mapbox's own MapView type (CLAUDE.md Rule 9: this is a
 *   named, deliberate type change, not silent — see MapScreen.kt's own
 *   updated comments at its two call sites).
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
     * to "heading-up" while turn-by-turn is active. Null means north-up.
     * UNVERIFIED ON A REAL DEVICE (CLAUDE.md Rule 13) for this Mapbox path
     * specifically — the osmdroid version's own sign convention was never
     * confirmed against a live compass either; carrying the same caveat
     * forward rather than claiming new certainty.
     */
    headingDeg: Float? = null,
    /**
     * Real device/travel heading, used to rotate the current-position
     * MARKER icon into a directional arrow — independent of [headingDeg]
     * (which only rotates the whole map, and only while navigating). Null
     * falls back to the plain dot marker.
     */
    markerHeadingDeg: Float? = null,
    onMapViewReady: (MapView) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val mapView = remember {
        configureMapbox()
        MapView(context)
    }

    DisposableEffect(mapView) {
        onDispose { mapView.onDestroy() }
    }

    // Bitmaps generated once per composition, not per frame or per update —
    // Mapbox's PointAnnotation `iconRotate` handles live rotation without
    // needing the bitmap itself redrawn (see renderArrowMarkerBitmap's doc).
    val dotMarkerBitmap = remember { renderDotMarkerBitmap() }
    val arrowMarkerBitmap = remember { renderArrowMarkerBitmap() }
    val anchorDotBitmap = remember { renderAnchorDotBitmap() }
    val destinationPinBitmap = remember { renderDestinationPinBitmap() }

    var pointAnnotationManager by remember { mutableStateOf<PointAnnotationManager?>(null) }
    var currentPositionAnnotation by remember { mutableStateOf<PointAnnotation?>(null) }
    var anchorAnnotation by remember { mutableStateOf<PointAnnotation?>(null) }
    var destinationAnnotation by remember { mutableStateOf<PointAnnotation?>(null) }

    // Same "follow me" vs "user is browsing" pattern the osmdroid version
    // used: a real user pan gesture (detected via OnMoveListener below)
    // turns auto-follow off; the recenter button turns it back on.
    // [isProgrammaticMove] guards EVERY programmatic `setCamera` call
    // (center recenter AND bearing updates) — REAL ON-DEVICE FINDING
    // (2026-09-04): this guard IS load-bearing here too, same as it was
    // for osmdroid, not redundant belt-and-braces as first assumed.
    // Mapbox's OnMoveListener apparently fires for ANY camera change, not
    // just genuine touch gestures — an unguarded per-frame bearing update
    // was found re-triggering `onMoveBegin` on literally the next frame
    // after the recenter button set this true, making the button appear
    // to do nothing. See the bearing-update call site below for the fix.
    var isFollowingLocation by remember { mutableStateOf(true) }
    val isProgrammaticMove = remember { mutableStateOf(false) }
    val lastCenteredPoint = remember { mutableStateOf<Point?>(null) }

    val positionSmoother = remember { PositionSmoother() }
    val targetPosition = remember { mutableStateOf<GeoPoint?>(null) }
    val targetHeadingDeg = remember { mutableStateOf(0f) }
    val targetMarkerHeadingDeg = remember { mutableStateOf<Float?>(null) }

    // REAL BUG FOUND on-device (2026-09-04, user report: "current location
    // indicator not visible, route line doesn't show"): this used to be
    // TWO separate LaunchedEffects — one here (keyed on `mapView`, running
    // `loadStyle` WITH the setup callback below) and a second one keyed on
    // `isDarkTheme` that called a BARE `loadStyle(...)` with no callback
    // (further down, now removed). Compose runs EVERY LaunchedEffect at
    // least once on first composition regardless of its key, so both fired
    // on app launch — and since Mapbox's `loadStyle` fully REPLACES the
    // style (sources, layers, and the annotation plugin's own internal
    // layer all get torn down), whichever call landed second silently wiped
    // out everything the first one had just added, with no error to see
    // (the base map tiles render fine either way, since a plain style load
    // succeeds regardless — only the CUSTOM sources/layers/annotation
    // manager this file adds were the casualty). Fixed by keying the WHOLE
    // style setup — load + re-add every custom source/layer/manager — on
    // [isDarkTheme] alone, so a theme change (which forces a real style
    // swap) redoes the setup instead of losing it, and there is only ever
    // ONE `loadStyle` call site.
    LaunchedEffect(isDarkTheme) {
        mapView.mapboxMap.loadStyle(if (isDarkTheme) Style.DARK else Style.MAPBOX_STREETS) { style ->
            // Anchor dashed line + route solid line as raw style
            // source/layers (not the simplified annotation plugin) — only
            // the raw LineLayer exposes `lineDasharray`, which the anchor
            // line needs to match the osmdroid version's dashed-line look.
            style.addSource(GeoJsonSource.Builder(SOURCE_ID_ANCHOR_LINE).build())
            style.addLayer(
                LineLayer(LAYER_ID_ANCHOR_LINE, SOURCE_ID_ANCHOR_LINE).apply {
                    lineColor(CtaRed.toArgb())
                    lineWidth(3.0)
                    lineDasharray(listOf(2.0, 1.5))
                    lineCap(LineCap.ROUND)
                    lineJoin(LineJoin.ROUND)
                },
            )
            style.addSource(GeoJsonSource.Builder(SOURCE_ID_ROUTE_LINE).build())
            style.addLayer(
                LineLayer(LAYER_ID_ROUTE_LINE, SOURCE_ID_ROUTE_LINE).apply {
                    lineColor(CtaRed.toArgb())
                    lineWidth(5.0)
                    lineCap(LineCap.ROUND)
                    lineJoin(LineJoin.ROUND)
                },
            )

            pointAnnotationManager = mapView.annotations.createPointAnnotationManager()
            // The previous manager's annotations (if any — a theme change
            // after the marker/route were already showing) no longer exist
            // in the new style; drop the stale handles so the smoothing
            // loop and update block below `create()` fresh ones instead of
            // calling `.update()` on now-orphaned references.
            currentPositionAnnotation = null
            anchorAnnotation = null
            destinationAnnotation = null
        }
    }

    LaunchedEffect(mapView) {
        mapView.mapboxMap.setCamera(CameraOptions.Builder().zoom(18.0).build())

        mapView.gestures.addOnMoveListener(object : OnMoveListener {
            override fun onMoveBegin(detector: com.mapbox.android.gestures.MoveGestureDetector) {
                if (!isProgrammaticMove.value) {
                    isFollowingLocation = false
                }
            }
            override fun onMove(detector: com.mapbox.android.gestures.MoveGestureDetector): Boolean = false
            override fun onMoveEnd(detector: com.mapbox.android.gestures.MoveGestureDetector) = Unit
        })

        onMapViewReady(mapView)
    }

    // Round 2 UI smoothness pass, carried over from the osmdroid version:
    // GNSS/DR ticks arrive at ~5-10Hz; this loop runs at display frame rate
    // (~60Hz) chasing the smoothed value via [PositionSmoother] so the
    // marker/camera glide instead of visibly stepping.
    LaunchedEffect(mapView) {
        while (true) {
            withFrameNanos { }
            // Bearing computed FIRST and applied to the camera before the
            // marker-rotation math below needs it (see that block's own
            // comment for why).
            //
            // REAL BUG FOUND on-device (2026-09-04, MapVerificationScreen
            // test: the recenter button appeared to do nothing while
            // heading-up was on): this call was UNGUARDED by
            // [isProgrammaticMove] — every ~60fps frame's bearing update
            // apparently registers with Mapbox's gesture plugin as a
            // camera move (contrary to this file's earlier assumption,
            // documented elsewhere, that Mapbox's OnMoveListener only
            // fires for genuine touch-gesture moves), re-firing
            // `onMoveBegin` and forcing `isFollowingLocation` back to
            // false on literally the next frame after the recenter button
            // had just set it true. Wrapping this call in the SAME guard
            // the center-recenter calls already use fixes it — confirmed
            // this guard IS load-bearing here after all, not the
            // redundant belt-and-braces it was assumed to be.
            val smoothedHeadingDeg = positionSmoother.stepHeading(targetHeadingDeg.value)
            isProgrammaticMove.value = true
            mapView.mapboxMap.setCamera(CameraOptions.Builder().bearing(smoothedHeadingDeg.toDouble()).build())
            isProgrammaticMove.value = false

            val manager = pointAnnotationManager
            val target = targetPosition.value
            if (manager != null && target != null) {
                val smoothed = positionSmoother.stepPosition(target.latitude, target.longitude)
                if (smoothed != null) {
                    val point = Point.fromLngLat(smoothed.second, smoothed.first)
                    // REAL BUG FOUND on-device (2026-09-04, MapVerificationScreen
                    // test): Mapbox's `icon-rotate` is VIEWPORT-relative (a
                    // fixed on-screen direction), NOT map/compass-relative —
                    // confirmed by testing heading-up mode at heading=180:
                    // the arrow pointed DOWN (the raw uncorrected heading)
                    // instead of UP (the correct "pointing where you're
                    // going" reading once the map itself has rotated 180 to
                    // put that heading at the top of the screen). Same
                    // correction the osmdroid version's `iconRotationDeg`
                    // doc already required, just a different sign: Mapbox's
                    // `bearing` convention already matches "heading points
                    // up" directly (no negation needed, unlike osmdroid's
                    // `setMapOrientation`), so the subtraction here uses the
                    // SAME sign, not flipped.
                    val rotationDeg = targetMarkerHeadingDeg.value?.let { it - smoothedHeadingDeg }
                    val existing = currentPositionAnnotation
                    if (existing == null) {
                        val options = PointAnnotationOptions()
                            .withPoint(point)
                            .withIconImage(if (rotationDeg != null) arrowMarkerBitmap else dotMarkerBitmap)
                            .apply { if (rotationDeg != null) withIconRotate(rotationDeg.toDouble()) }
                        currentPositionAnnotation = manager.create(options)
                    } else {
                        existing.point = point
                        existing.iconImageBitmap = if (rotationDeg != null) arrowMarkerBitmap else dotMarkerBitmap
                        existing.iconRotate = rotationDeg?.toDouble()
                        manager.update(existing)
                    }
                }
            }
        }
    }

    // Zoom-to-fit the route's own bounding box once per new route — same
    // reasoning as the osmdroid version's zoomToBoundingBox call (a freshly
    // computed route can be far from wherever the camera currently is).
    LaunchedEffect(routeGeometry) {
        val geometry = routeGeometry
        if (!geometry.isNullOrEmpty()) {
            val points = geometry.map { it.toMapboxPoint() }
            // Deprecated in favor of a callback-based overload (compiler
            // warning, not an error) — this synchronous form still works
            // and its EdgeInsets padding directly matches the fixed-margin
            // behavior the osmdroid version's zoomToBoundingBox(..., 100)
            // used, so it's kept rather than guessing at the newer async
            // API's exact semantics without a device to verify against.
            @Suppress("DEPRECATION")
            val cameraOptions = mapView.mapboxMap.cameraForCoordinates(points, com.mapbox.maps.EdgeInsets(100.0, 100.0, 100.0, 100.0), null, null)
            isProgrammaticMove.value = true
            mapView.mapboxMap.setCamera(cameraOptions)
            isProgrammaticMove.value = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { view ->
                val routeSource = view.mapboxMap.style?.getSource(SOURCE_ID_ROUTE_LINE) as? GeoJsonSource
                if (routeGeometry.isNullOrEmpty()) {
                    routeSource?.geometry(LineString.fromLngLats(emptyList()))
                } else {
                    routeSource?.geometry(LineString.fromLngLats(routeGeometry.map { it.toMapboxPoint() }))
                }

                val manager = pointAnnotationManager
                val destinationPoint = routeGeometry?.lastOrNull()?.toMapboxPoint()
                if (manager != null) {
                    if (destinationPoint == null) {
                        destinationAnnotation?.let { manager.delete(it) }
                        destinationAnnotation = null
                    } else {
                        val existing = destinationAnnotation
                        if (existing == null) {
                            destinationAnnotation = manager.create(
                                PointAnnotationOptions()
                                    .withPoint(destinationPoint)
                                    .withIconImage(destinationPinBitmap)
                                    .withIconAnchor(IconAnchor.BOTTOM),
                            )
                        } else {
                            existing.point = destinationPoint
                            manager.update(existing)
                        }
                    }
                }

                val anchorSource = view.mapboxMap.style?.getSource(SOURCE_ID_ANCHOR_LINE) as? GeoJsonSource
                val showAnchorLine = (mode == GnssMode.DEAD_RECKONING || mode == GnssMode.REACQUISITION) &&
                    anchorLatDeg != null && anchorLonDeg != null && currentLatDeg != null && currentLonDeg != null
                if (showAnchorLine) {
                    val anchorPoint = Point.fromLngLat(anchorLonDeg!!, anchorLatDeg!!)
                    val currentPoint = Point.fromLngLat(currentLonDeg!!, currentLatDeg!!)
                    anchorSource?.geometry(LineString.fromLngLats(listOf(anchorPoint, currentPoint)))
                    if (manager != null) {
                        val existing = anchorAnnotation
                        if (existing == null) {
                            anchorAnnotation = manager.create(
                                PointAnnotationOptions().withPoint(anchorPoint).withIconImage(anchorDotBitmap),
                            )
                        } else {
                            existing.point = anchorPoint
                            manager.update(existing)
                        }
                    }
                } else {
                    anchorSource?.geometry(LineString.fromLngLats(emptyList()))
                    manager?.let { m -> anchorAnnotation?.let { m.delete(it) } }
                    anchorAnnotation = null
                }

                targetHeadingDeg.value = headingDeg ?: 0f
                targetMarkerHeadingDeg.value = markerHeadingDeg
                if (currentLatDeg != null && currentLonDeg != null) {
                    val geoPoint = GeoPoint(currentLatDeg, currentLonDeg)
                    targetPosition.value = geoPoint
                    val point = geoPoint.toMapboxPoint()
                    val previousCenter = lastCenteredPoint.value
                    val shouldRecenter = isFollowingLocation &&
                        (previousCenter == null || distanceMeters(previousCenter, point) >= MIN_RECENTER_DISTANCE_M)
                    if (shouldRecenter) {
                        isProgrammaticMove.value = true
                        view.mapboxMap.setCamera(CameraOptions.Builder().center(point).build())
                        isProgrammaticMove.value = false
                        lastCenteredPoint.value = point
                    }
                }
            },
        )

        if (!isFollowingLocation) {
            FloatingIconButton(
                icon = painterResource(R.drawable.ic_recenter),
                contentDescription = "Resume following current location",
                onClick = {
                    isFollowingLocation = true
                    val geoPoint = targetPosition.value
                    val point = geoPoint?.toMapboxPoint()
                    if (point != null) {
                        isProgrammaticMove.value = true
                        mapView.mapboxMap.setCamera(CameraOptions.Builder().center(point).build())
                        isProgrammaticMove.value = false
                        lastCenteredPoint.value = point
                    } else {
                        lastCenteredPoint.value = null
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 96.dp),
            )
        }
    }
}

/** Equirectangular-approximation distance in meters — good enough at the few-meter recenter-threshold scale this is used for (same accuracy tradeoff osmdroid's own `distanceToAsDouble` made at this call site). */
private fun distanceMeters(a: Point, b: Point): Double {
    val metersPerDegLat = 111_320.0
    val metersPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(a.latitude()))
    val dLat = (b.latitude() - a.latitude()) * metersPerDegLat
    val dLon = (b.longitude() - a.longitude()) * metersPerDegLon
    return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
}
