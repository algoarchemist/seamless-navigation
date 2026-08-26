package com.sih26168.idr.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sih26168.idr.dr.DeadReckoningState
import com.sih26168.idr.fusion.FusedPositionUiState
import com.sih26168.idr.gnss.GnssModeUiState
import com.sih26168.idr.ml.MlVelocityUiState
import com.sih26168.idr.ui.components.VehicleMode
import com.sih26168.idr.ui.map.TrackCanvas

/**
 * Slice 8's primary screen (PRD.md Section 22's "single main screen"):
 * [TrackCanvas] as the base map layer (an abstract local-East/North-meter
 * grid, not real street geometry — see [MapScreen] for the real-street
 * counterpart added in Slice 8b) plus [StatusOverlayContent] (GNSS mode,
 * speed, motion state, alignment, vehicle-mode selector, recalibrate,
 * drift summary — FR10/PRD Section 30 WOW-factor #4).
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
    isDarkTheme: Boolean,
    isPipelinePaused: Boolean,
    vehicleMode: VehicleMode?,
    onToggleTheme: () -> Unit,
    onRecalibrate: () -> Unit,
    onShowDebugScreen: () -> Unit,
    onTogglePipelinePause: () -> Unit,
    onVehicleModeChange: (VehicleMode) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        TrackCanvas(
            fusedEastM = fusedState.fusedEastM,
            fusedNorthM = fusedState.fusedNorthM,
            mode = gnssState.mode,
            modifier = Modifier.fillMaxSize(),
        )
        StatusOverlayContent(
            drState = drState,
            gnssState = gnssState,
            mlState = mlState,
            fusedState = fusedState,
            mlModelLoadError = mlModelLoadError,
            isDarkTheme = isDarkTheme,
            isPipelinePaused = isPipelinePaused,
            vehicleMode = vehicleMode,
            onToggleTheme = onToggleTheme,
            onRecalibrate = onRecalibrate,
            onShowDebugScreen = onShowDebugScreen,
            onTogglePipelinePause = onTogglePipelinePause,
            onVehicleModeChange = onVehicleModeChange,
        )
    }
}
