package com.sih26168.idr.dr

import com.sih26168.idr.alignment.AlignmentRepository
import com.sih26168.idr.gnss.GnssMode
import com.sih26168.idr.gnss.GnssModeRepository
import com.sih26168.idr.motion.PotholeShockDetector
import com.sih26168.idr.motion.TurningDetector
import com.sih26168.idr.sensors.SampleRate
import com.sih26168.idr.sensors.SensorRepository
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Engineering default, unvalidated against a real outdoor test drive
// (CLAUDE.md Rule 13) — see LowPassFilter.kt's own doc for the general
// filter reasoning; 2.0 Hz is picked to sit comfortably below this
// project's ~10 Hz sample rate's 5 Hz Nyquist limit while still clearing
// typical road/engine vibration frequencies, which run well above a few Hz.
private const val DEFAULT_VIBRATION_FILTER_CUTOFF_HZ = 2.0

/**
 * Slice 3+5 (sensor -> baseline physics velocity/position; dead
 * reckoning state machine + ZUPT + non-holonomic constraint; per
 * CLAUDE.md's slice order): the Android/coroutine glue that connects
 * [SensorRepository]'s raw accel + orientation stream to the pure
 * [BaselinePhysicsIntegrator] math, applies [StationaryDetector]'s ZUPT
 * correction and [NonHolonomicConstraint]'s lateral-velocity suppression
 * each tick, gates the position "odometer" on [GnssModeRepository]'s
 * mode, and republishes the running WORLD-frame position/velocity
 * estimate as its own StateFlow.
 *
 * Deliberately a separate class from SensorRepository (CLAUDE.md
 * Rule 5 — one clearly stated responsibility per file): SensorRepository
 * owns raw sensor IO only; this owns turning that raw stream into a
 * corrected physics position estimate. This class itself still does no
 * ML and no GNSS/DR position fusion — TRANSITION/REACQUISITION blending
 * (Slice 7) lives in fusion/PositionFusion.kt + fusion/StateEstimator.kt,
 * which read this repository's position as one of two possible DR
 * inputs rather than this class doing any blending itself — see
 * [BaselinePhysicsIntegrator]'s doc for what remains uncorrected in the
 * physics estimate specifically.
 *
 * Motion-classification stand-in: [potholeShockDetector] discounts
 * East/North linear accel on a detected vertical shock before it reaches
 * [integrator], per PRD.md Section 14's `Pothole` effect — same
 * detector/logic [com.sih26168.idr.ml.MlVelocityRepository] applies to
 * its own feature path, applied here too since discounting one sample
 * doesn't touch the FROZEN Slice 3 baseline measurement already reported
 * in docs/PROJECT_MAP.md (that number came from [BaselinePhysicsIntegrator.update]
 * alone, with no corrections at all) — it only affects this LIVE display,
 * exactly like ZUPT/non-holonomic already do. Not separately surfaced in
 * the UI here (see MainActivity's ML section instead) — applying the same
 * discount twice on screen for what is fundamentally one event would be
 * redundant.
 *
 * UPDATE (PRD.md Section 15's phone-to-vehicle YAW alignment, previously
 * ML-feature-path-only): [alignmentRepository] is the SAME shared
 * [com.sih26168.idr.alignment.AlignmentRepository]
 * [com.sih26168.idr.ml.MlVelocityRepository] also reads. Its yaw offset
 * now corrects the heading passed to [NonHolonomicConstraint] below —
 * closing the documented gap where that constraint used raw device
 * azimuth as its vehicle-heading proxy unconditionally, even after real
 * alignment had converged for the ML path. [TurningDetector] deliberately
 * still uses RAW device azimuth (not alignment-corrected) — yaw RATE is
 * unaffected by a constant offset (it cancels out between two consecutive
 * readings of the same frame), so correcting it here would add complexity
 * with no behavioral effect.
 *
 * UPDATE (2026-08-30, PRD.md Section 11's vibration filter — capability
 * "AI Speed & Vibration Filter"): [accelEastFilter]/[accelNorthFilter]/
 * [accelUpFilter]/[gyroXFilter]/[gyroYFilter]/[gyroZFilter] low-pass
 * (`LowPassFilter.kt`) the WORLD-frame linear acceleration and raw gyro
 * components before they reach [integrator] and the ZUPT magnitude
 * calculation below — see `LowPassFilter.kt`'s own doc for why this is
 * scoped to this physics path only, not `ml/MlVelocityRepository.kt`'s
 * feature-extraction input (ONNX model train/inference parity, CLAUDE.md
 * Rule 20). Deliberately applied AFTER the pothole discount, not before —
 * [potholeShockDetector] needs to see the RAW, unfiltered vertical
 * acceleration spike to detect it at all; low-pass filtering that signal
 * FIRST would itself partially smooth away the very transient event this
 * detector exists to catch.
 */
