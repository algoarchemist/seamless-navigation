package com.sih26168.idr.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live UI-facing snapshot of the most recent accel/gyro samples plus the
 * *observed* delivery rate for each — not the requested rate, since
 * Android does not guarantee the requested sampling period is honored
 * exactly (CLAUDE.md Rule 10 requires this to be verified against real
 * delivery, not assumed).
 */
data class SensorUiState(
    val latestAccel: AccelSample? = null,
    val latestGyro: GyroSample? = null,
    val accelHz: Double = 0.0,
    val gyroHz: Double = 0.0,
)

// 100,000 microseconds = 100 ms = ~10 Hz, per PRD.md Section 8/11's target rate.
private const val TARGET_SAMPLING_PERIOD_US = 100_000

/**
 * Collects accelerometer + gyroscope samples at ~10 Hz. Listener
 * callbacks run on a dedicated background HandlerThread — never the
 * main/UI thread — per CLAUDE.md Android Rule 7. Results are published
 * through a StateFlow, the thread-safe hand-back point Compose observes;
 * StateFlow's value read/write is safe across threads by construction,
 * so no additional locking is needed here.
 *
 * Slice 1 scope only: no filtering, no gravity removal, no vehicle-frame
 * transform, no GNSS/location. This exists to prove sensors can be read
 * live and displayed without blocking the UI thread. Orientation
 * (Slice 2) and everything downstream builds on top of this.
 */
class SensorRepository(context: Context) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var handlerThread: HandlerThread? = null

    private var lastAccelTimestampNs: Long = 0L
    private var lastGyroTimestampNs: Long = 0L

    private val _state = MutableStateFlow(SensorUiState())
    val state: StateFlow<SensorUiState> = _state.asStateFlow()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val hz = if (lastAccelTimestampNs != 0L) {
                        SampleRate.hzFromDeltaNs(event.timestamp - lastAccelTimestampNs)
                    } else {
                        0.0
                    }
                    lastAccelTimestampNs = event.timestamp
                    _state.value = _state.value.copy(
                        latestAccel = AccelSample(
                            timestampNs = event.timestamp,
                            xMps2 = event.values[0],
                            yMps2 = event.values[1],
                            zMps2 = event.values[2],
                        ),
                        accelHz = hz,
                    )
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val hz = if (lastGyroTimestampNs != 0L) {
                        SampleRate.hzFromDeltaNs(event.timestamp - lastGyroTimestampNs)
                    } else {
                        0.0
                    }
                    lastGyroTimestampNs = event.timestamp
                    _state.value = _state.value.copy(
                        latestGyro = GyroSample(
                            timestampNs = event.timestamp,
                            xRadPerSec = event.values[0],
                            yRadPerSec = event.values[1],
                            zRadPerSec = event.values[2],
                        ),
                        gyroHz = hz,
                    )
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    /** Whether the device actually exposes both sensors this repository needs. */
    fun hasRequiredSensors(): Boolean = accelerometer != null && gyroscope != null

    fun start() {
        val thread = HandlerThread("SensorRepositoryThread").apply { start() }
        handlerThread = thread
        val bgHandler = Handler(thread.looper)

        accelerometer?.let {
            sensorManager.registerListener(listener, it, TARGET_SAMPLING_PERIOD_US, bgHandler)
        }
        gyroscope?.let {
            sensorManager.registerListener(listener, it, TARGET_SAMPLING_PERIOD_US, bgHandler)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        handlerThread?.quitSafely()
        handlerThread = null
        lastAccelTimestampNs = 0L
        lastGyroTimestampNs = 0L
    }
}
