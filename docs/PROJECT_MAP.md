# PROJECT_MAP.md — SIH26168 Intelligent Dead Reckoning

Living document. Update this in the same change as any file that is
added, removed, or has its responsibility/interface changed
(CLAUDE.md Rule 6/21). This is written to teach the pipeline, not just
list files — when in doubt, explain *why*, not just *what*.

Status as of last update: **Phase 0 scaffold + Slice 1+2+3 implemented
and on-device verified.** The folder/build-system skeleton
(`## Scaffold Status`) and Slice 1+2+3 (Android sensor -> live sensor
display, sensor -> orientation, sensor -> baseline physics
velocity/position, `## Slice 1+2+3`) are real, build-verified, and now
confirmed running on a real Samsung Galaxy S24 FE (Android 16 / One UI
8.5) with live accel/gyro readout at ~12.5 Hz observed, live
azimuth/pitch/roll orientation, and a live (expectedly drifting)
WORLD-frame position/velocity estimate all confirmed updating on
screen. Everything else under `## Planned File Map` is still a target
derived from PRD.md — no vehicle-frame alignment, ML, state-machine,
fusion, or map-matching code exists yet. Each entry gets flipped from
`PLANNED` to
`IMPLEMENTED` (with the fields below filled in for real) as it is
actually built.

---

## How to read this file

Each real entry, once implemented, should look like:

```
<file path>

Status: IMPLEMENTED / PLANNED
Purpose: <one sentence>
Inputs: <what data/calls it receives>
Outputs: <what it produces>
Connected to: <upstream> -> <this file> -> <downstream>
Important functions/classes: <list>
Important concepts/assumptions: <anything non-obvious, especially units,
  coordinate frames, or thresholds and why they were chosen>
```

---

## Scaffold Status (Phase 0 — implemented)

Repo root now matches CLAUDE.md's File Organization section:
`android/`, `ml/`, `data/`, `models/`, `docs/`, `scripts/`, `tests/`.
`PROJECT_MAP.md` itself was moved from repo root into `docs/` to match
that section (it had been left at root when first drafted).

```
android/settings.gradle.kts, build.gradle.kts, gradle.properties,
  gradle/wrapper/gradle-wrapper.properties
Status: IMPLEMENTED
Purpose: Root Gradle project definition — declares the AGP/Kotlin plugin
  versions once and includes the single :app module. Pins Gradle 8.7 via
  the wrapper properties.
Important concept: the wrapper *jar* (gradle-wrapper.jar) is now
  generated and present (`android/gradlew(.bat)` +
  `gradle/wrapper/gradle-wrapper.jar`) — Gradle 8.7 was downloaded once
  to a scratch location and used to run `gradle wrapper` inside
  `android/`. The project builds headlessly via `./gradlew.bat` with no
  further setup. `android/local.properties` (gitignored, machine-
  specific) points `sdk.dir` at the existing Android SDK install.

android/app/build.gradle.kts
Status: IMPLEMENTED
Purpose: App module build config — Kotlin + Jetpack Compose, minSdk 26
  (floor set by rotation-vector sensor + FusedLocationProvider, per
  PRD.md Section 21), compileSdk/targetSdk 34.
Important concept: dependencies for play-services-location and
  onnxruntime-android are declared now so the module graph resolves, but
  no code uses them yet — LocationRepository.kt and VelocityModel.kt
  /MotionClassifierModel.kt (Section below) are still PLANNED. Declaring
  a dependency early is not the same as building the feature. Also adds
  `kotlinx-coroutines-android` (StateFlow, used by SensorRepository —
  see `## Slice 1`) and a `src/test/kotlin` source set + JUnit4 for
  Slice 1's unit test.
Known issue: the resulting debug APK is ~76 MB, almost entirely
  onnxruntime-android's bundled native libs for every ABI — declared
  ahead of Slice 6 but not yet used. Not a problem for local builds, but
  worth trimming (e.g. `abiFilters`) before a demo APK needs to be
  side-loaded quickly.

android/app/src/main/AndroidManifest.xml
Status: IMPLEMENTED
Purpose: Declares ACCESS_FINE/COARSE_LOCATION, HIGH_SAMPLING_RATE_SENSORS,
  and required accelerometer/gyroscope/GPS hardware features; registers
  MainActivity as launcher.

android/app/src/main/res/values/{strings.xml,themes.xml}
Status: IMPLEMENTED
Purpose: App name string and a minimal Material theme (no custom
  branding yet — not needed for a scaffold).

