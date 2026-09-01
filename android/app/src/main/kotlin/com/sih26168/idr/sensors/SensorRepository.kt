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
    val latestOrientation: OrientationSample? = null,
    val accelHz: Double = 0.0,
    val gyroHz: Double = 0.0,
    val orientationHz: Double = 0.0,
    /** (Round 2 addition, 2026-08-28 — PRD.md FR12) Null if this device has no barometer (see [SensorRepository.hasBarometer]) or none received yet this run. */
    val latestPressure: PressureSample? = null,
    val pressureHz: Double = 0.0,
    /**
     * (Round 2 addition, 2026-08-28 — PRD.md Section 11's gravity-sensor
     * cross-check) Android's OWN fused, already-gravity-removed
     * accelerometer (TYPE_LINEAR_ACCELERATION), DEVICE frame. Read
     * alongside [latestAccel] purely as an instrumentation cross-check
     * against dr/WorldFrameAcceleration.kt's manual gravity subtraction —
     * NOT yet wired into any position pipeline (PRD.md Section 11: "adopted
     * only if it measures out better... decided empirically, not assumed").
     * Null if unavailable on this device or none received yet.
     */
    val latestLinearAcceleration: AccelSample? = null,
    /** (Round 2 addition, 2026-08-28) Android's own fused gravity vector (TYPE_GRAVITY), DEVICE frame — same cross-check purpose as [latestLinearAcceleration]. */
    val latestGravity: AccelSample? = null,
)

// 100,000 microseconds = 100 ms = ~10 Hz, per PRD.md Section 8/11's target rate.
private const val TARGET_SAMPLING_PERIOD_US = 100_000
private const val TARGET_SAMPLING_PERIOD_NS = TARGET_SAMPLING_PERIOD_US * 1_000L

/**
 * Collects accelerometer + gyroscope samples at ~10 Hz. Listener
 * callbacks run on a dedicated background HandlerThread — never the
 * main/UI thread — per CLAUDE.md Android Rule 7. Results are published
 * through a StateFlow, the thread-safe hand-back point Compose observes;
 * StateFlow's value read/write is safe across threads by construction,
 * so no additional locking is needed here.
 *
 * Slice 1+2 scope: raw accel/gyro (device frame) plus device orientation
 * relative to WORLD frame via the rotation-vector sensor (Slice 2, see
 * [OrientationSample]). Still no gravity removal, no vehicle-frame
 * transform (that needs phone-to-vehicle alignment, PRD.md Section 15,
 * which needs GNSS — a later slice), no GNSS/location.
 *
 * (Round 2, 2026-08-28): also reads barometer (PRD.md FR12) and
 * Android's own fused linear-acceleration/gravity sensors (Section 11's
 * gravity-sensor cross-check) — all three OPTIONAL, unlike
 * accel/gyro/rotation-vector above (see [hasRequiredSensors] vs.
 * [hasBarometer]).
 *
 * REAL BUG FIX (2026-08-29, found via capture/DriveDataLogger.kt's own
 * tick counter during an on-device smoke test): `registerListener`'s
 * requested period (below, [TARGET_SAMPLING_PERIOD_US]) is documented by
 * Android as a HINT, not a guarantee — confirmed NOT honored on the test
 * device (an Oppo/ColorOS phone), which delivered accel/gyro/orientation
 * at up to ~200 Hz regardless, 15-20x the ~10 Hz PRD.md Section 8/11
 * target. That over-delivery was silently reaching every downstream
 * consumer: dr/BaselineDeadReckoningRepository's integrator+ZUPT ran
 * 15-20x more often than designed (wasted CPU/battery, a real jank
 * source), Compose recomposed DriveScreen/MapScreen at the same rate, and
 * worst — ml/MlVelocityRepository ran full ONNX inference on every one of
 * those ticks AND fed features/FeatureExtractor.kt's "~1.0s trailing
 * window" (`WINDOW_SAMPLES = 10`, matched to ml/train_velocity_model.py's
 * OWN ~10 Hz training-data rate) only ~50ms of real elapsed time per
 * update — a silent train/inference parity break, not just a performance
 * one. [listener] now independently throttles PUBLISHING each sensor
 * type to [_state] to real ~10 Hz (see `lastPublished*TimestampNs`
 * below), fixing all of the above at this ONE source point (CLAUDE.md
 * Rule 5) rather than patching every downstream consumer separately. The
 * `accelHz`/`gyroHz`/`orientationHz` fields in [SensorUiState] are
 * DELIBERATELY left computed from the true RAW arrival rate, not gated by
 * this throttle — an "observed rate" that always read back ~10 Hz
 * regardless of real hardware behavior would defeat the whole honest-
 * measurement point of that field (CLAUDE.md Rule 13) and would have
 * hidden this exact bug from ever being found.
 */
