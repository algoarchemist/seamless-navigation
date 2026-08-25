package com.sih26168.idr

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.sih26168.idr.dr.BaselineDeadReckoningRepository
import com.sih26168.idr.dr.DeadReckoningState
import com.sih26168.idr.gnss.GnssModeRepository
import com.sih26168.idr.gnss.GnssModeUiState
import com.sih26168.idr.gnss.LocationRepository
import com.sih26168.idr.ml.MlVelocityRepository
import com.sih26168.idr.ml.MlVelocityUiState
import com.sih26168.idr.ml.VelocityModel
import com.sih26168.idr.sensors.SensorRepository
import com.sih26168.idr.sensors.SensorUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Slice 1+2+3+4+5+6 (per CLAUDE.md's slice order): reads live
 * accelerometer, gyroscope, and rotation-vector-derived orientation via
 * [SensorRepository]; reads GNSS fixes via [LocationRepository] and runs
 * the GNSS_AIDED/TRANSITION/DEAD_RECKONING/REACQUISITION hysteresis
 * state machine via [GnssModeRepository]; feeds sensors + GNSS mode into
 * [BaselineDeadReckoningRepository] for a WORLD-frame physics position
 * estimate corrected by ZUPT and a non-holonomic constraint; ALSO feeds
 * sensors + GNSS into [MlVelocityRepository] for a live ML-predicted
 * velocity, displayed side by side with the physics estimate (Slice 6
 * — see that class's doc for why it's parallel, not a replacement, yet).
 * Orientation is DEVICE-relative-to-WORLD frame only (CLAUDE.md
 * Rule 9/14) — no vehicle-frame alignment for the position estimate
 * (AlignmentEstimator only feeds the ML feature path so far).
 */
class MainActivity : ComponentActivity() {

    private lateinit var sensorRepository: SensorRepository
    private lateinit var deadReckoningRepository: BaselineDeadReckoningRepository
    private lateinit var locationRepository: LocationRepository
    private lateinit var gnssModeRepository: GnssModeRepository
    private var mlVelocityRepository: MlVelocityRepository? = null
    private var velocityModel: VelocityModel? = null

    // Reported via a StateFlow rather than a thrown exception surfaced
    // only in Logcat — a missing/corrupt bundled model is a real,
    // demo-relevant failure mode (e.g. forgot to copy the gitignored
    // asset per docs/PROJECT_MAP.md) and should be visible on screen.
    private val _mlModelLoadError = MutableStateFlow<String?>(null)
    private val mlModelLoadError: StateFlow<String?> = _mlModelLoadError.asStateFlow()

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
        sensorRepository = SensorRepository(applicationContext)
        locationRepository = LocationRepository(applicationContext)
        // GnssModeRepository must exist before BaselineDeadReckoningRepository —
        // Slice 5 wires the DR odometer's reset behavior to the GNSS mode.
        gnssModeRepository = GnssModeRepository(locationRepository, lifecycleScope)
        deadReckoningRepository = BaselineDeadReckoningRepository(sensorRepository, gnssModeRepository, lifecycleScope)

        try {
            val model = VelocityModel.loadFromAssets(applicationContext)
            velocityModel = model
            mlVelocityRepository = MlVelocityRepository(sensorRepository, gnssModeRepository, model, lifecycleScope)
        } catch (e: Exception) {
            // Loading a bundled asset / building an ONNX session can fail
            // in ways specific to the model file (missing, truncated,
            // schema mismatch) — caught here rather than crashing the
            // whole app, since Slice 1-5 must keep working even if the
            // ML half isn't wired up on this build.
            _mlModelLoadError.value = e.message ?: e::class.simpleName ?: "unknown error"
        }

        setContent {
            val uiState by sensorRepository.state.collectAsState()
            val drState by deadReckoningRepository.state.collectAsState()
            val gnssState by gnssModeRepository.state.collectAsState()
            val mlState by (mlVelocityRepository?.state ?: MutableStateFlow(MlVelocityUiState())).collectAsState()
            val mlError by mlModelLoadError.collectAsState()
            IdrSensorScreen(uiState, drState, gnssState, mlState, mlError, sensorRepository.hasRequiredSensors())
        }
    }

    // Sensors/GNSS are only active while the activity is visible, so the
    // demo doesn't drain battery/CPU in the background — start/stop is
    // tied to the standard Android lifecycle, not a custom one.
    override fun onResume() {
        super.onResume()
        sensorRepository.start()
        gnssModeRepository.start()
        if (locationRepository.hasLocationPermission()) {
            locationRepository.start()
        } else {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // Started after gnssModeRepository so its GNSS-mode-gated reset
        // (Slice 5) reads an already-ticking mode, not just the default.
        deadReckoningRepository.start()
        mlVelocityRepository?.start()
    }

    override fun onPause() {
        mlVelocityRepository?.stop()
        gnssModeRepository.stop()
        locationRepository.stop()
        deadReckoningRepository.stop()
        sensorRepository.stop()
        super.onPause()
    }

    override fun onDestroy() {
        velocityModel?.close()
        super.onDestroy()
    }
}

@Composable
private fun IdrSensorScreen(
    state: SensorUiState,
    drState: DeadReckoningState,
    gnssState: GnssModeUiState,
    mlState: MlVelocityUiState,
    mlModelLoadError: String?,
    hasRequiredSensors: Boolean,
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
                Text(text = "IDR MVP — Slice 6: + ML velocity + ML position (side by side with physics)")

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
                    text = "^ ZUPT (zero-velocity when stationary) and a simplified " +
                        "non-holonomic constraint are applied (Slice 5), but there is " +
                        "still no accelerometer bias correction and no vehicle-frame " +
                        "alignment — still expect drift during real motion.",
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
                        text = if (mlState.predictedVelocityMps != null) {
                            "ML predicted forward speed: %.2f m/s (compare to physics DR velocity above)".format(
                                mlState.predictedVelocityMps,
                            )
                        } else {
                            "ML predicted forward speed: waiting for first inference..."
                        },
                    )
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
                }
            }
        }
    }
}
