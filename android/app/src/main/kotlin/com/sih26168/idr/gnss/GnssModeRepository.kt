package com.sih26168.idr.gnss

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "GnssOutageDetector"

// Re-evaluate the state machine at 5 Hz — GNSS mode changes happen over
// seconds, not milliseconds, so this is deliberately much slower than
// the ~10 Hz sensor loop; it runs on its OWN clock (not piggybacked on
// SensorRepository's flow) because GNSS outage detection must keep
// working even if the accelerometer/gyroscope pipeline is unavailable —
// the two concerns are unrelated and CLAUDE.md Rule 5 says don't couple them.
private const val EVALUATION_INTERVAL_MS = 200L

data class GnssModeUiState(
    val mode: GnssMode = GnssMode.GNSS_AIDED,
    val latestFix: GnssFix? = null,
    val fixAgeMs: Long = Long.MAX_VALUE,
    val hasLocationPermission: Boolean = false,
    val lastTransition: GnssModeTransition? = null,
)

/**
 * Slice 4 (GNSS outage detection, per CLAUDE.md's slice order): the
 * Android/coroutine glue that repeatedly evaluates [GnssOutageDetector]
 * against [LocationRepository]'s latest fix on a wall-clock timer, and
 * republishes the current mode + fix info as its own StateFlow. Every
 * state transition is also logged to Logcat (CLAUDE.md Rule 17) with
 * enough detail (from/to mode, wall-clock time, trigger) to replay a
 * test run's transition timing afterward.
 */
class GnssModeRepository(
    private val locationRepository: LocationRepository,
    private val scope: CoroutineScope,
    private val detector: GnssOutageDetector = GnssOutageDetector(),
) {
    private val _state = MutableStateFlow(GnssModeUiState())
    val state: StateFlow<GnssModeUiState> = _state.asStateFlow()

    private var tickerJob: Job? = null

    fun start() {
        tickerJob = scope.launch {
            while (isActive) {
                val nowMs = System.currentTimeMillis()
                val locationState = locationRepository.state.value
                val fix = locationState.latestFix
                val fixAgeMs = if (fix != null) nowMs - fix.timeMs else Long.MAX_VALUE
                val gnssGoodNow = GnssQuality.isGood(fixAgeMs, fix?.accuracyM)

                val transitionCountBefore = detector.transitions.size
                val mode = detector.evaluate(nowMs, gnssGoodNow)
                if (detector.transitions.size > transitionCountBefore) {
                    val transition = detector.transitions.last()
                    Log.i(
                        TAG,
                        "${transition.fromMode} -> ${transition.toMode} at ${transition.atMs}ms: " +
                            transition.triggerDescription,
                    )
                }

                _state.value = GnssModeUiState(
                    mode = mode,
                    latestFix = fix,
                    fixAgeMs = fixAgeMs,
                    hasLocationPermission = locationState.hasLocationPermission,
                    lastTransition = detector.transitions.lastOrNull(),
                )

                delay(EVALUATION_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
    }
}