ml/requirements.txt, ml/README.md
Status: IMPLEMENTED (dependency list only, no scripts yet)
Purpose: Pins the Python packages the offline pipeline will need
  (scikit-learn primary per PRD.md Section 25, onnx/onnxruntime/
  skl2onnx for export+parity checking, pytest for Section 27 ML tests).
Important concept: torch/PyTorch is intentionally NOT listed —
  PRD.md Section 25 only reaches for an MLP if trees prove insufficient
  once real data is inspected in Phase 4, which has not happened. Adding
  it now would be building for a hypothetical, not a measured need.

data/README.md, data/raw/, data/processed/
Status: IMPLEMENTED (directories + convention doc; no data placed yet)
Purpose: Documents where IO-VNBD and any self-captured supplementary
  data will go once Phase 4 starts. Both are gitignored (large binary
  data does not belong in version control).

models/README.md
Status: IMPLEMENTED (convention doc only; no artifacts yet)
Purpose: Documents the `<model>_v<N>.onnx` versioning convention
  (PRD.md Section 25/28) so a demo regression can be traced to a
  specific exported model version. `.onnx`/`.tflite` files are
  gitignored — only the convention is committed, not the binaries.

scripts/README.md
Status: IMPLEMENTED (empty directory + purpose doc)
Purpose: Placeholder for one-off tooling; nothing needed yet.

tests/README.md, tests/unit/, tests/ml/, tests/integration/
Status: IMPLEMENTED (empty directories + purpose doc, per CLAUDE.md
  Rules 18-20 / PRD.md Section 27)
Purpose: Pre-creates the three test categories so the first unit test
  (expected in Slice 2, coordinate transforms) has an obvious home.

.gitignore
Status: IMPLEMENTED
Purpose: Excludes Android/Gradle build output, `local.properties`,
  Python venv/`__pycache__`, raw/processed data, and exported model
  binaries from version control. The Gradle wrapper jar is deliberately
  NOT excluded — it's committed (see `## Scaffold Status` above) so
  `./gradlew` works on a fresh clone with no Android Studio sync first.
```

No ML, state-machine, fusion, or map-matching code exists yet —
everything below this line (past `## Slice 1+2+3`) is still the
Slice-4-onward target, unchanged from the original plan.

---

## Slice 1+2+3 — live sensor display, sensor -> orientation, sensor -> baseline physics velocity/position (implemented)

