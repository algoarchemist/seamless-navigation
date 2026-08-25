package com.sih26168.idr.ml

import com.sih26168.idr.alignment.AlignmentEstimator
import com.sih26168.idr.dr.StationaryDetector
import com.sih26168.idr.dr.WorldFrameAcceleration
import com.sih26168.idr.features.FeatureExtractor
import com.sih26168.idr.gnss.GnssMode
import com.sih26168.idr.gnss.GnssModeRepository
import com.sih26168.idr.sensors.SampleRate
import com.sih26168.idr.sensors.SensorRepository
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MlVelocityUiState(
    val predictedVelocityMps: Float? = null,
    val isAligned: Boolean = false,
    val yawOffsetDeg: Float? = null,
    val alignmentSampleCount: Int = 0,
    val positionEastM: Double = 0.0,
    val positionNorthM: Double = 0.0,
)

// If no GNSS fix has ever been received, fixAgeMs is Long.MAX_VALUE —
// feeding that (converted to seconds) straight into the model would be
// a wildly out-of-training-distribution value. Clamped to a large but
// finite placeholder instead, in the spirit of PRD.md Section 13's
// "fall back rather than trust an out-of-distribution input" — this is
// NOT full out-of-distribution detection (that's future work), just a
// guard against feeding literal near-infinity to the model.
private const val MAX_ELAPSED_SINCE_FIX_S = 999f

/**
 * Slice 6 (ML inference wired in, per CLAUDE.md's slice order): the
 * Android/coroutine glue connecting SensorRepository + GnssModeRepository's
 * streams to AlignmentEstimator, FeatureExtractor, and VelocityModel,
 * republishing the live ML-predicted velocity AND (as of this change)
 * an ML-driven WORLD-frame position estimate as its own StateFlow.
 *
 * Deliberately a SEPARATE, PARALLEL repository to
 * BaselineDeadReckoningRepository (CLAUDE.md Rule 5) — it does NOT
 * modify or replace the physics-based position integrator. Both run
 * and display side by side, so the ML-vs-physics comparison PRD.md
 * Section 30 wants for the demo is directly visible on-device, not
 * just a desktop-measured claim, and Slice 5's tested physics pipeline
 * is completely untouched by this class's position math. Position
 * integration here follows PRD.md Section 16's `v[t] =
 * VelocityModel(features[t])` path directly — see MlPositionIntegrator.kt
 * for why it needs no separate non-holonomic step (satisfied by
 * construction) but does still need ZUPT (the model's own predictions
 * are close to, not exactly, zero at rest).
 *
 * KNOWN, DOCUMENTED GAP (CLAUDE.md Rule 13): vehicle-frame forward/
 * lateral acceleration here is computed by rotating WORLD-frame linear
 * acceleration (WorldFrameAcceleration.kt, already used by Slice 3/5)
 * onto an alignment-corrected heading — NOT the same computation path
 * ml/feature_extraction.py used (device-frame Gram-Schmidt against a
 * fixed, known mounting). See FeatureExtractor.kt's doc for the full
 * explanation of why, and why a true cross-language parity test isn't
 * possible yet.
 */
