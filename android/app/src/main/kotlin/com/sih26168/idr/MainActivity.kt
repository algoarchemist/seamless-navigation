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
import com.sih26168.idr.sensors.SensorRepository
import com.sih26168.idr.sensors.SensorUiState

/**
 * Slice 1 (Android sensor -> live sensor display, per CLAUDE.md's slice
 * order): reads live accelerometer + gyroscope via [SensorRepository]
 * and renders the latest sample plus observed delivery rate. No
 * orientation, fusion, GNSS, or ML yet — those are later slices.
 */
class MainActivity : ComponentActivity() {

    private lateinit var sensorRepository: SensorRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorRepository = SensorRepository(applicationContext)

        setContent {
            val uiState by sensorRepository.state.collectAsState()
            IdrSensorScreen(uiState, sensorRepository.hasRequiredSensors())
        }
    }

    // Sensors are only registered while the activity is visible, so the
    // demo doesn't drain battery/CPU in the background — start/stop is
    // tied to the standard Android lifecycle, not a custom one.
    override fun onResume() {
        super.onResume()
        sensorRepository.start()
    }

    override fun onPause() {
        sensorRepository.stop()
        super.onPause()
    }
}

@Composable
private fun IdrSensorScreen(state: SensorUiState, hasRequiredSensors: Boolean) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "IDR MVP — Slice 1: live sensor display")

                if (!hasRequiredSensors) {
                    Text(text = "This device is missing an accelerometer or gyroscope.")
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
            }
        }
    }
}