```
android/app/src/main/kotlin/com/sih26168/idr/sensors/SensorSample.kt
Status: IMPLEMENTED
Purpose: Data classes for a single accelerometer/gyroscope/orientation
  reading.
Outputs: AccelSample(timestampNs, xMps2, yMps2, zMps2),
  GyroSample(timestampNs, xRadPerSec, yRadPerSec, zRadPerSec),
  OrientationSample(timestampNs, azimuthRad, pitchRad, rollRad).
Important concepts/assumptions: AccelSample/GyroSample are DEVICE frame
  (raw, not gravity-compensated, not rotated to vehicle frame) per
  CLAUDE.md Rule 9/14. timestampNs is Android's SensorEvent.timestamp —
  boot-time monotonic nanoseconds, NOT wall-clock — per PRD.md
  Section 11 / CLAUDE.md Rule 14; nothing here reconciles it against
  GNSS/wall-clock time yet, since GNSS isn't read until a later slice.
  OrientationSample is DEVICE-relative-to-WORLD/EARTH frame (Slice 2,
  see OrientationMath.kt below) — explicitly NOT vehicle frame. Vehicle-
  frame alignment (PRD.md Section 15) needs a GNSS-aided initialization
  window and cannot be built before GNSS is read (later slice).
  Extended in Slice 3 with rotationMatrixDeviceToWorld (List<Float>,
  9 elements, row-major) — the same device->world rotation matrix
  azimuth/pitch/roll were extracted from, kept alongside them because
  Slice 3's WorldFrameAcceleration needs to rotate a raw 3D accel vector
  directly; reconstructing a matrix back out of azimuth/pitch/roll would
  be lossier/more error-prone than keeping the one already computed.
  Uses List<Float> rather than FloatArray specifically so this data
  class's generated equals()/hashCode() stay structurally correct
  (Kotlin data classes compare FloatArray fields by reference).

android/app/src/main/kotlin/com/sih26168/idr/sensors/SampleRate.kt
Status: IMPLEMENTED
Purpose: Pure functions converting a timestamp delta (ns) to an observed
  Hz or to elapsed seconds, with no Android dependency so both are
  unit-testable on the plain JVM (CLAUDE.md Rule 19).
Important functions: SampleRate.hzFromDeltaNs(deltaNs: Long): Double;
  SampleRate.secondsFromDeltaNs(deltaNs: Long): Double (added Slice 3,
  for BaselinePhysicsIntegrator's dt). Both return 0.0 for zero/negative
  deltas rather than dividing by zero or returning nonsense negative
  values — callers must treat 0.0 as "skip this step."
Connected to: SensorRepository -> SampleRate -> SensorUiState.accelHz/gyroHz/orientationHz;
  BaselineDeadReckoningRepository -> SampleRate.secondsFromDeltaNs -> BaselinePhysicsIntegrator

android/app/src/main/kotlin/com/sih26168/idr/sensors/SensorRepository.kt
Status: IMPLEMENTED
Purpose: Registers accelerometer + gyroscope + rotation-vector listeners
  at a requested ~10 Hz (100,000 us sampling period, PRD.md
  Section 8/11), converts rotation-vector quaternion samples to
  azimuth/pitch/roll + rotation matrix via OrientationMath, and
  publishes the latest sample of each plus its *observed* delivery rate.
  Still only raw IO + orientation — no physics integration happens here
  (that's BaselineDeadReckoningRepository, a separate consumer, per
  CLAUDE.md Rule 5's one-responsibility-per-file).
Inputs: android.content.Context (for SensorManager).
Outputs: StateFlow<SensorUiState> — {latestAccel, latestGyro,
  latestOrientation, accelHz, gyroHz, orientationHz}.
Connected to: MainActivity -> SensorRepository -> (StateFlow) -> Compose UI
  ; SensorRepository -> OrientationMath (pure function, orientation
  conversion only, no reverse dependency)
Important functions/classes: start()/stop() (lifecycle-tied — called
  from onResume/onPause, not onCreate/onDestroy, so sensors aren't
  active while backgrounded); hasRequiredSensors() (now also requires
  TYPE_ROTATION_VECTOR).
Important concepts/assumptions: listener callbacks run on a dedicated
  background HandlerThread ("SensorRepositoryThread"), never the
  main/UI thread, per CLAUDE.md Android Rule 7. StateFlow is the
  thread-safe hand-back point — its value can be written from the
  background thread and read from Compose on the main thread with no
  extra locking. Requesting a 100 ms period does not guarantee Android
  actually delivers at exactly 10 Hz; accelHz/gyroHz/orientationHz
  expose the real observed rate live so a demo-time stall or platform
  throttling is visible rather than assumed away (CLAUDE.md Rule 10).
  Rotation-vector's scalar quaternion component (w) is read from
  event.values[3] when present, else derived defensively via
  OrientationMath.scalarFromVectorPart (older devices/edge case).

android/app/src/main/kotlin/com/sih26168/idr/sensors/OrientationMath.kt
Status: IMPLEMENTED
Purpose: Pure-Kotlin (no android.* import) conversion from a rotation-
  vector quaternion to azimuth/pitch/roll radians, mirroring AOSP
  SensorManager.getRotationMatrixFromVector + getOrientation exactly so
  on-device output matches this math, not just approximates it.
  Unit-testable on the plain JVM (CLAUDE.md Rule 19), same pattern as
  SampleRate.kt.
Inputs: quaternion (x, y, z, w) — device-frame -> world-frame rotation,
  per Android's TYPE_ROTATION_VECTOR convention.
Outputs: OrientationAngles(azimuthRad, pitchRad, rollRad) — DEVICE
  orientation relative to WORLD/EARTH (ENU) frame. azimuth is
  clockwise-positive from magnetic north (Android's compass-bearing
  convention), NOT the counterclockwise-positive convention a raw
  quaternion rotation about +Z would suggest — verified explicitly in
  OrientationMathTest (see below), not assumed.
Important functions: quaternionToRotationMatrix, rotationMatrixToOrientation,
  orientationFromQuaternion, scalarFromVectorPart (clamps to avoid NaN
  from sqrt(negative) when floating-point error pushes a should-be-unit
  quaternion's vector-part norm fractionally above 1.0). SensorRepository
  now calls quaternionToRotationMatrix + rotationMatrixToOrientation
  directly (rather than the orientationFromQuaternion convenience
  wrapper) so it can keep the intermediate rotation matrix for
  OrientationSample.rotationMatrixDeviceToWorld (Slice 3 need) instead
  of discarding it.
Connected to: SensorRepository -> OrientationMath -> SensorUiState.latestOrientation

android/app/src/main/kotlin/com/sih26168/idr/dr/WorldFrameAcceleration.kt
Status: IMPLEMENTED
Purpose: Pure-Kotlin (no android.* import) math to rotate a raw DEVICE-
  frame accelerometer reading into WORLD frame (East, North, Up) using
  Slice 2's rotation matrix, then subtract standard gravity to get
  motion-caused linear acceleration. Explicitly WORLD frame, NOT vehicle
  frame (CLAUDE.md Rule 9/14) — works regardless of phone mounting,
  which is exactly why it's in-scope before phone-to-vehicle alignment
  (PRD.md Section 15, still PLANNED, needs GNSS) exists.
Inputs: device-frame accel (x, y, z, m/s^2) + rotationMatrixDeviceToWorld
  (List<Float>, 9 elements, from OrientationSample).
Outputs: WORLD-frame linear acceleration (East, North, Up components,
  m/s^2), gravity removed.
Important functions: rotateDeviceToWorld (row-major matrix-vector
  multiply), removeGravity (subtracts STANDARD_GRAVITY_MPS2 = 9.80665
  from the Up component only — a fixed approximation, not a per-location
  measured value; documented as negligible next to raw MEMS bias/noise).
Connected to: BaselineDeadReckoningRepository -> WorldFrameAcceleration -> BaselinePhysicsIntegrator

android/app/src/main/kotlin/com/sih26168/idr/dr/BaselinePhysicsIntegrator.kt
Status: IMPLEMENTED
Purpose: Double-integrates WORLD-frame linear acceleration into a 2D
  (East, North) position/velocity estimate via semi-implicit Euler
  (velocity updated from acceleration first, then position from the new
  velocity — more numerically stable than naive explicit Euler, still a
  one-line-per-step method appropriate for a 36-hour MVP, CLAUDE.md
  Rule 18). This is PRD.md Section 32's "physics baseline" fallback path
  — deliberately naive, with no ZUPT/bias correction/non-holonomic
  constraint (those are Slice 5), so position error is EXPECTED to grow
  rapidly and unboundedly, even sitting still. That is the honest,
  intended behavior of a physics-only baseline, not a bug — its purpose
  is to be the measured reference Slice 6's ML velocity model must beat
  (CLAUDE.md Rule 3).
Inputs: dtSeconds, linearAccelEastMps2, linearAccelNorthMps2 per tick.
Outputs: DeadReckoningState(positionEastM, positionNorthM,
  velocityEastMps, velocityNorthMps) — relative to wherever integration
  started; not yet tied to a real lat/lon (no GNSS fusion until a later
  slice).
Important functions/classes: update() (no-op on dtSeconds <= 0.0, same
  clock-reset guard convention as Slice 1's Hz calculation), reset(),
  currentState().
Connected to: BaselineDeadReckoningRepository -> BaselinePhysicsIntegrator -> DeadReckoningState

android/app/src/main/kotlin/com/sih26168/idr/dr/BaselineDeadReckoningRepository.kt
Status: IMPLEMENTED
Purpose: Android/coroutine glue connecting SensorRepository's raw
  accel + orientation StateFlow to the pure WorldFrameAcceleration +
  BaselinePhysicsIntegrator math, republishing the running position
  estimate as its own StateFlow. Kept as a separate class from
  SensorRepository (CLAUDE.md Rule 5) — SensorRepository owns raw
  sensor IO only, this owns turning that stream into a physics estimate.
Inputs: SensorRepository (read-only, via its StateFlow), a
  CoroutineScope (MainActivity's lifecycleScope) to collect on.
Outputs: StateFlow<DeadReckoningState>.
Connected to: SensorRepository -> BaselineDeadReckoningRepository -> MainActivity (Compose UI)
Important functions/classes: start()/stop() (lifecycle-tied, same
  pattern as SensorRepository), lastProcessedAccelTimestampNs (guards
  against reprocessing — SensorRepository's StateFlow re-emits on every
  gyro/orientation update too, not just accel, so this dedupes by accel
  timestamp before running an integration step).
Important concepts/assumptions: orientation and accel come from
  independent sensor listeners a few ms apart at ~10 Hz; using the
  latest available orientation for the current accel sample is an
  accepted, documented approximation for this baseline (CLAUDE.md
  Rule 9/14), not a silently-ignored timing mismatch.

android/app/src/main/kotlin/com/sih26168/idr/MainActivity.kt
Status: IMPLEMENTED
Purpose: Slice 1+2+3 entry point — instantiates SensorRepository and
  BaselineDeadReckoningRepository, starts/stops both on onResume/onPause,
  and renders the latest accel/gyro/orientation sample plus observed Hz
  and the live baseline-physics position/velocity via a Compose screen
  (IdrSensorScreen).
Connected to: SensorRepository -> MainActivity -> IdrSensorScreen (Compose);
  SensorRepository -> BaselineDeadReckoningRepository -> MainActivity -> IdrSensorScreen
Important concepts/assumptions: orientation is displayed in degrees but
  every internal value stays in radians — the rad->deg conversion
  happens only at this UI display boundary (CLAUDE.md Rule 15), never
  silently earlier in the pipeline. The DR readout is captioned on-screen
  as a naive baseline expected to drift, so a demo viewer isn't misled
  into thinking it's an accurate position (CLAUDE.md Rule 13's honesty
  requirement extended to in-app copy, not just final reported metrics).
  No filtering, no GNSS, no ML yet — purely a live readout proving the
  sensor + orientation + physics-integration pipeline works end-to-end
  without blocking the UI thread.

android/app/src/test/kotlin/com/sih26168/idr/sensors/SampleRateTest.kt
Status: IMPLEMENTED
Purpose: JUnit4 unit test for SampleRate.hzFromDeltaNs and
  secondsFromDeltaNs — 100 ms -> 10 Hz / 0.1 s, 20 ms -> 50 Hz, zero/
  negative delta -> 0.0 (clock-reset guard, both functions). Satisfies
  CLAUDE.md Rule 19 before anything downstream (the live Hz readout,
  and Slice 3's dt calculation) relies on this math.

android/app/src/test/kotlin/com/sih26168/idr/sensors/OrientationMathTest.kt
Status: IMPLEMENTED
Purpose: JUnit4 unit tests for OrientationMath — identity quaternion ->
  zero azimuth/pitch/roll; a known 90-degree yaw quaternion -> -90-degree
  azimuth (catches the clockwise-vs-counterclockwise sign convention
  mismatch between Android's compass-bearing azimuth and a raw
  quaternion rotation — this test caught a wrong sign assumption during
  development, per CLAUDE.md Rule 19's purpose); scalar-derivation
  matches an explicit w; scalar derivation clamps instead of NaN-ing on
  floating-point norm overshoot; identity quaternion -> identity
  rotation matrix.

android/app/src/test/kotlin/com/sih26168/idr/dr/WorldFrameAccelerationTest.kt
Status: IMPLEMENTED
Purpose: JUnit4 unit tests for WorldFrameAcceleration — identity
  rotation leaves a vector unchanged; a non-identity rotation matrix
  correctly routes device axes per a real row-major matrix-vector
  multiply (not a pass-through bug); a stationary at-rest device reading
  is near-zero after removeGravity (the whole-pipeline "sitting still"
  case removeGravity exists to handle); removeGravity only touches the
  Up component, East/North pass through unchanged.

android/app/src/test/kotlin/com/sih26168/idr/dr/BaselinePhysicsIntegratorTest.kt
Status: IMPLEMENTED
Purpose: JUnit4 unit tests for BaselinePhysicsIntegrator — dtSeconds <= 0
  is a no-op; ten ticks of constant 1 m/s^2 east acceleration at
  dt=0.1s match the closed-form semi-implicit-Euler analytic result
  (v=1.0 m/s, pos=0.55 m — hand-derived, not just "close enough");
  reset() zeroes state; East and North integrate independently of each
  other. Satisfies CLAUDE.md Rule 19 before this math is trusted for any
  reported drift number (PRD.md Section 28).
```

