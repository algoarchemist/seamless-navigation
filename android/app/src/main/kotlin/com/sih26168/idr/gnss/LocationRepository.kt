package com.sih26168.idr.gnss

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live UI-facing snapshot of the most recent GNSS fix and whether we currently hold permission to read it. */
data class LocationUiState(
    val latestFix: GnssFix? = null,
    val hasLocationPermission: Boolean = false,
)

// ~1 Hz per PRD.md Section 11 ("much lower rate, ~1 Hz" than the ~10 Hz IMU stream).
private const val GNSS_UPDATE_INTERVAL_MS = 1_000L

/**
 * Collects GNSS fixes via FusedLocationProviderClient (PRD.md
 * Section 21). Slice 4 scope only: reads and republishes raw fixes —
 * no outage state machine here (that's [GnssOutageDetector], a
 * separate file per CLAUDE.md Rule 5), no fusion with the dead-reckoned
 * position (Slice 7).
 *
 * Requires ACCESS_FINE_LOCATION, a runtime-granted permission on
 * API 26+ — this class only checks/reports whether it has that
 * permission ([hasLocationPermission]); requesting it from the user is
 * an Activity-level UI concern, handled in MainActivity.
 */
class LocationRepository(private val context: Context) {

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    private var handlerThread: HandlerThread? = null

    private val _state = MutableStateFlow(LocationUiState())
    val state: StateFlow<LocationUiState> = _state.asStateFlow()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            _state.value = _state.value.copy(
                latestFix = GnssFix(
                    timeMs = location.time,
                    latitudeDeg = location.latitude,
                    longitudeDeg = location.longitude,
                    // Missing accuracy is reported as "as bad as possible"
                    // rather than a false 0m, so GnssQuality correctly
                    // treats it as untrustworthy instead of perfect.
                    accuracyM = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE,
                    speedMps = if (location.hasSpeed()) location.speed else null,
                    bearingDeg = if (location.hasBearing()) location.bearing else null,
                ),
            )
        }
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** No-ops (and reports the permission gap via [state]) if location permission is not yet granted. */
    // Lint's permission-flow analysis can't trace the guard through
    // hasLocationPermission() as a separate call — the guard is real
    // (verified by the early return above), this only silences the
    // false-positive MissingPermission warning.
    @Suppress("MissingPermission")
    fun start() {
        val granted = hasLocationPermission()
        _state.value = _state.value.copy(hasLocationPermission = granted)
        if (!granted) return

        val thread = HandlerThread("LocationRepositoryThread").apply { start() }
        handlerThread = thread

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GNSS_UPDATE_INTERVAL_MS).build()
        fusedLocationClient.requestLocationUpdates(request, callback, thread.looper)
    }

    fun stop() {
        fusedLocationClient.removeLocationUpdates(callback)
        handlerThread?.quitSafely()
        handlerThread = null
    }
}
