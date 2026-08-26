package com.sih26168.idr.fusion

import android.util.Log
import com.sih26168.idr.dr.BaselineDeadReckoningRepository
import com.sih26168.idr.gnss.GnssMode
import com.sih26168.idr.gnss.GnssModeRepository
import com.sih26168.idr.gnss.GnssQuality
import com.sih26168.idr.ml.MlVelocityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "StateEstimator"

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
    /**
     * Every REACQUISITION-instant drift measurement this run, oldest
     * first — same values [driftSummary] snapshots (it always mirrors
     * this list's last entry), kept as a running log instead of a single
     * overwritten field so `ui/screens/HistoryScreen.kt` (Slice 8b) can
     * show a REAL trip/outage history (CLAUDE.md Rule 13) rather than
     * fabricating one, matching the Figma "Timeline" screen's list
     * concept with actually-measured data instead of its placeholder
     * mileage chart.
     */
    val driftHistory: List<DriftSummaryResult> = emptyList(),
    /**
     * The real-world lat/lon this run's local (0,0) East/North origin
     * corresponds to — the SAME point [outageAnchorLatDeg]/
     * [outageAnchorLonDeg] track internally, continuously overwritten
     * while GNSS_AIDED. Exposed so `ui/map/StreetMapView.kt` can invert
     * [fusedEastM]/[fusedNorthM] back to real lat/lon via
     * [GeoProjection.toLatLon] and place a marker on real street tiles.
     * Null until the first-ever GNSS fix this run (no anchor to convert
     * against yet).
     */
    val anchorLatDeg: Double? = null,
    val anchorLonDeg: Double? = null,
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
    private val driftHistory = mutableListOf<DriftSummaryResult>()

    private var collectJob: Job? = null

    fun start() {
        positionFusion.reset()
        outageAnchorLatDeg = null
        outageAnchorLonDeg = null
        lastAidedAtMs = null
        previousMode = null
        lastDriftSummary = null
        driftHistory.clear()
        _state.value = FusedPositionUiState()

        collectJob = scope.launch {
            gnssModeRepository.state.collect { gnssState ->
                val fix = gnssState.latestFix ?: return@collect
                val nowMs = System.currentTimeMillis()

                // REAL BUG (2026-08-26, user report: "stationary indoors,
                // still shows tens of kilometres of drift"): this branch used
                // to trust `gnssState.mode == GNSS_AIDED` alone as proof the
                // fix was accurate. It isn't -- `mode` defaults to GNSS_AIDED
                // at cold start (before the first GnssOutageDetector.evaluate()
                // tick ever runs) AND stays GNSS_AIDED for up to
                // `outageEnterDwellMs` (2s) after quality actually degrades,
                // by design (CLAUDE.md Rule 16 hysteresis -- a single bad
                // sample can't flip the mode). A stale/inaccurate fix landing
                // in either window (e.g. Play Services' Fused Location
                // Provider handing back a cached fix from a previous
                // location, or a low-quality Wi-Fi-based indoor fix) was
                // silently accepted as the permanent outage anchor with no
                // independent accuracy/age check -- every later drift number
                // measured against that wrong anchor was then nonsense.
                // Re-checking GnssQuality.isGood() here (using the SAME
                // fixAgeMs/accuracyM this fix was already classified with
                // upstream) closes that gap: only a fix that is ACTUALLY good
                // right now can move the anchor.
                if (gnssState.mode == GnssMode.GNSS_AIDED && GnssQuality.isGood(gnssState.fixAgeMs, fix.accuracyM)) {
                    outageAnchorLatDeg = fix.latitudeDeg
                    outageAnchorLonDeg = fix.longitudeDeg
                    lastAidedAtMs = nowMs
                } else if (outageAnchorLatDeg == null) {
                    // REAL BUG (2026-08-26, user report: "no feature to track
                    // my position on the map"): without ANY anchor,
                    // ui/screens/MapScreen.kt has no lat/lon to project the
                    // fused DR position onto, so it draws NO marker at all --
                    // even while this class is correctly fusing real
                    // physics/ML position underneath. That happens whenever
                    // the FIRST fix this run isn't good enough to pass
                    // GnssQuality (e.g. MapScreen.kt's own documented real
                    // case: an 8.8s-old/29.7m-accuracy indoor fix near a
                    // window) -- mode never reaches GNSS_AIDED, so the strict
                    // branch above never fires. A PROVISIONAL anchor from the
                    // very first fix ever seen (any quality) fixes this: it's
                    // the same small, bounded approximation
                    // BaselineDeadReckoningRepository's own integrator already
                    // makes by not resetting until GNSS_AIDED either (its
                    // zero-point is really "wherever the phone was at app
                    // launch," which is a few seconds/meters from this first
                    // fix, not a new source of error). Once a fix that is
                    // BOTH in GNSS_AIDED mode AND passes GnssQuality.isGood()
                    // arrives, the strict branch above overwrites this with
                    // the accurate point -- a one-time visual snap-to-fix,
                    // same as any nav app's behavior when GPS lock improves,
                    // not silently kept wrong forever.
                    outageAnchorLatDeg = fix.latitudeDeg
                    outageAnchorLonDeg = fix.longitudeDeg
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
                    driftHistory.add(lastDriftSummary!!)
                    // Logged (not just shown in ui/screens/HistoryScreen.kt)
                    // so a `logcat` capture running during a real demo/test
                    // drive durably records the actual measured numbers
                    // (PRD.md Section 28/35) -- the in-app History list is
                    // in-memory only and is lost the moment the app process
                    // dies, which is too fragile to be the only record of a
                    // real measured result.
                    Log.i(
                        TAG,
                        "Outage #${driftHistory.size} drift: ${lastDriftSummary!!.driftMeters}m " +
                            "over ${lastDriftSummary!!.distanceTravelledMeters}m travelled",
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
                    driftHistory = driftHistory.toList(),
                    anchorLatDeg = outageAnchorLatDeg,
                    anchorLonDeg = outageAnchorLonDeg,
                )
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }
}