**Build verification (this environment):**
- Slice 1 (2026-08-24): `./gradlew.bat test` — BUILD SUCCESSFUL, all 4
  SampleRateTest cases pass. `./gradlew.bat assembleDebug` — BUILD
  SUCCESSFUL, produces `android/app/build/outputs/apk/debug/app-debug.apk`
  (~76 MB — see the "Known issue" note on `app/build.gradle.kts` above).
- Slice 1 on-device (2026-08-25): installed via `./gradlew.bat
  installDebug` on a real Samsung Galaxy S24 FE (SM-S721B, Android 16 /
  One UI 8.5) connected over USB with ADB debugging authorized.
  `MainActivity` launched, live accel/gyro values update on screen, and
  observed sample rate reads **~12.5 Hz** against the requested ~10 Hz
  (100,000 us period) — confirms CLAUDE.md Rule 10 (real observed rate,
  not assumed) and that Android delivered faster than the requested
  period rather than throttling below it.
- Slice 2 (2026-08-25): `./gradlew.bat test` — BUILD SUCCESSFUL, all 9
  tests pass (4 SampleRateTest + 5 OrientationMathTest). One test's
  expected sign was initially wrong (assumed a +90-degree quaternion
  yaw reads as +90-degree azimuth) and the test caught it before it
  reached the device — Android's azimuth is clockwise-positive, a raw
  quaternion rotation about +Z is counterclockwise-positive, so the
  correct expected value is -90 degrees; fixed in the test, not by
  changing the math (the math was transcribed directly from AOSP's own
  algorithm and re-verified by hand). Then `./gradlew.bat installDebug`
  onto the same S24 FE — live orientation (azimuth/pitch/roll, degrees)
  confirmed updating on screen alongside accel/gyro, user-verified.
  Slice 1+2 now fully verified end-to-end on real hardware with real
  sensors, no faked data. Ready to start Slice 3 (sensor -> baseline
  physics velocity/position).
