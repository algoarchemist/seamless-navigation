package com.sih26168.idr.routing

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.util.GeoPoint

private const val TAG = "RoutingRepository"

/**
 * Real turn-by-turn routing via OSRM's free public demo server
 * (router.project-osrm.org) — chosen over a paid routing API/SDK for the
 * SAME reason osmdroid/CartoDB/Nominatim were: no API key or billing
 * account needed, so nothing here blocks on a credential this project
 * doesn't have (user explicitly asked to build real routing; this keeps
 * that decision consistent with the project's existing "no new
 * credential" pattern). Uses `java.net.HttpURLConnection` + `org.json`
 * (both bundled in the Android SDK) rather than adding Retrofit/OkHttp/
 * Gson — CLAUDE.md Rule 2's "smallest practical stack," same call already
 * made for osmdroid's tile fetching.
 *
 * HONEST GAP (CLAUDE.md Rule 13): OSRM's public demo server is rate-limited
 * and meant for light/demo traffic, not production load or dense usage —
 * same caveat already accepted for CartoDB's free tile endpoint.
 */
object RoutingRepository {

    /**
     * @return a real computed route, or null if OSRM returned no route
     *   (e.g. offline, no network, or a truly unreachable pair of points)
     *   — callers must handle null explicitly rather than assume success.
     */
    suspend fun computeRoute(
        originLatDeg: Double,
        originLonDeg: Double,
        destLatDeg: Double,
        destLonDeg: Double,
        destinationName: String,
    ): RouteResult? = withContext(Dispatchers.IO) {
        val url = URL(
            "https://router.project-osrm.org/route/v1/driving/" +
                "$originLonDeg,$originLatDeg;$destLonDeg,$destLatDeg" +
                "?overview=full&geometries=geojson&steps=true",
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "com.sih26168.idr (SIH26168 IDR MVP demo)")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "OSRM returned HTTP $responseCode for $url")
                return@withContext null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            if (root.optString("code") != "Ok") {
                Log.w(TAG, "OSRM returned code=${root.optString("code")} message=${root.optString("message")}")
                return@withContext null
            }

            val route = root.getJSONArray("routes").getJSONObject(0)
            val geometry = parseGeoJsonLineString(route.getJSONObject("geometry"))
            val steps = parseSteps(route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps"))

            RouteResult(
                geometry = geometry,
                distanceMeters = route.getDouble("distance"),
                durationSeconds = route.getDouble("duration"),
                steps = steps,
                destinationName = destinationName,
            )
        } catch (e: Exception) {
            // Network failure, malformed response, timeout, etc. — treated
            // as "no route available" rather than crashing the UI (same
            // resilience pattern MainActivity already applies to a failed
            // ONNX model load).
            Log.e(TAG, "computeRoute failed for $url", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseGeoJsonLineString(geometry: JSONObject): List<GeoPoint> {
        val coordinates = geometry.getJSONArray("coordinates")
        return (0 until coordinates.length()).map { i ->
            val pair = coordinates.getJSONArray(i)
            // GeoJSON orders coordinates [lon, lat] — the opposite of GeoPoint's (lat, lon).
            GeoPoint(pair.getDouble(1), pair.getDouble(0))
        }
    }

    private fun parseSteps(stepsJson: org.json.JSONArray): List<RouteStep> =
        (0 until stepsJson.length()).map { i ->
            val step = stepsJson.getJSONObject(i)
            val maneuver = step.getJSONObject("maneuver")
            val instruction = formatInstruction(
                type = maneuver.getString("type"),
                modifier = maneuver.optString("modifier", ""),
                streetName = step.optString("name", ""),
            )
            RouteStep(instruction = instruction, distanceMeters = step.getDouble("distance"))
        }

    /**
     * OSRM returns a structured maneuver (type + modifier), not a ready
     * English sentence — this maps that real classification into plain
     * text. A real derivation from OSRM's own real output, not an
     * invented instruction (CLAUDE.md Rule 13).
     */
    private fun formatInstruction(type: String, modifier: String, streetName: String): String {
        val onStreet = if (streetName.isNotBlank()) " onto $streetName" else ""
        return when (type) {
            "depart" -> "Start" + if (streetName.isNotBlank()) " on $streetName" else ""
            "arrive" -> "Arrive at destination"
            "turn" -> "Turn ${modifier.ifBlank { "" }}$onStreet".trim()
            "continue" -> "Continue$onStreet"
            "new name" -> "Continue$onStreet"
            "merge" -> "Merge$onStreet"
            "on ramp" -> "Take the ramp$onStreet"
            "off ramp" -> "Take the exit$onStreet"
            "fork" -> "Keep ${modifier.ifBlank { "straight" }}$onStreet"
            "end of road" -> "Turn ${modifier.ifBlank { "" }}$onStreet".trim()
            "roundabout", "rotary", "roundabout turn" -> "Enter the roundabout$onStreet"
            "exit roundabout", "exit rotary" -> "Exit the roundabout$onStreet"
            else -> "Continue$onStreet"
        }
    }
}
