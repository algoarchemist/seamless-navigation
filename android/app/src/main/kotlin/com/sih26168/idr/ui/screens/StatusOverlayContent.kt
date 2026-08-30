package com.sih26168.idr.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.sih26168.idr.R
import com.sih26168.idr.dr.DeadReckoningState
import com.sih26168.idr.fusion.DrSource
import com.sih26168.idr.fusion.FusedPositionUiState
import com.sih26168.idr.gnss.GnssMode
import com.sih26168.idr.gnss.GnssModeUiState
import com.sih26168.idr.ml.MlVelocityUiState
import com.sih26168.idr.ui.components.DriftSummaryCard
import com.sih26168.idr.ui.components.FloatingIconButton
import com.sih26168.idr.ui.components.GnssModeChangeBanner
import com.sih26168.idr.ui.components.StatusChip
import com.sih26168.idr.ui.components.VehicleMode
import com.sih26168.idr.ui.components.VehicleModeSelector
import com.sih26168.idr.ui.theme.DeadReckoningColor
import com.sih26168.idr.ui.theme.GnssAidedColor
import com.sih26168.idr.ui.theme.ReacquisitionColor
import com.sih26168.idr.ui.theme.TextPrimary
import com.sih26168.idr.ui.theme.TextSecondary
import com.sih26168.idr.ui.theme.TransitionColor
import kotlin.math.hypot

/**
 * The status overlay (GNSS mode/speed/motion/alignment header, vehicle-mode
 * selector + recalibrate + drift-summary footer) used by [MapScreen] (over
 * [com.sih26168.idr.ui.map.StreetMapView]'s real street tiles, Slice 8b).
 * Originally shared with the abstract-grid DriveScreen too (removed once
 * MapScreen's real map made it redundant) — extracted as its own
 * composable so a future second base layer could share one FR10 status
 * implementation instead of two copies drifting apart. Every value
 * displayed traces back to the same real repositories both screens were
 * already reading (CLAUDE.md Rule 8), unchanged by this extraction.
 */
