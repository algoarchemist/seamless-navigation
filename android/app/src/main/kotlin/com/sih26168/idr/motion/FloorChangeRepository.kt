package com.sih26168.idr.motion

import com.sih26168.idr.sensors.SensorRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FloorChangeUiState(
    val relativeAltitudeM: Float = 0f,
    val floorChangeDetected: Boolean = false,
    val floorDelta: Int = 0,
    /** Running signed count of confirmed floor changes this run (e.g. -3 after descending three levels). */
    val totalFloorsChanged: Int = 0,
    /** Whether this device even has a barometer — see SensorRepository.hasBarometer(). */
    val hasBarometer: Boolean = false,
)

/**
 * Android/coroutine glue driving [FloorChangeDetector] off
 * [SensorRepository]'s barometer stream (PRD.md FR12, Round 2 addition,
 * 2026-08-28). A SEPARATE repository (CLAUDE.md Rule 5) rather than
 * folded into BaselineDeadReckoningRepository/MlVelocityRepository —
 * floor detection is entirely independent of GNSS/DR position
 * estimation; nothing about it gates or is gated by either DR path.
 *
 * Not all devices expose a barometer — [FloorChangeUiState.hasBarometer]
 * reflects that honestly (CLAUDE.md Rule 13) rather than silently
 * showing "no floor change, ever" on a device that structurally can't
 * detect one.
 */
class FloorChangeRepository(
    private val sensorRepository: SensorRepository,
    private val scope: CoroutineScope,
    private val floorChangeDetector: FloorChangeDetector = FloorChangeDetector(),
) {
    private val _state = MutableStateFlow(FloorChangeUiState())
    val state: StateFlow<FloorChangeUiState> = _state.asStateFlow()

    private var lastProcessedPressureTimestampNs: Long? = null
    private var totalFloorsChanged = 0
    private var collectJob: Job? = null

    fun start() {
        floorChangeDetector.reset()
        lastProcessedPressureTimestampNs = null
        totalFloorsChanged = 0
        _state.value = FloorChangeUiState(hasBarometer = sensorRepository.hasBarometer())

        collectJob = scope.launch {
            sensorRepository.state.collect { sensorUiState ->
                val pressure = sensorUiState.latestPressure ?: return@collect
                val previousTimestampNs = lastProcessedPressureTimestampNs
                if (pressure.timestampNs == previousTimestampNs) return@collect
                lastProcessedPressureTimestampNs = pressure.timestampNs

                // Boot-time ms (not wall-clock) — same clock family as
                // every other sensor-timestamp-derived dwell calculation
                // in this codebase (CLAUDE.md Rule 9/14).
                val nowBootTimeMs = pressure.timestampNs / 1_000_000L
                val result = floorChangeDetector.evaluate(nowBootTimeMs, pressure.pressureHpa)
                if (result.floorChangeDetected) {
                    totalFloorsChanged += result.floorDelta
                }

                _state.value = FloorChangeUiState(
                    relativeAltitudeM = result.relativeAltitudeM,
                    floorChangeDetected = result.floorChangeDetected,
                    floorDelta = result.floorDelta,
                    totalFloorsChanged = totalFloorsChanged,
                    hasBarometer = sensorRepository.hasBarometer(),
                )
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }
}
