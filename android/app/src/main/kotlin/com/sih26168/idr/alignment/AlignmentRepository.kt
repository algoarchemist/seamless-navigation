package com.sih26168.idr.alignment

import android.util.Log
import com.sih26168.idr.gnss.GnssModeRepository
import com.sih26168.idr.motion.PhoneMovedDetector
import com.sih26168.idr.sensors.SensorRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "AlignmentRepository"

/** Published phone-to-vehicle alignment state — see [AlignmentEstimator] for the underlying math. */
data class AlignmentUiState(
    val yawOffsetRad: Float? = null,
    val sampleCount: Int = 0,
    val isAligned: Boolean = false,
    /** Mounting roll/pitch baseline (PRD.md Section 15) — see [AlignmentEstimator]'s doc. */
    val rollOffsetRad: Float? = null,
    val pitchOffsetRad: Float? = null,
    val pitchRollSampleCount: Int = 0,
    val isPitchRollAligned: Boolean = false,
    /** PRD.md Section 15's motorcycle-lean flag — true while current roll deviates from the established baseline beyond AlignmentEstimator.DEFAULT_MAX_ROLL_EXCURSION_RAD. */
    val reducedConfidenceDueToRoll: Boolean = false,
)

/**
 * The Android/coroutine glue that turns the pure [AlignmentEstimator]
 * (PRD.md Section 15's phone-to-vehicle yaw alignment) into a live,
 * SHARED estimate — read by both `dr/BaselineDeadReckoningRepository.kt`
 * (physics path) and `ml/MlVelocityRepository.kt` (ML path).
 *
 * PREVIOUSLY, this estimation ran privately inside
 * `ml/MlVelocityRepository.kt` alone. Two real consequences of that: (a)
 * it stopped existing entirely whenever the ONNX model failed to load
 * (`MainActivity` only constructs `MlVelocityRepository` on a successful
 * model load), so alignment tracking silently depended on ML working even
 * though it has nothing to do with ML inference; (b) the physics/DR
 * position path had no access to it at all, so
 * `dr/NonHolonomicConstraint.kt` used raw device azimuth as its vehicle-
 * heading proxy unconditionally, never the alignment-corrected heading
 * `ml/MlVelocityRepository.kt` already computed for its own feature path
 * (`vehicleHeadingRad = azimuthRad - yawOffsetRad`). Extracting this into
 * its own repository — driven only by [sensorRepository] (orientation)
 * and [gnssModeRepository] (GNSS bearing/speed), no ML dependency at all —
 * fixes both: one [AlignmentEstimator] instance, one real answer to
 * "what's the current yaw offset," read identically by whichever DR path
 * (physics, ML, or both) happens to be running.
 *
 * UPDATE (2026-09-02): also feeds device pitch/roll through so
 * [AlignmentEstimator] can establish the phone's mounting roll/pitch
 * baseline and flag PRD.md Section 15's motorcycle-lean carve-out
 * (`reducedConfidenceDueToRoll`) — see that class's own doc for the full
 * reasoning. Republished here alongside the yaw fields for any consumer
 * (currently `ml/MlVelocityRepository.kt` -> `StatusOverlayContent.kt`)
 * to surface as a confidence indicator (PRD.md Section 31).
 *
 * Also wires PRD.md Section 15's "Ongoing validation: the Motion
 * Classifier's `Phone Moved` output triggers re-initialization" —
 * [phoneMovedDetector] (`motion/PhoneMovedDetector.kt`'s deterministic
 * pitch/roll-change stand-in; no labeled classifier data exists yet for
 * PRD.md Section 14's real `Phone Moved` class) resets [alignmentEstimator]
 * automatically on a detected remount, in addition to the existing manual
 * "recalibrate" button (`MainActivity`/`ui/screens/StatusOverlayContent.kt`,
 * now calling [reset] on this class directly instead of reaching through
 * `MlVelocityRepository`).
 */
class AlignmentRepository(
    private val sensorRepository: SensorRepository,
    private val gnssModeRepository: GnssModeRepository,
    private val scope: CoroutineScope,
    private val alignmentEstimator: AlignmentEstimator = AlignmentEstimator(),
    private val phoneMovedDetector: PhoneMovedDetector = PhoneMovedDetector(),
) {
    private val _state = MutableStateFlow(AlignmentUiState())
    val state: StateFlow<AlignmentUiState> = _state.asStateFlow()

    private var collectJob: Job? = null

    fun start() {
        alignmentEstimator.reset()
        phoneMovedDetector.reset()
        _state.value = AlignmentUiState()

        collectJob = scope.launch {
            sensorRepository.state.collect { sensorUiState ->
                val orientation = sensorUiState.latestOrientation ?: return@collect
                val fix = gnssModeRepository.state.value.latestFix
                // Boot-time ms (not wall-clock) — same relative-duration-only
                // clock convention dr/StationaryDetector.kt's own caller
                // already uses (CLAUDE.md Rule 9/14).
                val nowMs = orientation.timestampNs / 1_000_000L

                val phoneMoved = phoneMovedDetector.evaluate(nowMs, orientation.pitchRad, orientation.rollRad)
                if (phoneMoved) {
                    // Logged (not just reflected via isAligned dropping back
                    // to false next tick) so a real demo/test-drive logcat
                    // capture durably records WHY re-alignment restarted —
                    // same "log every real state transition" precedent
                    // gnss/GnssOutageDetector.kt's own transitions already
                    // establish (CLAUDE.md Rule 17), applied here even
                    // though this isn't that literal state machine.
                    Log.i(TAG, "Phone Moved detected at ${nowMs}ms (pitch/roll changed) — resetting alignment")
                    alignmentEstimator.reset()
                }

                val alignment = alignmentEstimator.evaluate(
                    nowNs = orientation.timestampNs,
                    azimuthRad = orientation.azimuthRad,
                    gnssBearingDeg = fix?.bearingDeg,
                    gnssSpeedMps = fix?.speedMps,
                    pitchRad = orientation.pitchRad,
                    rollRad = orientation.rollRad,
                )

                _state.value = AlignmentUiState(
                    yawOffsetRad = alignment.yawOffsetRad,
                    sampleCount = alignment.sampleCount,
                    isAligned = alignment.isAligned,
                    rollOffsetRad = alignment.rollOffsetRad,
                    pitchOffsetRad = alignment.pitchOffsetRad,
                    pitchRollSampleCount = alignment.pitchRollSampleCount,
                    isPitchRollAligned = alignment.isPitchRollAligned,
                    reducedConfidenceDueToRoll = alignment.reducedConfidenceDueToRoll,
                )
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    /**
     * PRD.md Section 15's "Ongoing validation... flag for recalibration" /
     * Section 31/32's manual "hold phone flat, tap to calibrate" fallback —
     * the SAME reset [phoneMovedDetector] now also triggers automatically.
     * Resets [phoneMovedDetector] too, so a real deviation already
     * mid-flight when the user manually recalibrates doesn't immediately
     * re-fire the automatic path a moment later against a stale reference.
     */
    fun reset() {
        alignmentEstimator.reset()
        phoneMovedDetector.reset()
    }
}