@Composable
internal fun StatusOverlayContent(
    drState: DeadReckoningState,
    gnssState: GnssModeUiState,
    mlState: MlVelocityUiState,
    fusedState: FusedPositionUiState,
    mlModelLoadError: String?,
    isDarkTheme: Boolean,
    isPipelinePaused: Boolean,
    vehicleMode: VehicleMode?,
    onToggleTheme: () -> Unit,
    onRecalibrate: () -> Unit,
    onShowDebugScreen: () -> Unit,
    onTogglePipelinePause: () -> Unit,
    onVehicleModeChange: (VehicleMode) -> Unit,
    // REAL BUG FIX (2026-08-26, found testing the "Go" button on-device):
    // this bottom section (drift card + vehicle-mode selector + recalibrate)
    // used to render unconditionally, so on MapScreen it visually collided
    // with ActiveRouteCard/NavigationEtaBar — both occupy the same
    // bottom-of-screen area once a route is active, producing an illegible
    // overlapping mess (confirmed on a real S24 FE). MapScreen now passes
    // false while a route is active/navigating; the default true is for
    // when there is no competing bottom content (no active route).
    showBottomBar: Boolean = true,
) {
    var dismissedDrift by remember(fusedState.driftSummary) { mutableStateOf(false) }

    val speedMps = estimateSpeedMps(drState, mlState, gnssState, fusedState)
    val motionLabel = estimateMotionLabel(drState, mlState, speedMps)
    val gnssColor = when (gnssState.mode) {
        GnssMode.GNSS_AIDED -> GnssAidedColor
        GnssMode.TRANSITION -> TransitionColor
        GnssMode.DEAD_RECKONING -> DeadReckoningColor
        GnssMode.REACQUISITION -> ReacquisitionColor
    }

    // User-requested (2026-08-29) "popup when switching from gnss aided to
    // dead reckoning mode" — keyed off GnssModeRepository's own
    // `lastTransition` (already logged per CLAUDE.md Rule 17), not a
    // locally-tracked `gnssState.mode` diff, so this can't drift out of
    // sync with what actually got logged. Deliberately narrowed to
    // `fromMode == TRANSITION`: that's the one path into DEAD_RECKONING
    // that genuinely started from GNSS_AIDED (see GnssOutageDetector's
    // state diagram doc comment) — REACQUISITION bailing back to
    // DEAD_RECKONING re-enters the same mode but was never GNSS_AIDED to
    // begin with, so it's excluded to avoid re-notifying on every failed
    // reacquisition attempt during a marginal-GNSS stretch.
    // `dismissedTransitionAtMs` remembers WHICH transition (by timestamp)
    // was last dismissed/auto-dismissed, so re-entering DEAD_RECKONING a
    // second time later in the same session re-shows the banner instead
    // of staying permanently dismissed after the first outage.
    var dismissedTransitionAtMs by remember { mutableStateOf<Long?>(null) }
    val transition = gnssState.lastTransition
    val showModeChangeBanner = transition != null &&
        transition.toMode == GnssMode.DEAD_RECKONING &&
        transition.fromMode == GnssMode.TRANSITION &&
        transition.atMs != dismissedTransitionAtMs

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Top: status overlay (FR10 — GNSS state, speed, motion state, alignment).
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "IDR", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                Row {
                    // 2026-08-26, user-requested: pause/resume the live
                    // pipeline (sensors, GNSS, physics/ML DR, fusion) without
                    // leaving the screen — see MainActivity's
                    // startPipeline()/stopPipeline(), the same repository
                    // start()/stop() calls onResume()/onPause() already use,
                    // now also reachable from a button instead of only the
                    // Activity lifecycle.
                    TextButton(onClick = onTogglePipelinePause) {
                        Text(
                            text = if (isPipelinePaused) "Resume" else "Pause",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isPipelinePaused) DeadReckoningColor else TextSecondary,
                        )
                    }
                    // User-requested light mode (2026-08-26) — toggles
                    // IdrTheme's darkTheme param, which every chrome color
                    // in ui/theme/Color.kt reads from LocalIdrPalette.
                    TextButton(onClick = onToggleTheme) {
                        Text(
                            text = if (isDarkTheme) "Light" else "Dark",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                        )
                    }
                    // Reaches the pre-Slice-8 raw-value debug screen — kept
                    // discoverable, not hidden away, since it remains the
                    // only way to verify raw numbers.
                    TextButton(onClick = onShowDebugScreen) {
                        Text(text = "Debug", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    }
                }
            }
            if (showModeChangeBanner) {
                key(transition!!.atMs) {
                    GnssModeChangeBanner(onDismiss = { dismissedTransitionAtMs = transition.atMs })
                }
            }
            if (isPipelinePaused) {
                // Everything below (GNSS mode, speed, motion, alignment)
                // is now FROZEN at its last live value, not actually
                // updating — this chip is the honest signal of that
                // (CLAUDE.md Rule 13 extended to in-app copy, same
                // convention MainActivity's debug screen already uses).
                StatusChip(label = "PAUSED — live pipeline stopped", dotColor = DeadReckoningColor)
            }
            StatusChip(label = gnssState.mode.name, dotColor = gnssColor)
            StatusChip(label = "%.1f m/s".format(speedMps), dotColor = TextSecondary)
            StatusChip(label = motionLabel, dotColor = TextSecondary)
            if (!gnssState.hasLocationPermission) {
                // Honest distinction (CLAUDE.md Rule 13): without this, a
                // denied/never-granted location permission eventually
                // drives the hysteresis state machine into DEAD_RECKONING
                // (GnssQuality.isGood is always false with no fix) which
                // looks identical on screen to a real GNSS outage.
                Text(
                    text = "Location permission not granted — GNSS unavailable",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
            }
            Text(
                text = if (mlState.isAligned) {
                    "Aligned (%.1f deg, %d samples)".format(mlState.yawOffsetDeg, mlState.alignmentSampleCount)
                } else {
                    "Alignment: not yet established"
                },
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
            if (mlModelLoadError != null) {
                Text(
                    text = "ML unavailable: $mlModelLoadError",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
            }
        }

        // Bottom: vehicle-mode selector (pre-drive), recalibrate button, drift
        // summary — suppressed entirely (see showBottomBar's doc) whenever the
        // caller has its own competing bottom content (MapScreen's
        // ActiveRouteCard / NavigationEtaBar).
        if (showBottomBar) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (fusedState.driftSummary != null && !dismissedDrift) {
                    // Compact chip now (2026-08-26, user-requested — the
                    // previous full-width card was "annoying"), so no
                    // fillMaxWidth(): it should size to its own content,
                    // same as the status chips above it.
                    DriftSummaryCard(
                        driftSummary = fusedState.driftSummary,
                        onDismiss = { dismissedDrift = true },
                    )
                }
                // REAL BUG FIX (2026-08-26, found testing on a real S24 FE):
                // this used to be a Box with the recenter button laid over
                // the selector via Alignment.CenterEnd — a Box's aligned
                // children don't reserve space from each other, so once a
                // third pill (Walk) was added, the selector's Row ran
                // straight under the button and its label got visually
                // clipped/overlapped. A Row with weight(1f) on the selector
                // gives the button its own reserved 44dp instead, and the
                // selector (now horizontalScroll-able, see
                // VehicleModeSelector.kt) scrolls within whatever space is
                // actually left rather than overlapping anything.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Kept switchable at all times (not locked in after one
                    // pick) now that WALKING actually changes DR behavior
                    // mid-session — a rider might park and walk the rest of
                    // the way.
                    VehicleModeSelector(
                        selected = vehicleMode ?: VehicleMode.CAR,
                        onSelect = onVehicleModeChange,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    FloatingIconButton(
                        icon = painterResource(R.drawable.ic_recenter),
                        contentDescription = "Recalibrate phone-to-vehicle alignment",
                        onClick = onRecalibrate,
                    )
                }
            }
        }
    }
}

