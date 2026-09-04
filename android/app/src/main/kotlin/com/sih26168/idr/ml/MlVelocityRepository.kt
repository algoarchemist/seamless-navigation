package com.sih26168.idr.ml

import android.util.Log
import com.sih26168.idr.alignment.AlignmentRepository
import com.sih26168.idr.dr.WorldFrameAcceleration
import com.sih26168.idr.features.FeatureExtractor
import com.sih26168.idr.fusion.VelocityBiasCalibrator
import com.sih26168.idr.gnss.GnssMode
import com.sih26168.idr.gnss.GnssModeRepository
import com.sih26168.idr.gnss.GnssQuality
import com.sih26168.idr.motion.LongitudinalMotionClassifier
import com.sih26168.idr.motion.MotionStateClassifier
import com.sih26168.idr.motion.PotholeShockDetector
import com.sih26168.idr.motion.StationaryContext
import com.sih26168.idr.motion.StopEventClassifier
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

private const val TAG = "MlVelocityRepository"

data class MlVelocityUiState(
    /** The ONNX model's raw output, before Slice 7's bias correction. */
    val predictedVelocityRawMps: Float? = null,
    /** [predictedVelocityRawMps] + [velocityBiasMps] — bias-corrected, but BEFORE Round 2's damping/OOD guard (see [predictedVelocityDampedMps]). */
    val predictedVelocityCorrectedMps: Float? = null,
    /**
     * (Round 2 addition, 2026-08-28) [predictedVelocityCorrectedMps] after
     * [VelocityGuard]'s OOD rejection + exponential smoothing — this is
     * what actually feeds the position integrator now, not
     * [predictedVelocityCorrectedMps] directly (Round 1's behavior, which
     * let a single anomalous sample jump the position — see
     * [VelocityGuard]'s doc).
     */
    val predictedVelocityDampedMps: Float? = null,
    /** (Round 2 addition, 2026-08-28) [VelocityGuard] rejected this tick's prediction as implausible and held the last accepted value instead. */
    val isVelocityOutOfDistribution: Boolean = false,
    /** Currently learned bias (see VelocityBiasCalibrator) — 0 until at least one GNSS_AIDED sample above the speed gate. */
    val velocityBiasMps: Float = 0f,
    val isAligned: Boolean = false,
    val yawOffsetDeg: Float? = null,
    val alignmentSampleCount: Int = 0,
    /** (2026-09-02) PRD.md Section 15's motorcycle-lean flag — see AlignmentEstimator.kt's doc. Republished from AlignmentRepository, not computed here. */
    val reducedConfidenceDueToRoll: Boolean = false,
    val positionEastM: Double = 0.0,
    val positionNorthM: Double = 0.0,
    /** MotionStateClassifier overrode a physically-still tick to NOT ZUPT, because the raw model still predicts real speed. */
    val isCruising: Boolean = false,
    /** PotholeShockDetector fired this tick — forward/lateral accel was discounted before feature extraction. */
    val potholeShockDetectedThisTick: Boolean = false,
    /** LongitudinalMotionClassifier: vehicle-frame forward acceleration is above the Accelerating threshold this tick. */
    val isAccelerating: Boolean = false,
    /** LongitudinalMotionClassifier: vehicle-frame forward acceleration is below the (negative) Braking threshold this tick. */
    val isBraking: Boolean = false,
    /** motion/StopEventClassifier's richer context for THIS tick's ZUPT
     * decision — additional detail for logging/debug/UI, not a second
     * ZUPT decision (see that class's own doc). */
    val stationaryContext: StationaryContext = StationaryContext.MOVING,
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
 * streams to FeatureExtractor and VelocityModel, republishing the live
 * ML-predicted velocity AND (as of this change) an ML-driven WORLD-frame
 * position estimate as its own StateFlow.
 *
 * UPDATE: phone-to-vehicle yaw alignment is no longer owned here — it now
 * lives in [com.sih26168.idr.alignment.AlignmentRepository], a SHARED
 * estimate also read by dr/BaselineDeadReckoningRepository.kt (see that
 * repository's own doc and AlignmentRepository's for why: this class used
 * to be alignment tracking's only home, which meant it silently stopped
 * existing whenever the ONNX model failed to load, and the physics path
 * had no access to it at all).
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
 *
 * Slice 7 addition: PRD.md Section 17's online velocity-bias calibration.
 * [biasCalibrator] learns a running offset between this model's raw
 * output and GNSS's own speed reading while GNSS is trustworthy, and that
 * learned offset is what actually feeds [positionIntegrator] (not the raw
 * prediction) — see [VelocityBiasCalibrator]'s doc for why this is a
 * simple EWMA correction, not a Kalman filter.
 *
 * Motion-classification stand-ins: [motionStateClassifier] resolves
 * StationaryDetector's own documented "can't tell stationary from
 * cruising" ambiguity using this model's raw prediction as a
 * corroborating signal (deliberately ML-only — the physics-only
 * [com.sih26168.idr.dr.BaselineDeadReckoningRepository] baseline stays
 * untouched by any ML signal, per CLAUDE.md Rule 3); [potholeShockDetector]
 * discounts forward/lateral accel on a detected vertical shock before it
 * reaches [featureExtractor], per PRD.md Section 14's `Pothole` effect;
 * [longitudinalMotionClassifier] (2026-08-30) flags Accelerating/Braking
 * from the SAME alignment-corrected `accelForwardMps2` the model
 * consumes. All three are deterministic stand-ins, not the trained PRD
 * Section 14 classifier — see their own class docs for why.
 *
 * UPDATE (context-aware ZUPT): [stopEventClassifier] replaces the direct
 * `StationaryDetector` this class used to own — see that class's own doc
 * for the real, measured reason (accel/gyro alone were 100%
 * false-negative against real traffic stops). [motionStateClassifier]'s
 * existing contract is UNCHANGED — it still receives the plain accel/gyro
 * dwell-confirmed boolean (now read off
 * [com.sih26168.idr.motion.StopClassification.dwellConfirmedStationary]
 * instead of a second, redundant `StationaryDetector` call) and still
 * gets the final say on the ML path's own cruising override; the actual
 * ZUPT gate is `stopEventClassifier`'s result AND-ed with "not overridden
 * to cruising," so neither signal's existing protective behavior is lost.
 */
class MlVelocityRepository(
    private val sensorRepository: SensorRepository,
    private val gnssModeRepository: GnssModeRepository,
    private val velocityModel: VelocityModel,
    private val scope: CoroutineScope,
    private val alignmentRepository: AlignmentRepository,
    private val featureExtractor: FeatureExtractor = FeatureExtractor(),
    private val stopEventClassifier: StopEventClassifier = StopEventClassifier(),
    private val positionIntegrator: MlPositionIntegrator = MlPositionIntegrator(),
    private val biasCalibrator: VelocityBiasCalibrator = VelocityBiasCalibrator(),
    private val motionStateClassifier: MotionStateClassifier = MotionStateClassifier(),
    private val potholeShockDetector: PotholeShockDetector = PotholeShockDetector(),
    private val velocityGuard: VelocityGuard = VelocityGuard(),
    private val longitudinalMotionClassifier: LongitudinalMotionClassifier = LongitudinalMotionClassifier(),
) {
    private val _state = MutableStateFlow(MlVelocityUiState())
    val state: StateFlow<MlVelocityUiState> = _state.asStateFlow()

    private var lastProcessedAccelTimestampNs: Long? = null
    private var collectJob: Job? = null

    // Logged only on a CONTEXT CHANGE, not every tick — same convention as
    // dr/BaselineDeadReckoningRepository.kt's own StopEventClassifier logging.
    private var lastLoggedContext: StationaryContext? = null

    fun start() {
        positionIntegrator.reset()
        biasCalibrator.reset()
        velocityGuard.reset()
        stopEventClassifier.reset()
        longitudinalMotionClassifier.reset()
        lastProcessedAccelTimestampNs = null
        lastLoggedContext = null

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
                // Boot-time ms — computed once here (was previously computed
                // later, just before stopEventClassifier.evaluate()) since
                // longitudinalMotionClassifier.classify() below now also
                // needs it for its own dwell/hysteresis tracking (2026-09-03).
                val nowBootTimeMs = accel.timestampNs / 1_000_000L

                val gnssState = gnssModeRepository.state.value
                val fix = gnssState.latestFix

                // Same GNSS-mode-gated reset as BaselineDeadReckoningRepository
                // (Slice 5) — while GNSS is trustworthy, keep the ML position
                // odometer at zero too, so both readouts represent "distance
                // since GNSS was last good" on the same, comparable basis.
                if (gnssState.mode == GnssMode.GNSS_AIDED) {
                    positionIntegrator.reset()
                }

                // Round 2 (2026-08-28): reads the ONE shared alignment
                // estimate from AlignmentRepository instead of owning/
                // evaluating an AlignmentEstimator directly — the SAME
                // shared value dr/BaselineDeadReckoningRepository.kt also
                // reads, so both DR paths agree on one real yaw offset
                // instead of each maintaining its own independent estimate.
                val alignment = alignmentRepository.state.value

                // WORLD-frame linear acceleration — reuses Slice 3's
                // already-tested rotation + gravity-removal.
                val worldAccel = WorldFrameAcceleration.rotateDeviceToWorld(
                    deviceX = accel.xMps2,
                    deviceY = accel.yMps2,
                    deviceZ = accel.zMps2,
                    rotationMatrixDeviceToWorld = orientation.rotationMatrixDeviceToWorld,
                )
                val linearAccel = WorldFrameAcceleration.removeGravity(worldAccel)

                // PRD.md Section 14's Pothole effect: "discount the
                // acceleration sample(s) so a vertical/shock spike doesn't
                // get misread as forward acceleration." Checked BEFORE the
                // forward/lateral projection below, so a detected shock
                // discounts exactly the East/North components that
                // projection turns into accelForwardMps2/accelLateralMps2
                // — the Up component itself (what triggered the detection)
                // and gyro are left untouched, matching PRD's wording.
                val potholeShockDetectedThisTick = potholeShockDetector.isShock(linearAccel[2])
                val discountedLinearAccelEast = if (potholeShockDetectedThisTick) 0f else linearAccel[0]
                val discountedLinearAccelNorth = if (potholeShockDetectedThisTick) 0f else linearAccel[1]

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
                val accelForwardMps2 =
                    (discountedLinearAccelEast * forwardEast + discountedLinearAccelNorth * forwardNorth).toFloat()
                // Lateral = forward rotated -90 degrees (a fixed, internally-
                // consistent right-hand convention — its SIGN was never
                // independently verified against ground truth the way
                // forward's was in ml/feature_extraction.py, since IO-VNBD's
                // own lateral sign convention wasn't specifically checked
                // either; only forward's sign was empirically corrected).
                val lateralEast = forwardNorth
                val lateralNorth = -forwardEast
                val accelLateralMps2 =
                    (discountedLinearAccelEast * lateralEast + discountedLinearAccelNorth * lateralNorth).toFloat()
                val accelUpMps2 = linearAccel[2]

                // PRD.md Section 14's Accelerating/Braking classes — a
                // deterministic sign/magnitude stand-in over the SAME
                // vehicle-frame forward-acceleration feature the ONNX
                // model itself consumes (see LongitudinalMotionClassifier's
                // own doc for why this is ML-path-only).
                val longitudinalClassification = longitudinalMotionClassifier.classify(nowBootTimeMs, accelForwardMps2)

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

                val rawPredictedVelocityMps = velocityModel.predict(features)

                // Slice 7 (PRD.md Section 17): while GNSS is trustworthy,
                // learn the running offset between this model's raw output
                // and GNSS's own speed reading. Deliberately gated on the
                // SAME mode boundary the position integrators reset on
                // (GNSS_AIDED) — that's exactly when there's real ground
                // truth to calibrate against.
                if (gnssState.mode == GnssMode.GNSS_AIDED && fix?.speedMps != null) {
                    // Round 2 (2026-08-28 — PRD.md FR13/Section 17): weight
                    // this sample's pull on the learned bias by how accurate
                    // THIS fix actually is, instead of trusting every fix
                    // that merely clears GnssQuality.isGood's binary gate
                    // identically (a 24m fix vs. a 2m fix).
                    val confidenceWeight = GnssQuality.confidenceWeight(fix.accuracyM)
                    biasCalibrator.update(
                        gnssSpeedMps = fix.speedMps,
                        rawPredictedVelocityMps = rawPredictedVelocityMps,
                        confidenceWeight = confidenceWeight,
                    )
                }
                val correctedVelocityMps = biasCalibrator.correctedVelocity(rawPredictedVelocityMps)

                // Round 2 (2026-08-28 — PRD.md FR3/Section 13): OOD guard +
                // damping BEFORE this reaches the position integrator — see
                // VelocityGuard's doc for why the ML path needed this and
                // the physics path didn't.
                val guardedVelocity = velocityGuard.apply(correctedVelocityMps)
                val dampedVelocityMps = guardedVelocity.velocityMps

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

                // Same trustworthy-GNSS-speed preference as
                // dr/BaselineDeadReckoningRepository.kt's own wiring — see
                // StopEventClassifier's honest-limitation note for why this
                // is preferred over the model's own (self-referential)
                // speed estimate whenever it's actually available. Also
                // carries that file's 2026-09-02 REAL BUG fix (see its own
                // comment for the full on-device finding): widened from
                // GNSS_AIDED-only to also trust REACQUISITION, which by
                // construction already has a fix continuously good for >=
                // GnssOutageDetector's reacquisitionEnterDwellMs — excluding
                // it left this path's ZUPT gate with no independent signal
                // to break a self-referential "damped velocity already
                // drifted, so it never reads as near-zero" deadlock.
                val gnssSpeedForClassifier = if (
                    (gnssState.mode == GnssMode.GNSS_AIDED || gnssState.mode == GnssMode.REACQUISITION) &&
                    fix != null &&
                    GnssQuality.isGood(gnssState.fixAgeMs, fix.accuracyM)
                ) {
                    fix.speedMps
                } else {
                    null
                }

                val classification = stopEventClassifier.evaluate(
                    nowMs = nowBootTimeMs,
                    linearAccelMagnitudeMps2 = linearAccelMagnitudeMps2,
                    gyroMagnitudeRadPerSec = gyroMagnitudeRadPerSec,
                    currentSpeedEstimateMps = dampedVelocityMps,
                    gnssSpeedMps = gnssSpeedForClassifier,
                    // Same 2026-09-03 fix as dr/BaselineDeadReckoningRepository.kt
                    // -- see StopEventClassifier's own doc.
                    significantMotionSupported = sensorRepository.hasSignificantMotionSensor(),
                    significantMotionEventCount = sensorUiState.significantMotionEventCount,
                )

                if (classification.context != lastLoggedContext) {
                    Log.d(
                        TAG,
                        "stop context -> ${classification.context} " +
                            "(currentSpeed=${"%.2f".format(classification.currentSpeedEstimateMps)} m/s, " +
                            "recentPeak=${"%.2f".format(classification.recentPeakSpeedMps)} m/s, " +
                            "duration=${classification.stationaryDurationMs}ms, " +
                            "zupt=${classification.shouldApplyZupt}): ${classification.reason}",
                    )
                    lastLoggedContext = classification.context
                }

                // Motion-classification stand-in: the plain accel/gyro
                // dwell-confirmed flag alone can't tell "truly at rest" from
                // "smoothly cruising" (see MotionStateClassifier's doc) —
                // corroborate with the raw model prediction before deciding
                // whether to override the context-aware ZUPT decision below.
                val motionClassification = motionStateClassifier.classify(
                    classification.dwellConfirmedStationary,
                    rawPredictedVelocityMps,
                )

                // The actual ZUPT gate: StopEventClassifier's context-aware
                // decision (catches real post-motion stops the old
                // accel/gyro-only signal missed), still overridable
                // whenever the raw model still predicts real speed despite
                // a quiet/near-zero reading.
                //
                // REAL BUG FIX (2026-09-04, bugs.jpeg code review): this
                // used to check `!motionClassification.isCruising` — but
                // MotionStateClassifier.classify() forces isCruising FALSE
                // whenever dwellConfirmedStationary is false, which is
                // exactly the case for StopEventClassifier's SUDDEN_STOP/
                // HARDWARE_CONFIRMED_IDLE fast paths (they exist specifically
                // for stops the accel/gyro dwell check does NOT confirm).
                // So the override could never fire in exactly the contexts
                // it exists to protect, reopening the false-ZUPT bug class
                // StopEventClassifier itself was built to fix. Using
                // predictsRealMotion() here checks the SAME raw-model
                // threshold unconditionally, independent of dwell
                // confirmation — motionClassification.isCruising (still
                // computed above) is untouched and keeps its original
                // meaning for the published UI state below.
                val shouldZupt = classification.shouldApplyZupt &&
                    !motionStateClassifier.predictsRealMotion(rawPredictedVelocityMps)

                val positionState = positionIntegrator.update(
                    dtSeconds = dtSeconds,
                    velocityMps = dampedVelocityMps,
                    headingRad = vehicleHeadingRad,
                    isStationary = shouldZupt,
                )

                _state.value = MlVelocityUiState(
                    predictedVelocityRawMps = rawPredictedVelocityMps,
                    predictedVelocityCorrectedMps = correctedVelocityMps,
                    predictedVelocityDampedMps = dampedVelocityMps,
                    isVelocityOutOfDistribution = guardedVelocity.wasOutOfDistribution,
                    velocityBiasMps = biasCalibrator.currentBiasMps,
                    isAligned = alignment.isAligned,
                    yawOffsetDeg = alignment.yawOffsetRad?.let { Math.toDegrees(it.toDouble()).toFloat() },
                    alignmentSampleCount = alignment.sampleCount,
                    reducedConfidenceDueToRoll = alignment.reducedConfidenceDueToRoll,
                    positionEastM = positionState.positionEastM,
                    positionNorthM = positionState.positionNorthM,
                    isCruising = motionClassification.isCruising,
                    potholeShockDetectedThisTick = potholeShockDetectedThisTick,
                    isAccelerating = longitudinalClassification.isAccelerating,
                    isBraking = longitudinalClassification.isBraking,
                    stationaryContext = classification.context,
                )
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }
}
