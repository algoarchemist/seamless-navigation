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
import com.sih26168.idr.sensors.SensorRepository
import com.sih26168.idr.sensors.SensorUiState

/**
 * Slice 1+2+3+4+5 (per CLAUDE.md's slice order): reads live
 * accelerometer, gyroscope, and rotation-vector-derived orientation via
 * [SensorRepository]; reads GNSS fixes via [LocationRepository] and runs
 * the GNSS_AIDED/TRANSITION/DEAD_RECKONING/REACQUISITION hysteresis
 * state machine via [GnssModeRepository]; feeds sensors + GNSS mode into
 * [BaselineDeadReckoningRepository] for a WORLD-frame (not vehicle-frame)
 * physics position estimate corrected by ZUPT and a non-holonomic
 * constraint; renders all of it live. No ML, no actual fusion between
 * GNSS and the DR position estimate yet (Slice 7) — those are later
 * slices. Orientation is DEVICE-relative-to-WORLD frame only (CLAUDE.md
 * Rule 9/14) — no vehicle-frame alignment.
 */
class MainActivity : ComponentActivity() {

    private lateinit var sensorRepository: SensorRepository
    private lateinit var deadReckoningRepository: BaselineDeadReckoningRepository
    private lateinit var locationRepository: LocationRepository
    private lateinit var gnssModeRepository: GnssModeRepository

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

        setContent {
            val uiState by sensorRepository.state.collectAsState()
            val drState by deadReckoningRepository.state.collectAsState()
            val gnssState by gnssModeRepository.state.collectAsState()
            IdrSensorScreen(uiState, drState, gnssState, sensorRepository.hasRequiredSensors())
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
    }

    override fun onPause() {
        gnssModeRepository.stop()
        locationRepository.stop()
        deadReckoningRepository.stop()
        sensorRepository.stop()
        super.onPause()
    }
}

@Composable
private fun IdrSensorScreen(
    state: SensorUiState,
    drState: DeadReckoningState,
    gnssState: GnssModeUiState,
    hasRequiredSensors: Boolean,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "IDR MVP — Slice 5: + ZUPT + non-holonomic constraint")

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
            }
        }
    }
}
