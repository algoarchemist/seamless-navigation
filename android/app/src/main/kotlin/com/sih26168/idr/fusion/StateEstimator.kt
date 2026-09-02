package com.sih26168.idr.fusion

import android.util.Log
import com.sih26168.idr.dr.BaselineDeadReckoningRepository
import com.sih26168.idr.gnss.GnssMode
import com.sih26168.idr.gnss.GnssModeRepository
import com.sih26168.idr.gnss.GnssQuality
import com.sih26168.idr.map.MapConstraint
import com.sih26168.idr.ml.MlVelocityRepository
import com.sih26168.idr.ml.ReacquisitionDriftModel
import kotlin.math.atan2
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "StateEstimator"

// Engineering default, unvalidated against a real outdoor test drive
// (CLAUDE.md Rule 13) — matches ui/screens/MapScreen.kt's own identical
// floor for its heading-up map rotation fallback (same underlying signal,
// physics DR velocity vector), kept as a literal here rather than a shared
// constant to avoid a new cross-module dependency for one number.
private const val MIN_SPEED_FOR_ROAD_SNAP_HEADING_MPS = 0.5

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
    /**
     * (Round 2 addition, 2026-08-28 — PRD.md FR8/Section 18) The fused
     * compass heading (degrees, 0-360) for "heading-up" map rotation
     * (`ui/map/StreetMapView.kt`). Round 1 had `ui/screens/MapScreen.kt`
     * compute this itself with a hard cutover between GNSS bearing and a
     * DR-derived bearing at the GNSS_AIDED/DEAD_RECKONING boundary — see
     * [HeadingFusion]'s doc for why that produced a visible map flip on
     * reacquisition. This field is now the single source of truth,
     * blended the same way [fusedEastM]/[fusedNorthM] already are.
     */
    val fusedHeadingDeg: Float = 0f,
    /**
     * PRD.md Section 19's MVP map constraint (`map/MapConstraint.kt`):
     * true if THIS tick's [fusedEastM]/[fusedNorthM] was snapped onto the
     * active route's road geometry. Always false with no active route, no
     * GNSS anchor yet, or while GNSS_AIDED (a real GPS fix doesn't need
     * road-snap correction) — see [StateEstimator.setActiveRouteGeometry].
     */
    val roadSnapped: Boolean = false,
    /** Distance from the pre-snap fused position to the road, meters. Null unless [roadSnapped]. */
    val distanceToRoadM: Double? = null,
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
 *
 * Later addition: PRD.md Section 19's MVP map constraint
 * ([map.MapConstraint]) is applied to [fused] here, AFTER
 * [PositionFusion.update] but before publishing — the road-snap is a
 * per-tick correction to this class's OUTPUT position only, same
 * architectural boundary [PositionFusion]'s own freeze/interpolate
 * corrections already use; it never writes back into
 * [deadReckoningRepository]/[mlVelocityRepository]'s own accumulated
 * state. [setActiveRouteGeometry] is a plain mutable field set once from
 * the UI thread (`ui/screens/MapScreen.kt`, whenever its active route
 * changes) and read every tick from this same collection coroutine — the
 * SAME "settable field read on the collecting coroutine" pattern
 * [BaselineDeadReckoningRepository.walkingModeEnabled] already
 * establishes, not a new style introduced here.
 *
 * Later addition: PRD.md Section 17's "AI-based" GNSS+INS fusion half
 * (previously entirely classical — STATUS_AND_ROADMAP.md's own flagged
 * decision point). [reacquisitionDriftModel] predicts EXPECTED along-
 * track DR drift (meters) the INSTANT REACQUISITION begins, from
 * [outageSpeedStats]' running mean/std of DR speed accumulated over the
 * outage plus its real elapsed duration — [PositionFusion.blendDurationForDriftMs]
 * turns that into an adaptive REACQUISITION blend duration, set via
 * [PositionFusion.setReacquisitionBlendMs] BEFORE [positionFusion]'s own
 * `update()` runs this same tick. [reacquisitionDriftModel] is nullable,
 * same resilience pattern [mlVelocityRepository] already establishes — if
 * the ONNX asset fails to load, [positionFusion] simply keeps its
 * [PositionFusion.DEFAULT_REACQUISITION_BLEND_MS] fixed value, the exact
 * previous classical behavior.
 */
