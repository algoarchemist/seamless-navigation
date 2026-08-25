package com.sih26168.idr.dr

import com.sih26168.idr.sensors.SampleRate
import com.sih26168.idr.sensors.SensorRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Slice 3 (sensor -> baseline physics velocity/position, no ML, per
 * CLAUDE.md's slice order): the Android/coroutine glue that connects
 * [SensorRepository]'s raw accel + orientation stream to the pure
 * [BaselinePhysicsIntegrator] math, and republishes the running
 * WORLD-frame position/velocity estimate as its own StateFlow.
 *
 * Deliberately a separate class from SensorRepository (CLAUDE.md
 * Rule 5 — one clearly stated responsibility per file): SensorRepository
 * owns raw sensor IO only; this owns turning that raw stream into a
 * physics position estimate. No ML, no GNSS, no state machine, no ZUPT
 * yet — see [BaselinePhysicsIntegrator]'s doc for why this is expected
 * to drift.
 */
class BaselineDeadReckoningRepository(
    private val sensorRepository: SensorRepository,
    private val scope: CoroutineScope,
) {
    private val integrator = BaselinePhysicsIntegrator()

    // Guards against reprocessing the same accel sample twice — SensorRepository's
    // StateFlow re-emits on every gyro/orientation update too, not just accel.
    private var lastProcessedAccelTimestampNs: Long = 0L

    private val _state = MutableStateFlow(DeadReckoningState())
    val state: StateFlow<DeadReckoningState> = _state.asStateFlow()

    private var collectJob: Job? = null

    fun start() {
        integrator.reset()
        lastProcessedAccelTimestampNs = 0L
        _state.value = DeadReckoningState()

        collectJob = scope.launch {
            sensorRepository.state.collect { sensorUiState ->
                val accel = sensorUiState.latestAccel ?: return@collect
                val orientation = sensorUiState.latestOrientation ?: return@collect
                if (accel.timestampNs == lastProcessedAccelTimestampNs) return@collect

                val dtSeconds = SampleRate.secondsFromDeltaNs(
                    accel.timestampNs - lastProcessedAccelTimestampNs,
                )
                lastProcessedAccelTimestampNs = accel.timestampNs

                // Orientation and accel are read from independent sensor
                // listeners a few ms apart at ~10 Hz; using the latest
                // available orientation for "now"'s accel sample is an
                // accepted approximation for this baseline, not silently
                // ignored (CLAUDE.md Rule 9/14).
                val worldAccel = WorldFrameAcceleration.rotateDeviceToWorld(
                    deviceX = accel.xMps2,
                    deviceY = accel.yMps2,
                    deviceZ = accel.zMps2,
                    rotationMatrixDeviceToWorld = orientation.rotationMatrixDeviceToWorld,
                )
                val linearAccel = WorldFrameAcceleration.removeGravity(worldAccel)

                val newState = integrator.update(
                    dtSeconds = dtSeconds,
                    linearAccelEastMps2 = linearAccel[0].toDouble(),
                    linearAccelNorthMps2 = linearAccel[1].toDouble(),
                )
                _state.value = newState
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }
}