class MlVelocityRepository(
    private val sensorRepository: SensorRepository,
    private val gnssModeRepository: GnssModeRepository,
    private val velocityModel: VelocityModel,
    private val scope: CoroutineScope,
    private val alignmentEstimator: AlignmentEstimator = AlignmentEstimator(),
    private val featureExtractor: FeatureExtractor = FeatureExtractor(),
    private val stationaryDetector: StationaryDetector = StationaryDetector(),
    private val positionIntegrator: MlPositionIntegrator = MlPositionIntegrator(),
) {
    private val _state = MutableStateFlow(MlVelocityUiState())
    val state: StateFlow<MlVelocityUiState> = _state.asStateFlow()

    private var lastProcessedAccelTimestampNs: Long? = null
    private var collectJob: Job? = null

    fun start() {
        positionIntegrator.reset()
        lastProcessedAccelTimestampNs = null

        collectJob = scope.launch {
            sensorRepository.state.collect { sensorUiState ->
                val accel = sensorUiState.latestAccel ?: return@collect
                val gyro = sensorUiState.latestGyro ?: return@collect
                val orientation = sensorUiState.latestOrientation ?: return@collect
                val previousTimestampNs = lastProcessedAccelTimestampNs
                if (accel.timestampNs == previousTimestampNs) return@collect
                val dtSeconds = if (previousTimestampNs != null) {
                    SampleRate.secondsFromDeltaNs(accel.timestampNs - previousTimestampNs)
                } else {
                    0.0 // first sample this run — no prior timestamp to diff against
                }
                lastProcessedAccelTimestampNs = accel.timestampNs

                val gnssState = gnssModeRepository.state.value
                val fix = gnssState.latestFix

                // Same GNSS-mode-gated reset as BaselineDeadReckoningRepository
                // (Slice 5) — while GNSS is trustworthy, keep the ML position
                // odometer at zero too, so both readouts represent "distance
                // since GNSS was last good" on the same, comparable basis.
                if (gnssState.mode == GnssMode.GNSS_AIDED) {
                    positionIntegrator.reset()
                }

                val alignment = alignmentEstimator.evaluate(
                    nowNs = accel.timestampNs,
                    azimuthRad = orientation.azimuthRad,
                    gnssBearingDeg = fix?.bearingDeg,
                    gnssSpeedMps = fix?.speedMps,
                )

                // WORLD-frame linear acceleration — reuses Slice 3's
                // already-tested rotation + gravity-removal.
                val worldAccel = WorldFrameAcceleration.rotateDeviceToWorld(
                    deviceX = accel.xMps2,
                    deviceY = accel.yMps2,
                    deviceZ = accel.zMps2,
                    rotationMatrixDeviceToWorld = orientation.rotationMatrixDeviceToWorld,
                )
                val linearAccel = WorldFrameAcceleration.removeGravity(worldAccel)

                // Project onto the alignment-corrected heading to get
                // vehicle-frame forward/lateral — same projection
                // technique as dr/NonHolonomicConstraint.kt, applied to
                // acceleration instead of velocity. Falls back to raw
                // device azimuth (yaw offset 0) before alignment
                // converges, same accepted approximation
                // NonHolonomicConstraint already documents.
                val vehicleHeadingRad = orientation.azimuthRad - (alignment.yawOffsetRad ?: 0f)
                val forwardEast = sin(vehicleHeadingRad.toDouble())
                val forwardNorth = cos(vehicleHeadingRad.toDouble())
                val accelForwardMps2 = (linearAccel[0] * forwardEast + linearAccel[1] * forwardNorth).toFloat()
                // Lateral = forward rotated -90 degrees (a fixed, internally-
                // consistent right-hand convention — its SIGN was never
                // independently verified against ground truth the way
                // forward's was in ml/feature_extraction.py, since IO-VNBD's
                // own lateral sign convention wasn't specifically checked
                // either; only forward's sign was empirically corrected).
                val lateralEast = forwardNorth
                val lateralNorth = -forwardEast
                val accelLateralMps2 = (linearAccel[0] * lateralEast + linearAccel[1] * lateralNorth).toFloat()
                val accelUpMps2 = linearAccel[2]

                // Yaw rate: rotate the RAW gyro vector into world frame the
                // same way as accel (angular velocity transforms as a
                // vector under a pure rotation) and take its Up component —
                // rotation about the vertical axis is heading-independent,
                // so no heading projection is needed here, unlike forward/lateral.
                val worldGyro = WorldFrameAcceleration.rotateDeviceToWorld(
                    deviceX = gyro.xRadPerSec,
                    deviceY = gyro.yRadPerSec,
                    deviceZ = gyro.zRadPerSec,
                    rotationMatrixDeviceToWorld = orientation.rotationMatrixDeviceToWorld,
                )
                val gyroYawRateRadPerSec = worldGyro[2]

                val elapsedSinceLastGnssFixS = if (gnssState.fixAgeMs == Long.MAX_VALUE) {
                    MAX_ELAPSED_SINCE_FIX_S
                } else {
                    (gnssState.fixAgeMs / 1000f).coerceAtMost(MAX_ELAPSED_SINCE_FIX_S)
                }

                val features = featureExtractor.update(
                    timestampNs = accel.timestampNs,
                    accelForwardMps2 = accelForwardMps2,
                    accelLateralMps2 = accelLateralMps2,
                    accelUpMps2 = accelUpMps2,
                    gyroYawRateRadPerSec = gyroYawRateRadPerSec,
                    elapsedSinceLastGnssFixS = elapsedSinceLastGnssFixS,
                )

                val predictedVelocityMps = velocityModel.predict(features)

                // ZUPT for the ML position path — see MlPositionIntegrator.kt's
                // doc for why this is still needed even though the model was
                // trained on data that includes stationary segments (its
                // predictions near rest are small but not exactly zero).
                // Reuses the SAME linear-acceleration/gyro-magnitude signal
                // and StationaryDetector class as BaselineDeadReckoningRepository,
                // for a consistent definition of "stationary" app-wide.
                val linearAccelMagnitudeMps2 = sqrt(
                    linearAccel[0] * linearAccel[0] +
                        linearAccel[1] * linearAccel[1] +
                        linearAccel[2] * linearAccel[2],
                )
                val gyroMagnitudeRadPerSec = sqrt(
                    gyro.xRadPerSec * gyro.xRadPerSec +
                        gyro.yRadPerSec * gyro.yRadPerSec +
                        gyro.zRadPerSec * gyro.zRadPerSec,
                )
                val nowBootTimeMs = accel.timestampNs / 1_000_000L
                val stationary = stationaryDetector.evaluate(
                    nowBootTimeMs,
                    linearAccelMagnitudeMps2,
                    gyroMagnitudeRadPerSec,
                )

                val positionState = positionIntegrator.update(
                    dtSeconds = dtSeconds,
                    velocityMps = predictedVelocityMps,
                    headingRad = vehicleHeadingRad,
                    isStationary = stationary,
                )

                _state.value = MlVelocityUiState(
                    predictedVelocityMps = predictedVelocityMps,
                    isAligned = alignment.isAligned,
                    yawOffsetDeg = alignment.yawOffsetRad?.let { Math.toDegrees(it.toDouble()).toFloat() },
                    alignmentSampleCount = alignment.sampleCount,
                    positionEastM = positionState.positionEastM,
                    positionNorthM = positionState.positionNorthM,
                )
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }
}