- Slice 3 (2026-08-25): `./gradlew.bat test` — BUILD SUCCESSFUL, all 19
  tests pass (6 SampleRateTest + 5 OrientationMathTest +
  4 WorldFrameAccelerationTest + 4 BaselinePhysicsIntegratorTest).
  Then `./gradlew.bat installDebug` onto the same S24 FE — live
  "Baseline physics DR" east/north position and velocity readout
  confirmed updating on screen alongside accel/gyro/orientation,
  user-verified. Slice 1+2+3 now fully verified end-to-end on real
  hardware with real sensors, no faked data, no ML. Ready to start
  Slice 4 (GNSS outage detection).

---

## Planned File Map (target architecture, per PRD.md Section 10/19)

### android/

```
sensors/SensorRepository.kt (+ SensorSample.kt, SampleRate.kt, OrientationMath.kt)
Status: IMPLEMENTED — see `## Slice 1+2+3` above for full detail.
Purpose: Collect accelerometer + gyroscope + rotation-vector samples
  from Android Sensor APIs at ~10 Hz, timestamp them consistently, and
  convert rotation-vector to device-vs-world azimuth/pitch/roll (+
  rotation matrix).
Outputs: Timestamped AccelSample/GyroSample (device frame, m/s^2,
  rad/s) and OrientationSample (device-vs-world frame, rad + rotation
  matrix) via StateFlow<SensorUiState>.
