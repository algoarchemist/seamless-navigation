package com.sih26168.idr.fusion

import com.sih26168.idr.dr.BaselineDeadReckoningRepository
import com.sih26168.idr.gnss.GnssMode
import com.sih26168.idr.gnss.GnssModeRepository
import com.sih26168.idr.ml.MlVelocityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which DR source actually fed the fused position on the most recent tick. */
enum class DrSource { PHYSICS, ML }

data class FusedPositionUiState(
    val fusedEastM: Double = 0.0,
    val fusedNorthM: Double = 0.0,
    val drSourceUsed: DrSource = DrSource.PHYSICS,
    /** Float.MAX_VALUE before the first-ever GNSS_AIDED tick this run (same sentinel convention as GnssModeUiState.fixAgeMs). */
    val secondsSinceLastGnssAided: Float = Float.MAX_VALUE,
    /**
     * PRD.md Section 30 WOW-factor #4 — a REAL measured drift number,
     * snapshotted once at the instant REACQUISITION begins (see
     * [StateEstimator]'s doc). Null until an outage has actually ended
     * once this run; stays at its last value after that (the UI decides
     * whether/when to stop showing it, this class doesn't auto-clear it).
     */
    val driftSummary: DriftSummaryResult? = null,
)

/**
 * Slice 7 (Fusion / re-alignment on GNSS reacquisition, per CLAUDE.md's
 * slice order): the Android/coroutine glue that turns
 * [fusion.PositionFusion]'s pure per-mode blending logic into a live,
 * on-device fused position estimate. This is the file
 * `docs/PROJECT_MAP.md` previously listed as `PLANNED`.
 *
 * Drives off [gnssModeRepository]'s own StateFlow (its ~5Hz cadence is
 * plenty for a ~1s REACQUISITION blend window) and reads
 * [deadReckoningRepository]/[mlVelocityRepository]'s latest `.value`
 * synchronously inside that collection — the SAME "driving flow +
 * synchronous sibling reads" pattern [BaselineDeadReckoningRepository] and
 * [MlVelocityRepository] already use to read `gnssModeRepository.state.value`,
 * not a new style introduced here.
 *
 * DR source selection (per the discussed PRD.md Section 16 interpretation
 * "v[t] = VelocityModel(...) (or physics fallback)"): ML position is used
 * once [com.sih26168.idr.ml.MlVelocityUiState.isAligned] is true, else the
 * physics position is used. [mlVelocityRepository] is nullable so this
 * still works (physics-only) if the ONNX model failed to load — same
 * resilience pattern [com.sih26168.idr.MainActivity] already applies to
 * the ML half generally.
 *
 * Reacquisition needs the newly-reacquired GNSS fix expressed in the SAME
 * local frame the DR delta has been accumulating in since the outage
 * began. [outageAnchorLatDeg]/[outageAnchorLonDeg] capture exactly that
 * frame's origin: continuously overwritten with the latest fix's lat/lon
 * while `GNSS_AIDED` — the SAME trigger boundary
 * [BaselineDeadReckoningRepository]/[MlVelocityRepository] already reset
 * their own position integrators on, so the anchor and the DR-zero-point
 * are always the same real-world location by construction.
 *
 * Slice 8 addition: [driftSummary] via [fusion.DriftSummary] — detects
 * the SAME "mode just changed" pattern [PositionFusion] already uses
 * internally, snapshotting the DR position and the newly-reacquired
 * GNSS position the INSTANT mode transitions into `REACQUISITION` (the
 * only moment both a "DR guess" and a "GNSS truth" exist side by side in
 * the same local frame). This is a REAL measured number from data
 * already flowing through this class each tick, not a fabricated one
 * (CLAUDE.md Rule 13).
 */
class StateEstimator(
    private val gnssModeRepository: GnssModeRepository,
    private val deadReckoningRepository: BaselineDeadReckoningRepository,
    private val mlVelocityRepository: MlVelocityRepository?,
    private val scope: CoroutineScope,
    private val positionFusion: PositionFusion = PositionFusion(),
) {
    private val _state = MutableStateFlow(FusedPositionUiState())
    val state: StateFlow<FusedPositionUiState> = _state.asStateFlow()

    private var outageAnchorLatDeg: Double? = null
    private var outageAnchorLonDeg: Double? = null
    private var lastAidedAtMs: Long? = null
    private var previousMode: GnssMode? = null
    private var lastDriftSummary: DriftSummaryResult? = null

    private var collectJob: Job? = null

    fun start() {
        positionFusion.reset()
        outageAnchorLatDeg = null
        outageAnchorLonDeg = null
        lastAidedAtMs = null
        previousMode = null
        lastDriftSummary = null
        _state.value = FusedPositionUiState()

        collectJob = scope.launch {
            gnssModeRepository.state.collect { gnssState ->
                val fix = gnssState.latestFix ?: return@collect
                val nowMs = System.currentTimeMillis()

                if (gnssState.mode == GnssMode.GNSS_AIDED) {
                    outageAnchorLatDeg = fix.latitudeDeg
                    outageAnchorLonDeg = fix.longitudeDeg
                    lastAidedAtMs = nowMs
                }

                val mlState = mlVelocityRepository?.state?.value
                val useMl = mlState?.isAligned == true
                val physicsState = deadReckoningRepository.state.value
                val drEastM = if (useMl) mlState!!.positionEastM else physicsState.positionEastM
                val drNorthM = if (useMl) mlState!!.positionNorthM else physicsState.positionNorthM

                var newFixEastM: Double? = null
                var newFixNorthM: Double? = null
                if (gnssState.mode == GnssMode.REACQUISITION) {
                    val anchorLat = outageAnchorLatDeg
                    val anchorLon = outageAnchorLonDeg
                    if (anchorLat != null && anchorLon != null) {
                        val (eastM, northM) = GeoProjection.toLocalMeters(
                            latDeg = fix.latitudeDeg,
                            lonDeg = fix.longitudeDeg,
                            refLatDeg = anchorLat,
                            refLonDeg = anchorLon,
                        )
                        newFixEastM = eastM
                        newFixNorthM = northM
                    }
                }

                // The instant mode ENTERS REACQUISITION is the only moment a
                // DR guess and a GNSS truth both exist in the same local
                // frame — snapshot the drift right here, before PositionFusion
                // starts blending the two together.
                if (previousMode != GnssMode.REACQUISITION &&
                    gnssState.mode == GnssMode.REACQUISITION &&
                    newFixEastM != null &&
                    newFixNorthM != null
                ) {
                    lastDriftSummary = DriftSummary.compute(
                        drEastM = drEastM,
                        drNorthM = drNorthM,
                        gnssEastM = newFixEastM,
                        gnssNorthM = newFixNorthM,
                    )
                }
                previousMode = gnssState.mode

                val fused = positionFusion.update(
                    nowMs = nowMs,
                    mode = gnssState.mode,
                    drEastM = drEastM,
                    drNorthM = drNorthM,
                    newFixEastM = newFixEastM,
                    newFixNorthM = newFixNorthM,
                )

                _state.value = FusedPositionUiState(
                    fusedEastM = fused.eastM,
                    fusedNorthM = fused.northM,
                    drSourceUsed = if (useMl) DrSource.ML else DrSource.PHYSICS,
                    secondsSinceLastGnssAided = lastAidedAtMs?.let { (nowMs - it) / 1000f } ?: Float.MAX_VALUE,
                    driftSummary = lastDriftSummary,
                )
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }
}