class SensorRepository(context: Context) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    // Round 2 additions (2026-08-28): all three optional — not every
    // device has a barometer, and even accelerometer/gyroscope-equipped
    // devices sometimes lack the FUSED linear-acceleration/gravity
    // virtual sensors (they're software-composited by the platform, not
    // guaranteed present). hasRequiredSensors() below deliberately does
    // NOT require any of these — the app must keep working without them.
    private val barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val linearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    private var handlerThread: HandlerThread? = null

    private var lastAccelTimestampNs: Long = 0L
    private var lastGyroTimestampNs: Long = 0L
    private var lastOrientationTimestampNs: Long = 0L
    private var lastPressureTimestampNs: Long = 0L

    // Separate from the RAW-arrival timestamps above (which still track
    // every real event so accelHz/gyroHz/orientationHz stay honest) —
    // these track only the last PUBLISHED sample per type, gating
    // downstream delivery to real ~10 Hz regardless of how fast the
    // hardware actually delivers (see this class's own doc comment).
    private var lastPublishedAccelTimestampNs: Long? = null
    private var lastPublishedGyroTimestampNs: Long? = null
    private var lastPublishedOrientationTimestampNs: Long? = null

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

                    val lastPublished = lastPublishedAccelTimestampNs
                    if (lastPublished != null && event.timestamp - lastPublished < TARGET_SAMPLING_PERIOD_NS) {
                        return
                    }
                    lastPublishedAccelTimestampNs = event.timestamp

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

                    val lastPublished = lastPublishedGyroTimestampNs
                    if (lastPublished != null && event.timestamp - lastPublished < TARGET_SAMPLING_PERIOD_NS) {
                        return
                    }
                    lastPublishedGyroTimestampNs = event.timestamp

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
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val hz = if (lastOrientationTimestampNs != 0L) {
                        SampleRate.hzFromDeltaNs(event.timestamp - lastOrientationTimestampNs)
                    } else {
                        0.0
                    }
                    lastOrientationTimestampNs = event.timestamp

                    val lastPublished = lastPublishedOrientationTimestampNs
                    if (lastPublished != null && event.timestamp - lastPublished < TARGET_SAMPLING_PERIOD_NS) {
                        return
                    }
                    lastPublishedOrientationTimestampNs = event.timestamp

                    // event.values = [x, y, z, (w), (headingAccuracy)] — the
                    // vector part of a device-frame -> world-frame unit
                    // quaternion. w (values[3]) is present on modern devices
                    // but derived defensively if the array is shorter.
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    val w = if (event.values.size > 3) {
                        event.values[3]
                    } else {
                        OrientationMath.scalarFromVectorPart(x, y, z)
                    }
                    // Computed once and reused for both the human-readable
                    // angles and the raw matrix Slice 3 needs, rather than
                    // calling OrientationMath.orientationFromQuaternion
                    // (which would silently redo this same matrix step).
                    val rotationMatrix = OrientationMath.quaternionToRotationMatrix(x, y, z, w)
                    val angles = OrientationMath.rotationMatrixToOrientation(rotationMatrix)

                    _state.value = _state.value.copy(
                        latestOrientation = OrientationSample(
                            timestampNs = event.timestamp,
                            azimuthRad = angles.azimuthRad,
                            pitchRad = angles.pitchRad,
                            rollRad = angles.rollRad,
                            rotationMatrixDeviceToWorld = rotationMatrix.toList(),
                        ),
                        orientationHz = hz,
                    )
                }
                // Round 2 additions (2026-08-28) — PRD.md FR12 (barometer)
                // and Section 11's gravity-sensor cross-check (linear
                // acceleration / gravity). Same observed-rate-tracking
                // pattern as accel/gyro/orientation above for the
                // barometer; linear-acceleration/gravity skip Hz tracking
                // deliberately — they're a one-off instrumentation
                // cross-check (see SensorUiState's doc), not a pipeline
                // dependency whose live rate needs verifying.
                Sensor.TYPE_PRESSURE -> {
                    val hz = if (lastPressureTimestampNs != 0L) {
                        SampleRate.hzFromDeltaNs(event.timestamp - lastPressureTimestampNs)
                    } else {
                        0.0
                    }
                    lastPressureTimestampNs = event.timestamp
                    _state.value = _state.value.copy(
                        latestPressure = PressureSample(timestampNs = event.timestamp, pressureHpa = event.values[0]),
                        pressureHz = hz,
                    )
                }
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    _state.value = _state.value.copy(
                        latestLinearAcceleration = AccelSample(
                            timestampNs = event.timestamp,
                            xMps2 = event.values[0],
                            yMps2 = event.values[1],
                            zMps2 = event.values[2],
                        ),
                    )
                }
                Sensor.TYPE_GRAVITY -> {
                    _state.value = _state.value.copy(
                        latestGravity = AccelSample(
                            timestampNs = event.timestamp,
                            xMps2 = event.values[0],
                            yMps2 = event.values[1],
                            zMps2 = event.values[2],
                        ),
                    )
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    /** Whether the device actually exposes all sensors this repository needs. */
    fun hasRequiredSensors(): Boolean =
        accelerometer != null && gyroscope != null && rotationVector != null

    /** (Round 2 addition, 2026-08-28) Not all devices have a barometer — callers (e.g. motion/FloorChangeRepository.kt) must degrade honestly, not assume it's present. */
    fun hasBarometer(): Boolean = barometer != null

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
        rotationVector?.let {
            sensorManager.registerListener(listener, it, TARGET_SAMPLING_PERIOD_US, bgHandler)
        }
        barometer?.let {
            sensorManager.registerListener(listener, it, TARGET_SAMPLING_PERIOD_US, bgHandler)
        }
        linearAccelerationSensor?.let {
            sensorManager.registerListener(listener, it, TARGET_SAMPLING_PERIOD_US, bgHandler)
        }
        gravitySensor?.let {
            sensorManager.registerListener(listener, it, TARGET_SAMPLING_PERIOD_US, bgHandler)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        handlerThread?.quitSafely()
        handlerThread = null
        lastAccelTimestampNs = 0L
        lastGyroTimestampNs = 0L
        lastOrientationTimestampNs = 0L
        lastPressureTimestampNs = 0L
        lastPublishedAccelTimestampNs = null
        lastPublishedGyroTimestampNs = null
        lastPublishedOrientationTimestampNs = null
    }
}
