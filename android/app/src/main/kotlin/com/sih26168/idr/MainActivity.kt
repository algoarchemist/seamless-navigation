package com.sih26168.idr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.sih26168.idr.sensors.SensorRepository
import com.sih26168.idr.sensors.SensorUiState

/**
 * Slice 1+2+3 (per CLAUDE.md's slice order): reads live accelerometer,
 * gyroscope, and rotation-vector-derived orientation via
 * [SensorRepository]; feeds that into [BaselineDeadReckoningRepository]
 * for a naive WORLD-frame (not vehicle-frame) physics-only position
 * estimate; renders both live. No ML, no GNSS, no state machine yet —
 * those are later slices. Orientation is DEVICE-relative-to-WORLD frame
 * only (CLAUDE.md Rule 9/14) — no vehicle-frame alignment.
 */
class MainActivity : ComponentActivity() {

    private lateinit var sensorRepository: SensorRepository
    private lateinit var deadReckoningRepository: BaselineDeadReckoningRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorRepository = SensorRepository(applicationContext)
        deadReckoningRepository = BaselineDeadReckoningRepository(sensorRepository, lifecycleScope)

        setContent {
            val uiState by sensorRepository.state.collectAsState()
            val drState by deadReckoningRepository.state.collectAsState()
            IdrSensorScreen(uiState, drState, sensorRepository.hasRequiredSensors())
        }
    }

    // Sensors are only registered while the activity is visible, so the
    // demo doesn't drain battery/CPU in the background — start/stop is
    // tied to the standard Android lifecycle, not a custom one.
    override fun onResume() {
        super.onResume()
        sensorRepository.start()
        deadReckoningRepository.start()
    }

    override fun onPause() {
        deadReckoningRepository.stop()
        sensorRepository.stop()
        super.onPause()
    }
}

@Composable
private fun IdrSensorScreen(
    state: SensorUiState,
    drState: DeadReckoningState,
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
                Text(text = "IDR MVP — Slice 3: live sensor + orientation + baseline physics DR")

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
                    text = "Baseline physics DR (WORLD frame, m from start): " +
                        "east=%.2f north=%.2f".format(drState.positionEastM, drState.positionNorthM),
                )
                Text(
                    text = "Baseline physics DR velocity (m/s): east=%.2f north=%.2f".format(
                        drState.velocityEastMps, drState.velocityNorthMps,
                    ),
                )
                Text(
                    text = "^ naive double-integration of raw accel, no ZUPT/bias " +
                        "correction yet — expect rapid, unbounded drift (Slice 3 baseline only).",
                )
            }
        }
    }
}