class BaselineDeadReckoningRepository(
    private val sensorRepository: SensorRepository,
    private val gnssModeRepository: GnssModeRepository,
    private val scope: CoroutineScope,
    private val alignmentRepository: AlignmentRepository,
    private val stationaryDetector: StationaryDetector = StationaryDetector(),
    private val potholeShockDetector: PotholeShockDetector = PotholeShockDetector(),
    private val turningDetector: TurningDetector = TurningDetector(),
    private val vibrationFilterCutoffHz: Double = DEFAULT_VIBRATION_FILTER_CUTOFF_HZ,
) {
    private val integrator = BaselinePhysicsIntegrator()
    private val accelEastFilter = LowPassFilter(vibrationFilterCutoffHz)
    private val accelNorthFilter = LowPassFilter(vibrationFilterCutoffHz)
    private val accelUpFilter = LowPassFilter(vibrationFilterCutoffHz)
    private val gyroXFilter = LowPassFilter(vibrationFilterCutoffHz)
    private val gyroYFilter = LowPassFilter(vibrationFilterCutoffHz)
    private val gyroZFilter = LowPassFilter(vibrationFilterCutoffHz)

    // 2026-08-26, user-requested Walking mode (explicit override of PRD.md
    // Section 6's Car/Motorcycle-only scope): NonHolonomicConstraint's
    // "can't move sideways relative to heading" assumption is a VEHICLE
    // assumption only — a walking pedestrian can strafe/turn on the spot
    // in a way a car physically cannot, so applying it while walking would
    // suppress real lateral motion as if it were sensor noise. Plain
    // mutable field (not a StateFlow) because it's set once per user
    // selection from the UI thread, read every tick from the same
    // coroutine this class already collects on — no cross-thread hazard.
    // ZUPT (StationaryDetector) is UNCHANGED for both modes: a stationary
    // pedestrian should still get zero-velocity-corrected same as a
    // stationary vehicle.
    var walkingModeEnabled: Boolean = false

    // null means "no accel sample processed yet this run" — deliberately NOT
    // a 0L sentinel. A prior version used 0L and, on the very first sample,
    // computed dt as (accel.timestampNs - 0L) = the device's entire boot-time
    // uptime in nanoseconds (thousands of seconds), which the integrator then
    // multiplied straight into velocity/position on a single tick — observed
    // on-device as an immediate jump to tens of thousands of m/s. Guards
    // against reprocessing the same accel sample twice too — SensorRepository's
    // StateFlow re-emits on every gyro/orientation update too, not just accel.
    private var lastProcessedAccelTimestampNs: Long? = null

    private val _state = MutableStateFlow(DeadReckoningState())
    val state: StateFlow<DeadReckoningState> = _state.asStateFlow()

    private var collectJob: Job? = null

    fun start() {
        integrator.reset()
        turningDetector.reset()
        accelEastFilter.reset()
        accelNorthFilter.reset()
        accelUpFilter.reset()
        gyroXFilter.reset()
        gyroYFilter.reset()
        gyroZFilter.reset()
        lastProcessedAccelTimestampNs = null
        _state.value = DeadReckoningState()

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

                // While GNSS is trustworthy, keep the "position since drift
                // start" odometer at zero — the moment GNSS is lost, the DR
                // readout then represents distance traveled purely during
                // THIS outage, which is what PRD.md Section 28 wants
                // measured (drift over a GNSS-denied segment), not
                // accumulated drift since app launch. This does NOT fuse
                // GNSS and DR positions together — that blending is
                // Slice 7 (Fusion / re-alignment on GNSS reacquisition).
                if (gnssModeRepository.state.value.mode == GnssMode.GNSS_AIDED) {
                    integrator.reset()
                }

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

                // PRD.md Section 14's Pothole effect: discount East/North
                // (forward-ish) accel on a detected vertical shock so it
                // doesn't get misread as forward acceleration — the Up
                // component that triggered detection is left untouched.
                // Checked on the RAW (pre-filter) Up component — see this
                // class's own doc for why shock detection must see the
                // signal before the low-pass filter below smooths it.
                val potholeShockDetectedThisTick = potholeShockDetector.isShock(linearAccel[2])
                val linearAccelEastMps2 = if (potholeShockDetectedThisTick) 0.0 else linearAccel[0].toDouble()
                val linearAccelNorthMps2 = if (potholeShockDetectedThisTick) 0.0 else linearAccel[1].toDouble()

                // Raw (pre-filter) ZUPT input magnitude — published on
                // DeadReckoningState purely for offline analysis (see that
                // class's own doc), computed from the SAME east/north/up +
                // gyro components the filtered version below uses, just
                // before LowPassFilter.filter() runs on them. Not used by
                // StationaryDetector itself, which still gates on filtered.
                val rawLinearAccelMagnitudeMps2 = sqrt(
                    linearAccelEastMps2 * linearAccelEastMps2 +
                        linearAccelNorthMps2 * linearAccelNorthMps2 +
                        linearAccel[2] * linearAccel[2],
                )
                val rawGyroMagnitudeRadPerSec = sqrt(
                    gyro.xRadPerSec * gyro.xRadPerSec +
                        gyro.yRadPerSec * gyro.yRadPerSec +
                        gyro.zRadPerSec * gyro.zRadPerSec,
                ).toDouble()

                // PRD.md Section 11's low-pass vibration filter — smooths
                // everyday road/engine-vibration noise out of the signal
                // BEFORE it reaches the double-integrator and the ZUPT
                // magnitude check below. See this class's own doc and
                // LowPassFilter.kt for the full reasoning (physics-path-
                // only, applied after the pothole discount above).
                val filteredAccelEastMps2 = accelEastFilter.filter(linearAccelEastMps2, dtSeconds)
                val filteredAccelNorthMps2 = accelNorthFilter.filter(linearAccelNorthMps2, dtSeconds)
                val filteredAccelUpMps2 = accelUpFilter.filter(linearAccel[2].toDouble(), dtSeconds)
                val filteredGyroXRadPerSec = gyroXFilter.filter(gyro.xRadPerSec.toDouble(), dtSeconds)
                val filteredGyroYRadPerSec = gyroYFilter.filter(gyro.yRadPerSec.toDouble(), dtSeconds)
                val filteredGyroZRadPerSec = gyroZFilter.filter(gyro.zRadPerSec.toDouble(), dtSeconds)

                integrator.update(
                    dtSeconds = dtSeconds,
                    linearAccelEastMps2 = filteredAccelEastMps2,
                    linearAccelNorthMps2 = filteredAccelNorthMps2,
                )

                // ZUPT / non-holonomic correction — see StationaryDetector
                // and NonHolonomicConstraint docs for the honest limits of
                // each (constant-velocity motion can look "stationary").
                // Computed from the FILTERED components above, not raw —
                // see DeadReckoningState's own doc for why that's now a
                // disclosed, real change to what these two fields mean.
                val linearAccelMagnitudeMps2 = sqrt(
                    filteredAccelEastMps2 * filteredAccelEastMps2 +
                        filteredAccelNorthMps2 * filteredAccelNorthMps2 +
                        filteredAccelUpMps2 * filteredAccelUpMps2,
                )
                val gyroMagnitudeRadPerSec = sqrt(
                    filteredGyroXRadPerSec * filteredGyroXRadPerSec +
                        filteredGyroYRadPerSec * filteredGyroYRadPerSec +
                        filteredGyroZRadPerSec * filteredGyroZRadPerSec,
                )
                // Boot-time ms (not wall-clock) — this only needs a
                // relative dwell duration, same clock family as the
                // accel/gyro timestamps it's derived from (CLAUDE.md Rule 9/14).
                val nowBootTimeMs = accel.timestampNs / 1_000_000L
                val stationary = stationaryDetector.evaluate(
                    nowBootTimeMs,
                    linearAccelMagnitudeMps2.toFloat(),
                    gyroMagnitudeRadPerSec.toFloat(),
                )

                // PRD.md Section 20's `Turning` exemption: computed every
                // tick (not just when about to gate on it) so TurningDetector's
                // internal previous-azimuth/timestamp tracking never misses a
                // sample and reports a falsely huge yaw rate on the next tick
                // it IS consulted — same reasoning YawRate/AlignmentEstimator
                // already document for their own every-tick evaluate() calls.
                val turning = turningDetector.evaluate(orientation.timestampNs, orientation.azimuthRad)

                if (stationary) {
                    integrator.overrideVelocity(0.0, 0.0)
                } else if (!walkingModeEnabled && !turning) {
                    // Round 2 (2026-08-28): use the alignment-corrected
                    // vehicle heading, not raw device azimuth, for the
                    // non-holonomic projection — same correction the ML
                    // path (MlVelocityRepository) already applied in
                    // Round 1. Round 1 left this path unaligned, which let
                    // the physics-derived heading (used for the map's
                    // heading-up rotation, ui/screens/MapScreen.kt) drift
                    // far enough from true heading to flip the map ~180
                    // degrees on GNSS reacquisition — see
                    // alignment/AlignmentRepository.kt's doc for the full
                    // finding. Falls back to raw azimuth (yaw offset 0)
                    // before alignment converges — same accepted
                    // approximation the ML path already documents. Also
                    // skipped during a genuine turn (PRD.md Section 20's
                    // Turning exemption) — real lateral velocity during a
                    // turn isn't sensor noise the non-holonomic constraint
                    // should suppress.
                    val vehicleHeadingRad = orientation.azimuthRad -
                        (alignmentRepository.state.value.yawOffsetRad ?: 0f)
                    val preConstraint = integrator.currentState()
                    val (forwardEastMps, forwardNorthMps) = NonHolonomicConstraint.suppressLateralVelocity(
                        velocityEastMps = preConstraint.velocityEastMps,
                        velocityNorthMps = preConstraint.velocityNorthMps,
                        headingRad = vehicleHeadingRad,
                    )
                    integrator.overrideVelocity(forwardEastMps, forwardNorthMps)
                }
                // else: Walking mode, or a genuine turn in progress — leave
                // the integrator's own double-integrated velocity untouched.
                // Walking mode's lateral motion isn't sensor noise (a
                // pedestrian can strafe); a turn's real lateral velocity
                // isn't either (PRD.md Section 20) — only straight-line
                // vehicle motion gets the non-holonomic correction.

                // Publishes the SAME ZUPT/Turning inputs/decisions already
                // computed above (not recomputed) — see DeadReckoningState's
                // own doc for why (capture/DriveDataLogger.kt, 2026-08-29).
                _state.value = integrator.currentState().copy(
                    linearAccelMagnitudeMps2 = linearAccelMagnitudeMps2,
                    gyroMagnitudeRadPerSec = gyroMagnitudeRadPerSec,
                    isStationary = stationary,
                    isTurning = turning,
                    rawLinearAccelMagnitudeMps2 = rawLinearAccelMagnitudeMps2,
                    rawGyroMagnitudeRadPerSec = rawGyroMagnitudeRadPerSec,
                )
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }
}
