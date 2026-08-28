package com.sih26168.idr.alignment

import com.sih26168.idr.gnss.GnssModeRepository
import com.sih26168.idr.sensors.SensorRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android/coroutine glue around the pure [AlignmentEstimator] (PRD.md
 * Section 15), publishing ONE canonical live yaw-alignment estimate that
 * BOTH [com.sih26168.idr.ml.MlVelocityRepository] (Round 1) and
 * [com.sih26168.idr.dr.BaselineDeadReckoningRepository] (Round 2,
 * 2026-08-28 — see that class's doc for why the physics path needed
 * this) read.
 *
 * Round 1 had [AlignmentEstimator] living directly inside
 * MlVelocityRepository, evaluated once per sensor tick there — fine
 * while only the ML path needed it, but wrong to duplicate: giving the
 * physics repository its OWN second estimator would evaluate the SAME
 * sensor tick twice against two independently-accumulating estimators
 * (SensorRepository's StateFlow is read by both repositories' own
 * collectors), which could converge to two slightly different yaw
 * offsets instead of one shared truth for "the vehicle's real heading
 * offset." Hoisting it out to its own repository — same "one shared
 * StateFlow, read synchronously by multiple consumers" pattern
 * [GnssModeRepository] already established for GNSS mode — fixes that by
 * construction: only THIS class's collector ever calls
 * [AlignmentEstimator.evaluate].
 */
class AlignmentRepository(
    private val sensorRepository: SensorRepository,
    private val gnssModeRepository: GnssModeRepository,
    private val scope: CoroutineScope,
    private val alignmentEstimator: AlignmentEstimator = AlignmentEstimator(),
) {
    private val _state = MutableStateFlow(UNALIGNED)
    val state: StateFlow<AlignmentEstimate> = _state.asStateFlow()

    private var lastProcessedAccelTimestampNs: Long? = null
    private var collectJob: Job? = null

    fun start() {
        alignmentEstimator.reset()
        lastProcessedAccelTimestampNs = null
        _state.value = UNALIGNED

        collectJob = scope.launch {
            sensorRepository.state.collect { sensorUiState ->
                val accel = sensorUiState.latestAccel ?: return@collect
                val orientation = sensorUiState.latestOrientation ?: return@collect
                val previousTimestampNs = lastProcessedAccelTimestampNs
                if (accel.timestampNs == previousTimestampNs) return@collect
                lastProcessedAccelTimestampNs = accel.timestampNs

                // Same synchronous-sibling-read pattern BaselineDeadReckoningRepository
                // already uses for gnssModeRepository.state.value — this repository's
                // own tick cadence (accel-driven, ~10 Hz) is what matters for
                // YawRate's dt, not GNSS's own much slower fix rate.
                val fix = gnssModeRepository.state.value.latestFix
                _state.value = alignmentEstimator.evaluate(
                    nowNs = accel.timestampNs,
                    azimuthRad = orientation.azimuthRad,
                    gnssBearingDeg = fix?.bearingDeg,
                    gnssSpeedMps = fix?.speedMps,
                )
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    /**
     * PRD.md Section 15's "Ongoing validation... Phone Moved... flag for
     * recalibration" / Section 31/32's manual "hold phone flat, tap to
     * calibrate" fallback. Discards the accumulated yaw estimate so both
     * consumers re-converge from scratch on the next sustained
     * straight-line GNSS-aided segment.
     */
    fun reset() {
        alignmentEstimator.reset()
        _state.value = UNALIGNED
    }

    companion object {
        private val UNALIGNED = AlignmentEstimate(yawOffsetRad = null, sampleCount = 0, isAligned = false)
    }
}
