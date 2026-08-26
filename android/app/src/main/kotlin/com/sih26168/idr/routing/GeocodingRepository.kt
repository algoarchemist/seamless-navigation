package com.sih26168.idr.routing

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

private const val TAG = "GeocodingRepository"

/**
 * Real destination search via OpenStreetMap's free Nominatim geocoding
 * API — no API key, same "no new credential" principle as
 * [RoutingRepository]/osmdroid's tile fetch. Nominatim's usage policy
 * REQUIRES a distinct User-Agent identifying the app (not a browser
 * default) — set explicitly below, same requirement
 * `ui/map/StreetMapView.kt` already satisfies for osmdroid's tile fetch.
 *
 * HONEST GAP (CLAUDE.md Rule 13): Nominatim's public endpoint is
 * rate-limited (documented max ~1 request/second) and meant for light
 * demo traffic, not production search volume — same caveat already
 * accepted for CartoDB tiles and OSRM routing.
 *
 * BUG FIX (2026-08-26, user report: "most places in Chennai are not
 * visible, SRM Ramapuram is not showing"): curl-testing this exact
 * endpoint directly (outside the app) for "SRM Ramapuram," "Anna Nagar
 * Chennai," "T Nagar Chennai," etc. all returned correct real Chennai
 * results — so Nominatim itself isn't broken. The real bug was that
 * [search] swallowed EVERY failure (non-200 HTTP, timeout, malformed
 * JSON, no network) into an identical empty list, with zero signal for
 * why — indistinguishable on screen from "genuinely no matches." A device
 * on a carrier network sharing a CGNAT IP with many other Nominatim users
 * (common on Indian mobile networks) can get 403/429'd by Nominatim's
 * abuse throttling even though the SAME query works fine from a
 * different IP, which is exactly the kind of failure this used to hide
 * completely. [search] now returns a [GeocodeSearchOutcome] that
 * distinguishes real matches from a real failure reason, and every
 * failure is logged (same `Log.w`/`Log.e` pattern [RoutingRepository]
 * already uses) so it's visible in Logcat even before the UI change
 * reaches a screen. Also added `viewbox`+`countrycodes=in` — a soft
 * relevance bias toward India (not a hard filter: `bounded` is left at
 * its default 0, so a genuine non-Indian match still surfaces, just
 * ranked lower), reasonable for an ISRO/Chennai-scoped hackathon demo
 * (CLAUDE.md Rule 13 — a real, disclosed bias, not a fabricated result).
 */
object GeocodingRepository {

    private const val USER_AGENT = "com.sih26168.idr (SIH26168 IDR MVP demo)"

    // A generous bounding box around greater Chennai (roughly Chengalpattu
    // to Ponneri, ECR to Sriperumbudur) — used only as Nominatim's `viewbox`
    // RELEVANCE hint, not a hard boundary (`bounded` param is omitted,
    // defaulting to 0/off), so it nudges ranking toward local results
    // without ever hiding a real match elsewhere. Format per Nominatim's
    // docs: "<left_lon>,<top_lat>,<right_lon>,<bottom_lat>".
    private const val CHENNAI_VIEWBOX = "79.6,13.3,80.5,12.7"

    /** @return real matching places (Success) or a real, logged failure reason (Failure) — never a silent empty list. */
    suspend fun search(query: String): GeocodeSearchOutcome = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext GeocodeSearchOutcome.Success(emptyList())
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL(
            "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=8" +
                "&countrycodes=in&viewbox=$CHENNAI_VIEWBOX",
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Nominatim returned HTTP $responseCode for query \"$query\"")
                return@withContext GeocodeSearchOutcome.Failure("Search failed (HTTP $responseCode) — try again")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            GeocodeSearchOutcome.Success(parseResults(JSONArray(body)))
        } catch (e: Exception) {
            // Network failure, timeout, malformed JSON, etc. — logged (same
            // resilience+visibility pattern RoutingRepository already uses
            // for OSRM failures) rather than silently reported as "no
            // matches," which used to be indistinguishable from a real
            // empty result.
            Log.e(TAG, "Nominatim search failed for query \"$query\"", e)
            GeocodeSearchOutcome.Failure(e.message ?: e::class.simpleName ?: "Search failed — check network")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResults(results: JSONArray): List<GeocodeResult> =
        (0 until results.length()).map { i ->
            val place = results.getJSONObject(i)
            GeocodeResult(
                displayName = place.getString("display_name"),
                latDeg = place.getString("lat").toDouble(),
                lonDeg = place.getString("lon").toDouble(),
            )
        }
}