Connected to: -> MainActivity (live display) and
  -> BaselineDeadReckoningRepository (Slice 3, IMPLEMENTED) both consume
  this. -> FeatureExtractor, -> AlignmentEstimator are still PLANNED
  downstream consumers (Slice 4+) — not wired yet.
Important concept: Android sensor timestamps are boot-time monotonic,
  not wall-clock — must be reconciled explicitly against GNSS time,
  never assumed equal (CLAUDE.md Rule 9/14). Orientation is
  device-relative-to-WORLD frame only (Slice 2) — vehicle-frame
  transform is a separate step (AlignmentEstimator, still PLANNED)
  that needs GNSS and hasn't been built yet.

dr/{WorldFrameAcceleration,BaselinePhysicsIntegrator,BaselineDeadReckoningRepository}.kt
Status: IMPLEMENTED — see `## Slice 1+2+3` above for full detail.
Purpose: Slice 3's naive WORLD-frame (not vehicle-frame) physics-only
  dead-reckoning baseline — rotate raw accel into world frame, remove
  gravity, double-integrate to position/velocity. No ZUPT, no ML, no
  GNSS fusion; expected to drift rapidly by design (PRD.md Section 32
  fallback path / CLAUDE.md Rule 3's required physics comparison point).
Connected to: SensorRepository -> BaselineDeadReckoningRepository -> MainActivity

LocationRepository.kt
Status: PLANNED
Purpose: Collect GNSS fixes (lat/lon, speed, course, accuracy) via
  FusedLocationProvider/LocationManager; expose availability/quality for
  outage detection.
Connected to: -> GnssOutageDetector, -> StateEstimator

FeatureExtractor.kt
Status: PLANNED
Purpose: Compute the windowed feature set (mean/variance/energy of
  vehicle-frame accel + gyro, jerk, etc.) at the same cadence used by
  the trained models — must mirror the Python-side feature logic
  exactly (CLAUDE.md Rule 20).
Connected to: SensorRepository -> FeatureExtractor -> VelocityModel,
  MotionClassifierModel

AlignmentEstimator.kt
Status: PLANNED
Purpose: Estimate phone-to-vehicle pitch/roll/yaw during an
  initialization window (gravity for pitch/roll, GNSS course vs. device
  heading for yaw); re-triggered on a "Phone Moved" classification.
Connected to: SensorRepository, LocationRepository -> FeatureExtractor
  (vehicle-frame transform)

VelocityModel.kt / MotionClassifierModel.kt
Status: PLANNED
Purpose: On-device ONNX/LiteRT inference wrappers for the two trained
  models (PRD.md Sections 13/14).
Connected to: FeatureExtractor -> {VelocityModel, MotionClassifierModel}
  -> StateEstimator

GnssOutageDetector.kt
Status: PLANNED
Purpose: Implement the GNSS_AIDED / TRANSITION / DEAD_RECKONING /
  REACQUISITION state machine with hysteresis (PRD.md Section 18).
Connected to: LocationRepository -> GnssOutageDetector -> StateEstimator

StateEstimator.kt
Status: PLANNED
Purpose: Integrate heading + ML velocity into position, apply ZUPT
  (Stationary) and non-holonomic constraint, blend with GNSS during
  fusion/reacquisition. Supersedes Slice 3's BaselinePhysicsIntegrator
  as the primary estimator once built (Slice 5) — BaselinePhysicsIntegrator
  stays in the codebase as the measured physics-only comparison point
  (CLAUDE.md Rule 3), not deleted.
Connected to: everything above -> StateEstimator -> MapConstraint -> UI

MapConstraint.kt
Status: PLANNED
Purpose: Snap the estimated position to the nearest plausible road
  segment (PRD.md Section 19 — MVP-level, not a full map matcher).

UI (Compose screens)
Status: PLANNED
Purpose: Live map + status header (GNSS state, speed, motion class,
  alignment confidence), vehicle-mode selector (PRD.md Section 22).
```

### ml/

```
inspect_dataset.py
Status: PLANNED
Purpose: First Phase-4 task — inspect IO-VNBD structure, sensors,
  labels, sampling rate, and identify gaps vs. live phone data
  (PRD.md Section 24). Findings get written back into this file once
  known.

feature_extraction.py
Status: PLANNED
Purpose: Python mirror of the Kotlin FeatureExtractor logic, used for
  training. Any divergence between this and FeatureExtractor.kt is a
  top project risk (PRD.md Section 31) and must be tested.

train_velocity_model.py / train_motion_classifier.py
Status: PLANNED
Purpose: Train + evaluate the two models (PRD.md Sections 13/14/25),
  report metrics per PRD.md Section 28, export to ONNX/LiteRT.

export_model.py
Status: PLANNED
Purpose: Convert trained models to ONNX/LiteRT and run an output-parity
  check against the on-device inference path (CLAUDE.md Rule 20).
```

### data/, models/, tests/, scripts/

```
Status: PLANNED — populated as Phases 3–9 proceed. models/ artifacts
must be versioned (e.g. velocity_v1.onnx, velocity_v2.onnx) so a
regression can be traced to a specific model version.
```

---

## Open questions to resolve in Phase 4 (dataset inspection)

- Exact IO-VNBD sensor channels, units, and sampling rate vs. our
  target ~10 Hz.
- Whether IO-VNBD includes labels usable for the motion classifier
  (pothole, phone-moved) or only for velocity/position ground truth.
- Whether self-captured supplementary data will be needed (PRD.md
  Section 24) — record the decision here once made.

## Change log

- Initial skeleton created alongside PRD.md and CLAUDE.md, before any
  implementation. No code exists yet.
- Phase 0 scaffold: created `android/`, `ml/`, `data/`, `models/`,
  `docs/`, `scripts/`, `tests/` per CLAUDE.md File Organization; moved
  `PROJECT_MAP.md` from repo root into `docs/` to match it; added a
  minimal buildable-once-synced Android Gradle+Compose skeleton
  (placeholder `MainActivity`, no sensor/location code), `ml/`
  dependency pins with no training scripts yet, directory-purpose docs
  for `data/`, `models/`, `scripts/`, `tests/`, and a root `.gitignore`.
  See `## Scaffold Status` above for the itemized list. Next: Slice 1
  (Android sensor -> live sensor display).
- Slice 1 implemented: `sensors/{SensorSample,SampleRate,SensorRepository}.kt`
  read live accelerometer + gyroscope at ~10 Hz on a background thread
  and publish via StateFlow; `MainActivity.kt` displays the latest
  sample and observed Hz. Added a JUnit4 unit test for the Hz
  calculation (CLAUDE.md Rule 19). Also installed a headless Gradle 8.7
  toolchain (`android/gradlew(.bat)` + committed wrapper jar) since none
  existed in this environment, and pointed `local.properties` at the
  pre-existing Android SDK. `./gradlew test` and `./gradlew assembleDebug`
  both pass.
- On-device verification (2026-08-25): installed and ran Slice 1 on a
  real Samsung Galaxy S24 FE (Android 16 / One UI 8.5) over USB ADB.
  Live accel/gyro readout confirmed working, observed rate ~12.5 Hz.
  Phase 1 (Slice 1) is now complete and hardware-verified. Next: Slice 2
  (sensor -> orientation).
- Slice 2 implemented (2026-08-25): added `OrientationSample` (device-
  vs-world frame, rad) to `SensorSample.kt` and `OrientationMath.kt` — a
  pure-Kotlin quaternion -> azimuth/pitch/roll conversion mirroring
  AOSP's own SensorManager algorithm, unit-tested on the plain JVM
  (`OrientationMathTest.kt`, 5 cases; CLAUDE.md Rule 19). Registered the
  TYPE_ROTATION_VECTOR listener in `SensorRepository.kt` alongside
  accel/gyro; `MainActivity.kt` now displays live azimuth/pitch/roll in
  degrees (rad->deg conversion only at that display boundary, CLAUDE.md
  Rule 15). Deliberately did NOT build vehicle-frame alignment
  (PRD.md Section 15) in this slice — that needs a GNSS-aided
  initialization window and GNSS isn't read until a later slice.
  `./gradlew test` — all 9 tests pass. Installed on the same S24 FE via
  `./gradlew installDebug`; live orientation readout confirmed updating
  on screen, user-verified. Slice 1+2 complete and hardware-verified.
  Next: Slice 3 (sensor -> baseline physics velocity/position).
- Slice 3 implemented (2026-08-25): added a `dr/` package (deliberately
  separate from `sensors/` — CLAUDE.md Rule 5) with `WorldFrameAcceleration.kt`
  (pure: rotate device-frame accel into WORLD frame via Slice 2's
  rotation matrix, then remove gravity) and `BaselinePhysicsIntegrator.kt`
  (pure: semi-implicit-Euler double integration to a 2D East/North
  position + velocity — PRD.md Section 32's physics-baseline fallback
  path, deliberately naive with no ZUPT/bias correction so it drifts
  fast, by design). `BaselineDeadReckoningRepository.kt` is the Android/
  coroutine glue that collects SensorRepository's StateFlow, dedupes
  by accel timestamp, and feeds the two pure classes. Extended
  `OrientationSample` with `rotationMatrixDeviceToWorld` (List<Float>,
  not FloatArray — avoids Kotlin's data-class array-equality footgun)
  since Slice 3 needs the raw matrix, not just azimuth/pitch/roll;
  `SensorRepository.kt` now keeps that matrix instead of discarding it.
  Added `SampleRate.secondsFromDeltaNs` alongside the existing Hz
  function. `MainActivity.kt` displays the live position/velocity with
  an explicit on-screen caption that it's a naive baseline expected to
  drift (CLAUDE.md Rule 13's honesty requirement applied to in-app copy,
  not just final reported numbers). Added `WorldFrameAccelerationTest.kt`
  and `BaselinePhysicsIntegratorTest.kt` (CLAUDE.md Rule 19) — the
  integrator test checks against a hand-derived closed-form result, not
  just "looks plausible." `./gradlew test` — all 19 tests pass. Installed
  on the same S24 FE via `./gradlew installDebug`; live position/velocity
  readout confirmed updating on screen, user-verified. Slice 1+2+3
  complete and hardware-verified, no ML anywhere yet. Next: Slice 4
  (GNSS outage detection) — needed before phone-to-vehicle alignment
  (PRD.md Section 15) can be attempted, since that needs a GNSS-aided
  initialization window.
