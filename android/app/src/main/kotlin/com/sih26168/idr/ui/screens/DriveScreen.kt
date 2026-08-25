package com.sih26168.idr.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.sih26168.idr.ui.components.StatusChip
import com.sih26168.idr.ui.components.VehicleMode
import com.sih26168.idr.ui.components.VehicleModeSelector
import com.sih26168.idr.ui.map.TrackCanvas
import com.sih26168.idr.ui.theme.DeadReckoningColor
import com.sih26168.idr.ui.theme.GnssAidedColor
import com.sih26168.idr.ui.theme.ReacquisitionColor
import com.sih26168.idr.ui.theme.TextPrimary
import com.sih26168.idr.ui.theme.TextSecondary
import com.sih26168.idr.ui.theme.TransitionColor
import kotlin.math.hypot

/**
 * Slice 8's primary screen (PRD.md Section 22's "single main screen"):
 * [TrackCanvas] as the base map layer, a status overlay (GNSS mode,
 * speed, motion state, alignment — FR10), the pre-drive
 * [VehicleModeSelector] (PRD Section 6), a manual-recalibrate
 * [FloatingIconButton] (PRD Section 15/31/32), and [DriftSummaryCard]
 * (PRD Section 30 WOW-factor #4) shown once real drift data exists.
 *
 * A pure function of already-real state (CLAUDE.md Rule 8 — no
 * placeholder production data path): every value displayed traces back
 * to [com.sih26168.idr.sensors.SensorRepository]/
 * [com.sih26168.idr.gnss.GnssModeRepository]/
 * [com.sih26168.idr.dr.BaselineDeadReckoningRepository]/
 * [com.sih26168.idr.ml.MlVelocityRepository]/
 * [com.sih26168.idr.fusion.StateEstimator], same as
 * [com.sih26168.idr.IdrSensorScreen] (the pre-Slice-8 debug screen,
 * still reachable via [onShowDebugScreen] — kept per CLAUDE.md Rule 5,
 * it remains the only raw-number verification view across Slices 1-7).
 */
@Composable
fun DriveScreen(
    drState: DeadReckoningState,
    gnssState: GnssModeUiState,
    mlState: MlVelocityUiState,
    fusedState: FusedPositionUiState,
    mlModelLoadError: String?,
    onRecalibrate: () -> Unit,
    onShowDebugScreen: () -> Unit,
) {
    var vehicleMode by remember { mutableStateOf<VehicleMode?>(null) }
    var dismissedDrift by remember(fusedState.driftSummary) { mutableStateOf(false) }

    val speedMps = estimateSpeedMps(drState, mlState, gnssState, fusedState)
    val motionLabel = estimateMotionLabel(drState, mlState)
    val gnssColor = when (gnssState.mode) {
        GnssMode.GNSS_AIDED -> GnssAidedColor
        GnssMode.TRANSITION -> TransitionColor
        GnssMode.DEAD_RECKONING -> DeadReckoningColor
        GnssMode.REACQUISITION -> ReacquisitionColor
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TrackCanvas(
            fusedEastM = fusedState.fusedEastM,
            fusedNorthM = fusedState.fusedNorthM,
            mode = gnssState.mode,
            modifier = Modifier.fillMaxSize(),
        )

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
                    // Reaches the pre-Slice-8 raw-value debug screen (see this
                    // file's doc comment) — kept discoverable, not hidden away,
                    // since it remains the only way to verify raw numbers.
                    TextButton(onClick = onShowDebugScreen) {
                        Text(text = "Debug", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    }
                }
                StatusChip(label = gnssState.mode.name, dotColor = gnssColor)
                StatusChip(label = "%.1f m/s".format(speedMps), dotColor = TextSecondary)
                StatusChip(label = motionLabel, dotColor = TextSecondary)
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

            // Bottom: vehicle-mode selector (pre-drive), recalibrate button, drift summary.
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (fusedState.driftSummary != null && !dismissedDrift) {
                    DriftSummaryCard(
                        driftSummary = fusedState.driftSummary,
                        onDismiss = { dismissedDrift = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (vehicleMode == null) {
                        VehicleModeSelector(
                            selected = VehicleMode.CAR,
                            onSelect = { vehicleMode = it },
                        )
                    } else {
                        StatusChip(label = "Mode: ${vehicleMode!!.name}", dotColor = TextSecondary)
                    }
                    FloatingIconButton(
                        icon = painterResource(R.drawable.ic_recenter),
                        contentDescription = "Recalibrate phone-to-vehicle alignment",
                        onClick = onRecalibrate,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
    }
}

/**
 * FR10's "estimated speed" — reflects whichever source is actually
 * authoritative right now, matching fusion/StateEstimator's own DR-
 * source selection: real GNSS speed while GNSS_AIDED, else the ML
 * velocity if that's the DR source in use, else the physics velocity's
 * magnitude (already ZUPT-corrected by dr/BaselineDeadReckoningRepository).
 */
private fun estimateSpeedMps(
    drState: DeadReckoningState,
    mlState: MlVelocityUiState,
    gnssState: GnssModeUiState,
    fusedState: FusedPositionUiState,
): Float {
    val gnssSpeed = gnssState.latestFix?.speedMps
    return when {
        gnssState.mode == GnssMode.GNSS_AIDED && gnssSpeed != null -> gnssSpeed
        fusedState.drSourceUsed == DrSource.ML && mlState.predictedVelocityCorrectedMps != null ->
            mlState.predictedVelocityCorrectedMps
        else -> hypot(drState.velocityEastMps, drState.velocityNorthMps).toFloat()
    }
}

// PRD.md FR10's "current motion class" (see also Section 14) — this
// project only implements a REAL subset of the full 8-class taxonomy
// (docs/PROJECT_MAP.md: the trained classifier is still blocked on
// labeled data). Priority order below shows ONLY what's real: Pothole
// (motion/PotholeShockDetector) -> Cruising (motion/MotionStateClassifier)
// -> Stationary (near-zero ZUPT-corrected physics velocity) -> a generic
// "Moving" fallback. No Turning/Accelerating/Braking/Phone-Moved label is
// ever shown, since those detectors don't exist (CLAUDE.md Rule 13).
private const val STATIONARY_SPEED_EPSILON_MPS = 0.05

private fun estimateMotionLabel(drState: DeadReckoningState, mlState: MlVelocityUiState): String = when {
    mlState.potholeShockDetectedThisTick -> "Pothole"
    mlState.isCruising -> "Cruising"
    hypot(drState.velocityEastMps, drState.velocityNorthMps) < STATIONARY_SPEED_EPSILON_MPS -> "Stationary"
    else -> "Moving"
}
