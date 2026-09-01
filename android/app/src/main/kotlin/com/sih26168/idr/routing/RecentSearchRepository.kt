package com.sih26168.idr.routing

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "RecentSearchRepository"
private const val PREFS_NAME = "idr_recent_searches"
private const val KEY_ENTRIES = "entries"
private const val MAX_ENTRIES = 8

/**
 * Persists the user's own past destination picks locally (SharedPreferences
 * + org.json — the same JSON library GeocodingRepository already parses
 * Nominatim responses with, no new dependency), most-recent-first, so
 * ui/screens/SearchScreen.kt can show a real "Recent" list the way Google
 * Maps' own search screen does.
 *
 * HONEST SCOPE CUT (CLAUDE.md Rule 13): the reference screenshot's Recent
 * rows also show live business status ("Open · Closes 22:00"). Nominatim
 * (this app's only geocoding source) doesn't return opening-hours/business
 * data, so that line is left out entirely rather than invented.
 */
object RecentSearchRepository {

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getRecent(context: Context): List<GeocodeResult> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val entry = array.getJSONObject(i)
                GeocodeResult(
                    displayName = entry.getString("name"),
                    latDeg = entry.getDouble("lat"),
                    lonDeg = entry.getDouble("lon"),
                )
            }
        } catch (e: Exception) {
            // Corrupt local cache (e.g. a format change across app
            // versions) — recover to an empty list rather than crash the
            // search screen; logged so it's visible in Logcat, same
            // "fail loud in the log, fail soft on screen" pattern
            // GeocodingRepository.search already uses for real network
            // failures.
            Log.w(TAG, "Failed to parse stored recent searches — resetting", e)
            emptyList()
        }
    }

    /** Records [place] as the most recent pick — re-inserted at the front if already present, capped at [MAX_ENTRIES]. */
    fun add(context: Context, place: GeocodeResult) {
        val deduped = listOf(place) + getRecent(context).filter { it.displayName != place.displayName }
        val array = JSONArray()
        deduped.take(MAX_ENTRIES).forEach { result ->
            array.put(
                JSONObject()
                    .put("name", result.displayName)
                    .put("lat", result.latDeg)
                    .put("lon", result.lonDeg),
            )
        }
        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }
}
