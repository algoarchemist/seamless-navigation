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
 */
object OfflineRouteCache {

    private const val SAVED_ROUTE_FILE_NAME = "offline_route.json"

    /** Zoom range chosen to cover typical turn-by-turn viewing (14 = neighborhood, 18 = street-level, matching StreetMapView's live zoom). */
    private const val MIN_ZOOM = 14
    private const val MAX_ZOOM = 18

    fun downloadRouteTiles(
        context: Context,
        mapView: MapView,
        routeGeometry: List<GeoPoint>,
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
        cacheManager.downloadAreaAsync(context, points, MIN_ZOOM, MAX_ZOOM, callback)
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
