package com.sih26168.idr.routing

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * PRD-extending, user-requested feature (2026-08-26): "ability to
 * download the route for offline purposes (that particular trip alone)."
 * Two real, separate pieces, both scoped to ONE already-computed route,
 * not a general offline-maps feature:
 *
 * 1. [downloadRouteTiles] — pre-fetches the actual map tiles along the
 *    route corridor into osmdroid's existing tile cache
 *    (`ui/map/StreetMapView.kt`'s `configureOsmdroid`'s `osmdroidTileCache`
 *    path) via osmdroid's own [CacheManager], so [MapView] can render
 *    that corridor from disk with no network once downloaded — the SAME
 *    cache MapView already checks before hitting the network for any
 *    tile, so no new lookup path is introduced.
 * 2. [saveRoute]/[loadSavedRoute] — the route GEOMETRY and STEPS
 *    themselves (not just tile imagery) persisted as JSON to app-external
 *    storage (same `getExternalFilesDir(null)` location
 *    `capture/SensorRecorder.kt` already uses), so the computed route can
 *    be redrawn without re-calling OSRM even if network is unavailable
 *    later.
 *
 * Only one saved route is kept at a time (this trip alone, per the
 * request) — saving a new one overwrites the last.
 *
 * [prefetchLiveZoomTiles] (added 2026-08-29, user-requested "smoother
 * working" — no tile pre-fetch beyond the explicit download button, so
 * browsing the live map along a route could still stutter on a slow
 * connection) is a THIRD, separate piece from the two above: an
 * AUTOMATIC, silent prefetch of the route corridor fired the instant a
 * route is computed (`MapScreen.kt`, no button tap needed), scoped down
 * from [downloadRouteTiles]'s full [MIN_ZOOM]..[MAX_ZOOM] range to just
 * the single zoom level `ui/map/StreetMapView.kt` actually displays while
 * driving (18) — a deliberately smaller, lighter background download than
 * the explicit "Download offline" button's guaranteed-offline promise,
 * since this fires without the user's explicit consent to the data usage
 * (see that function's own doc for the exact tradeoff this was scoped
 * against). Both share [downloadTiles], the actual [CacheManager] call —
 * only the zoom RANGE and whether it's user-visible differ.
 */
object OfflineRouteCache {

    private const val SAVED_ROUTE_FILE_NAME = "offline_route.json"

    /** Zoom range chosen to cover typical turn-by-turn viewing (14 = neighborhood, 18 = street-level, matching StreetMapView's live zoom). */
    private const val MIN_ZOOM = 14
    private const val MAX_ZOOM = 18

    /** StreetMapView's own live-viewing zoom (`controller.setZoom(18.0)`) — kept as one named constant so [prefetchLiveZoomTiles] can never silently drift out of sync with what the map actually displays. */
    private const val LIVE_VIEWING_ZOOM = 18

    fun downloadRouteTiles(
        context: Context,
        mapView: MapView,
        routeGeometry: List<GeoPoint>,
        onProgress: (downloaded: Int, total: Int) -> Unit,
        onComplete: () -> Unit,
        onFailed: () -> Unit,
    ) = downloadTiles(context, mapView, routeGeometry, MIN_ZOOM, MAX_ZOOM, onProgress, onComplete, onFailed)

    /**
     * Silent, automatic, best-effort — fired once per newly-computed
     * route (see `MapScreen.kt`'s Start button), no progress UI, and a
     * failure (no network, tile source rejects bulk caching, etc.) is
     * swallowed rather than surfaced: this is a "smoother if it works"
     * optimization layered on top of the map's existing normal on-demand
     * tile loading, not a promise the way the explicit download button
     * is — a failure here changes nothing the user would notice (the map
     * simply falls back to fetching tiles live, exactly like today).
     * Single zoom level ([LIVE_VIEWING_ZOOM]) rather than
     * [downloadRouteTiles]'s full range specifically to keep the
     * automatic/no-consent data usage modest (user-scoped decision,
     * 2026-08-29 — the wider range was considered and deliberately not
     * used here).
     */
    fun prefetchLiveZoomTiles(context: Context, mapView: MapView, routeGeometry: List<GeoPoint>) {
        downloadTiles(
            context = context,
            mapView = mapView,
            routeGeometry = routeGeometry,
            minZoom = LIVE_VIEWING_ZOOM,
            maxZoom = LIVE_VIEWING_ZOOM,
            onProgress = { _, _ -> },
            onComplete = {},
            onFailed = {},
        )
    }

    private fun downloadTiles(
        context: Context,
        mapView: MapView,
        routeGeometry: List<GeoPoint>,
        minZoom: Int,
        maxZoom: Int,
        onProgress: (downloaded: Int, total: Int) -> Unit,
        onComplete: () -> Unit,
        onFailed: () -> Unit,
    ) {
        val cacheManager = try {
            CacheManager(mapView)
        } catch (e: Exception) {
            // TileSourcePolicyException etc. — the current tile source
            // doesn't allow bulk caching. Surfaced as a normal failure,
            // not a crash (same resilience pattern as RoutingRepository).
            onFailed()
            return
        }

        val points = ArrayList(routeGeometry)
        val callback = object : CacheManager.CacheManagerCallback {
            private var total = 0
            override fun onTaskComplete() = onComplete()
            override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                onProgress(progress, total)
            }
            override fun downloadStarted() = Unit
            override fun setPossibleTilesInArea(total: Int) {
                this.total = total
            }
            override fun onTaskFailed(errors: Int) = onFailed()
        }
        cacheManager.downloadAreaAsync(context, points, minZoom, maxZoom, callback)
    }

    fun saveRoute(context: Context, route: RouteResult) {
        val json = JSONObject().apply {
            put("destinationName", route.destinationName)
            put("distanceMeters", route.distanceMeters)
            put("durationSeconds", route.durationSeconds)
            put(
                "geometry",
                JSONArray().apply {
                    route.geometry.forEach { point ->
                        put(JSONArray().put(point.latitude).put(point.longitude))
                    }
                },
            )
            put(
                "steps",
                JSONArray().apply {
                    route.steps.forEach { step ->
                        put(JSONObject().put("instruction", step.instruction).put("distanceMeters", step.distanceMeters))
                    }
                },
            )
        }
        File(context.getExternalFilesDir(null), SAVED_ROUTE_FILE_NAME).writeText(json.toString())
    }

    /** @return the last saved trip's route, or null if none was ever saved or the file is unreadable/corrupt. */
    fun loadSavedRoute(context: Context): RouteResult? {
        val file = File(context.getExternalFilesDir(null), SAVED_ROUTE_FILE_NAME)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val geometryJson = json.getJSONArray("geometry")
            val geometry = (0 until geometryJson.length()).map { i ->
                val pair = geometryJson.getJSONArray(i)
                GeoPoint(pair.getDouble(0), pair.getDouble(1))
            }
            val stepsJson = json.getJSONArray("steps")
            val steps = (0 until stepsJson.length()).map { i ->
                val step = stepsJson.getJSONObject(i)
                RouteStep(step.getString("instruction"), step.getDouble("distanceMeters"))
            }
            RouteResult(
                geometry = geometry,
                distanceMeters = json.getDouble("distanceMeters"),
                durationSeconds = json.getDouble("durationSeconds"),
                steps = steps,
                destinationName = json.getString("destinationName"),
            )
        } catch (e: Exception) {
            null
        }
    }
}
