# PROJECT_MAP.md — SIH26168 Intelligent Dead Reckoning

Living document. Update this in the same change as any file that is
added, removed, or has its responsibility/interface changed
(CLAUDE.md Rule 6/21). This is written to teach the pipeline, not just
list files — when in doubt, explain *why*, not just *what*.

Status as of last update: **Phase 0 scaffold + Slice 1 implemented and
on-device verified.** The folder/build-system skeleton
(`## Scaffold Status`) and Slice 1 (Android sensor -> live sensor
display, `## Slice 1`) are real, build-verified, and now confirmed
running on a real Samsung Galaxy S24 FE (Android 16 / One UI 8.5) with
live accel/gyro readout at ~12.5 Hz observed. Everything else under
`## Planned File Map` is still a target derived from PRD.md — no
orientation, ML, state-machine, fusion, or map-matching code exists
yet. Each entry gets flipped from `PLANNED` to `IMPLEMENTED` (with the
fields below filled in for real) as it is actually built.

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

No orientation, ML, state-machine, fusion, or map-matching code exists
yet — everything below this line (past `## Slice 1`) is still the
Slice-2-onward target, unchanged from the original plan.

---

## Slice 1 — Android sensor -> live sensor display (implemented)

```
android/app/src/main/kotlin/com/sih26168/idr/sensors/SensorSample.kt
Status: IMPLEMENTED
Purpose: Data classes for a single accelerometer/gyroscope reading.
Outputs: AccelSample(timestampNs, xMps2, yMps2, zMps2),
  GyroSample(timestampNs, xRadPerSec, yRadPerSec, zRadPerSec).
Important concepts/assumptions: values are in DEVICE frame (raw, not
  gravity-compensated, not rotated to vehicle frame — that is Slice 2)
  per CLAUDE.md Rule 9/14. timestampNs is Android's SensorEvent.timestamp
  — boot-time monotonic nanoseconds, NOT wall-clock — per PRD.md
  Section 11 / CLAUDE.md Rule 14; nothing here reconciles it against
  GNSS/wall-clock time yet, since GNSS isn't read until a later slice.

android/app/src/main/kotlin/com/sih26168/idr/sensors/SampleRate.kt
Status: IMPLEMENTED
Purpose: Pure function converting a timestamp delta (ns) to an observed
  Hz, with no Android dependency so it is unit-testable on the plain JVM
  (CLAUDE.md Rule 19).
Important functions: SampleRate.hzFromDeltaNs(deltaNs: Long): Double —
  returns 0.0 for zero/negative deltas rather than dividing by zero or
  returning a nonsense negative rate.
Connected to: SensorRepository -> SampleRate -> SensorUiState.accelHz/gyroHz

android/app/src/main/kotlin/com/sih26168/idr/sensors/SensorRepository.kt
Status: IMPLEMENTED
Purpose: Registers accelerometer + gyroscope listeners at a requested
  ~10 Hz (100,000 us sampling period, PRD.md Section 8/11) and publishes
  the latest sample of each plus its *observed* delivery rate.
Inputs: android.content.Context (for SensorManager).
Outputs: StateFlow<SensorUiState> — {latestAccel, latestGyro, accelHz, gyroHz}.
Connected to: MainActivity -> SensorRepository -> (StateFlow) -> Compose UI
Important functions/classes: start()/stop() (lifecycle-tied — called
  from onResume/onPause, not onCreate/onDestroy, so sensors aren't
  active while backgrounded); hasRequiredSensors().
Important concepts/assumptions: listener callbacks run on a dedicated
  background HandlerThread ("SensorRepositoryThread"), never the
  main/UI thread, per CLAUDE.md Android Rule 7. StateFlow is the
  thread-safe hand-back point — its value can be written from the
  background thread and read from Compose on the main thread with no
  extra locking. Requesting a 100 ms period does not guarantee Android
  actually delivers at exactly 10 Hz; accelHz/gyroHz expose the real
  observed rate live so a demo-time stall or platform throttling is
  visible rather than assumed away (CLAUDE.md Rule 10).

android/app/src/main/kotlin/com/sih26168/idr/MainActivity.kt
Status: IMPLEMENTED
Purpose: Slice 1 entry point — instantiates SensorRepository, starts/
  stops it on onResume/onPause, and renders the latest accel/gyro sample
  plus observed Hz via a Compose screen (IdrSensorScreen).
Connected to: SensorRepository -> MainActivity -> IdrSensorScreen (Compose)
Important concepts/assumptions: no orientation math, no filtering, no
  GNSS — purely a live readout to prove the sensor pipeline works
  end-to-end without blocking the UI thread.

android/app/src/test/kotlin/com/sih26168/idr/sensors/SampleRateTest.kt
Status: IMPLEMENTED
Purpose: JUnit4 unit test for SampleRate.hzFromDeltaNs — 100 ms -> 10 Hz,
  20 ms -> 50 Hz, zero delta -> 0.0, negative delta -> 0.0 (clock-reset
  guard). Satisfies CLAUDE.md Rule 19 before anything downstream (the
  live Hz readout) relies on this math.
```

**Build verification (this environment, 2026-08-24):**
- `./gradlew.bat test` — BUILD SUCCESSFUL, all 4 SampleRateTest cases pass.
- `./gradlew.bat assembleDebug` — BUILD SUCCESSFUL, produces
  `android/app/build/outputs/apk/debug/app-debug.apk` (~76 MB — see the
  "Known issue" note on `app/build.gradle.kts` above).
- **On-device verification (2026-08-25): done.** Installed via
  `./gradlew.bat installDebug` on a real Samsung Galaxy S24 FE
  (SM-S721B, Android 16 / One UI 8.5) connected over USB with ADB
  debugging authorized. `MainActivity` launched, live accel/gyro values
  update on screen, and observed sample rate reads **~12.5 Hz** against
  the requested ~10 Hz (100,000 us period) — confirms CLAUDE.md Rule 10
  (real observed rate, not assumed) and that Android delivered faster
  than the requested period rather than throttling below it. Slice 1 is
  now fully verified end-to-end on real hardware with real sensors, no
  faked data. Ready to start Slice 2 (sensor -> orientation).

---

## Planned File Map (target architecture, per PRD.md Section 10/19)

### android/

```
sensors/SensorRepository.kt (+ SensorSample.kt, SampleRate.kt)
Status: IMPLEMENTED — see `## Slice 1` above for full detail.
Purpose: Collect accelerometer + gyroscope samples from Android Sensor
  APIs at ~10 Hz and timestamp them consistently.
Outputs: Timestamped AccelSample/GyroSample objects (device frame,
  m/s^2, rad/s) via StateFlow<SensorUiState>.
Connected to: currently -> MainActivity (live display) only.
  -> FeatureExtractor, -> AlignmentEstimator are still PLANNED
  downstream consumers (Slice 2+) — not wired yet.
Important concept: Android sensor timestamps are boot-time monotonic,
  not wall-clock — must be reconciled explicitly against GNSS time,
  never assumed equal (CLAUDE.md Rule 9/14). No rotation-vector sensor
  is read yet; that belongs to Slice 2 (orientation), not Slice 1.

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
  fusion/reacquisition.
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
  See `## Slice 1` above for detail. Phase 1 (Slice 1) is now complete
  and hardware-verified. Next: Slice 2 (sensor -> orientation).