/**
 * FR10's "estimated speed" — reflects whichever source is actually
 * authoritative right now, matching fusion/StateEstimator's own DR-source
 * selection: real GNSS speed while GNSS_AIDED, else the ML velocity if
 * that's the DR source in use, else the physics velocity's magnitude
 * (already ZUPT-corrected by dr/BaselineDeadReckoningRepository).
 */
internal fun estimateSpeedMps(
    drState: DeadReckoningState,
    mlState: MlVelocityUiState,
    gnssState: GnssModeUiState,
    fusedState: FusedPositionUiState,
): Float {
    val gnssSpeed = gnssState.latestFix?.speedMps
    val physicsSpeedMps = hypot(drState.velocityEastMps, drState.velocityNorthMps).toFloat()
    // REAL BUG FIX (2026-08-26, indoor on-device test): raw GNSS speed is
    // Doppler-derived and can report a nonzero "ghost" speed (e.g. 15 m/s)
    // purely from indoor multipath, even while every other sensor agrees
    // the phone is stationary (accel ~= gravity only, gyro ~= 0, ZUPT has
    // already zeroed the physics velocity below STATIONARY_SPEED_EPSILON_MPS).
    // GNSS position accuracy passing its threshold says nothing about
    // speed-reading quality, so a GNSS_AIDED fix alone isn't enough reason
    // to trust gnssSpeed here — reject it specifically when it contradicts
    // a ZUPT-confirmed-stationary physics state, and fall through to the
    // next source instead.
    val gnssSpeedContradictsStationaryPhysics =
        gnssSpeed != null &&
            physicsSpeedMps < STATIONARY_SPEED_EPSILON_MPS &&
            gnssSpeed >= STATIONARY_SPEED_EPSILON_MPS
    return when {
        gnssState.mode == GnssMode.GNSS_AIDED && gnssSpeed != null && !gnssSpeedContradictsStationaryPhysics ->
            gnssSpeed
        fusedState.drSourceUsed == DrSource.ML && mlState.predictedVelocityCorrectedMps != null ->
            mlState.predictedVelocityCorrectedMps
        else -> physicsSpeedMps
    }
}

// PRD.md FR10's "current motion class" (see also Section 14) — this
// project only implements a REAL subset of the full 8-class taxonomy via
// deterministic stand-ins (docs/PROJECT_MAP.md: the actual TRAINED
// classifier is still blocked on labeled data, CLAUDE.md Rule 13).
// UPDATE (2026-08-30): Turning (dr/TurningDetector.kt, via
// DeadReckoningState.isTurning) and Accelerating/Braking
// (motion/LongitudinalMotionClassifier.kt) are now real signals and shown
// here too — priority order below is a DISPLAY choice (only one label
// fits), not a claim these are mutually exclusive underlying states (a
// car can genuinely be turning AND accelerating at once). Phone Moved is
// deliberately still NOT shown here — it is a one-shot reset EVENT
// (motion/PhoneMovedDetector.kt, logged via alignment/AlignmentRepository.kt),
// not an ongoing motion state a per-tick label fits well.
private const val STATIONARY_SPEED_EPSILON_MPS = 0.3

// BUG FIX (2026-08-26, real outdoor walking test): this used to recompute
// speed from drState.velocityEastMps/NorthMps directly — the RAW physics
// DR velocity, ignoring GNSS/ML. That silently disagreed with the speed
// chip right above it (estimateSpeedMps(), which prefers GNSS/ML speed
// when available): a user could see "1.2 m/s" in one chip and
// "Stationary" in the very next one, because physics-only ZUPT had
// zeroed the physics velocity this tick while the ACTUAL authoritative
// speed estimate said otherwise. Now takes the same [speedMps] value the
// chip above already displays, so the two can never contradict each
// other. Threshold also raised from 0.05 to 0.3 m/s — 0.05 was tight
// enough that ordinary GNSS speed noise near walking pace could still
// read as "Stationary" even while genuinely moving.
internal fun estimateMotionLabel(drState: DeadReckoningState, mlState: MlVelocityUiState, speedMps: Float): String = when {
    mlState.potholeShockDetectedThisTick -> "Pothole"
    drState.isTurning -> "Turning"
    mlState.isAccelerating -> "Accelerating"
    mlState.isBraking -> "Braking"
    mlState.isCruising -> "Cruising"
    speedMps < STATIONARY_SPEED_EPSILON_MPS -> "Stationary"
    else -> "Moving"
}
