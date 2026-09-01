package com.sih26168.idr.routing

import android.content.Context

private const val PREFS_NAME = "idr_saved_places"

/**
 * Which quick-access saved-place shortcut — mirrors Google Maps' own
 * Home/Work row on its search screen (the reference the user asked
 * ui/screens/SearchScreen.kt to match). Only two slots: PRD.md has no
 * saved-places spec at all, so this stays the smallest version of the
 * pattern rather than growing into a general "saved lists" feature
 * (CLAUDE.md Rule 4).
 */
enum class SavedPlaceSlot(val label: String) {
    HOME("Home"),
    WORK("Work"),
}

/**
 * Persists the user's own Home/Work locations locally (SharedPreferences —
 * same "no new dependency for two key-value slots" choice
 * GeocodingRepository/RoutingRepository already make by talking to plain
 * HTTP endpoints instead of pulling in a networking library). A slot
 * reads back null ("not set") until the user actually picks a location
 * for it in SearchScreen — never a placeholder or guessed address
 * (CLAUDE.md Rule 13).
 */
object SavedPlacesRepository {

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(context: Context, slot: SavedPlaceSlot): GeocodeResult? {
        val stored = prefs(context)
        val name = stored.getString("${slot.name}_name", null) ?: return null
        val lat = stored.getString("${slot.name}_lat", null)?.toDoubleOrNull() ?: return null
        val lon = stored.getString("${slot.name}_lon", null)?.toDoubleOrNull() ?: return null
        return GeocodeResult(displayName = name, latDeg = lat, lonDeg = lon)
    }

    fun save(context: Context, slot: SavedPlaceSlot, place: GeocodeResult) {
        prefs(context).edit()
            .putString("${slot.name}_name", place.displayName)
            // Stored as strings (not putFloat) to keep the full Double
            // precision Nominatim returned — a Float round-trip here would
            // silently shave precision off a coordinate, the exact kind of
            // silent-change CLAUDE.md Rule 9 warns against for frame/unit
            // conversions.
            .putString("${slot.name}_lat", place.latDeg.toString())
            .putString("${slot.name}_lon", place.lonDeg.toString())
            .apply()
    }
}
