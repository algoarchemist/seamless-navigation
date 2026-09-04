package com.sih26168.idr

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.sih26168.idr.alignment.AlignmentRepository
import com.sih26168.idr.capture.CaptureLabel
import com.sih26168.idr.capture.DriveDataLogger
import com.sih26168.idr.capture.SensorRecorder
import com.sih26168.idr.dr.BaselineDeadReckoningRepository
import com.sih26168.idr.dr.DeadReckoningState
import com.sih26168.idr.dr.WorldFrameAcceleration
import com.sih26168.idr.fusion.DrSource
import com.sih26168.idr.fusion.FusedPositionUiState
import com.sih26168.idr.fusion.StateEstimator
import com.sih26168.idr.gnss.GnssModeRepository
import com.sih26168.idr.gnss.GnssModeUiState
import com.sih26168.idr.gnss.LocationRepository
import com.sih26168.idr.ml.MlVelocityRepository
import com.sih26168.idr.ml.MlVelocityUiState
import com.sih26168.idr.ml.ReacquisitionDriftModel
import com.sih26168.idr.ml.VelocityModel
import com.sih26168.idr.motion.FloorChangeRepository
import com.sih26168.idr.motion.FloorChangeUiState
import com.sih26168.idr.sensors.SensorRepository
import com.sih26168.idr.sensors.SensorUiState
import com.sih26168.idr.ui.components.AppTab
import com.sih26168.idr.ui.components.BottomNavBar
import com.sih26168.idr.ui.components.VehicleMode
import com.sih26168.idr.ui.screens.HistoryScreen
import com.sih26168.idr.ui.screens.MapScreen
import com.sih26168.idr.ui.theme.IdrTheme
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Slice 1+2+3+4+5+6+7 (per CLAUDE.md's slice order): reads live
 * accelerometer, gyroscope, and rotation-vector-derived orientation via
 * [SensorRepository]; reads GNSS fixes via [LocationRepository] and runs
 * the GNSS_AIDED/TRANSITION/DEAD_RECKONING/REACQUISITION hysteresis
 * state machine via [GnssModeRepository]; feeds sensors + GNSS mode into
 * [BaselineDeadReckoningRepository] for a WORLD-frame physics position
 * estimate corrected by ZUPT and a non-holonomic constraint; ALSO feeds
 * sensors + GNSS into [MlVelocityRepository] for a live ML-predicted
 * velocity (now bias-corrected against GNSS speed, Slice 7), displayed
 * side by side with the physics estimate (Slice 6 — see that class's doc
 * for why it's parallel, not a replacement). Slice 7 additionally feeds
 * [GnssModeRepository], [BaselineDeadReckoningRepository], and
 * [MlVelocityRepository] into [StateEstimator], which actually blends
 * GNSS and DR positions together on TRANSITION/REACQUISITION (freeze /
 * blend-toward-fix, per PRD.md Section 18) — the fused readout is a
 * fourth, additional position display, not a replacement for the other
 * two (same "show it side by side" philosophy as Slice 6).
 * Orientation is DEVICE-relative-to-WORLD frame only (CLAUDE.md
 * Rule 9/14). (Round 2, 2026-08-28): [AlignmentRepository] is now a
 * single shared instance feeding BOTH the ML feature path and the
 * physics DR path's non-holonomic-constraint heading — Round 1 only fed
 * the former, which is what let the physics-path heading flip the map on
 * reacquisition (see [AlignmentRepository]'s and
 * [BaselineDeadReckoningRepository]'s docs).
 */
data class RecordingUiState(
    val isRecording: Boolean = false,
    val recordedCount: Int = 0,
    val lastSavedFileName: String? = null,
)

/**
 * UI state for capture/DriveDataLogger.kt's Start/Stop button (added
 * 2026-08-29) — same shape as [RecordingUiState], kept as its own type
 * rather than reused: this logs GNSS+DR state for threshold validation
 * during a real test drive, a different real-world scenario from
 * [RecordingUiState]'s stationary motion-classifier capture, and the two
 * are started/stopped independently.
 */
data class DriveLogUiState(
    val isLogging: Boolean = false,
    val recordedCount: Int = 0,
    val lastSavedFileName: String? = null,
)

// How long a single "Mark Pothole" tap keeps CaptureLabel.POTHOLE active
// on recorded ticks — an engineering default (a real pothole shock is
// much shorter than this; the window is deliberately generous so a
// slightly-late tap still captures the real event), not yet validated
// against a real self-captured drive (CLAUDE.md Rule 13).
private const val POTHOLE_MARKER_WINDOW_MS = 500L

class MainActivity : ComponentActivity() {

    private lateinit var sensorRepository: SensorRepository
    private lateinit var floorChangeRepository: FloorChangeRepository
    private lateinit var alignmentRepository: AlignmentRepository
    private lateinit var deadReckoningRepository: BaselineDeadReckoningRepository
    private lateinit var locationRepository: LocationRepository
    private lateinit var gnssModeRepository: GnssModeRepository
    private lateinit var stateEstimator: StateEstimator
    private var mlVelocityRepository: MlVelocityRepository? = null
    private var velocityModel: VelocityModel? = null
    private var reacquisitionDriftModel: ReacquisitionDriftModel? = null

    // 2026-08-26, user-requested Pause button: a `by mutableStateOf`
    // property (not a plain `var`) so Compose recomposes the Pause/Resume
    // label and the "PAUSED" chip the moment this changes, even though it's
    // also read from onResume()/onPause() (non-Composable lifecycle
    // callbacks) to decide whether the pipeline should auto-restart.
    private var isPipelinePaused by mutableStateOf(false)

    // Reported via a StateFlow rather than a thrown exception surfaced
    // only in Logcat — a missing/corrupt bundled model is a real,
    // demo-relevant failure mode (e.g. forgot to copy the gitignored
    // asset per docs/PROJECT_MAP.md) and should be visible on screen.
    private val _mlModelLoadError = MutableStateFlow<String?>(null)
    private val mlModelLoadError: StateFlow<String?> = _mlModelLoadError.asStateFlow()

    // One-off data-capture tool (capture/SensorRecorder.kt) — gathers
    // real, physically-moved-phone sensor data for the "self-captured
    // Pothole/Phone-Moved data" docs/PROJECT_MAP.md lists as blocking
    // train_motion_classifier.py. Deliberately NOT wired into any of the
    // position/fusion pipeline above — it only reads sensorRepository's
    // stream, same as any other consumer.
    private val sensorRecorder = SensorRecorder()
    private val _recordingState = MutableStateFlow(RecordingUiState())
    private val recordingState: StateFlow<RecordingUiState> = _recordingState.asStateFlow()
    private var recorderCollectJob: Job? = null
    private var lastProcessedRecorderAccelTimestampNs: Long? = null

    // 2026-09-01: the two PRD.md Section 14 classes IO-VNBD has no ground
    // truth for (see SensorRecordEntry's own doc) — tapped live during a
    // real self-captured drive, not derived from sensor values. Pothole is
    // MOMENTARY (a brief shock, so the tap just opens a short labeling
    // window around the moment it happened — POTHOLE_MARKER_WINDOW_MS is a
    // generous over-estimate of a real pothole event's duration, an
    // engineering default like the rest of this project's unvalidated
    // thresholds, CLAUDE.md Rule 13); Phone Moved is a STATE the phone
    // stays in until tapped again, so it's a plain toggle.
    private var isPotholeMarkerActive by mutableStateOf(false)
    private var isPhoneMovedActive by mutableStateOf(false)
    private var potholeMarkerJob: Job? = null

    private fun currentCaptureLabel(): String = when {
        isPotholeMarkerActive -> CaptureLabel.POTHOLE
        isPhoneMovedActive -> CaptureLabel.PHONE_MOVED
        else -> CaptureLabel.NONE
    }

    // One-off data-capture tool (capture/DriveDataLogger.kt) — gathers a
    // real test drive's GNSS+DR ticks so the "engineering default, not
    // yet validated" thresholds in gnss/GnssQuality.kt, gnss/
    // GnssOutageDetector.kt, and dr/StationaryDetector.kt can be checked
    // against real data via scripts/analyze_drive_log.py, instead of
    // staying guesses indefinitely. Same "reads already-real values,
    // doesn't touch the shipped pipeline" separation as sensorRecorder
    // above (CLAUDE.md Rule 8).
    private val driveDataLogger = DriveDataLogger()
    private val _driveLogState = MutableStateFlow(DriveLogUiState())
    private val driveLogState: StateFlow<DriveLogUiState> = _driveLogState.asStateFlow()
    private var driveLogCollectJob: Job? = null

    // Must be registered before the activity reaches STARTED — a property
    // initializer (runs during construction, before onCreate) satisfies that.
    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            locationRepository.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A real outdoor test drive runs minutes at a time with the phone
        // mounted/handled, not actively tapped — without this the screen
        // locks mid-run and the live GNSS<->DR transition (the actual
        // thing being tested) goes unobserved. Demo/test-only convenience,
        // not a claimed behavior of the shipped navigation logic itself.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensorRepository = SensorRepository(applicationContext)
        // Round 2 (2026-08-28 — PRD.md FR12): independent of GNSS/DR
        // entirely (see FloorChangeRepository's doc), so it only needs
        // sensorRepository — constructed here, ahead of everything else
        // that reads sensorRepository, purely by convention (no ordering
        // dependency actually exists between this and the other
        // repositories below).
        floorChangeRepository = FloorChangeRepository(sensorRepository, lifecycleScope)
        locationRepository = LocationRepository(applicationContext)
        // GnssModeRepository must exist before BaselineDeadReckoningRepository —
        // Slice 5 wires the DR odometer's reset behavior to the GNSS mode.
        gnssModeRepository = GnssModeRepository(locationRepository, lifecycleScope)
        // PRD.md Section 15's phone-to-vehicle yaw alignment — ONE shared
        // instance, constructed before AND passed into both DR paths below,
        // so physics and ML agree on a single real alignment estimate
        // instead of each tracking (or, previously, only the ML path
        // tracking) its own. See AlignmentRepository's own doc for why this
        // used to live privately inside MlVelocityRepository, and why the
        // physics path needed it too (BaselineDeadReckoningRepository's own
        // doc) — independent of the ONNX model below, so this fix keeps
        // working even on a build where the model fails to load.
        alignmentRepository = AlignmentRepository(sensorRepository, gnssModeRepository, lifecycleScope)
        deadReckoningRepository = BaselineDeadReckoningRepository(
            sensorRepository,
            gnssModeRepository,
            lifecycleScope,
            alignmentRepository,
        )

        try {
            val model = VelocityModel.loadFromAssets(applicationContext)
            velocityModel = model
            mlVelocityRepository = MlVelocityRepository(
                sensorRepository,
                gnssModeRepository,
                model,
                lifecycleScope,
                alignmentRepository,
            )
        } catch (e: Exception) {
            // Loading a bundled asset / building an ONNX session can fail
            // in ways specific to the model file (missing, truncated,
            // schema mismatch) — caught here rather than crashing the
            // whole app, since Slice 1-5 must keep working even if the
            // ML half isn't wired up on this build.
            _mlModelLoadError.value = e.message ?: e::class.simpleName ?: "unknown error"
        }

        // PRD.md Section 17's "AI-based" fusion half — a SEPARATE try/catch
        // from the velocity model above: this model's own load failure
        // (missing/corrupt asset) must not be conflated with, or block,
        // the velocity model's independent success/failure. StateEstimator
        // treats a null value here the exact same resilience way it
        // already treats a null mlVelocityRepository — falls back to the
        // previous fixed-1-second classical blend, not a crash.
        try {
            reacquisitionDriftModel = ReacquisitionDriftModel.loadFromAssets(applicationContext)
        } catch (e: Exception) {
            Log.w("MainActivity", "reacquisition_drift_v1.onnx failed to load — classical fixed-blend fusion only", e)
        }

        // Constructed after the try/catch blocks above so it sees the FINAL
        // mlVelocityRepository/reacquisitionDriftModel values (null if
        // either ONNX model failed to load) — StateEstimator falls back to
        // physics-only fusion / a fixed classical blend in that case rather
        // than crashing (CLAUDE.md Rule 13's resilience pattern, same as
        // everywhere else the ML half is optional).
        stateEstimator = StateEstimator(
            gnssModeRepository,
            deadReckoningRepository,
            mlVelocityRepository,
            lifecycleScope,
            reacquisitionDriftModel = reacquisitionDriftModel,
        )

        setContent {
            val uiState by sensorRepository.state.collectAsState()
            val drState by deadReckoningRepository.state.collectAsState()
            val gnssState by gnssModeRepository.state.collectAsState()
            val mlState by (mlVelocityRepository?.state ?: MutableStateFlow(MlVelocityUiState())).collectAsState()
            val fusedState by stateEstimator.state.collectAsState()
            val floorState by floorChangeRepository.state.collectAsState()
            val mlError by mlModelLoadError.collectAsState()
            val recState by recordingState.collectAsState()
            val driveLogUiState by driveLogState.collectAsState()
            var showDebugScreen by remember { mutableStateOf(false) }
            var selectedTab by remember { mutableStateOf(AppTab.MAP) }
            var isDarkTheme by remember { mutableStateOf(true) }
            var vehicleMode by remember { mutableStateOf<VehicleMode?>(null) }
            BackHandler(enabled = showDebugScreen) { showDebugScreen = false }
            // User-reported bug (2026-08-26): with no BackHandler at all on
            // the normal Map/History tabs, the system back button fell
            // straight through to ComponentActivity's default behavior
            // (finish the Activity) from ANY tab, closing the whole app
            // instead of navigating within it — surprising from History,
            // where a user expects Back to return to the main Map screen
            // first, same as any standard bottom-nav app. Only enabled when
            // NOT already on Map (and not on the debug screen, which the
            // handler above already owns) so Back from Map itself still
            // falls through to the normal "exit app" behavior — the
            // conventional bottom-nav-app convention (only the true home tab
            // exits on Back).
            BackHandler(enabled = !showDebugScreen && selectedTab != AppTab.MAP) {
                selectedTab = AppTab.MAP
            }

            // Walking mode actually changes DR behavior (see
            // dr/BaselineDeadReckoningRepository.walkingModeEnabled's doc) —
            // this is the one place that live selection reaches the
            // repository, same "Compose state -> plain mutable field on a
            // non-Composable repository" pattern onRecalibrate already uses
            // for alignmentRepository.reset().
            LaunchedEffect(vehicleMode) {
                deadReckoningRepository.walkingModeEnabled = vehicleMode == VehicleMode.WALKING
            }

            IdrTheme(darkTheme = isDarkTheme) {
                if (showDebugScreen) {
                    IdrSensorScreen(
                        uiState, drState, gnssState, mlState, fusedState, floorState, mlError, recState,
                        sensorRepository.hasRequiredSensors(),
                        onStartRecording = ::startRecording,
                        onStopRecording = ::stopRecordingAndSave,
                        driveLogState = driveLogUiState,
                        onStartDriveLog = ::startDriveLog,
                        onStopDriveLog = ::stopDriveLogAndSave,
                        isPotholeMarkerActive = isPotholeMarkerActive,
                        isPhoneMovedActive = isPhoneMovedActive,
                        onMarkPothole = ::markPothole,
                        onTogglePhoneMoved = ::togglePhoneMoved,
                    )
                } else {
                    // Slice 8b: two tabs (Map/History) share the SAME live
                    // state above — switching tabs never re-reads a
                    // different data source, only changes which screen
                    // presents it (MapScreen's real street tiles, or
                    // HistoryScreen's measured-drift log). The DRIVE tab's
                    // abstract local-meter grid (ui/screens/DriveScreen.kt,
                    // ui/map/TrackCanvas.kt) was removed once MapScreen's
                    // real map + routing made it redundant — it showed the
                    // same StatusOverlayContent as MapScreen but over a
                    // fake grid instead of a real street map, so it added
                    // no capability MapScreen didn't already have. The tab
                    // bar lives outside both screens so each screen's own
                    // bottom-aligned content (vehicle selector, drift card)
                    // never overlaps it.
                    Column(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            when (selectedTab) {
                                AppTab.MAP -> MapScreen(
                                    drState = drState,
                                    gnssState = gnssState,
                                    mlState = mlState,
                                    fusedState = fusedState,
                                    mlModelLoadError = mlError,
                                    isDarkTheme = isDarkTheme,
                                    isPipelinePaused = isPipelinePaused,
                                    vehicleMode = vehicleMode,
                                    onToggleTheme = { isDarkTheme = !isDarkTheme },
                                    onRecalibrate = alignmentRepository::reset,
                                    onShowDebugScreen = { showDebugScreen = true },
                                    onTogglePipelinePause = ::togglePipelinePause,
                                    onVehicleModeChange = { vehicleMode = it },
                                    onActiveRouteGeometryChanged = stateEstimator::setActiveRouteGeometry,
                                )
                                AppTab.HISTORY -> HistoryScreen(driftHistory = fusedState.driftHistory)
                            }
                        }
                        BottomNavBar(selected = selectedTab, onSelect = { selectedTab = it })
                    }
                }
            }
        }
    }

    // Sensors/GNSS are only active while the activity is visible, so the
    // demo doesn't drain battery/CPU in the background — start/stop is
    // tied to the standard Android lifecycle, not a custom one.
    // 2026-08-26, user-requested Pause button: the exact same repository
    // start() sequence onResume() already used, extracted so a UI button can
    // trigger it too, not just the Activity lifecycle. Order preserved
    // (gnssModeRepository before deadReckoningRepository before
    // stateEstimator — see the original inline comments this replaced).
    private fun startPipeline() {
        sensorRepository.start()
        // Independent of GNSS/DR (see FloorChangeRepository's doc) — only
        // needs sensorRepository, which is already started above.
        floorChangeRepository.start()
        gnssModeRepository.start()
        if (locationRepository.hasLocationPermission()) {
            locationRepository.start()
        } else {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // Before deadReckoningRepository/mlVelocityRepository — both read
        // alignmentRepository.state.value synchronously each tick, so it
        // must already be collecting (same "GnssModeRepository before
        // BaselineDeadReckoningRepository" ordering rationale as above).
        alignmentRepository.start()
        deadReckoningRepository.start()
        mlVelocityRepository?.start()
        stateEstimator.start()
    }

    // Mirrors startPipeline() in reverse order (same as the original
    // onPause() body) — safe to call even if already stopped (each
    // repository's own stop() is idempotent: unregistering an unregistered
    // listener / cancelling a null job / quitting a null HandlerThread are
    // all no-ops), which matters since onPause() calls this unconditionally
    // even when the user already paused manually via the button.
    private fun stopPipeline() {
        stateEstimator.stop()
        mlVelocityRepository?.stop()
        deadReckoningRepository.stop()
        alignmentRepository.stop()
        floorChangeRepository.stop()
        gnssModeRepository.stop()
        locationRepository.stop()
        sensorRepository.stop()
    }

    private fun togglePipelinePause() {
        if (isPipelinePaused) {
            isPipelinePaused = false
            startPipeline()
        } else {
            isPipelinePaused = true
            stopPipeline()
        }
    }

    override fun onResume() {
        super.onResume()
        // Respects a manual pause across a background/foreground cycle —
        // without this check, backgrounding the app (which always stops the
        // pipeline via onPause() below) and returning would silently
        // resume it even if the user had deliberately paused first.
        if (!isPipelinePaused) {
            startPipeline()
        }

        // Always collecting (same lifecycle-tied start/stop convention as
        // every other repository here) — whether a tick actually gets
        // appended is gated by RecordingUiState.isRecording inside the
        // loop, toggled by the Start/Stop button.
        lastProcessedRecorderAccelTimestampNs = null
        recorderCollectJob = lifecycleScope.launch {
            sensorRepository.state.collect { sensorUiState ->
                if (!_recordingState.value.isRecording) return@collect
                val accel = sensorUiState.latestAccel ?: return@collect
                val gyro = sensorUiState.latestGyro ?: return@collect
                val orientation = sensorUiState.latestOrientation ?: return@collect
                // Same dedup guard as BaselineDeadReckoningRepository/
                // MlVelocityRepository — SensorRepository's StateFlow
                // re-emits on every gyro/orientation update too, not just
                // accel, so without this each real tick would be recorded
                // 2-3x with near-identical values.
                if (accel.timestampNs == lastProcessedRecorderAccelTimestampNs) return@collect
                lastProcessedRecorderAccelTimestampNs = accel.timestampNs

                sensorRecorder.record(
                    timestampNs = accel.timestampNs,
                    accelXMps2 = accel.xMps2,
                    accelYMps2 = accel.yMps2,
                    accelZMps2 = accel.zMps2,
                    gyroXRadPerSec = gyro.xRadPerSec,
                    gyroYRadPerSec = gyro.yRadPerSec,
                    gyroZRadPerSec = gyro.zRadPerSec,
                    azimuthRad = orientation.azimuthRad,
                    pitchRad = orientation.pitchRad,
                    rollRad = orientation.rollRad,
                    label = currentCaptureLabel(),
                )
                _recordingState.value = _recordingState.value.copy(recordedCount = sensorRecorder.recordedCount)
            }
        }

        // Same always-collecting / gated-by-flag convention as the
        // recorder loop above — ticks off deadReckoningRepository's own
        // StateFlow (already ~10 Hz, one emission per real accel sample,
        // per BaselineDeadReckoningRepository's own dedup guard) rather
        // than re-deriving a separate tick source. GNSS state is read via
        // `.value` at the same instant (same synchronous-snapshot pattern
        // BaselineDeadReckoningRepository itself already uses to read
        // gnssModeRepository.state.value.mode), so each row is GNSS+DR at
        // the same moment without needing a `combine()` flow.
        driveLogCollectJob = lifecycleScope.launch {
            deadReckoningRepository.state.collect { dr ->
                if (!_driveLogState.value.isLogging) return@collect
                val gnss = gnssModeRepository.state.value
                driveDataLogger.record(
                    timestampNs = System.nanoTime(),
                    gnssMode = gnss.mode.name,
                    gnssFixAccuracyM = gnss.latestFix?.accuracyM,
                    gnssFixAgeMs = gnss.fixAgeMs,
                    gnssSpeedMps = gnss.latestFix?.speedMps,
                    drVelocityEastMps = dr.velocityEastMps,
                    drVelocityNorthMps = dr.velocityNorthMps,
                    linearAccelMagnitudeMps2 = dr.linearAccelMagnitudeMps2,
                    gyroMagnitudeRadPerSec = dr.gyroMagnitudeRadPerSec,
                    isStationary = dr.isStationary,
                    rawLinearAccelMagnitudeMps2 = dr.rawLinearAccelMagnitudeMps2,
                    rawGyroMagnitudeRadPerSec = dr.rawGyroMagnitudeRadPerSec,
                    isTurning = dr.isTurning,
                    gnssBearingDeg = gnss.latestFix?.bearingDeg,
                )
                _driveLogState.value = _driveLogState.value.copy(recordedCount = driveDataLogger.recordedCount)
            }
        }
    }

    override fun onPause() {
        recorderCollectJob?.cancel()
        recorderCollectJob = null
        driveLogCollectJob?.cancel()
        driveLogCollectJob = null
        stopPipeline()
        super.onPause()
    }

    override fun onDestroy() {
        velocityModel?.close()
        reacquisitionDriftModel?.close()
        super.onDestroy()
    }

    private fun startRecording() {
        sensorRecorder.reset()
        lastProcessedRecorderAccelTimestampNs = null
        isPotholeMarkerActive = false
        isPhoneMovedActive = false
        potholeMarkerJob?.cancel()
        _recordingState.value = RecordingUiState(isRecording = true, recordedCount = 0)
    }

    // Re-triggerable: tapping again while a window is already open just
    // restarts the window (covers back-to-back potholes) rather than
    // stacking jobs that could each independently clear the flag early.
    private fun markPothole() {
        potholeMarkerJob?.cancel()
        isPotholeMarkerActive = true
        potholeMarkerJob = lifecycleScope.launch {
            delay(POTHOLE_MARKER_WINDOW_MS)
            isPotholeMarkerActive = false
        }
    }

    private fun togglePhoneMoved() {
        isPhoneMovedActive = !isPhoneMovedActive
    }

    // Writing the JSON off the main thread (Dispatchers.IO) — CLAUDE.md
    // Android Rule 7's "don't block the UI thread" spirit, even though a
    // single capture's file is small; the write happens after
    // isRecording flips to false so no new ticks race with it.
    private fun stopRecordingAndSave() {
        _recordingState.value = _recordingState.value.copy(isRecording = false)
        val json = sensorRecorder.toJsonArray()
        val fileName = "capture_${System.currentTimeMillis()}.json"
        lifecycleScope.launch(Dispatchers.IO) {
            val file = File(getExternalFilesDir(null), fileName)
            file.writeText(json)
            withContext(Dispatchers.Main) {
                _recordingState.value = _recordingState.value.copy(lastSavedFileName = fileName)
            }
        }
    }

    private fun startDriveLog() {
        driveDataLogger.reset()
        _driveLogState.value = DriveLogUiState(isLogging = true, recordedCount = 0)
    }

    // Same off-main-thread write as stopRecordingAndSave() above (CLAUDE.md
    // Android Rule 7) — CSV, not JSON, specifically so scripts/
    // analyze_drive_log.py can load it with pandas.read_csv directly.
    private fun stopDriveLogAndSave() {
        _driveLogState.value = _driveLogState.value.copy(isLogging = false)
        val csv = driveDataLogger.toCsv()
        val fileName = "drive_log_${System.currentTimeMillis()}.csv"
        lifecycleScope.launch(Dispatchers.IO) {
            val file = File(getExternalFilesDir(null), fileName)
            file.writeText(csv)
            withContext(Dispatchers.Main) {
                _driveLogState.value = _driveLogState.value.copy(lastSavedFileName = fileName)
            }
        }
    }
}

@Composable
private fun IdrSensorScreen(
    state: SensorUiState,
    drState: DeadReckoningState,
    gnssState: GnssModeUiState,
    mlState: MlVelocityUiState,
    fusedState: FusedPositionUiState,
    floorState: FloorChangeUiState,
    mlModelLoadError: String?,
    recordingState: RecordingUiState,
    hasRequiredSensors: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    driveLogState: DriveLogUiState,
    onStartDriveLog: () -> Unit,
    onStopDriveLog: () -> Unit,
    isPotholeMarkerActive: Boolean,
    isPhoneMovedActive: Boolean,
    onMarkPothole: () -> Unit,
    onTogglePhoneMoved: () -> Unit,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "IDR MVP — Slice 7: + GNSS/DR position fusion + ML velocity bias calibration")

                Button(onClick = if (recordingState.isRecording) onStopRecording else onStartRecording) {
                    Text(text = if (recordingState.isRecording) "Stop recording" else "Start recording")
                }
                Text(
                    text = if (recordingState.isRecording) {
                        "Recording live sensor data... %d samples captured so far.".format(
                            recordingState.recordedCount,
                        )
                    } else if (recordingState.lastSavedFileName != null) {
                        "Last capture: %d samples saved to %s (app-external-files dir)".format(
                            recordingState.recordedCount, recordingState.lastSavedFileName,
                        )
                    } else {
                        "Not recording. Move the phone after tapping Start — accel/gyro/orientation " +
                            "are logged with millisecond timestamps to a JSON file on Stop."
                    },
                )
                // PRD.md Section 24's self-captured Pothole/Phone-Moved
                // labels (2026-09-01) — only meaningful while recordingState
                // is active, but left tappable regardless so a stray tap
                // before Start is harmless (currentCaptureLabel() is only
                // read from inside the recording loop, which is itself
                // gated on isRecording).
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onMarkPothole) {
                        Text(text = if (isPotholeMarkerActive) "Pothole marked!" else "Mark Pothole")
                    }
                    Button(onClick = onTogglePhoneMoved) {
                        Text(text = if (isPhoneMovedActive) "Phone Moved (tap to end)" else "Mark Phone Moved")
                    }
                }

                // Real test-drive logger (capture/DriveDataLogger.kt,
                // 2026-08-29) — for validating gnss/GnssQuality.kt,
                // gnss/GnssOutageDetector.kt, and dr/StationaryDetector.kt's
                // "engineering default, not yet validated" thresholds
                // against a real drive: tap Start before setting off, tap
                // Stop after, then `adb pull` the saved CSV and run
                // scripts/analyze_drive_log.py against it.
                Button(onClick = if (driveLogState.isLogging) onStopDriveLog else onStartDriveLog) {
                    Text(text = if (driveLogState.isLogging) "Stop drive log" else "Start drive log")
                }
                Text(
                    text = if (driveLogState.isLogging) {
                        "Logging GNSS+DR state for threshold validation... %d ticks captured so far.".format(
                            driveLogState.recordedCount,
                        )
                    } else if (driveLogState.lastSavedFileName != null) {
                        "Last drive log: %d ticks saved to %s (app-external-files dir)".format(
                            driveLogState.recordedCount, driveLogState.lastSavedFileName,
                        )
                    } else {
                        "Not logging. Start before a real test drive, Stop after — saves a CSV of " +
                            "GNSS mode/accuracy/speed + DR velocity/ZUPT state per tick."
                    },
                )

                if (!hasRequiredSensors) {
                    Text(text = "This device is missing an accelerometer, gyroscope, or rotation-vector sensor.")
                    return@Column
                }

                val accel = state.latestAccel
                Text(
                    text = if (accel != null) {
                        "Accel (device frame, m/s^2): x=%.3f y=%.3f z=%.3f".format(
                            accel.xMps2, accel.yMps2, accel.zMps2,
                        )
                    } else {
                        "Accel: waiting for first sample..."
                    },
                )
                Text(text = "Accel observed rate: %.1f Hz".format(state.accelHz))

                val gyro = state.latestGyro
                Text(
                    text = if (gyro != null) {
                        "Gyro (device frame, rad/s): x=%.3f y=%.3f z=%.3f".format(
                            gyro.xRadPerSec, gyro.yRadPerSec, gyro.zRadPerSec,
                        )
                    } else {
                        "Gyro: waiting for first sample..."
                    },
                )
                Text(text = "Gyro observed rate: %.1f Hz".format(state.gyroHz))

                val orientation = state.latestOrientation
                Text(
                    text = if (orientation != null) {
                        // rad -> deg conversion happens only here, at the
                        // human-display boundary (CLAUDE.md Rule 15) —
                        // every internal value stays in radians.
                        "Orientation (device-vs-world frame, deg): " +
                            "azimuth=%.1f pitch=%.1f roll=%.1f".format(
                                Math.toDegrees(orientation.azimuthRad.toDouble()),
                                Math.toDegrees(orientation.pitchRad.toDouble()),
                                Math.toDegrees(orientation.rollRad.toDouble()),
                            )
                    } else {
                        "Orientation: waiting for first sample..."
                    },
                )
                Text(text = "Orientation observed rate: %.1f Hz".format(state.orientationHz))

                Text(
                    text = "Baseline physics DR (WORLD frame, m since GNSS last good): " +
                        "east=%.2f north=%.2f".format(drState.positionEastM, drState.positionNorthM),
                )
                Text(
                    text = "Baseline physics DR velocity (m/s): east=%.2f north=%.2f".format(
                        drState.velocityEastMps, drState.velocityNorthMps,
                    ),
                )
                Text(
                    text = "^ ZUPT (zero-velocity when stationary), a simplified " +
                        "non-holonomic constraint (Slice 5), and (Round 2, 2026-08-28) the " +
                        "same phone-to-vehicle yaw alignment the ML path uses are applied — " +
                        "but there is still no accelerometer bias correction, so still " +
                        "expect drift during real motion.",
                )
                Text(text = "Stop context: ${drState.stationaryContext}")
                Text(
                    text = "^ motion/StopEventClassifier.kt — SUDDEN_STOP fires fast on a " +
                        "post-motion stop (real drive found accel/gyro alone 100% false-" +
                        "negative on real traffic stops); BRIEF_STOP/LONG_IDLE come from the " +
                        "original accel/gyro dwell; MOVING covers real motion AND noisy/" +
                        "unconfirmed near-zero readings (neither gets ZUPT'd).",
                )

                Text(text = "GNSS mode: ${gnssState.mode}")
                if (!gnssState.hasLocationPermission) {
                    Text(text = "Location permission not granted — GNSS mode above reflects 'no fix' only.")
                }
                val fix = gnssState.latestFix
                Text(
                    text = if (fix != null) {
                        "GNSS fix: lat=%.6f lon=%.6f accuracy=%.1fm age=%dms".format(
                            fix.latitudeDeg, fix.longitudeDeg, fix.accuracyM, gnssState.fixAgeMs,
                        )
                    } else {
                        "GNSS fix: none yet"
                    },
                )
                val transition = gnssState.lastTransition
                Text(
                    text = if (transition != null) {
                        "Last transition: ${transition.fromMode} -> ${transition.toMode} " +
                            "(${transition.triggerDescription})"
                    } else {
                        "Last transition: none yet"
                    },
                )

                if (mlModelLoadError != null) {
                    Text(text = "ML velocity model failed to load: $mlModelLoadError")
                } else {
                    Text(
                        text = if (mlState.predictedVelocityDampedMps != null) {
                            "ML predicted forward speed: %.2f m/s damped (corrected=%.2f, raw=%.2f, learned bias=%.2f)".format(
                                mlState.predictedVelocityDampedMps,
                                mlState.predictedVelocityCorrectedMps,
                                mlState.predictedVelocityRawMps,
                                mlState.velocityBiasMps,
                            )
                        } else {
                            "ML predicted forward speed: waiting for first inference..."
                        },
                    )
                    Text(
                        text = "^ bias is an online correction learned from GNSS speed while " +
                            "GNSS_AIDED (PRD Section 17) — held constant, still applied, during " +
                            "an outage; 0.00 until GNSS has been good and moving above ~18 km/h. " +
                            "\"damped\" (Round 2) is corrected + VelocityGuard's OOD guard/EMA " +
                            "smoothing — this is what actually feeds the position below now.",
                    )
                    if (mlState.isVelocityOutOfDistribution) {
                        Text(
                            text = "^ OOD GUARD FIRED this tick — raw prediction was implausible " +
                                "(NaN/infinite or > the plausible-speed bound); held the last " +
                                "accepted value instead of integrating it.",
                        )
                    }
                    Text(
                        text = "ML-based DR position (WORLD frame, m since GNSS last good): " +
                            "east=%.2f north=%.2f".format(mlState.positionEastM, mlState.positionNorthM),
                    )
                    Text(
                        text = "^ position from ML velocity + heading (PRD Section 16), ZUPT " +
                            "applied, non-holonomic satisfied by construction (no lateral " +
                            "component is ever predicted) — compare directly to the physics " +
                            "DR position above; same GNSS-mode-gated reset, same units.",
                    )
                    Text(
                        text = if (mlState.isAligned) {
                            "Phone-to-vehicle yaw alignment: locked, offset=%.1f deg (%d samples)".format(
                                mlState.yawOffsetDeg, mlState.alignmentSampleCount,
                            )
                        } else {
                            "Phone-to-vehicle yaw alignment: not yet established " +
                                "(${mlState.alignmentSampleCount} samples so far — needs sustained " +
                                "straight-line driving above ~18 km/h with GNSS available)"
                        },
                    )
                    Text(
                        text = if (mlState.reducedConfidenceDueToRoll) {
                            "Phone-to-vehicle roll baseline: REDUCED CONFIDENCE — current roll " +
                                "deviates from the stationary-established mounting baseline by more " +
                                "than the motorcycle-lean threshold (PRD Section 15)"
                        } else {
                            "Phone-to-vehicle roll baseline: within normal range"
                        },
                    )
                    Text(
                        text = if (mlState.isCruising) {
                            "Motion state: CRUISING (looks physically still, but the raw model " +
                                "still predicts real speed — ZUPT skipped this tick)"
                        } else {
                            "Motion state: not overridden to cruising this tick"
                        },
                    )
                    Text(
                        text = "^ deterministic stand-in for PRD Section 14's Stationary/Cruising " +
                            "classes (not the trained classifier — see MotionStateClassifier.kt), " +
                            "ML-only so the physics baseline above stays untouched.",
                    )
                    Text(
                        text = if (mlState.potholeShockDetectedThisTick) {
                            "Pothole/shock: DETECTED this tick — forward/lateral accel discounted " +
                                "before feature extraction (and in the physics path too)"
                        } else {
                            "Pothole/shock: none detected this tick"
                        },
                    )
                    Text(
                        text = "^ deterministic vertical-accel threshold, PRD Section 14's Pothole " +
                            "effect — NOT validated against real pothole data (none exists yet).",
                    )
                    Text(
                        text = when {
                            mlState.isAccelerating -> "Longitudinal motion: ACCELERATING this tick"
                            mlState.isBraking -> "Longitudinal motion: BRAKING this tick"
                            else -> "Longitudinal motion: neither Accelerating nor Braking this tick"
                        },
                    )
                    Text(
                        text = "^ deterministic sign/magnitude threshold on the SAME alignment-" +
                            "corrected forward-acceleration feature the ONNX model consumes — PRD " +
                            "Section 14's Accelerating/Braking classes (see " +
                            "LongitudinalMotionClassifier.kt), NOT validated against real labeled data.",
                    )
                    Text(
                        text = if (drState.isTurning) {
                            "Turning: DETECTED this tick — non-holonomic lateral-velocity " +
                                "suppression is relaxed while this is true"
                        } else {
                            "Turning: not detected this tick"
                        },
                    )
                    Text(
                        text = "^ deterministic yaw-rate threshold, PRD Section 14's Turning class " +
                            "(see TurningDetector.kt) — physics-path signal, shown here alongside " +
                            "the other ML-side motion signals for one combined readout.",
                    )
                }

                Text(
                    text = "Fused position (state estimator, WORLD frame, m since GNSS last good): " +
                        "east=%.2f north=%.2f, DR source=%s".format(
                            fusedState.fusedEastM, fusedState.fusedNorthM, fusedState.drSourceUsed,
                        ),
                )
                Text(
                    text = if (fusedState.secondsSinceLastGnssAided == Float.MAX_VALUE) {
                        "^ no GNSS_AIDED fix yet this run — fusion has no anchor to reference."
                    } else {
                        "^ %.1fs since GNSS was last aided — frozen during TRANSITION, blended " +
                            "toward the new fix over REACQUISITION; this is a raw elapsed-time " +
                            "figure, not a formal confidence/uncertainty estimate (Slice 7).".format(
                                fusedState.secondsSinceLastGnssAided,
                            )
                    },
                )

                // Round 2 (2026-08-28 — PRD.md FR12): barometer-based
                // floor/level-change detection.
                Text(
                    text = if (!floorState.hasBarometer) {
                        "Floor detection: this device has no barometer."
                    } else {
                        "Floor detection: relative altitude=%.2fm, total floors changed=%d%s".format(
                            floorState.relativeAltitudeM,
                            floorState.totalFloorsChanged,
                            if (floorState.floorChangeDetected) {
                                " — FLOOR CHANGE just detected (%s)".format(
                                    if (floorState.floorDelta > 0) "up" else "down",
                                )
                            } else {
                                ""
                            },
                        )
                    },
                )
                Text(
                    text = "^ deterministic threshold on relative barometric altitude " +
                        "(PRD Section 2/3's multi-level-parking scenario) — NOT validated " +
                        "against a real multi-level test yet (CLAUDE.md Rule 13).",
                )

                // Round 2 (2026-08-28 — PRD.md Section 11): gravity-sensor
                // cross-check. Purely observational — this does NOT feed
                // the DR pipeline; it lets a real test drive compare our
                // own manual gravity subtraction against Android's own
                // fused sensors before deciding whether to switch
                // (CLAUDE.md Rule 3: a real comparison, not an assumption).
                val ourLinearAccel = if (state.latestAccel != null && state.latestOrientation != null) {
                    val world = WorldFrameAcceleration.rotateDeviceToWorld(
                        deviceX = state.latestAccel.xMps2,
                        deviceY = state.latestAccel.yMps2,
                        deviceZ = state.latestAccel.zMps2,
                        rotationMatrixDeviceToWorld = state.latestOrientation.rotationMatrixDeviceToWorld,
                    )
                    WorldFrameAcceleration.removeGravity(world)
                } else {
                    null
                }
                val ourLinearAccelMagnitude = ourLinearAccel?.let {
                    sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2])
                }
                val androidLinearAccelMagnitude = state.latestLinearAcceleration?.let {
                    sqrt(it.xMps2 * it.xMps2 + it.yMps2 * it.yMps2 + it.zMps2 * it.zMps2)
                }
                Text(
                    text = "Gravity-removal cross-check (m/s^2 magnitude): ours=%s, Android's own=%s".format(
                        ourLinearAccelMagnitude?.let { "%.3f".format(it) } ?: "n/a",
                        androidLinearAccelMagnitude?.let { "%.3f".format(it) } ?: "n/a (no TYPE_LINEAR_ACCELERATION on this device)",
                    ),
                )
                Text(
                    text = "^ instrumentation only (PRD Section 11) — not wired into the DR " +
                        "pipeline; compare these two on a real drive before deciding whether " +
                        "to switch dr/WorldFrameAcceleration's manual subtraction for Android's " +
                        "own fused sensor (CLAUDE.md Rule 3: adopt only on a measured win).",
                )

                Text(
                    text = if (fusedState.roadSnapped) {
                        // distanceToRoadM is always non-null alongside
                        // roadSnapped=true (StateEstimator sets both
                        // together) — the ?: 0.0 is just to keep
                        // String.format's numeric conversion happy with a
                        // non-null Double type, not a real fallback path.
                        "Map constraint: SNAPPED this tick, %.1fm from the active route's road " +
                            "geometry".format(fusedState.distanceToRoadM ?: 0.0)
                    } else {
                        "Map constraint: not snapped this tick (no active route, no GNSS anchor " +
                            "yet, GNSS_AIDED already trusted, moving too slowly for a reliable " +
                            "heading, or nothing within snap range/heading tolerance)"
                    },
                )
                Text(
                    text = "^ PRD Section 19 MVP map constraint (map/MapConstraint.kt) — nearest-" +
                        "road-snap + heading-compatibility check against the active route's real " +
                        "OSRM geometry, NOT a general road dataset or an HMM map matcher.",
                )
            }
        }
    }
}