class StateEstimator(
    private val gnssModeRepository: GnssModeRepository,
    private val deadReckoningRepository: BaselineDeadReckoningRepository,
    private val mlVelocityRepository: MlVelocityRepository?,
    private val scope: CoroutineScope,
    private val positionFusion: PositionFusion = PositionFusion(),
    private val headingFusion: HeadingFusion = HeadingFusion(),
    private val reacquisitionDriftModel: ReacquisitionDriftModel? = null,
) {
    private val _state = MutableStateFlow(FusedPositionUiState())
    val state: StateFlow<FusedPositionUiState> = _state.asStateFlow()

    private var outageAnchorLatDeg: Double? = null
    private var outageAnchorLonDeg: Double? = null
    // Accuracy (meters) of the fix the anchor was last set FROM — null
    // while the anchor is only the PROVISIONAL any-quality one (see the
    // branch below), since that anchor was never claimed to be ground
    // truth in the first place. Tracked so the drift-recording check
    // further down can require the anchor itself, not just the
    // reacquisition fix, to have been trustworthy (GnssQuality's doc).
    private var outageAnchorAccuracyM: Float? = null
    private var lastAidedAtMs: Long? = null
    private var previousMode: GnssMode? = null
    private var lastDriftSummary: DriftSummaryResult? = null
    private val driftHistory = mutableListOf<DriftSummaryResult>()

    // Below this speed, a DR-derived bearing (atan2 of a near-zero
    // velocity vector) is noise, not a real heading — same principle
    // StationaryDetector already applies to ZUPT. Holding the last
    // confident heading instead of spinning the map at near-zero speed
    // matches Round 1's MapScreen.kt behavior exactly (moved here, not
    // changed) so this refactor is heading-value-neutral by itself.
    private var lastConfidentHeadingDeg: Float = 0f

    // Running mean/std of DR speed DURING the current outage (reset every
    // time GNSS_AIDED is good, accumulated every tick it isn't) — the SAME
    // two statistics ml/train_reacquisition_model.py computed over its
    // simulated outage windows, feeding [reacquisitionDriftModel] at the
    // instant REACQUISITION begins. See [RunningStats]'s own doc for why
    // Welford's algorithm, not a stored sample list.
    private val outageSpeedStats = RunningStats()

    // Active route geometry (lat/lon, WGS84 degrees) to road-snap against —
    // null while no route is active. Deliberately plain lat/lon pairs, not
    // osmdroid's GeoPoint, so this fusion-layer class stays free of a UI/map
    // library dependency (CLAUDE.md Rule 5) — ui/screens/MapScreen.kt
    // converts its osmdroid route geometry before calling
    // [setActiveRouteGeometry].
    private var activeRouteGeometryLatLon: List<Pair<Double, Double>>? = null

    // Cached local-meter road segments projected from
    // [activeRouteGeometryLatLon], plus the anchor they were projected
    // against — re-projecting the full route on every ~10Hz tick would be
    // wasted work when neither the route nor the anchor has changed since
    // the last tick. Invalidated (see [setActiveRouteGeometry]) whenever
    // the route itself changes; recomputed below whenever the anchor moves.
    private var cachedRoadSegments: List<MapConstraint.Segment> = emptyList()
    private var cachedRoadSegmentsAnchorLatDeg: Double? = null
    private var cachedRoadSegmentsAnchorLonDeg: Double? = null

    private var collectJob: Job? = null

    /**
     * Sets (or clears, with null) the active route's geometry for PRD.md
     * Section 19's road-snap constraint. Called from
     * `ui/screens/MapScreen.kt` whenever its `activeRoute` changes — see
     * this class's own doc comment for why a plain field, not a StateFlow.
     */
    fun setActiveRouteGeometry(geometryLatLon: List<Pair<Double, Double>>?) {
        activeRouteGeometryLatLon = geometryLatLon
        cachedRoadSegments = emptyList()
        cachedRoadSegmentsAnchorLatDeg = null
        cachedRoadSegmentsAnchorLonDeg = null
    }

    fun start() {
        positionFusion.reset()
        headingFusion.reset()
        outageAnchorLatDeg = null
        outageAnchorLonDeg = null
        outageAnchorAccuracyM = null
        lastAidedAtMs = null
        previousMode = null
        lastDriftSummary = null
        driftHistory.clear()
        lastConfidentHeadingDeg = 0f
        outageSpeedStats.reset()
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
                    outageAnchorAccuracyM = fix.accuracyM
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

                // PRD.md Section 17's "AI-based" fusion input: accumulates
                // whichever DR source is actually active this tick (same
                // selection as drEastM/drNorthM above) into
                // [outageSpeedStats], matching ml/train_reacquisition_model.py's
                // use of the velocity model's own predictions as the "live
                // on-device signal." Reset the instant GNSS is trustworthy
                // again (a new outage's stats must not include the
                // previous one's) — the SAME trigger boundary
                // [lastAidedAtMs] itself resets on, just above.
                val currentSpeedMps = if (useMl && mlState?.predictedVelocityCorrectedMps != null) {
                    mlState.predictedVelocityCorrectedMps.toDouble()
                } else {
                    hypot(physicsState.velocityEastMps, physicsState.velocityNorthMps)
                }
                if (gnssState.mode == GnssMode.GNSS_AIDED && GnssQuality.isGood(gnssState.fixAgeMs, fix.accuracyM)) {
                    outageSpeedStats.reset()
                } else {
                    outageSpeedStats.accumulate(currentSpeedMps)
                }

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
                    // REAL BUG (2026-09-01, on-device test — phone
                    // stationary indoors, History tab logging 0.3-30m of
                    // "drift" every reacquisition cycle): a drift NUMBER is
                    // only as honest as its two endpoints. `newFixEastM`/
                    // `newFixNorthM` above only required
                    // GnssOutageDetector's looser "is GNSS available at
                    // all" bar (gnssState.mode == REACQUISITION, backed by
                    // GnssQuality.DEFAULT_MAX_ACCURACY_M's 25m) — indoors,
                    // that's easily satisfied by a multipath-corrupted fix
                    // that's actually 5-30m from the truth, so what got
                    // recorded was GNSS position noise mislabeled as
                    // dead-reckoning drift (CLAUDE.md Rule 13). Requiring
                    // BOTH the anchor (`outageAnchorAccuracyM`, captured
                    // when the anchor was set) and this fix to independently
                    // clear the stricter ground-truth bar before recording
                    // anything keeps the state machine's own timing (mode
                    // transitions, blend duration below) untouched — it
                    // only gates whether this cycle's gap gets PRESENTED as
                    // a measured drift result. See
                    // GnssQuality.DEFAULT_MAX_ACCURACY_FOR_GROUND_TRUTH_M's
                    // doc for the full reasoning.
                    val anchorAccuracyM = outageAnchorAccuracyM
                    val anchorIsGroundTruth = anchorAccuracyM != null &&
                        anchorAccuracyM <= GnssQuality.DEFAULT_MAX_ACCURACY_FOR_GROUND_TRUTH_M
                    val fixIsGroundTruth =
                        fix.accuracyM <= GnssQuality.DEFAULT_MAX_ACCURACY_FOR_GROUND_TRUTH_M
                    if (anchorIsGroundTruth && fixIsGroundTruth) {
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
                    } else {
                        Log.i(
                            TAG,
                            "Reacquisition fix rejected as drift ground truth " +
                                "(anchorAccuracyM=$anchorAccuracyM, fixAccuracyM=${fix.accuracyM}, " +
                                "bound=${GnssQuality.DEFAULT_MAX_ACCURACY_FOR_GROUND_TRUTH_M}m) -- not recorded",
                        )
                    }

                    // PRD.md Section 17's "AI-based" fusion: predicts THIS
                    // outage's expected drift from its real duration + the
                    // DR speed statistics just accumulated above, and sets
                    // positionFusion's blend duration for it BEFORE the
                    // update() call below sees REACQUISITION mode for the
                    // first time this outage — see PositionFusion's own doc
                    // for the full reasoning and the "why not Kalman/EKF"
                    // scope note.
                    val outageDurationS = lastAidedAtMs?.let { (nowMs - it) / 1000f } ?: 0f
                    val predictedDriftM = reacquisitionDriftModel?.predict(
                        outageDurationS = outageDurationS,
                        avgPredictedSpeedMps = outageSpeedStats.mean().toFloat(),
                        predictedSpeedStdMps = outageSpeedStats.populationStdDev().toFloat(),
                    )
                    if (predictedDriftM != null) {
                        positionFusion.setReacquisitionBlendMs(PositionFusion.blendDurationForDriftMs(predictedDriftM))
                        Log.i(TAG, "Outage #${driftHistory.size} predicted drift: ${predictedDriftM}m (adaptive blend set)")
                    }
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

                // Round 2 (2026-08-28 — PRD.md FR8/Section 18): DR-derived
                // bearing from the PHYSICS velocity vector, moved here
                // unchanged from Round 1's ui/screens/MapScreen.kt (same
                // 0.5 m/s confidence threshold, same last-confident-heading
                // hold) — now benefits from BaselineDeadReckoningRepository's
                // own Round 2 alignment fix (see that class's doc), so this
                // bearing itself is more accurate than Round 1's, on top of
                // now being smoothly blended instead of hard-cut.
                val speedForHeadingMps = hypot(physicsState.velocityEastMps, physicsState.velocityNorthMps)
                val drHeadingDeg = if (speedForHeadingMps > 0.5) {
                    val bearingDeg = Math.toDegrees(
                        atan2(physicsState.velocityEastMps, physicsState.velocityNorthMps),
                    ).toFloat()
                    (if (bearingDeg < 0f) bearingDeg + 360f else bearingDeg).also { lastConfidentHeadingDeg = it }
                } else {
                    lastConfidentHeadingDeg
                }
                val fusedHeadingDeg = headingFusion.update(
                    nowMs = nowMs,
                    mode = gnssState.mode,
                    drHeadingDeg = drHeadingDeg,
                    newFixHeadingDeg = fix.bearingDeg,
                )

                // PRD.md Section 19's MVP map constraint: only while GNSS
                // ISN'T already the trusted source (a real GPS fix needs no
                // road-snap "correction" — GNSS_AIDED's `fused` position IS
                // the real fix, see PositionFusion) AND an active route +
                // anchor exist to snap against.
                var roadSnapEastM: Double? = null
                var roadSnapNorthM: Double? = null
                var roadDistanceM: Double? = null
                val anchorLatForSnap = outageAnchorLatDeg
                val anchorLonForSnap = outageAnchorLonDeg
                val routeGeometry = activeRouteGeometryLatLon
                if (gnssState.mode != GnssMode.GNSS_AIDED &&
                    anchorLatForSnap != null &&
                    anchorLonForSnap != null &&
                    routeGeometry != null &&
                    routeGeometry.size >= 2
                ) {
                    if (cachedRoadSegmentsAnchorLatDeg != anchorLatForSnap ||
                        cachedRoadSegmentsAnchorLonDeg != anchorLonForSnap
                    ) {
                        val localPoints = routeGeometry.map { (latDeg, lonDeg) ->
                            GeoProjection.toLocalMeters(latDeg, lonDeg, anchorLatForSnap, anchorLonForSnap)
                        }
                        cachedRoadSegments = localPoints.zipWithNext { start, end ->
                            MapConstraint.Segment(
                                startEastM = start.first,
                                startNorthM = start.second,
                                endEastM = end.first,
                                endNorthM = end.second,
                            )
                        }
                        cachedRoadSegmentsAnchorLatDeg = anchorLatForSnap
                        cachedRoadSegmentsAnchorLonDeg = anchorLonForSnap
                    }

                    // Heading from the physics DR velocity vector — the SAME
                    // fallback heading source ui/screens/MapScreen.kt already
                    // uses for its own heading-up map rotation, reused here
                    // rather than introducing a second heading estimate.
                    // Below the speed floor the direction is noise, not a
                    // real heading (same reasoning StationaryDetector/
                    // MapScreen's own 0.5 m/s check already document), so no
                    // snap is attempted rather than snap on a meaningless
                    // heading-compatibility check.
                    val speedForHeadingMps = hypot(physicsState.velocityEastMps, physicsState.velocityNorthMps)
                    if (speedForHeadingMps > MIN_SPEED_FOR_ROAD_SNAP_HEADING_MPS && cachedRoadSegments.isNotEmpty()) {
                        val headingRad = atan2(physicsState.velocityEastMps, physicsState.velocityNorthMps).toFloat()
                        val snap = MapConstraint.snapToRoad(
                            eastM = fused.eastM,
                            northM = fused.northM,
                            headingRad = headingRad,
                            segments = cachedRoadSegments,
                        )
                        if (snap.snapped) {
                            roadSnapEastM = snap.eastM
                            roadSnapNorthM = snap.northM
                            roadDistanceM = snap.distanceToRoadM
                        }
                    }
                }

                _state.value = FusedPositionUiState(
                    fusedEastM = roadSnapEastM ?: fused.eastM,
                    fusedNorthM = roadSnapNorthM ?: fused.northM,
                    drSourceUsed = if (useMl) DrSource.ML else DrSource.PHYSICS,
                    secondsSinceLastGnssAided = lastAidedAtMs?.let { (nowMs - it) / 1000f } ?: Float.MAX_VALUE,
                    driftSummary = lastDriftSummary,
                    driftHistory = driftHistory.toList(),
                    anchorLatDeg = outageAnchorLatDeg,
                    anchorLonDeg = outageAnchorLonDeg,
                    fusedHeadingDeg = fusedHeadingDeg,
                    roadSnapped = roadSnapEastM != null,
                    distanceToRoadM = roadDistanceM,
                )
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }
}
