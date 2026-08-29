# PROJECT_MAP.md — SIH26168 Intelligent Dead Reckoning

Living document. Update this in the same change as any file that is
added, removed, or has its responsibility/interface changed
(CLAUDE.md Rule 6/21). This is written to teach the pipeline, not just
list files — when in doubt, explain *why*, not just *what*.

Status as of last update: **Slices 1-8b implemented and on-device
verified**, plus an explicit user-approved scope expansion into full
turn-by-turn navigation (routing/geocoding/offline tile caching) beyond
PRD.md's original Section 7/22 boundaries. The Android app has live
sensors, orientation, a physics+ZUPT dead-reckoning position, a real
GNSS-outage state machine with hysteresis, an ONNX velocity model
running on-device (measured ~4.2x more accurate than the physics
baseline — see `## Phase 4` findings below), GNSS/DR fusion with
re-alignment on reacquisition, two deterministic (not yet ML-trained)
motion-classification stand-ins, a Figma-derived UI with Drive/Map/
History tabs, real OpenStreetMap street tiles, and full search ->
route -> turn-by-turn navigation against live Nominatim/OSRM services.
`train_motion_classifier.py` remains PLANNED (blocked on self-captured
Pothole/Phone-Moved labels IO-VNBD doesn't provide). A true outdoor
GNSS_AIDED lock (needed to verify TRANSITION/REACQUISITION blending and
live turn-by-turn progress against real motion) has not yet happened
this project — every fix so far has come from indoor/marginal-signal
on-device testing, which has itself surfaced and fixed several real
bugs (noisy Doppler speed, REACQUISITION flapping, a missing map
anchor, tile-loading fights with `animateTo`, and others).

**The full chronological history — every dated bug fix, scope decision,
and on-device verification — now lives in `summary.txt` at the repo
root, not here.** This file stays focused on current-state structure:
what each file's responsibility, inputs/outputs, and non-obvious
assumptions are *right now* (CLAUDE.md Rule 21). Read `summary.txt`
for the story of how it got there.

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
  /MotionClassifierModel.kt (Section below) are still PLANNED at this
  point in the timeline (Phase 0). UPDATE: both LocationRepository.kt
  (Slice 4) and VelocityModel.kt (Slice 6) are now IMPLEMENTED and
  actually use these dependencies — only MotionClassifierModel.kt
  remains planned. Declaring a dependency early is not the same as
  building the feature, but by Slice 6 that gap has closed for onnxruntime.
  Also adds
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

No fusion or map-matching code exists yet — everything below this line
(past `## Slice 1-5`) is still a target, unchanged from the original
plan, EXCEPT the Slice 6 ML files (alignment/, features/, ml/ packages,
further down in this same "Planned File Map" section) — those ARE
implemented; the "Planned File Map" heading refers to the map's
original scope, not every entry's current status, and each entry's own
`Status:` line is the actual source of truth.

---

## Slice 1-5 — live sensor display, sensor -> orientation, sensor -> baseline physics velocity/position, GNSS outage detection, dead reckoning state-machine wiring + ZUPT + non-holonomic constraint (implemented)

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
REAL BUG FIX (2026-08-29, found via capture/DriveDataLogger.kt's own
  tick counter during an on-device smoke test): confirmed the requested
  100ms/10Hz period is a HINT Android does not enforce — the test device
  (Oppo/ColorOS) delivered accel/gyro/orientation at up to ~200 Hz
  regardless, 15-20x the PRD.md Section 8/11 target. That reached every
  downstream consumer unthrottled: BaselineDeadReckoningRepository's
  integrator+ZUPT and MlVelocityRepository's full ONNX inference both ran
  15-20x more often than designed, AND features/FeatureExtractor.kt's
  "~1.0s trailing window" (matched to ml/train_velocity_model.py's own
  ~10 Hz training rate) was actually only spanning ~50ms of real time per
  update — a silent train/inference parity break, not just wasted CPU/
  battery/recomposition. Fixed by independently throttling PUBLISHING
  each sensor type to real ~10 Hz inside the listener (separate
  `lastPublished*TimestampNs` trackers, gated on real elapsed time before
  `_state.value` is updated) — fixed once at this source point (CLAUDE.md
  Rule 5) rather than patched in every downstream consumer.
  accelHz/gyroHz/orientationHz are DELIBERATELY left computed from the
  true RAW arrival rate (unthrottled) so the observed-rate readout stays
  honest (CLAUDE.md Rule 13) — throttling that too would have hidden this
  exact bug. Verified on-device: pulled a drive-log CSV before and after
  the fix — mean inter-tick interval went from ~5-7ms (unthrottled) to a
  measured 100.2ms (9.98 Hz) after, matching the target almost exactly.

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
  existed. UPDATE (Slice 6): AlignmentEstimator.kt is now IMPLEMENTED,
  but only feeds MlVelocityRepository's ML feature path — this file
  (and BaselineDeadReckoningRepository's physics position estimate) are
  still deliberately alignment-free, per Slice 5's original design.
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
  — [update] itself still has no bias correction, but ZUPT and the
  non-holonomic constraint (Slice 5, see StationaryDetector.kt /
  NonHolonomicConstraint.kt below) are now applied by the caller via
  overrideVelocity() after each update(), materially reducing at-rest
  drift versus the Slice 3/4 baseline (measured: sub-2m -> near-zero
  over 15+ seconds at rest, see Slice 5's build-verification entry).
  update() alone, without those corrections, remains the honest
  physics-only reference Slice 6's ML velocity model must beat
  (CLAUDE.md Rule 3).
Inputs: dtSeconds, linearAccelEastMps2, linearAccelNorthMps2 per tick
  (update()); velocityEastMps, velocityNorthMps (overrideVelocity()).
Outputs: DeadReckoningState(positionEastM, positionNorthM,
  velocityEastMps, velocityNorthMps) — relative to wherever integration
  started; not yet tied to a real lat/lon (no GNSS fusion until Slice 7).
Important functions/classes: update() (no-op on dtSeconds <= 0.0, same
  clock-reset guard convention as Slice 1's Hz calculation);
  overrideVelocity() (added Slice 5 — sets velocity only, leaves
  position untouched, unlike reset() which zeroes both; the mechanism
  ZUPT and the non-holonomic constraint both use); reset(); currentState().
Connected to: BaselineDeadReckoningRepository -> BaselinePhysicsIntegrator -> DeadReckoningState

android/app/src/main/kotlin/com/sih26168/idr/dr/StationaryDetector.kt
Status: IMPLEMENTED
Purpose: Deterministic (no ML) "is the phone stationary right now"
  detector gating the ZUPT correction. PRD.md Section 14 frames
  Stationary->ZUPT as an ML motion-classifier effect, but that
  classifier is Slice 6 — CLAUDE.md's slice order puts ZUPT in Slice 5,
  before ML, so this is a lightweight physics-only stand-in: sustained
  low linear-acceleration-magnitude AND low gyro-magnitude, with the
  same hysteresis/dwell principle as GnssOutageDetector (CLAUDE.md
  Rule 16's spirit, applied here even though that rule technically only
  names the GNSS state machine).
Inputs: nowMs (boot-time ms, not wall-clock — only relative durations
  matter here), linearAccelMagnitudeMps2, gyroMagnitudeRadPerSec.
Outputs: Boolean (also readable via isStationary).
Important concepts/assumptions: HONEST LIMITATION — constant-velocity
  straight-line motion also produces near-zero acceleration/gyro, so
  this cannot distinguish "truly at rest" from "smoothly coasting."
  A real system would additionally gate on GNSS speed or the eventual
  ML classifier; neither is available here. Thresholds (0.25 m/s^2,
  0.05 rad/s, 300ms dwell) are engineering defaults, not yet validated
  against a real test drive (CLAUDE.md Rule 13).
Connected to: BaselineDeadReckoningRepository -> StationaryDetector -> BaselinePhysicsIntegrator.overrideVelocity

android/app/src/main/kotlin/com/sih26168/idr/dr/NonHolonomicConstraint.kt
Status: IMPLEMENTED
Purpose: PRD.md Section 20's non-holonomic constraint (a road vehicle
  can't move sideways relative to its heading) — pure vector projection
  suppressing the velocity component perpendicular to heading.
Inputs: velocityEastMps, velocityNorthMps, headingRad (device azimuth).
Outputs: Pair<Double, Double> (forward-only East/North velocity).
Important concepts/assumptions: PRD.md Section 20 specifies this in
  VEHICLE frame with a Turning exemption from the ML motion classifier.
  Neither exists yet, so this is a deliberately simplified WORLD-frame
  stand-in: it uses the device's own WORLD-frame heading as a proxy for
  vehicle heading, under the explicit assumption the phone's yaw tracks
  the vehicle's yaw (true if rigidly mounted; false if loose, e.g. a
  cup holder). No Turning exemption either, so a genuine turn's real
  lateral velocity gets suppressed too — an accepted, documented
  over-constraint for this slice, to be relaxed once Slice 6's motion
  classifier can flag Turning windows.
Connected to: BaselineDeadReckoningRepository -> NonHolonomicConstraint -> BaselinePhysicsIntegrator.overrideVelocity

android/app/src/main/kotlin/com/sih26168/idr/dr/BaselineDeadReckoningRepository.kt
Status: IMPLEMENTED
Purpose: Android/coroutine glue connecting SensorRepository's raw
  accel/gyro/orientation StateFlow to the pure WorldFrameAcceleration +
  BaselinePhysicsIntegrator math; applies StationaryDetector's ZUPT and
  NonHolonomicConstraint's lateral suppression each tick (Slice 5); gates
  the position "odometer" on GnssModeRepository's mode (Slice 5's "state
  machine" wiring); republishes the running position estimate as its own
  StateFlow. Kept as a separate class from SensorRepository (CLAUDE.md
  Rule 5) — SensorRepository owns raw sensor IO only, this owns turning
  that stream into a corrected physics estimate.
Inputs: SensorRepository (read-only, via its StateFlow),
  GnssModeRepository (read-only, via its StateFlow, added Slice 5), a
  CoroutineScope (MainActivity's lifecycleScope) to collect on, an
  optional StationaryDetector (defaulted).
Outputs: StateFlow<DeadReckoningState>.
Connected to: SensorRepository -> BaselineDeadReckoningRepository -> MainActivity (Compose UI);
  GnssModeRepository -> BaselineDeadReckoningRepository (mode-gated reset);
  motion/PotholeShockDetector -> BaselineDeadReckoningRepository (2026-08-25)
Important functions/classes: start()/stop() (lifecycle-tied, same
  pattern as SensorRepository), lastProcessedAccelTimestampNs: Long?
  (guards against reprocessing — SensorRepository's StateFlow re-emits
  on every gyro/orientation update too, not just accel, so this dedupes
  by accel timestamp before running an integration step).
Important concepts/assumptions: orientation and accel come from
  independent sensor listeners a few ms apart at ~10 Hz; using the
  latest available orientation for the current accel sample is an
  accepted, documented approximation for this baseline (CLAUDE.md
  Rule 9/14), not a silently-ignored timing mismatch. GNSS-mode gating
  (Slice 5): while mode == GNSS_AIDED, the integrator is reset every
  tick, so the moment GNSS is lost, the DR readout starts counting from
  zero — representing distance traveled purely during THIS outage
  (PRD.md Section 28's actual measurement target), not accumulated
  drift since app launch. This class itself does NOT fuse GNSS and DR
  positions together — that blending lives in fusion/PositionFusion.kt +
  fusion/StateEstimator.kt (Slice 7), which read this repository's
  position as one of two possible DR inputs.
  UPDATE (2026-08-25, same day): owns a `motion/PotholeShockDetector`
  instance — before `integrator.update()`, a detected vertical shock
  (`potholeShockDetector.isShock(linearAccel[2])`) zeroes the East/North
  linear-accel components passed in for that tick (PRD.md Section 14's
  Pothole "discount the acceleration sample(s)" effect). Not separately
  surfaced in the UI here — see MlVelocityRepository's matching entry,
  which applies the identical detector to its own path and IS shown on
  screen; showing the same per-tick event twice would be redundant.
Bug found + fixed during Slice 4 on-device verification (2026-08-25):
  lastProcessedAccelTimestampNs was originally a Long defaulting to 0L
  as the "no sample yet" sentinel. On the very first accel sample of a
  run, that made dt compute as (accel.timestampNs - 0L) — the device's
  entire boot-time uptime in nanoseconds (thousands of seconds) — fed
  straight into BaselinePhysicsIntegrator as one massive spurious dt on
  a single tick. Observed on-device as position/velocity jumping to
  ~1e11 m / ~65,000 m/s immediately on launch, instead of the small,
  plausible near-zero drift expected for a stationary phone. Root cause
  was NOT reusing the exact same "!= 0L" first-sample guard
  SensorRepository already uses correctly for its Hz calculation.
  Fixed by changing the field to a nullable Long (null = genuinely no
  prior sample), which makes the bug class structurally impossible
  rather than just patching the one call site. Re-verified on the same
  S24 FE: position/velocity now stay small (sub-2m, sub-0.1 m/s) at
  rest, as expected. (Slice 5's ZUPT then reduced that further to
  near-zero — see build-verification entry below.)

android/app/src/main/kotlin/com/sih26168/idr/gnss/GnssFix.kt
Status: IMPLEMENTED
Purpose: A single GNSS fix, reduced from android.location.Location to
  what the outage detector needs.
Outputs: GnssFix(timeMs, latitudeDeg, longitudeDeg, accuracyM, speedMps,
  bearingDeg).
Important concepts/assumptions: timeMs is WALL-CLOCK
  (System.currentTimeMillis()-based, from Location.getTime()) — unlike
  sensor timestamps (boot-time monotonic, see sensors/SensorSample.kt).
  The two clocks are still NOT directly reconciled against each other
  (CLAUDE.md Rule 9/14) even after Slice 7 — fusion/StateEstimator.kt
  uses its own wall-clock `nowMs` (consistent with this file's own
  clock family) and only reads the DR repositories' already-computed
  position deltas, it never mixes a boot-time sensor timestamp with a
  wall-clock GNSS timestamp directly. No consumer has needed to bridge
  the two clock families yet.

android/app/src/main/kotlin/com/sih26168/idr/gnss/GnssQuality.kt
Status: IMPLEMENTED
Purpose: Pure function classifying whether a fix is "good enough to
  navigate on right now" from its age and accuracy — deliberately
  separate from GnssOutageDetector (CLAUDE.md Rule 5): this answers
  "is GNSS good this instant," the detector answers "given a history of
  that, what state are we in."
Inputs: fixAgeMs, accuracyM (nullable — null means no fix ever
  received), maxFixAgeMs (default 3000ms), maxAccuracyM (default 25m).
Outputs: Boolean.
Important concepts/assumptions: thresholds are engineering defaults,
  not yet validated against a real outage test run (PRD.md Section 28)
  — not to be reported to judges as measured figures (CLAUDE.md Rule 13).
Connected to: GnssModeRepository -> GnssQuality -> GnssOutageDetector

android/app/src/main/kotlin/com/sih26168/idr/gnss/GnssOutageDetector.kt
Status: IMPLEMENTED
Purpose: The GNSS_AIDED / TRANSITION / DEAD_RECKONING / REACQUISITION
  hysteresis state machine (PRD.md Section 18). Pure Kotlin — no
  Android dependency — driven by repeated evaluate(nowMs, gnssGoodNow)
  calls, so the dwell-time/hysteresis logic is unit-testable on the
  plain JVM with a synthetic time sequence (CLAUDE.md Rule 19).
Inputs: nowMs (wall-clock), gnssGoodNow (from GnssQuality) per call.
Outputs: current GnssMode; a running List<GnssModeTransition> log
  (fromMode, toMode, atMs, triggerDescription) satisfying CLAUDE.md
  Rule 17's "every transition logged for replay" requirement at the
  pure-math layer (GnssModeRepository additionally Logcats each one).
Important functions/classes: evaluate() (the state machine step);
  outageEnterDwellMs / reacquisitionEnterDwellMs / transitionDwellMs /
  reacquisitionDwellMs (constructor params, defaults 2000/2000/1000/
  1000ms — engineering defaults, not yet empirically validated).
Important concepts/assumptions: hysteresis (CLAUDE.md Rule 16) — a
  single bad/good sample cannot flip the mode; leaving GNSS_AIDED
  requires GNSS bad continuously for outageEnterDwellMs, leaving
  DEAD_RECKONING requires GNSS good continuously for
  reacquisitionEnterDwellMs, and TRANSITION/REACQUISITION each have
  their own minimum dwell before the next transition is even
  considered. TRANSITION/REACQUISITION are state-machine bookkeeping
  ONLY in this slice — they do NOT yet blend GNSS and DR position
  estimates together (PRD.md Section 18's "freeze/average"/"blend"
  behavior is Slice 7, Fusion / re-alignment on GNSS reacquisition).
Connected to: GnssModeRepository -> GnssOutageDetector -> GnssModeUiState

android/app/src/main/kotlin/com/sih26168/idr/gnss/LocationRepository.kt
Status: IMPLEMENTED
Purpose: Collects GNSS fixes via FusedLocationProviderClient at ~1 Hz
  (PRD.md Section 11/21) and republishes the latest as LocationUiState.
  Slice 4 scope only: reads and republishes raw fixes — no outage state
  machine here (CLAUDE.md Rule 5, that's GnssOutageDetector), no fusion
  with the dead-reckoned position (Slice 7).
Inputs: android.content.Context.
Outputs: StateFlow<LocationUiState> — {latestFix, hasLocationPermission}.
Important functions/classes: hasLocationPermission() (checks
  ACCESS_FINE_LOCATION at runtime — the actual permission REQUEST from
  the user is an Activity-level UI concern, handled in MainActivity, not
  here); start() no-ops (and reports the gap via state) if permission
  isn't granted yet, rather than crashing on the Google Play Services
  call. Registers on a dedicated background HandlerThread, same pattern
  as SensorRepository, per CLAUDE.md Android Rule 7.
Connected to: MainActivity (permission grant) -> LocationRepository -> GnssModeRepository

android/app/src/main/kotlin/com/sih26168/idr/gnss/GnssModeRepository.kt
Status: IMPLEMENTED
Purpose: Android/coroutine glue repeatedly evaluating GnssOutageDetector
  against LocationRepository's latest fix on its OWN 5 Hz wall-clock
  timer (deliberately NOT piggybacked on SensorRepository's flow — GNSS
  outage detection must keep working even if the accelerometer/gyro
  pipeline is unavailable; the two concerns are unrelated, CLAUDE.md
  Rule 5), republishing mode + fix info as its own StateFlow, and
  Logcat-ing every transition (CLAUDE.md Rule 17).
Inputs: LocationRepository (read-only, via its StateFlow), a
  CoroutineScope, an optional GnssOutageDetector (defaulted).
Outputs: StateFlow<GnssModeUiState> — {mode, latestFix, fixAgeMs,
  hasLocationPermission, lastTransition}.
Connected to: LocationRepository -> GnssModeRepository -> MainActivity (Compose UI)
Important concepts/assumptions: fixAgeMs and the detector's nowMs both
  use System.currentTimeMillis() (wall-clock) consistently with
  GnssFix.timeMs — no boot-time/wall-clock mixing occurs in this
  repository (CLAUDE.md Rule 9/14).

android/app/src/main/kotlin/com/sih26168/idr/MainActivity.kt
Status: IMPLEMENTED
Purpose: Slice 1+2+3+4+5+6+7+8 entry point — instantiates SensorRepository,
  LocationRepository, GnssModeRepository, BaselineDeadReckoningRepository,
  (Slice 6) loads VelocityModel from assets and instantiates
  MlVelocityRepository, and (Slice 7) instantiates fusion/StateEstimator
  AFTER the ML try/catch block (so it sees the final, possibly-null
  mlVelocityRepository), in that dependency order; starts/stops all on
  onResume/onPause (stateEstimator started/stopped last/first since it
  reads the other two repositories' latest `.value`); requests
  ACCESS_FINE_LOCATION at runtime via ActivityResultContracts if not
  already granted; renders the latest accel/gyro/orientation sample,
  observed Hz, live corrected DR position/velocity, live GNSS mode/fix/
  transition, the live ML-predicted velocity (raw/corrected/bias, Slice 7)
  + alignment status, AND (Slice 7) the fused GNSS/DR position + which DR
  source fed it — all side by side, via a Compose screen (IdrSensorScreen)
  — scrollable (see bug note below).
Connected to: SensorRepository -> MainActivity -> IdrSensorScreen (Compose);
  SensorRepository -> BaselineDeadReckoningRepository -> MainActivity -> IdrSensorScreen;
  LocationRepository -> GnssModeRepository -> MainActivity -> IdrSensorScreen;
  GnssModeRepository -> BaselineDeadReckoningRepository (Slice 5);
  SensorRepository, GnssModeRepository -> MlVelocityRepository -> MainActivity -> IdrSensorScreen (Slice 6);
  GnssModeRepository, BaselineDeadReckoningRepository, MlVelocityRepository -> fusion/StateEstimator -> MainActivity -> IdrSensorScreen (Slice 7);
  MainActivity -> ui/theme/IdrTheme -> ui/screens/DriveScreen (DEFAULT screen, Slice 8) / IdrSensorScreen (debug toggle)
Important functions/classes: requestLocationPermission
  (registerForActivityResult(RequestPermission()), registered as a
  property initializer since it must be registered before the activity
  reaches STARTED) — starts LocationRepository once granted; if already
  granted on resume, starts it directly instead of re-prompting.
  VelocityModel.loadFromAssets() is wrapped in try/catch — a missing or
  corrupt bundled model (e.g. forgot to copy the gitignored asset, see
  .gitignore) is caught and surfaced as an on-screen message instead of
  crashing the whole app; Slices 1-5 keep working even if the ML half
  isn't wired up on a given build.
Important concepts/assumptions: orientation is displayed in degrees but
  every internal value stays in radians — the rad->deg conversion
  happens only at this UI display boundary (CLAUDE.md Rule 15), never
  silently earlier in the pipeline. The DR readout is captioned on-screen
  with what corrections ARE applied (ZUPT, non-holonomic) and what still
  ISN'T (bias correction, vehicle-frame alignment), so a demo viewer
  isn't misled into thinking it's a fully accurate position (CLAUDE.md
  Rule 13's honesty requirement extended to in-app copy, not just final
  reported metrics). If location permission isn't granted, the GNSS mode
  readout still works (reflects "no fix" honestly) rather than crashing
  or hiding the section. UPDATE (Slice 7): actual GNSS/DR position fusion
  now exists (fusion/StateEstimator.kt) and ML velocity now feeds the
  position integrator with a bias-corrected value — see those files'
  entries for the full writeup.
  UPDATE (2026-08-25, same day): also instantiates `capture/SensorRecorder`
  and a Start/Stop Recording button — a one-off data-capture tool
  (`RecordingUiState`, own `sensorRepository.state` collector gated on
  `isRecording`, same dedup-by-accel-timestamp guard as the other
  collectors) that writes captured accel/gyro/orientation to a
  hand-written JSON file (`getExternalFilesDir(null)`, no new
  dependency) on Stop. Not wired into the DR/fusion pipeline at all —
  read-only consumer of SensorRepository, same as any other. UPDATE
  (2026-08-25, same day): the Compose screen also renders
  `motion/MotionStateClassifier`'s cruising-override status and
  `motion/PotholeShockDetector`'s shock-detected-this-tick status, both
  captioned with the same "state the limitation right next to the
  number" convention as everything else on this screen.
  UPDATE (Slice 8, 2026-08-25): `setContent` now wraps everything in
  `ui/theme/IdrTheme` and, by DEFAULT, renders `ui/screens/DriveScreen`
  (the new Figma-derived polished screen) instead of `IdrSensorScreen`.
  A `showDebugScreen` Compose state (`var ... by remember`) plus a
  `BackHandler(enabled = showDebugScreen)` lets a "Debug" text button on
  DriveScreen switch to the ORIGINAL `IdrSensorScreen` raw-value dump,
  and the system back button returns to DriveScreen — `IdrSensorScreen`
  itself was NOT modified or deleted (still the only place to verify
  every raw number across Slices 1-7). `onRecalibrate` passes
  `{ mlVelocityRepository?.resetAlignment() }` straight through to
  DriveScreen, the same nullable-safe-call pattern already used for
  `mlVelocityRepository?.start()`/`stop()`.
BUG FOUND + FIXED on real-device verification (2026-08-25): the Column
  had no scroll modifier — as the screen accumulated more readout lines
  across Slices 4/5/6, it silently overflowed past the bottom of the
  display with NO way to reach the extra content (confirmed by swiping,
  which did nothing — proving it wasn't scrollable, not just that a
  swipe gesture was needed). This meant Slice 6's entire ML section was
  present in the composition but genuinely invisible on a real S24 FE
  screen. Fixed by adding `.verticalScroll(rememberScrollState())` to
  the Column; re-verified by scrolling to and screenshotting the
  previously-hidden ML section. A real lesson: a screen that "builds
  successfully" and "doesn't crash" is not the same as "the content is
  actually visible" — this could only be caught by looking at the real
  device output, not by compiling or by reading the Compose code.

android/app/src/main/kotlin/com/sih26168/idr/capture/SensorRecorder.kt
Status: IMPLEMENTED (2026-08-25)
Purpose: A minimal, one-off data-capture tool (CLAUDE.md Rule 18) for
  gathering real, physically-moved-phone sensor data — the
  self-captured Pothole/Phone-Moved/Cruising data
  `ml/train_motion_classifier.py` is blocked on. NOT part of the shipped
  demo's state machine or position estimate (same "clearly separated
  test tooling" spirit as CLAUDE.md Rule 8, even though this records
  real motion rather than faking anything) — purely a logger a caller
  starts/stops and reads back. Pure in-memory accumulation, no Android/
  file IO inside the class itself, so the elapsed-ms math stays
  plain-JVM unit-testable (CLAUDE.md Rule 19); writing the JSON to disk
  is `MainActivity`'s job.
Inputs: record() takes one sensor tick (timestampNs + accel/gyro/orientation floats).
Outputs: toJsonArray() — hand-written JSON (no new dependency, CLAUDE.md
  Rule 2), a flat array of flat per-tick objects.
Important functions/classes: record() (elapsedMs computed relative to
  the FIRST recorded tick's timestampNs, same boot-time-monotonic clock
  family as every other sensor timestamp in this codebase — CLAUDE.md
  Rule 9/14, NOT wall-clock); reset(); recordedCount.
Connected to: MainActivity (Start/Stop Recording button, own
  sensorRepository.state collector) -> SensorRecorder -> JSON file
  (getExternalFilesDir(null), pulled via `adb pull` for offline
  inspection)
Real usage (2026-08-25): used once, live, moving the phone by hand for
  ~10 seconds. Captured 128 samples at ~12.6 Hz (matching the app's
  already-observed sensor rate) — accel magnitude swung from a ~9.7-9.8
  m/s^2 at-rest baseline to a peak of 22.49 m/s^2 during the hand
  motion, confirming the whole capture pipeline logs real, millisecond-
  timestamped sensor changes, not synthetic/placeholder data. One
  capture is nowhere near enough labeled data to train a classifier on
  — this only proves the tooling works end-to-end.

android/app/src/main/kotlin/com/sih26168/idr/capture/DriveDataLogger.kt
Status: IMPLEMENTED (new file, 2026-08-29)
Purpose: A minimal, one-off data-capture tool (CLAUDE.md Rule 18), same
  spirit as SensorRecorder.kt above but for validating the three
  "engineering default, not yet validated" threshold groups against a
  REAL TEST DRIVE instead of gathering ML training data: gnss/
  GnssQuality.kt's max-accuracy/max-fix-age, gnss/GnssOutageDetector.kt's
  four dwell constants, and dr/StationaryDetector.kt's ZUPT accel/gyro/
  dwell thresholds. Logs one CSV row per DR tick (device's real observed
  sensor rate, e.g. ~135-200 Hz on the test device — faster than the
  ~10 Hz nominal design target, which only means a richer log, not a
  problem) combining dr/BaselineDeadReckoningRepository's DeadReckoningState
  (now also carrying its raw ZUPT inputs/decision, see that class's own
  doc for why) with gnss/GnssModeRepository's GnssModeUiState at the same
  instant. NOT part of the shipped demo's state machine (CLAUDE.md
  Rule 8) — only reads already-real values, same as SensorRecorder.
  CSV (not JSON) specifically so scripts/analyze_drive_log.py can load it
  with `pandas.read_csv` directly.
Inputs: record() takes one tick's GNSS mode/accuracy/fixAge/speed + DR
  velocity/accel-magnitude/gyro-magnitude/isStationary.
Outputs: toCsv() — hand-written CSV (no new dependency, CLAUDE.md Rule 2).
Connected to: MainActivity (Start/Stop drive log button on the debug
  screen, own deadReckoningRepository.state collector reading
  gnssModeRepository.state.value synchronously each tick) -> DriveDataLogger
  -> CSV file (getExternalFilesDir(null), pulled via `adb pull`) ->
  scripts/analyze_drive_log.py
Real usage (2026-08-29): smoke-tested indoors (phone handled, not a real
  drive) for ~35s — 4742 rows, GNSS mode flapped GNSS_AIDED/TRANSITION/
  DEAD_RECKONING/REACQUISITION repeatedly (expected indoors, same
  marginal-GNSS behavior already documented elsewhere in this file) and
  scripts/analyze_drive_log.py parsed the real pulled CSV without error.
  This only proves the tooling works end-to-end — a real outdoor test
  drive with an intentional GNSS-denied stretch (tunnel/underpass/
  parking structure) is still needed before any of the three threshold
  groups above can be called validated.

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
  other; overrideVelocity() changes velocity but leaves position
  untouched (added Slice 5); an overridden velocity correctly feeds into
  the NEXT tick's position update. Satisfies CLAUDE.md Rule 19 before
  this math is trusted for any reported drift number (PRD.md Section 28).

android/app/src/test/kotlin/com/sih26168/idr/dr/StationaryDetectorTest.kt
Status: IMPLEMENTED
Purpose: JUnit4 unit tests for StationaryDetector — starts not
  stationary; a brief below-threshold blip does not commit (one ms
  before the dwell boundary is checked explicitly); sustained
  below-threshold for the full dwell commits to stationary; high linear
  accel alone (regardless of gyro) prevents stationary, and vice versa;
  a mid-streak spike resets the dwell clock rather than accumulating
  across the interruption. Satisfies CLAUDE.md Rule 19 before this gates
  a real velocity correction.

android/app/src/test/kotlin/com/sih26168/idr/dr/NonHolonomicConstraintTest.kt
Status: IMPLEMENTED
Purpose: JUnit4 unit tests for NonHolonomicConstraint.suppressLateralVelocity
  — heading-north keeps north, drops east (and vice versa for
  heading-east); velocity purely along heading is unchanged; velocity
  purely perpendicular to heading is fully suppressed; reverse motion
  along heading preserves its sign; zero velocity stays zero. Each
  non-trivial case is checked against a hand-derived expected vector,
  not just "some suppression happened."

android/app/src/test/kotlin/com/sih26168/idr/gnss/GnssQualityTest.kt
Status: IMPLEMENTED
Purpose: JUnit4 unit tests for GnssQuality.isGood — no fix ever received
  is not good; fresh+accurate is good; fresh-but-inaccurate is not good;
  accurate-but-stale is not good; boundary values are inclusive of the
  threshold; one unit past either boundary is not good. Satisfies
  CLAUDE.md Rule 19 before this classification feeds the state machine.

android/app/src/test/kotlin/com/sih26168/idr/gnss/GnssOutageDetectorTest.kt
Status: IMPLEMENTED
Purpose: JUnit4 unit tests for the hysteresis state machine — starts in
  GNSS_AIDED with no transitions; a brief bad blip that recovers before
  the dwell threshold does NOT flip the mode (CLAUDE.md Rule 16's "a
  single noisy sample must never flip the mode," directly verified, not
  assumed); sustained bad GNSS moves GNSS_AIDED -> TRANSITION ->
  DEAD_RECKONING at the exact dwell boundaries (one ms before is checked
  explicitly, not just "eventually"); GNSS recovering mid-TRANSITION
  returns to GNSS_AIDED instead of continuing to DEAD_RECKONING;
  sustained good GNSS moves DEAD_RECKONING -> REACQUISITION ->
  GNSS_AIDED; GNSS degrading again mid-REACQUISITION returns to
  DEAD_RECKONING; an interrupted good streak in DEAD_RECKONING resets
  the dwell timer rather than accumulating across the interruption.

android/app/src/test/kotlin/com/sih26168/idr/alignment/YawRateTest.kt
Status: IMPLEMENTED (Slice 6)
Purpose: JUnit4 unit tests for YawRate.radPerSecond — first sample (no
  previous) is null; a quarter turn over 1s reads exactly pi/4 rad/s;
  wrap-around across the +-180 degree boundary reads as a small step
  (20 degrees), not a near-360-degree jump — the case this function
  exists to get right; zero/negative dt returns null; negative turn
  direction is preserved (sign matters, not just magnitude).

android/app/src/test/kotlin/com/sih26168/idr/alignment/AlignmentEstimatorTest.kt
Status: IMPLEMENTED (Slice 6)
Purpose: JUnit4 unit tests for the yaw-alignment accumulator — starts
  unaligned; matching azimuth/bearing converges to a zero offset once
  enough samples accumulate; a consistent 10-degree offset is correctly
  recovered; below-minimum-speed samples don't count; high-yaw-rate
  (turning) samples don't count; a null GNSS bearing doesn't count but
  STILL updates the internal yaw-rate tracker (verified by a two-call
  sequence proving the first call's azimuth was retained); wraps
  correctly across the +-180 degree boundary (179 vs -179 degrees
  correctly resolves to -2 degrees, not +2 or +-358 — an initial hand-
  derivation of this test's own expected value had the sign backwards,
  caught by cross-checking with Python's math.atan2 before trusting it,
  not just trusting the first answer that compiled); reset() clears
  accumulated state.

android/app/src/test/kotlin/com/sih26168/idr/features/RollingWindowTest.kt
Status: IMPLEMENTED (Slice 6)
Purpose: JUnit4 unit tests for the rolling-window math — mean of an
  empty window is zero; mean matches hand calculation; std of fewer
  than two samples is zero; std uses SAMPLE standard deviation (ddof=1)
  — explicitly checked against BOTH the correct sample-std value AND
  documented as NOT matching what population std (ddof=0) would give,
  so this parity-critical detail can't silently regress; energy is mean
  of squares; oldest sample is evicted once capacity is exceeded; fewer
  samples than capacity still computes over what's available rather
  than zero-padding (matches pandas' min_periods=1).

android/app/src/test/kotlin/com/sih26168/idr/features/FeatureExtractorTest.kt
Status: IMPLEMENTED (Slice 6)
Purpose: JUnit4 unit tests for the full feature vector — returns
  exactly 13 elements; each input lands at its documented index (not
  silently transposed); elapsed-since-fix passes through unwindowed;
  first tick has zero jerk and counts as a sign change (the pandas
  NaN-quirk replication); jerk matches a hand calculation for a steady
  ramp — INCLUDING correctly modeling that the rolling window mixes in
  the first tick's 0.0 jerk (two of this file's test expectations were
  initially wrong for exactly this reason — assumed the windowed output
  would equal the latest instantaneous value alone, forgot earlier
  in-window samples are averaged in too; caught by the test failing
  against the real code, fixed the test's math, not the implementation);
  constant acceleration has zero std/jerk after the first tick; sign
  change is detected when forward acceleration crosses zero; window
  only retains the most recent 10 samples; non-forward channels
  (lateral/up/gyro) are tracked independently of each other.

android/app/src/test/kotlin/com/sih26168/idr/ml/MlPositionIntegratorTest.kt
Status: IMPLEMENTED (2026-08-25)
Purpose: JUnit4 unit tests for MlPositionIntegrator — zero/negative dt
  is a no-op; heading north moves purely north, heading east moves
  purely east (independently checked, basic trig sanity); isStationary
  forces effective velocity to zero regardless of the predicted speed
  passed in; position accumulates correctly across multiple ticks;
  negative (reverse) velocity moves the opposite direction; reset()
  clears accumulated position. See MlPositionIntegrator.kt's entry
  above for why these synthetic-input tests, despite all passing,
  could not have caught the real on-device sensitivity finding — that
  required actual sensor noise on real hardware, not unit tests.

android/app/src/test/kotlin/com/sih26168/idr/fusion/GeoProjectionTest.kt
Status: IMPLEMENTED (Slice 7, 2026-08-25)
Purpose: JUnit4 unit tests for GeoProjection.toLocalMeters — same point as
  reference is (0,0); one degree of latitude is ~111,320m north; one
  degree of longitude AT THE EQUATOR is ~111,320m east; one degree of
  longitude at 60 degrees latitude is ~half that (cos(60 deg) = 0.5,
  directly verifying the metersPerDegLon scaling, not just "some smaller
  number"); south/west of the reference are negative.

android/app/src/test/kotlin/com/sih26168/idr/fusion/VelocityBiasCalibratorTest.kt
Status: IMPLEMENTED (Slice 7, 2026-08-25)
Purpose: JUnit4 unit tests for VelocityBiasCalibrator — no samples yet is
  zero bias (corrected == raw); below the minimum-speed gate does not
  update; the first qualifying sample sets bias exactly to that sample's
  error (no partial-EMA blending yet); a second sample blends toward the
  new error by exactly emaAlpha (hand-derived: bias 1.0 -> 1.1 for a 2.0
  error at alpha=0.1, not just "moved somewhat"); a consistent error
  converges toward that value over many samples; correctedVelocity adds
  the learned bias to a raw prediction; reset() clears both bias and
  sample count.

android/app/src/test/kotlin/com/sih26168/idr/fusion/PositionFusionTest.kt
Status: IMPLEMENTED (Slice 7, 2026-08-25)
Purpose: JUnit4 unit tests for PositionFusion — GNSS_AIDED always returns
  (0,0) regardless of DR input; TRANSITION freezes at the DR position from
  the instant it was entered and does NOT track DR continuing to
  accumulate in the background on later ticks in the same mode;
  re-entering TRANSITION re-freezes at the NEW value, not the old one;
  DEAD_RECKONING passes the live DR delta straight through across
  multiple ticks; REACQUISITION at t=0 returns exactly the DR start
  position, at the midpoint returns the exact hand-derived linear
  interpolation, and at/past the blend window returns exactly the new fix
  position (three distinct points on the same line, not just "it moves
  somewhere between them"); REACQUISITION with no fix available yet falls
  back to raw DR passthrough; reset() clears mode tracking so the next
  mode change re-anchors correctly. Satisfies CLAUDE.md Rule 19 before
  this math drives the demo-facing fused position number.

android/app/src/test/kotlin/com/sih26168/idr/capture/SensorRecorderTest.kt
Status: IMPLEMENTED (2026-08-25)
Purpose: JUnit4 unit tests for SensorRecorder — no entries yet is zero
  recordedCount and an empty JSON array; the FIRST recorded tick has
  elapsedMs=0 relative to itself; elapsedMs for later ticks is measured
  relative to that first tick, in whole milliseconds (hand-derived: 250ms
  and 1000ms deltas checked exactly, not just "some positive number");
  recordedCount tracks the number of ticks; reset() clears entries AND
  re-anchors the next tick's elapsedMs at 0 (not carried over from before
  reset); toJsonArray() includes every accel/gyro/orientation field for
  each entry.

android/app/src/test/kotlin/com/sih26168/idr/motion/MotionStateClassifierTest.kt
Status: IMPLEMENTED (2026-08-25)
Purpose: JUnit4 unit tests for MotionStateClassifier — physically still
  + slow raw prediction is stationary; physically still + fast raw
  prediction overrides to cruising; NOT physically still is neither
  stationary nor cruising regardless of velocity (checked at both 0.0
  and 10.0 m/s, proving the override never fires without the physical-
  stillness precondition); the boundary value exactly at
  minCruisingSpeedMps counts as cruising (>=, not >); just below the
  boundary still counts as stationary. Satisfies CLAUDE.md Rule 19 before
  this gates the ML position path's ZUPT.

android/app/src/test/kotlin/com/sih26168/idr/motion/PotholeShockDetectorTest.kt
Status: IMPLEMENTED (2026-08-25)
Purpose: JUnit4 unit tests for PotholeShockDetector — below the
  threshold is not a shock; AT the threshold IS a shock (>=, not >);
  well above the threshold is a shock; a large negative spike is also a
  shock (proving magnitude is used, not raw sign — a shock can push the
  Up component either direction); a small negative value is not a shock;
  zero is never a shock. Satisfies CLAUDE.md Rule 19 before this
  discounts real acceleration samples in both the physics and ML paths.
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
- Slice 4 (2026-08-25): `./gradlew.bat test` — BUILD SUCCESSFUL, all 32
  tests pass (6 SampleRateTest + 5 OrientationMathTest +
  4 WorldFrameAccelerationTest + 4 BaselinePhysicsIntegratorTest +
  6 GnssQualityTest + 7 GnssOutageDetectorTest). `./gradlew.bat
  installDebug` onto the same S24 FE. The device briefly dropped off
  ADB mid-session (Windows Device Manager showed the ADB USB interface
  in a stale `CM_PROB_PHANTOM` state while the MTP interface still
  enumerated fine — a driver-binding glitch, not a cable/app problem);
  restarting the ADB server and re-toggling USB debugging on the phone
  resolved it.
  First on-device run surfaced a real bug (not expected-baseline
  drift): the DR position/velocity readout jumped to ~1e11 m /
  ~65,000 m/s within seconds of launch. Root-caused to
  BaselineDeadReckoningRepository's first-accel-sample dt calculation
  (see that file's entry above for the fix) — fixed, retested (32/32
  still pass), reinstalled, and re-verified via screenshot: position/
  velocity now stay small (sub-2m, sub-0.1 m/s) at rest, as expected of
  the naive baseline. A system "Android App Compatibility" dialog also
  appeared once, warning that `onnxruntime-android`'s bundled
  `.so` libraries aren't 16KB-page-size aligned (Android 15+
  requirement) — harmless today since no ONNX inference code exists
  yet (Slice 6), but noted as a known issue to revisit before Slice 6
  bundles real inference.
  GNSS mode readout confirmed live via screenshot: correctly showed
  `DEAD_RECKONING` after `TRANSITION` (no fix available indoors),
  with the exact expected trigger description logged
  ("TRANSITION window elapsed, GNSS still degraded/lost") — the
  hysteresis state machine's on-device behavior matches its unit-tested
  behavior. Slice 1+2+3+4 now fully verified end-to-end on real
  hardware, no faked data, no ML. Ready to start Slice 5 (dead
  reckoning: state machine + ZUPT + non-holonomic constraint).
- Slice 5 (2026-08-25): `./gradlew.bat test` — BUILD SUCCESSFUL, all 46
  tests pass (32 from Slices 1-4 + 6 StationaryDetectorTest +
  6 NonHolonomicConstraintTest + 2 new BaselinePhysicsIntegratorTest
  cases for overrideVelocity()). `./gradlew.bat installDebug` onto the
  same S24 FE. Verified via two screenshots ~15 seconds apart, phone at
  rest: position stayed at essentially zero the whole time
  (east=-0.01/north=0.00, then east=0.00/north=-0.02; velocity 0.00/0.00
  both times) — versus Slice 4's -0.85/-1.56 m over a comparable period.
  Confirms ZUPT is actually holding the estimate still at rest, not just
  reducing drift by a fixed amount once. GNSS mode readout still correct
  (DEAD_RECKONING, no fix available indoors, matching Slice 4's verified
  behavior — GNSS-mode-gated integrator reset couldn't be visually
  confirmed indoors without a real GPS lock, since indoors GNSS never
  reaches GNSS_AIDED; that specific behavior is verified by code
  inspection + the existing GnssOutageDetector unit tests, and will get
  a direct visual check once tested outdoors with real GPS). Slice
  1+2+3+4+5 now fully verified end-to-end on real hardware, no faked
  data, no ML, no GNSS/DR position fusion yet. Ready to start Slice 6
  (ML inference: velocity + motion classifier wired in).
- Slice 6 Kotlin wiring (2026-08-25): after Slice 6's Python training
  pipeline (Phase 4 findings + ml/ scripts, documented separately
  above), built the on-device half: alignment/{YawRate,AlignmentEstimator}.kt,
  features/{RollingWindow,FeatureExtractor}.kt,
  ml/{VelocityModel,MlVelocityRepository}.kt. `./gradlew test` — all 76
  tests pass (46 from Slices 1-5 + 29 new + 1 net from a removed/added
  case), after fixing 3 test bugs found DURING development (a
  wrap-around sign error in AlignmentEstimatorTest, and two
  FeatureExtractorTest cases that forgot the rolling window mixes in
  earlier ticks' values rather than reflecting only the latest
  instantaneous value) — each caught by the test failing against
  correct code, and fixed in the test, not the implementation.
  `./gradlew installDebug` onto the same S24 FE: no crash, ONNX Runtime
  loaded the bundled 21 MB model and produced live predictions
  (observed 0.11-0.74 m/s while stationary — a plausible small noise
  floor, not an obviously-broken output) with the alignment status
  honestly showing "not yet established" (correct — stationary indoors,
  no GNSS fix, exactly the condition under which real alignment
  shouldn't be claimed). Found and fixed a real Compose bug along the
  way: the screen had no scroll modifier, so the new ML section was
  present in the layout but genuinely invisible below the bottom edge
  on a real device — only caught by looking at the actual screen, not
  by a successful build. Slice 1-6 (Kotlin half) now fully verified
  end-to-end on real hardware. Explicitly NOT done: ML velocity is
  displayed for comparison only, not fed into the position integrator;
  the motion classifier; a true cross-language feature-parity test
  (documented as a known gap, not silently assumed fine). Next:
  either wire ML velocity into the actual position integrator (PRD.md
  Section 16), or Slice 7 (GNSS/DR fusion on reacquisition) — decision
  not yet made, both are legitimate next steps.

---

## Planned File Map (target architecture, per PRD.md Section 10/19)

### android/

```
sensors/SensorRepository.kt (+ SensorSample.kt, SampleRate.kt, OrientationMath.kt)
Status: IMPLEMENTED — see `## Slice 1-5` above for full detail.
Purpose: Collect accelerometer + gyroscope + rotation-vector samples
  from Android Sensor APIs at ~10 Hz, timestamp them consistently, and
  convert rotation-vector to device-vs-world azimuth/pitch/roll (+
  rotation matrix).
Outputs: Timestamped AccelSample/GyroSample (device frame, m/s^2,
  rad/s) and OrientationSample (device-vs-world frame, rad + rotation
  matrix) via StateFlow<SensorUiState>.
Connected to: -> MainActivity (live display), -> BaselineDeadReckoningRepository,
  and (Slice 6, IMPLEMENTED) -> MlVelocityRepository (which itself
  drives AlignmentEstimator and FeatureExtractor) all consume this.
Important concept: Android sensor timestamps are boot-time monotonic,
  not wall-clock — must be reconciled explicitly against GNSS time,
  never assumed equal (CLAUDE.md Rule 9/14). Orientation is
  device-relative-to-WORLD frame only (Slice 2) — a true DEVICE-frame
  vehicle-frame transform (matching Section 15/23's original plan) is
  still not built; what Slice 6 built instead is a WORLD-frame
  heading-projection approximation (AlignmentEstimator + MlVelocityRepository,
  IMPLEMENTED) used only for ML feature extraction, NOT for the
  physics position estimate — see AlignmentEstimator.kt's entry below
  for the full reasoning and MlVelocityRepository's entry for the
  documented parity gap this creates.

dr/{WorldFrameAcceleration,BaselinePhysicsIntegrator,BaselineDeadReckoningRepository}.kt
Status: IMPLEMENTED — see `## Slice 1+2+3` above for full detail.
Purpose: Slice 3's naive WORLD-frame (not vehicle-frame) physics-only
  dead-reckoning baseline — rotate raw accel into world frame, remove
  gravity, double-integrate to position/velocity. No ZUPT, no ML, no
  GNSS fusion; expected to drift rapidly by design (PRD.md Section 32
  fallback path / CLAUDE.md Rule 3's required physics comparison point).
Connected to: SensorRepository -> BaselineDeadReckoningRepository -> MainActivity

gnss/{GnssFix,GnssQuality,GnssOutageDetector,LocationRepository,GnssModeRepository}.kt
Status: IMPLEMENTED — see `## Slice 1+2+3+4` above for full detail.
Purpose: Collect GNSS fixes via FusedLocationProvider (LocationRepository),
  classify fix quality (GnssQuality), and run the GNSS_AIDED/TRANSITION/
  DEAD_RECKONING/REACQUISITION hysteresis state machine
  (GnssOutageDetector), glued together and republished by
  GnssModeRepository.
Connected to: MainActivity (permission) -> LocationRepository ->
  GnssModeRepository -> GnssOutageDetector -> MainActivity (Compose UI).
  -> StateEstimator is still a PLANNED downstream consumer (Slice 5+,
  once GNSS mode actually gates DR behavior instead of just being
  displayed).

android/app/src/main/kotlin/com/sih26168/idr/alignment/{YawRate,AlignmentEstimator}.kt
Status: IMPLEMENTED (Slice 6, 2026-08-25) — see `## Slice 1-6` build
  verification below for full detail.
Purpose: PRD.md Section 15's phone-to-vehicle YAW alignment. Scope
  note: pitch/roll are deliberately NOT separately estimated — Android's
  rotation-vector sensor already fuses gravity into its own azimuth/
  pitch/roll (sensors/OrientationMath.kt), so device orientation
  reaching this class is already gravity-referenced. The only piece
  nothing else computes is the YAW offset between device compass
  azimuth and true vehicle heading, which gravity alone can never
  resolve (PRD.md Section 15's own stated reason for using GNSS course).
Inputs: azimuthRad (from OrientationSample), gnssBearingDeg/gnssSpeedMps
  (nullable, from GnssFix), a wall/boot-time nowNs per tick.
Outputs: AlignmentEstimate(yawOffsetRad, sampleCount, isAligned).
Important functions/classes: YawRate.radPerSecond (pure, angle-unwrap-
  aware WORLD-frame turning-rate calculation from consecutive azimuth
  samples — deliberately NOT derived from raw device gyro Z, which only
  approximates yaw rate if the phone happens to be held upright/flat,
  exactly the unknown-mounting problem this class exists to solve);
  AlignmentEstimator.evaluate (circular-mean accumulation of
  device-azimuth-minus-GNSS-course, gated to straight-line driving
  above a minimum speed — plain arithmetic angle averaging is wrong
  near the +-180 degree wrap boundary, so this sums sin/cos and
  recovers the mean via atan2, verified with a dedicated wrap-around
  unit test); reset() (exposed for a future "Phone Moved" re-trigger —
  not yet invoked automatically, no motion classifier exists).
Important concepts/assumptions: engineering-default thresholds
  (min 5.0 m/s, max yaw rate 0.1 rad/s, min 20 samples), not yet
  validated against a real test drive (CLAUDE.md Rule 13). Explicit
  limitations matching PRD.md Section 15's own: assumes at least one
  clean straight-line moving segment with GNSS near trip start; does
  not re-estimate while GNSS is unavailable; no "Phone Moved"
  re-trigger yet.
Connected to: SensorRepository, GnssModeRepository -> MlVelocityRepository -> AlignmentEstimator

android/app/src/main/kotlin/com/sih26168/idr/features/{RollingWindow,FeatureExtractor}.kt
Status: IMPLEMENTED (Slice 6, 2026-08-25)
Purpose: Kotlin mirror of ml/feature_extraction.py's windowed feature
  computation — turns a stream of already vehicle-frame-rotated accel/
  gyro ticks into the same 13-element feature vector the Python
  training pipeline produced, in the FEATURE_COLUMNS order (load-
  bearing for ONNX input — must never be reordered independently of
  the Python list). Deliberately does NOT do the device-frame ->
  vehicle-frame rotation itself (that's the caller's job, CLAUDE.md
  Rule 5) — starts from already-rotated forward/lateral/up components.
Inputs: per tick — timestampNs, accelForwardMps2, accelLateralMps2,
  accelUpMps2, gyroYawRateRadPerSec, elapsedSinceLastGnssFixS.
Outputs: FloatArray(13).
Important functions/classes: RollingWindow (fixed-capacity trailing
  window; mean/std(ddof=1, SAMPLE std matching pandas' rolling().std()
  default, NOT population std — a real, easy-to-miss parity detail,
  directly unit-tested against a hand-derived value)/energy — mirrors
  pandas `.rolling(w, min_periods=1)` including using however many
  samples are available before the window fills); FeatureExtractor.update
  (jerk = d(accel_forward)/dt per tick, fed into its own rolling window
  like Python; zero-crossing rate replicates pandas' specific
  `sign().diff() != 0` mechanics INCLUDING the NaN-comparison quirk
  that counts the very first-ever sample as a "change" — deliberately
  replicated for exact parity, not "cleaned up" into something
  different, and called out explicitly in the code comment).
PARITY GAP, explicitly documented not glossed over (CLAUDE.md Rule 13):
  the Python pipeline built vehicle-frame axes via device-frame
  Gram-Schmidt against a FIXED, KNOWN mounting (the offline dataset's
  phone never moved relative to the car). MlVelocityRepository instead
  supplies forward/lateral/up computed by projecting WORLD-frame linear
  acceleration onto an alignment-corrected heading — mathematically
  similar under the "phone yaw tracks vehicle yaw once aligned"
  assumption, NOT bit-identical to what training saw. A true
  cross-language output-parity test (CLAUDE.md Rule 20) isn't yet
  possible; tracked as a real, known gap.
Connected to: MlVelocityRepository -> FeatureExtractor -> VelocityModel

android/app/src/main/kotlin/com/sih26168/idr/ml/VelocityModel.kt
Status: IMPLEMENTED (Slice 6, 2026-08-25) — motion classifier
  (MotionClassifierModel.kt) still PLANNED, blocked on
  train_motion_classifier.py (itself blocked on self-captured data).
Purpose: ONNX Runtime Mobile wrapper for the trained velocity
  regression model. Loads `models/velocity_v1.onnx` (bundled as an
  Android asset — gitignored like the rest of the model artifacts,
  see .gitignore/models/README.md for the regenerate-and-copy step)
  once, then runs single-row inference per tick.
Inputs: FloatArray(13) from FeatureExtractor, matching order.
Outputs: Float (predicted forward velocity, m/s).
Important concepts/assumptions: input/output ONNX tensor names
  ("input" / "variable") were VERIFIED directly against the exported
  model file via onnxruntime's Python API
  (`session.get_inputs()/get_outputs()`) before hardcoding — "variable"
  is skl2onnx's default regressor output name, not guessed (CLAUDE.md
  Rule 13). Threading: this class does not manage threading itself —
  CLAUDE.md Android Rule 7 requires the caller (MlVelocityRepository)
  to invoke predict() off the main thread, which it does via the same
  coroutine-collector pattern as BaselineDeadReckoningRepository.
Connected to: FeatureExtractor -> VelocityModel -> MlVelocityRepository

MotionClassifierModel.kt
Status: PLANNED
Purpose: On-device ONNX/LiteRT inference wrapper for the motion
  classifier (PRD.md Section 14). Blocked on train_motion_classifier.py
  (itself blocked on self-captured Pothole/Phone-Moved data per the
  Phase 4 findings) — no model exists yet to wrap.
Connected to: FeatureExtractor -> MotionClassifierModel -> StateEstimator

android/app/src/main/kotlin/com/sih26168/idr/ml/MlPositionIntegrator.kt
Status: IMPLEMENTED (2026-08-25, follow-up to initial Slice 6 wiring)
Purpose: Integrates the ML-predicted forward speed directly into a 2D
  WORLD-frame position estimate, per PRD.md Section 16's
  `dx=v*cos(heading)*dt, dy=v*sin(heading)*dt` — using ML velocity
  instead of dr/BaselinePhysicsIntegrator.kt's physics-fallback path.
  This is what actually completes PRD.md Section 16's "v[t] =
  VelocityModel(features[t])" — MlVelocityRepository previously only
  displayed the scalar speed for comparison; this makes it produce a
  real position.
Inputs: dtSeconds, velocityMps (from VelocityModel), headingRad (the
  SAME alignment-corrected heading used to build the features that
  velocity was predicted from — using a different heading here would
  silently misdirect the position relative to what the model actually
  predicted), isStationary (from dr/StationaryDetector.kt, reused).
Outputs: MlDeadReckoningState(positionEastM, positionNorthM).
Important concepts/assumptions: unlike BaselinePhysicsIntegrator, there
  is NO acceleration-integration state/momentum here — each tick's
  position delta depends only on THAT tick's model-predicted speed and
  heading, not on velocity carried from the previous tick, since the
  model predicts speed directly rather than us inferring it from
  double-integrated acceleration. Non-holonomic constraint is satisfied
  BY CONSTRUCTION (the model never predicts a lateral component, so
  there's nothing to suppress) — dr/NonHolonomicConstraint.kt is NOT
  needed or used here, unlike the physics path.
REAL ON-DEVICE FINDING (2026-08-25) — see MlVelocityRepository.kt's
  entry below for the full writeup: this "no momentum" design means
  ZUPT here gates a full, unbounded per-tick prediction rather than a
  small, bounded, carried delta — making this integrator measurably
  MORE sensitive to brief real or false-positive "non-stationary"
  moments than BaselinePhysicsIntegrator is for an identical
  disturbance. Observed on the real S24 FE: a ~1.6m position jump over
  ~45s while the phone was ostensibly stationary, while the physics
  position stayed flat the whole time. Documented as a known, deferred
  limitation (explicit product decision), not fixed reflexively.
Unit tests: tests/.../ml/MlPositionIntegratorTest.kt (7 cases) — zero/
  negative dt is a no-op; heading north/east move purely
  north/east respectively (basic trig sanity, each independently
  checked); isStationary forces effective velocity to zero regardless
  of the predicted speed passed in; position accumulates correctly
  across multiple ticks; negative (reverse) velocity moves the opposite
  direction; reset() clears state. Note these unit tests, all using
  synthetic inputs, could NOT have caught the real on-device finding
  above — that only showed up from real sensor noise interacting with
  the (correctly-implemented, per these tests) no-momentum design,
  exactly the kind of gap between "unit-tested" and "verified on real
  hardware" this project's whole slice-by-slice on-device verification
  discipline exists to catch.

android/app/src/main/kotlin/com/sih26168/idr/ml/MlVelocityRepository.kt
Status: IMPLEMENTED (Slice 6, 2026-08-25; position integration added
  2026-08-25 in a follow-up change)
Purpose: The Android/coroutine glue wiring SensorRepository +
  GnssModeRepository's streams through AlignmentEstimator,
  WorldFrameAcceleration (reused from Slice 3/5), FeatureExtractor,
  VelocityModel, and (follow-up) MlPositionIntegrator, republishing the
  live ML-predicted velocity AND an ML-driven WORLD-frame position as
  its own StateFlow. Deliberately a SEPARATE, PARALLEL repository to
  BaselineDeadReckoningRepository (CLAUDE.md Rule 5) — does NOT modify
  or replace the physics position integrator; Slice 5's tested physics
  pipeline is completely untouched. Both run and display side by side,
  so the ML-vs-physics comparison PRD.md Section 30 wants for the demo
  is directly visible on-device, not just a desktop-measured claim.
Outputs: StateFlow<MlVelocityUiState> — {predictedVelocityMps,
  isAligned, yawOffsetDeg, alignmentSampleCount, positionEastM,
  positionNorthM}.
Important concepts/assumptions: yaw rate for the gyro feature is
  computed by rotating the RAW gyro vector into world frame the same
  way as accel (angular velocity transforms as a vector under a pure
  rotation) and taking its Up component directly — heading-independent,
  unlike forward/lateral, so no alignment-offset projection needed for
  that one; lateral acceleration's SIGN convention is a fixed,
  internally-consistent choice (forward rotated -90 degrees) that was
  never independently verified against ground truth the way forward's
  sign was in ml/feature_extraction.py — documented as an open,
  unverified assumption, not asserted as correct; elapsed-since-last-fix
  is clamped to 999s if no fix has ever been received, guarding against
  feeding a near-infinite value to the model (a minimal stand-in for
  PRD.md Section 13's "fall back if input is out of distribution," not
  full out-of-distribution detection). Reuses SensorRepository's own
  StationaryDetector class (a NEW, separate instance from
  BaselineDeadReckoningRepository's — see MlPositionIntegrator.kt's
  entry below for why two independent instances, given identical
  inputs, still produced very different real-world position behavior).
REAL FINDING from on-device testing (2026-08-25) — position drift
  asymmetry, not a coding bug: while the phone sat stationary during
  verification, the physics position stayed flat (sub-0.05 m the whole
  time, matching Slice 5's earlier result) but the ML-based position
  jumped by ~1.6 m over roughly 45 seconds, then flattened out again
  (near-zero growth for the following ~15s) — a burst, not a steady
  leak. Root cause: physics ZUPT zeroes a CARRIED MOMENTUM state
  (BaselinePhysicsIntegrator's velocity only grows by accel*dt each
  tick it's not gated, so a brief few-hundred-ms non-stationary blip
  before StationaryDetector re-triggers only injects a tiny position
  delta). MlPositionIntegrator has NO momentum concept — every tick
  the gate is open, it applies the model's FULL predicted speed for
  that tick (not a small incremental delta), so the exact same brief
  disturbance (plausibly the phone/table being nudged during active
  ADB/screenshot commands) can inject a position jump an order of
  magnitude larger than the physics path produces for an identical
  disturbance. The code does exactly what it was designed to do — this
  is a real DESIGN limitation of "ZUPT-gate a raw per-tick prediction"
  vs "ZUPT-zero a carried momentum," not a bug to patch blindly.
  DELIBERATELY NOT FIXED YET (explicit product decision, 2026-08-25):
  candidate fixes considered — a minimum-speed deadband (ignore
  predictions below some cutoff even when not strictly "stationary"),
  smoothing/low-pass-filtering the model's raw output before
  integrating, or a stricter/longer StationaryDetector dwell time for
  this specific path — none implemented yet, since picking the right
  one needs more thought than a reflexive patch; documented here as a
  known, measured, honest limitation instead of silently shipped or
  silently ignored.
  UPDATE (Slice 7, 2026-08-25): now also owns a `fusion/VelocityBiasCalibrator`
  instance — while `GNSS_AIDED` with a real `fix.speedMps`, it learns a
  running EWMA offset between this model's raw output and GNSS's own
  speed (PRD.md Section 17), and that CORRECTED velocity (not the raw
  one) is what actually feeds `positionIntegrator` from here on.
  `MlVelocityUiState.predictedVelocityMps` was renamed/split into
  `predictedVelocityRawMps` + `predictedVelocityCorrectedMps` +
  `velocityBiasMps` so raw/corrected/learned-bias are all honestly
  visible in the UI rather than one silently replacing the other.
  UPDATE (2026-08-25, same day): now also owns a `motion/MotionStateClassifier`
  and a `motion/PotholeShockDetector` instance. Before ZUPT-gating
  `positionIntegrator`, `physicallyStill` (from `stationaryDetector`) is
  corroborated against `rawPredictedVelocityMps` via
  `MotionStateClassifier.classify()` — a physically-still tick with a
  meaningful raw prediction gets overridden to NOT ZUPT
  (`MlVelocityUiState.isCruising = true`). Before the forward/lateral
  accel projection, `potholeShockDetector.isShock(linearAccel[2])` is
  checked; a detected shock zeroes the East/North linear-accel
  components before they're turned into `accelForwardMps2`/
  `accelLateralMps2` and reach `featureExtractor`
  (`MlVelocityUiState.potholeShockDetectedThisTick`). Both are
  deterministic stand-ins for PRD.md Section 14 classes — see their own
  entries under `## Planned File Map` -> `motion/` for the full
  reasoning and honest limitations.
  UPDATE (Slice 8, 2026-08-25): added a public `resetAlignment()`
  wrapping the already-private `alignmentEstimator.reset()` call — PRD.md
  Section 15's "Phone Moved... flag for recalibration" / Section 31/32's
  manual "hold phone flat, tap to calibrate" fallback. `DriveScreen`'s
  recalibrate `FloatingIconButton` calls this through `MainActivity`, a
  second (manual) caller of the same reset the automatic Phone-Moved
  re-trigger was already built for.
Connected to: SensorRepository, GnssModeRepository -> MlVelocityRepository -> MainActivity (Compose UI);
  MlVelocityRepository -> fusion/StateEstimator (Slice 7, reads its position + isAligned);
  motion/MotionStateClassifier, motion/PotholeShockDetector -> MlVelocityRepository (2026-08-25);
  DriveScreen's recalibrate button -> MainActivity -> MlVelocityRepository.resetAlignment() (Slice 8)

fusion/GeoProjection.kt
Status: IMPLEMENTED (Slice 7, 2026-08-25)
Purpose: Pure Kotlin (no android.* import), converts a GNSS lat/lon fix
  into local East/North meters relative to a reference lat/lon, via an
  equirectangular/flat-earth tangent-plane approximation. Explicitly NOT
  geodesically exact (WGS84 ellipsoid curvature ignored) — documented as
  fine at the few-km demo scale, per CLAUDE.md Rule 13, not silently
  assumed exact. Its one caller need: express a newly-reacquired GNSS fix
  in the SAME local frame the DR position has already been accumulating
  in, so fusion/PositionFusion.kt can blend the two during REACQUISITION.
Important functions: toLocalMeters(latDeg, lonDeg, refLatDeg, refLonDeg).
Connected to: fusion/StateEstimator -> GeoProjection -> fusion/PositionFusion

fusion/VelocityBiasCalibrator.kt
Status: IMPLEMENTED (Slice 7, 2026-08-25)
Purpose: PRD.md Section 17's online velocity-bias calibration — a simple
  EWMA correction, explicitly NOT a Kalman filter (Section 17's own
  "loosely-coupled complementary approach... given the 36-hour budget").
  Tracks `gnssSpeedMps - rawPredictedVelocityMps` while GNSS is
  trustworthy and above a minimum-speed gate (same 5.0 m/s / ~18 km/h
  rationale as AlignmentEstimator's own gate — GNSS speed is noisy at low
  speed); the learned bias is held constant (still applied) once GNSS is
  lost, since there's no ground truth left to recalibrate against during
  DEAD_RECKONING.
Important functions/classes: update(gnssSpeedMps, rawPredictedVelocityMps)
  (no-ops below the speed gate); correctedVelocity(raw) (adds the learned
  bias); reset().
Connected to: ml/MlVelocityRepository -> VelocityBiasCalibrator -> ml/MlPositionIntegrator

fusion/PositionFusion.kt
Status: IMPLEMENTED (Slice 7, 2026-08-25)
Purpose: The position-level counterpart to gnss/GnssOutageDetector.kt's
  mode-level state machine — PRD.md Section 18's TRANSITION
  "freeze/average" and REACQUISITION "blend DR position toward new GNSS
  fix" behavior, finally implemented. Pure Kotlin, driven by repeated
  update() calls (CLAUDE.md Rule 19), same pattern as GnssOutageDetector's
  own evaluate(). Assumes its drEastM/drNorthM input is already "meters
  since GNSS was last good" (exactly what BaselineDeadReckoningRepository
  and MlVelocityRepository's position integrators already produce, both
  resetting to (0,0) the instant mode == GNSS_AIDED) — this class does
  NOT duplicate that reset logic, it only decides how much of the DR
  delta to trust/show per mode.
Important functions/classes: update(nowMs, mode, drEastM, drNorthM,
  newFixEastM?, newFixNorthM?) — GNSS_AIDED always returns (0,0);
  TRANSITION freezes at the DR position from the INSTANT TRANSITION was
  entered (chosen "freeze" half of "freeze/average" — simpler, and
  TRANSITION's short ~1s default dwell makes freeze vs. average nearly
  indistinguishable in practice); DEAD_RECKONING passes the live DR delta
  straight through; REACQUISITION linearly interpolates from the DR
  position at REACQUISITION's start toward the new fix's local-frame
  position over `reacquisitionBlendMs` (default 1000ms — deliberately a
  SEPARATE constant from GnssOutageDetector's own default REACQUISITION
  dwell, not read from it, to avoid coupling to that class's private
  internals; matching by default is intentional but not structurally
  enforced), falling back to raw DR passthrough if no fix is available
  yet that tick.
Connected to: fusion/StateEstimator -> PositionFusion -> FusedPositionUiState

fusion/StateEstimator.kt
Status: IMPLEMENTED (Slice 7, 2026-08-25)
Purpose: The Android/coroutine glue that turns PositionFusion's pure
  per-mode blending logic into a live, on-device fused position estimate
  — actual GNSS/DR position blending (not just mode display). ZUPT
  (StationaryDetector) and the non-holonomic constraint were already
  IMPLEMENTED as of Slice 5, applied directly inside
  BaselineDeadReckoningRepository/MlVelocityRepository rather than here —
  see `## Slice 1+2+3+4+5` above; this file's job is specifically the
  GNSS<->DR position reconciliation those two classes still don't do for
  each other.
  Drives off gnssModeRepository.state.collect{} (its own ~5Hz cadence —
  plenty for a ~1s REACQUISITION blend window) and reads
  deadReckoningRepository/mlVelocityRepository's latest `.value`
  synchronously inside — the SAME "driving flow + synchronous sibling
  reads" pattern BaselineDeadReckoningRepository and MlVelocityRepository
  already use to read gnssModeRepository.state.value, not a new style
  introduced here.
  DR source selection matches the discussed PRD.md Section 16
  interpretation ("v[t] = VelocityModel(...) (or physics fallback)"): ML
  position is used once MlVelocityUiState.isAligned is true, else physics
  position is used — this means the fused number inherits the already-
  documented ML-position burst-sensitivity limitation (see
  MlPositionIntegrator.kt's entry) rather than introducing a new one.
  `mlVelocityRepository` is a NULLABLE constructor parameter specifically
  so this still works (physics-only) if the ONNX model failed to load —
  same resilience pattern MainActivity already applies to the ML half
  generally.
  `outageAnchorLatDeg`/`outageAnchorLonDeg` are continuously overwritten
  with the latest fix's lat/lon while GNSS_AIDED — the SAME trigger
  boundary the two DR repositories already reset their own position
  integrators on, so the anchor and the DR-zero-point are always the same
  real-world location by construction, with no extra synchronization
  needed. `secondsSinceLastGnssAided` is an honest raw elapsed-time
  figure (Float.MAX_VALUE before the first-ever aided fix this run, same
  sentinel convention GnssModeUiState.fixAgeMs already uses) — explicitly
  NOT a fabricated confidence/uncertainty percentage (CLAUDE.md Rule 13).
  No direct unit test for this file itself — same as GnssModeRepository/
  BaselineDeadReckoningRepository, its correctness rests on
  PositionFusion/GeoProjection's own tests plus on-device verification,
  not a coroutine-flow test of the glue.
  UPDATE (Slice 8, 2026-08-25): `FusedPositionUiState.driftSummary`
  (PRD.md Section 30 WOW-factor #4) — detects the tick `mode`
  transitions INTO `REACQUISITION` (tracked via a new `previousMode`
  field, the SAME "mode just changed" pattern PositionFusion already
  uses internally) and snapshots `fusion/DriftSummary.compute()` using
  the DR position and the newly-reacquired GNSS position, both already
  computed that tick for PositionFusion's own blend — no new sensor
  data or geodesy needed, this is a small reduction over data already
  flowing through this class.
Connected to: GnssModeRepository, BaselineDeadReckoningRepository,
  MlVelocityRepository -> StateEstimator -> MainActivity (Compose UI);
  fusion/DriftSummary -> StateEstimator.driftSummary -> ui/components/DriftSummaryCard (Slice 8)

fusion/DriftSummary.kt
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: PRD.md Section 30 WOW-factor #4 — "an honest, on-screen drift
  measurement at the end of the outage segment." Pure Kotlin (CLAUDE.md
  Rule 19), computes a REAL drift measurement from two already-computed
  local-meter positions: where DR thought the phone was vs. where the
  newly-reacquired GNSS fix says it actually is.
Inputs: drEastM/drNorthM, gnssEastM/gnssNorthM (all local meters,
  relative to the same outage anchor).
Outputs: DriftSummaryResult(driftMeters, distanceTravelledMeters).
Important concepts/assumptions: HONEST LIMITATION (CLAUDE.md Rule 13) —
  `distanceTravelledMeters` is the STRAIGHT-LINE distance from the
  outage anchor to the DR position, not an integrated path length —
  nothing in this codebase separately accumulates true path length (the
  DR integrators only ever track current position/velocity). Close for
  a mostly-forward drive, understates distance for a route with sharp
  turns or backtracking — documented here, not silently presented as exact.
Connected to: fusion/StateEstimator.kt -> DriftSummary.compute() -> FusedPositionUiState.driftSummary

motion/MotionStateClassifier.kt
Status: IMPLEMENTED (2026-08-25)
Purpose: A DETERMINISTIC, PARTIAL stand-in for two of PRD.md Section 14's
  8 Motion Classification classes — `Stationary` and `Cruising` — NOT the
  trained Random Forest/GBT classifier Section 14 actually specifies
  (that needs a reported confusion matrix per class, which needs labeled
  data that doesn't exist yet; `train_motion_classifier.py` stays
  blocked). Resolves dr/StationaryDetector.kt's own documented
  limitation — "constant-velocity straight-line motion also produces
  near-zero acceleration/gyro... cannot distinguish 'truly at rest' from
  'smoothly coasting'" — a genuine accelerometer/gyro-only sensor-physics
  limit no threshold on accel/gyro alone can fix. Breaks the tie using an
  independent signal already computed every tick: the ML velocity
  model's RAW prediction (ml/VelocityModel.kt). Same "deterministic
  stand-in before the real ML classifier" precedent as StationaryDetector
  itself.
Inputs: physicallyStill (StationaryDetector's own accel/gyro-only
  output), rawPredictedVelocityMps (pre-bias-correction ML prediction).
Outputs: MotionClassification(isStationary, isCruising) — mutually
  exclusive; both false if not physicallyStill (no ambiguity to resolve
  there — this class only resolves the ONE stationary-vs-cruising tie).
Important functions/classes: classify() — physicallyStill=false -> both
  false; physicallyStill=true and rawPredictedVelocityMps below
  minCruisingSpeedMps (default 1.0 m/s, engineering default, unvalidated
  per CLAUDE.md Rule 13) -> isStationary; at/above the threshold ->
  isCruising (ZUPT override).
Connected to: ml/MlVelocityRepository -> MotionStateClassifier -> ml/MlPositionIntegrator
  (ML-side ONLY — see MlVelocityRepository's entry for why the physics
  baseline deliberately does NOT get this signal, CLAUDE.md Rule 3)

motion/PotholeShockDetector.kt
Status: IMPLEMENTED (2026-08-25)
Purpose: A DETERMINISTIC, PARTIAL stand-in for PRD.md Section 14's
  `Pothole` class, whose downstream effect is stated precisely: "discount
  the acceleration sample(s) so a vertical/shock spike doesn't get
  misread as forward acceleration." Implemented as exactly that — a
  threshold on the magnitude of the WORLD-frame vertical (Up) linear-accel
  component (dr/WorldFrameAcceleration.kt's existing output, index 2).
  NOT the trained classifier — HONEST LIMITATION (CLAUDE.md Rule 13):
  proves the discounting MECHANISM works on a large-enough vertical
  spike, does NOT prove the threshold correctly distinguishes a real
  pothole from a speed bump, a curb, or the phone being bumped/dropped —
  no real pothole data exists yet to validate against.
Inputs: verticalLinearAccelMps2.
Outputs: Boolean (isShock).
Important functions/classes: isShock() — magnitude-based (a shock can
  push the Up component either direction), threshold defaults to 4.0
  m/s^2 (engineering default, unvalidated — normal driving vertical
  noise after gravity removal is typically well under 2 m/s^2).
Connected to: dr/BaselineDeadReckoningRepository AND
  ml/MlVelocityRepository -> PotholeShockDetector -> (discounted
  East/North linear accel fed into BaselinePhysicsIntegrator.update() /
  FeatureExtractor.update() respectively). Wired into BOTH paths
  (unlike MotionStateClassifier) since discounting one sample doesn't
  touch the FROZEN Slice 3 baseline measurement already reported
  (that came from BaselinePhysicsIntegrator.update() alone, no
  corrections at all) — it only affects the LIVE corrected display,
  exactly like ZUPT/non-holonomic already do for the physics path.

MapConstraint.kt
Status: PLANNED
Purpose: Snap the estimated position to the nearest plausible road
  segment (PRD.md Section 19 — MVP-level, not a full map matcher).

UI (Compose screens)
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: Live map + status header (GNSS state, speed, motion class,
  alignment confidence), vehicle-mode selector (PRD.md Section 22). See
  the new `ui/theme/`, `ui/components/`, `ui/map/`, `ui/screens/` entries
  immediately below for the full per-file writeup — `MapConstraint.kt`
  above is still the only PLANNED piece of PRD.md Section 22's screen
  (no real road-snapping exists).

ui/theme/Color.kt
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: Design tokens extracted directly from the Figma "Navigation app
  (Community)" file's Design-tab inspector (not eyeballed from a
  screenshot) — the Color Styles frame (dark-theme text:
  `TextPrimary`=`#FFFFFF`, `TextSecondary`=`#EBEBF5`) and the "Map app
  Color" frame (`DarkBackground`=`#0B0C0D`, `PanelBackground`=`#2D3443`,
  `CtaRed`=`#B13025`, `CtaRedPressed`=`#281000`, `AccentBlue`=`#2B64A8`,
  `AccentBlueLight`=`#499FEF`, `NeutralIconButtonBg`=`#383E42`,
  `AlertRed`=`#FF0000`), plus `GlassSurface`/`GlassBorder` from the
  Modal Sheet component's own inspector values (see GlassCard.kt).
Important concepts/assumptions: `GnssAidedColor`/`TransitionColor`/
  `DeadReckoningColor`/`ReacquisitionColor` are a REASONED EXTENSION, not
  a Figma import — the source file has no 4-state status palette (its
  own map screen only shows a single "on route" state). Documented at
  the token definition site so this honest distinction stays visible,
  not buried in a component file.
Connected to: Color Styles/"Map app Color" Figma frames (inspected via
  browser automation, 2026-08-25) -> Color.kt -> every ui/ file below

ui/theme/Type.kt
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: Material3 `Typography` populated from the Figma Text Styles
  frame's iOS-HIG-named scale (LargeTitle/Title1-3/Headline/Body/
  Callout/Subheadline/Footnote/Caption1-2 x Regular/Bold).
Important concepts/assumptions: HONEST GAP (CLAUDE.md Rule 13) — the
  STYLE NAMES were read directly from Figma's inspector, but exact px/
  line-height per step was NOT individually drilled into (would need
  opening every nested text node); the sizes here are the standard
  values those iOS HIG names denote, mapped onto Material3 roles. A
  reasoned adaptation, not a pixel-exact extraction like Color.kt's hex
  values — stated explicitly here rather than implied to be equally precise.

ui/theme/Shape.kt
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: `LargeGlassCardRadius` = 40dp is a real, directly-inspected
  value (the Figma Modal Sheet component's own corner radius, read via
  its right-panel Appearance section). `GlassCardRadius` (24dp, compact
  cards) and `PillShape` (chips/buttons, matching Figma's own pill-button
  convention) are reasonable derived steps, not separately measured.

ui/theme/IdrTheme.kt
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: Wraps Color.kt/Type.kt into a `MaterialTheme` (`darkColorScheme`).
Connected to: MainActivity.kt -> IdrTheme -> DriveScreen (IdrSensorScreen,
  the pre-Slice-8 debug screen, deliberately keeps its OWN internal
  `MaterialTheme` call untouched — nesting is harmless, the inner one wins).

res/drawable/ic_recenter.xml
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: A REAL exported Figma asset — the "Current Location" icon
  component (Interactive Components page, node 16-1601), right-click ->
  Export as SVG -> hand-converted to an Android vector drawable. Only the
  crosshair glyph paths are kept; the Figma export's own background
  circle/blur was dropped since FloatingIconButton.kt supplies its own
  button chrome.
Connected to: Figma node 16-1601 (exported 2026-08-25) -> ic_recenter.xml -> DriveScreen's recalibrate FloatingIconButton

res/drawable/ic_car.xml
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: A REAL exported Figma asset — the car tab-bar icon
  (Interactive Components page, node 301-2689, "G02 / Default" variant),
  exported the same way as ic_recenter.xml. The Figma export's drop-
  shadow filter and circular clip were dropped (chrome, not glyph); only
  the car silhouette path is kept, at its original fill/alpha
  (`#EBEBF5` at 60% — matches TextSecondary exactly).
Connected to: Figma node 301-2689 (exported 2026-08-25) -> ic_car.xml -> VehicleModeSelector.kt

res/drawable/ic_motorcycle.xml
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: NOT a Figma asset (honestly documented in the file's own
  header comment) — the Figma transport-mode icon set (bell/inbox/car/
  location-pin) has no motorcycle icon. Rather than add the
  material-icons-extended dependency for one icon (CLAUDE.md Rule 2) or
  silently substitute an unrelated Figma icon, this is a small
  hand-drawn line glyph (two wheel circles + frame lines) in the same
  stroke weight as ic_recenter.xml.
Connected to: VehicleModeSelector.kt (Motorcycle option)

ui/components/GlassCard.kt
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: The frosted/glass card primitive — Figma's "Modal Sheet"
  component (Presentation page, the map screen's instruction card and
  bottom metrics sheet), inspected directly: corner radius 40, fill
  `#FFFFFF` at 45% opacity over the dark background, a subtle 1px light
  stroke.
Important concepts/assumptions: no real backdrop blur is applied —
  Compose has no first-class blur primitive at this project's minSdk 26
  floor, so the translucent fill alone approximates Figma's frosted
  look; documented as a simplification, not silently dropped.
Connected to: ui/theme/Color.kt (GlassSurface/GlassBorder), Shape.kt (GlassCardRadius) -> GlassCard -> DriftSummaryCard, StatusChip (background)

ui/components/StatusChip.kt
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: Pill/chip primitive (Figma's pill-button convention,
  generalized into a colored-dot + label status indicator). Used for
  GNSS mode and the motion-state readout (FR10).
Connected to: DriveScreen.kt -> StatusChip (GNSS mode, speed, motion state)

ui/components/FloatingIconButton.kt
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: Circular floating icon button — Figma's "Navigation Button"
  component (Simple Components frame's search/recenter/settings cluster
  on the map screen), inspected directly: solid `#383E42` circle, 44dp.
  Used once, for the manual recalibrate action (PRD Section 15/31/32).
Connected to: DriveScreen.kt -> FloatingIconButton(ic_recenter) -> MainActivity's onRecalibrate -> MlVelocityRepository.resetAlignment()

ui/components/VehicleModeSelector.kt
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: Two-option segmented control (Car/Motorcycle, PRD.md Section 6
  — corrected from the original brief's "Section 7" citation, which is
  actually titled "Out of Scope" and excludes only AUTOMATIC
  classification; the manual selector is Section 6's in-scope item).
Important concepts/assumptions: LOCAL UI STATE ONLY (CLAUDE.md Rule 8)
  — nothing in the pipeline currently branches on vehicle type; this is
  a real, working control that stores a selection without yet changing
  any physics/ML behavior, and is not pretending to.
Connected to: DriveScreen.kt -> VehicleModeSelector -> (local state only, no downstream consumer yet)

ui/components/DriftSummaryCard.kt
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: PRD.md Section 30 WOW-factor #4 — shows the REAL measured
  drift number fusion/DriftSummary.kt computed, on a GlassCard at the
  large (40dp, directly-inspected) radius. Dismissible — the caller
  (DriveScreen) owns the dismissed/shown state, this component has no
  internal visibility logic.
Connected to: fusion/StateEstimator.kt (FusedPositionUiState.driftSummary) -> DriveScreen.kt -> DriftSummaryCard

ui/components/GnssModeChangeBanner.kt
Status: IMPLEMENTED (new file, 2026-08-29)
Purpose: User-requested "popup when switching from gnss aided to dead
  reckoning mode". A transient, non-blocking banner (solid
  DeadReckoningColor background, white text, auto-dismisses after 4s or
  on manual X tap) shown inline at the top of ui/screens/
  StatusOverlayContent.kt — deliberately NOT a blocking AlertDialog,
  since this app's whole point is honest, UNINTERRUPTED navigation
  through a GNSS outage (CLAUDE.md Mission); a modal the driver has to
  dismiss the instant GNSS drops would work against that.
Important concepts/assumptions: StatusOverlayContent triggers this off
  gnss/GnssModeRepository's own `lastTransition` (already logged per
  CLAUDE.md Rule 17), narrowed to `fromMode == TRANSITION && toMode ==
  DEAD_RECKONING` — the one path into DEAD_RECKONING that genuinely
  started from GNSS_AIDED (see gnss/GnssOutageDetector.kt's state
  diagram). A REACQUISITION bail-back also lands in DEAD_RECKONING but
  is excluded on purpose — it was never GNSS_AIDED to begin with, and
  showing this on every failed reacquisition attempt during a marginal-
  GNSS stretch would be noisy, not honest signal. `dismissedTransitionAtMs`
  (StatusOverlayContent-local state, keyed by the transition's own
  timestamp) lets the banner re-show on a SECOND real outage later in the
  same session instead of staying permanently dismissed after the first.
  Only the GNSS_AIDED -> DEAD_RECKONING direction was requested/built;
  the symmetric "GNSS reacquired" case is NOT implemented.
Connected to: gnss/GnssModeRepository.kt (GnssModeUiState.lastTransition) -> StatusOverlayContent.kt -> GnssModeChangeBanner

ui/map/TrackCanvas.kt
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: The map layer — a Compose `Canvas`, NOT a real map SDK
  (decision made with the user during planning: zero new dependencies,
  works fully offline for a demo about GNSS-DENIED navigation, plots
  directly in the local East/North meters fusion/StateEstimator already
  produces — no new geodesy needed). Styling borrows Google Maps'
  LAYOUT pattern (dot-with-ring current position, accuracy halo,
  polyline route) but renders entirely in the Figma-extracted dark
  palette — NOT a separate light "Google palette" (an earlier planning
  draft proposed one; the user explicitly corrected this before
  implementation — see this file's own doc comment for the full note).
Important concepts/assumptions: the current-position dot is always
  drawn at canvas CENTER (a "follow-me" navigation view) — the outage
  anchor is what moves in screen space as the fused position grows.
  HONEST SIMPLIFICATION (CLAUDE.md Rule 13): draws a single STRAIGHT
  line from the outage anchor to the current fused position during
  DEAD_RECKONING/REACQUISITION, NOT a true curved path — nothing in
  this codebase accumulates a full position-history polyline
  (StateEstimator only ever publishes the CURRENT position per tick).
  This shows the NET divergence since the outage began, not the literal
  path shape; the real GNSS-vs-DR comparison at reacquisition is exact
  (see DriftSummaryCard/DriftSummary). `METERS_TO_PIXELS = 8f` is an
  engineering default, unvalidated (Rule 13).
Connected to: fusion/StateEstimator.kt (FusedPositionUiState) -> DriveScreen.kt -> TrackCanvas

ui/screens/DriveScreen.kt
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: Slice 8's primary screen (PRD.md Section 22's "single main
  screen") — composes TrackCanvas as the base layer, a status overlay
  (StatusChip for GNSS mode/speed/motion state, an alignment readout —
  FR10), VehicleModeSelector (pre-drive, PRD Section 6), the recalibrate
  FloatingIconButton, and DriftSummaryCard (shown once real drift data
  exists). A pure function of already-real state (CLAUDE.md Rule 8) —
  every value traces back to the same repositories IdrSensorScreen
  already reads.
Important functions: `estimateSpeedMps()` — real GNSS speed while
  GNSS_AIDED, else the ML velocity if that's fusion/StateEstimator's
  active DR source, else the physics velocity's magnitude (already
  ZUPT-corrected). `estimateMotionLabel()` — PRD.md FR10's "current
  motion class," but ONLY the real, implemented subset: Pothole ->
  Cruising -> Stationary (near-zero ZUPT-corrected physics velocity) ->
  a generic "Moving" fallback. No Turning/Accelerating/Braking/
  Phone-Moved label is ever shown, since those detectors don't exist
  (CLAUDE.md Rule 13) — this project only implements a partial subset
  of PRD.md Section 14's 8-class taxonomy (see motion/ entries above).
Connected to: BaselineDeadReckoningRepository, GnssModeRepository,
  MlVelocityRepository, StateEstimator (all read-only, via MainActivity) ->
  DriveScreen -> TrackCanvas/StatusChip/VehicleModeSelector/
  FloatingIconButton/DriftSummaryCard
```

```
ui/screens/MapScreen.kt
Status: IMPLEMENTED
Purpose: The MAP tab — real OpenStreetMap tiles (ui/map/StreetMapView),
  destination search + routing (routing/GeocodingRepository Nominatim +
  routing/RoutingRepository OSRM), and turn-by-turn navigation. Same
  StatusOverlayContent as DriveScreen underneath; search/routing is
  layered on top as its own state machine (idle -> destination selected
  -> route active -> navigating).
Search UI: (changed 2026-08-28, user-requested "search destination like
  Google Maps, redirects to map page when searched") the idle state no
  longer shows a live, typeable dropdown floating over the map tiles.
  It now shows a collapsed, tappable bar (search icon + placeholder or
  the selected destination's name); tapping it sets `showSearchScreen =
  true`, which renders ui/screens/SearchScreen.kt as a full-page overlay
  covering this entire screen — same manual boolean-state screen-swap
  pattern MainActivity already uses for its debug screen / tab
  switching, one level down. SearchScreen owns the live query/results/
  debounced-Nominatim-search state itself now (moved out of MapScreen);
  MapScreen only receives the FINAL picked GeocodeResult via
  `onResultSelected`, which sets `selectedDestination` and closes the
  overlay — landing back on the map with the "Start" routing button, the
  same as before. A `BackHandler(enabled = showSearchScreen)` closes the
  search page on system Back (added after, so it wins over
  MainActivity's own tab-level BackHandler while the page is open).
Connected to: routing/GeocodingRepository, routing/RoutingRepository,
  routing/OfflineRouteCache, fusion/GeoProjection, ui/screens/SearchScreen
  (new, opened on demand) -> ui/components/ActiveRouteCard/
  NavigationInstructionCard/NavigationEtaBar
```

```
ui/screens/SearchScreen.kt
Status: IMPLEMENTED (new file, 2026-08-28; restyled 2026-08-29)
Purpose: Full-page destination search, Google Maps' own pattern — opened
  by MapScreen when its collapsed search bar is tapped, covers the whole
  screen rather than drawing a dropdown over the live map. Owns the
  debounced (~500ms, Nominatim's ~1 req/sec usage-policy cap) live
  query/results/error state that used to live inline in MapScreen.
Restyled 2026-08-29 (user-supplied Google Maps search-screen screenshot,
  "implement the same in my app"): rounded search pill (back arrow + text
  field + mic/clear button), a Home/Work quick-access row, and a "Recent"
  list shown while the query is empty — matching the reference layout.
  Two pieces needed real data behind them rather than static copies of
  the screenshot (CLAUDE.md Rule 13):
   - Home/Work read/write through the new routing/SavedPlacesRepository
     (SharedPreferences) — a slot is genuinely unset ("Set location") until
     the user picks a place for it. Tapping an unset slot puts the screen
     into a `settingSlot` mode: the next result tapped is SAVED to that
     slot (and also handed back via onResultSelected) instead of just
     being searched.
   - "Recent" reads/writes through the new routing/RecentSearchRepository
     (SharedPreferences + org.json), most-recent-first, capped at 8,
     recorded whenever a result is picked outside `settingSlot` mode.
  Two things visible in the reference were deliberately NOT built:
   - Per-row business-hours status ("Open · Closes 22:00") — Nominatim
     (this app's only geocoding source) has no opening-hours data to show
     there, so it's left out rather than invented.
   - The third "… More" shortcut (opens additional saved lists in real
     Google Maps) — this app has no additional saved-place concept, so
     there's nothing for it to open; a non-functional button would be a
     fake affordance.
  The mic button uses Android's own `android.speech.RecognizerIntent`
  (platform API, no new dependency) to launch the system speech-
  recognition UI and read back its real transcribed text; a device with
  no speech-recognition app shows a real error (Toast), not a silent
  no-op. New icons (no material-icons-extended dependency, CLAUDE.md
  Rule 2 — same reasoning as ic_car.xml/ic_motorcycle.xml/ic_walk.xml):
  res/drawable/ic_home.xml, ic_work.xml, ic_mic.xml, ic_recent.xml (hand-
  drawn), ic_place.xml (standard AOSP Material "place" glyph, reused
  directly rather than pulled in via the extended icon pack).
Inputs: `initialQuery` (prefills the field, e.g. re-opening on an
  already-picked destination), `onBack`, `onResultSelected`.
Outputs: calls `onResultSelected(GeocodeResult)` when a result is
  tapped — does not navigate itself; the caller (MapScreen) closes the
  overlay and acts on the result.
Connected to: routing/GeocodingRepository.search, routing/
  SavedPlacesRepository, routing/RecentSearchRepository -> SearchScreen ->
  MapScreen (onResultSelected/onBack callbacks only, no shared state)
Important concepts: no navigation library added (CLAUDE.md Rule 2) —
  this is a plain Composable shown/hidden by a boolean in the caller,
  consistent with every other screen swap in this app.
```

```
routing/SavedPlacesRepository.kt
Status: IMPLEMENTED (new file, 2026-08-29)
Purpose: Persists the user's own Home/Work locations (SharedPreferences,
  full-precision lat/lon stored as strings, not Float, to avoid a silent
  precision loss). A slot (`SavedPlaceSlot.HOME`/`WORK`) reads back null
  until the user has actually picked a location for it via SearchScreen —
  never a placeholder or guessed address (CLAUDE.md Rule 13).
Inputs: `get(context, slot)`, `save(context, slot, GeocodeResult)`.
Outputs: `GeocodeResult?` per slot.
Connected to: ui/screens/SearchScreen.kt (only caller)
```

```
routing/RecentSearchRepository.kt
Status: IMPLEMENTED (new file, 2026-08-29)
Purpose: Persists the user's own past destination picks (SharedPreferences
  + org.json, most-recent-first, deduped by display name, capped at 8) so
  SearchScreen's "Recent" section shows real history, not sample data. A
  corrupt local cache recovers to an empty list (logged) rather than
  crashing the search screen.
Inputs: `getRecent(context)`, `add(context, GeocodeResult)`.
Outputs: `List<GeocodeResult>`, most-recent-first.
Connected to: ui/screens/SearchScreen.kt (only caller)
```

### ml/

```
inspect_dataset.py
Status: IMPLEMENTED
Purpose: Walks every smartphone trip CSV under IO-VNBD's "Categorised
  IOVNB Dataset" tree, validates schema by column POSITION (not by
  string-matching the raw header text, which is inconsistently
  byte-encoded — see Phase 4 findings above), computes observed sample
  rate, flags anomalies, and writes a reusable manifest. Turns the
  manual Phase 4 inspection into a rerunnable script (CLAUDE.md Rule 18).
Inputs: --dataset-root (default: data/raw/IO-VNBD/extracted/.../
  Categorised IOVNB Dataset — deliberately NOT the sibling
  "Uncategorised..." tree, see below), --output.
Outputs: data/processed/io_vnbd_smartphone_manifest.csv (gitignored,
  reproducible from raw/) — per-trip row count, schema variant,
  observed Hz, duration, satellite-parse-failure count, vehicle-file
  pairing status, and free-text notes for anything flagged. Also prints
  a human-readable summary to stdout.
Important functions: _load_smartphone_csv (position-based column
  renaming against SMARTPHONE_COLUMNS_24 / the 20-column
  no-orientation-or-magnetometer variant documented for Driver F's
  trips), _observed_hz (median timestamp delta, not mean — robust to
  the occasional GPS-outage-related timestamp gap the dataset's own
  documentation mentions), _observed_gps_fix_interval_s (median time
  between actual gps_latitude_deg value CHANGES, not assumed from the
  documented 1 Hz — see the real finding below, this is what caught the
  1 Hz claim being wrong), _count_satellite_parse_failures (the
  "GPS SATELLITES IN RANGE" column is formatted "USED / VISIBLE", e.g.
  "27 / 28", not a plain number).
Real finding while building this (2026-08-25): IO-VNBD's "Categorised
  IOVNB Dataset" and "Uncategorised IOVNB Dataset" folders both contain
  the SAME 72 underlying trips (confirmed by diffing a sample pair —
  identical row counts, identical values modulo float64 repr noise from
  a re-export, only the column header text differs cosmetically, e.g.
  "GYROSCOPE Yaw/Pitch/Roll" vs "GYROSCOPE X/Y/Z" for the exact same
  numbers). A naive recursive scan over the dataset root finds 144
  files and would silently duplicate every trip — a real risk of
  train/val leakage per PRD.md Section 25, not a hypothetical one. Fixed
  by scanning only "Categorised..." by default, which is also
  structurally better (S-/V- pairs live in the same subfolder there,
  which this script's vehicle-file pairing depends on). Also found: a
  small number of rows (up to 800 in one trip) have their
  "GPS SATELLITES IN RANGE" field corrupted into date strings like
  "Dec-14" — a classic Excel auto-date-conversion artifact from some
  point in the dataset's preparation, not a parsing bug in this script;
  harmless for us since satellite count isn't a feature either model
  will use. Also found (second pass, same day): the documented "1 Hz"
  GPS update rate is wrong — measured across the real data, GPS fixes
  actually only change ~every 9.0 seconds (68/72 trips consistent; the
  remaining trips are dedicated stationary/parked recordings where the
  fix never changes at all — expected there, not a gap in the
  measurement). Same "requested rate != delivered rate" lesson our own
  Android app already learned in Slice 1. Real implication: PRD.md
  Section 13's "elapsed time since last GNSS fix" feature must use this
  measured ~9s interval, not the documented 1s.
Unit tests: tests/ml/test_inspect_dataset.py (7 cases, pure helpers
  only, synthetic data — no dependency on the real gitignored download,
  so they run anywhere) — verifies median-not-mean Hz calculation
  against a synthetic outlier gap, satellite-format regex including the
  real "Dec-14" artifact as a test case, empty/single-row edge cases,
  and the GPS-fix-interval measurement (including the "fix never
  changes" zero case).
Real run against the actual downloaded dataset (2026-08-25): 72 unique
  trips, 1,070,745 total rows, 25.1 hours, all 24-column schema, all
  within the [8, 12] Hz tolerance, all correctly paired with a vehicle
  file, median measured GPS fix interval 9.0s across 70 non-stationary
  trips, 68/72 trips flagged for that interval mismatch plus 12 for the
  harmless satellite-format issue above (some trips flagged for both).
  `python -m pytest tests/ml/test_inspect_dataset.py` — 7/7 pass.

feature_extraction.py
Status: IMPLEMENTED
Purpose: Turns each IO-VNBD smartphone trip into a per-tick table of
  VEHICLE-frame windowed features (PRD.md Section 11) + a GPS-speed
  ground-truth label, ready for training the velocity model. Python
  mirror of the still-PLANNED Kotlin FeatureExtractor.kt — any
  divergence between the two is a top project risk (PRD.md Section 31)
  and must eventually be tested via output-parity (CLAUDE.md Rule 20)
  once the Kotlin side exists.
Inputs: --dataset-root (default: same Categorised tree as
  inspect_dataset.py), --output.
Outputs: data/processed/io_vnbd_features.parquet (gitignored,
  reproducible) — one row per original 10 Hz sample, per trip:
  vehicle-frame accel (forward/lateral/up, m/s^2) and gyro (yaw/pitch/
  roll rate, rad/s) plus windowed mean/std/energy/jerk/zero-crossing-
  rate statistics (1.0s trailing window, WINDOW_SAMPLES=10 — a default,
  not yet empirically tuned), elapsed_since_last_gnss_fix_s,
  previous_gps_speed_mps, and the label_gps_speed_mps target.
Important functions: _vehicle_frame_axes / rotate_to_vehicle_frame
  (pure, vectorized numpy — the Gram-Schmidt vehicle-frame construction
  from the module's docstring), extract_trip_features (one trip),
  build_dataset (all trips, tags each row with trip_name/driver_group
  so a downstream split can respect trip boundaries per PRD.md
  Section 25), _elapsed_since_last_gps_fix_s (reused concept from
  inspect_dataset.py's measured-not-assumed GPS timing finding).
REAL BUG CAUGHT AND FIXED (2026-08-25, before any model was trained):
  the dataset's own figure (Phase 4 finding above) suggested
  +device-Y is the forward/direction-of-travel axis. Built the vehicle-
  frame rotation on that assumption, then sanity-checked it against
  real GPS ground truth (correlate windowed forward-acceleration
  against the ACTUAL speed change at the next real GPS fix, across all
  72 trips, 13,902-13,905 fix-to-fix segments) before trusting it for
  training — correlation came out NEGATIVE (-0.136 to -0.137 depending
  on exact windowing), i.e. accelerating correlated with SLOWING DOWN,
  backwards from physics. Root-caused to the figure's arrow simply not
  matching the actual sign in the data (a diagram is a claim, not a
  verified fact — CLAUDE.md Rule 13). Fixed by flipping the forward
  axis to -device-Y; re-ran the same correlation check and got +0.137,
  matching physical expectation. (A separate, smaller finding along the
  way: Android's gravity/accelerometer convention reports the reaction
  force, already pointing "up" — an intermediate version of this code
  negated it unnecessarily; turned out to be mathematically a no-op for
  the forward axis specifically, since Gram-Schmidt's projection term
  is sign-invariant in the axis being projected against, but was still
  fixed for correct up/lateral labeling.) This whole investigation is
  exactly what CLAUDE.md Rule 18's "prototype small, verify against
  real data" and Rule 19's "test before anything downstream relies on
  it" are for — caught before `train_velocity_model.py` was ever
  written, not discovered later as a mysteriously bad model.
Unit tests: tests/ml/test_feature_extraction.py (6 cases) — flat-phone
  and tilted-phone (30 degree, hand-derived expected vector) Gram-Schmidt
  cases for the corrected forward axis, orthogonality/unit-norm
  self-consistency of the {forward,lateral,up} basis for an arbitrary
  skewed gravity vector, and the GPS-fix-interval helper. All pass;
  none of these unit tests alone could have caught the sign error above
  (they verify internal self-consistency, not which real-world direction
  is "correct") — that required the real-data correlation check, which
  is exactly why that check was necessary and is recorded here, not
  just the unit tests.
Real run against the actual downloaded dataset (2026-08-25):
  1,070,745 rows, 72 trips, all succeeded. `python -m pytest
  tests/ml/test_feature_extraction.py` — 6/6 pass.

train_velocity_model.py
Status: IMPLEMENTED (velocity model only — train_motion_classifier.py still PLANNED, Slice 6 continues)
Purpose: Trains a RandomForestRegressor on feature_extraction.py's
  output and measures it against the SAME naive physics-integration +
  ZUPT baseline the Android app ships (a Python re-implementation of
  dr/BaselinePhysicsIntegrator.kt + dr/StationaryDetector.kt's
  thresholds, kept in sync manually — CLAUDE.md Rule 3: ML is only
  justified once measured against the real physics baseline, not
  assumed better).
Inputs: --features (default: data/processed/io_vnbd_features.parquet).
Outputs: printed MAE/RMSE (overall + per-trip, deduplicated to real GPS
  fix changes) + feature importances. No model artifact saved by THIS
  script — that's export_model.py's job (now IMPLEMENTED too, see
  below), gated on this result being good enough to justify shipping
  (it is, see below).
Important functions: split_trips (by trip_name, not row — PRD.md
  Section 25 no-leakage requirement; fixed seed=42 for reproducibility,
  PRD.md Section 27), physics_baseline_velocity (the comparison
  baseline), evaluate_deduplicated_by_fix (a second, more
  independent-samples-honest metric alongside the full per-row one,
  since ~90 consecutive rows share one label — see the leakage note
  below).
IMPORTANT — feature leakage avoided, not just noted: PRD.md Section 13
  lists "previous velocity estimate" as a candidate input, and
  feature_extraction.py computes previous_gps_speed_mps, but this
  script does NOT use it as a training feature. Reason: since GPS is
  held for ~90 rows (Phase 4 finding), previous_gps_speed_mps equals
  the CURRENT label exactly for ~89/90 rows — training on it would let
  the model just copy a near-answer and score deceptively well without
  learning anything from the IMU. Excluded here; the live-deployment
  version of "previous velocity" (the model's own prior prediction
  during an outage) is a different, legitimate feature to revisit once
  real on-device autoregression exists.
REAL MEASURED RESULT (2026-08-25, S24 driver-shuffle seed=42, 58 train /
  14 val trips): **ML model MAE=1.244 m/s, RMSE=1.593 m/s** vs.
  **Physics+ZUPT baseline MAE=5.205 m/s, RMSE=6.345 m/s** — the ML
  model is ~4.2x more accurate overall. Per-trip (deduplicated to real
  GPS fix changes), ML beat the physics baseline on 13 of 14 held-out
  trips, sometimes by a wide margin (e.g. S-Vw14b: 1.619 vs 6.337); the
  one exception, S-Vtb3, physics actually won (0.496 vs 1.014) — not
  investigated further yet, flagged as a follow-up (possibly a short/
  low-speed trip where naive integration hasn't drifted much yet).
  This satisfies CLAUDE.md Rule 3 with a real, not assumed, comparison
  — ML is justified for the velocity model.
  Feature importance finding, reported honestly rather than omitted:
  `accel_up_std_mps2` (vertical-axis acceleration std within the
  window) dominates at 0.672 importance — far above any accel_forward
  feature. This is plausibly a real, legitimate signal (road/engine
  vibration amplitude scales with speed — a known technique in
  phone-based speed estimation, and exactly why naive forward-accel
  integration alone drifts so badly), not necessarily a red flag, but
  it does mean the model may be leaning on a "how much is this phone
  vibrating" proxy more than a kinematic forward-acceleration
  relationship — a plausible generalization risk (different road
  surfaces/vehicles/mounts might vibrate differently) worth keeping in
  mind, not yet tested.
  Evaluation-scope caveat (CLAUDE.md Rule 13 — stating what was NOT
  measured, not just what was): this MAE measures per-tick regression
  accuracy against GPS-labeled speed, including ticks where GPS was
  available. It does NOT yet simulate sustained multi-minute
  GNSS-outage position drift the way the deployed app would actually
  experience it (Section 16's dx/dy integration compounds velocity
  error over time in a way a single-tick MAE doesn't capture) — that
  end-to-end drift measurement is Slice 9's job, not this script's.
Unit tests: tests/ml/test_train_velocity_model.py (14 cases, added
  2026-08-25 — this file initially shipped with zero tests, only
  validated by one real run; CLAUDE.md Rule 19 requires the math be
  tested before anything relies on it, not just observed working once)
  — split_trips: no train/val overlap, union covers every trip,
  deterministic with a fixed seed, different seeds CAN differ, val
  fraction respected, at-least-one-val-trip edge case;
  physics_baseline_velocity: constant acceleration matches hand-
  calculated Euler integration, ZUPT zeroes velocity when stationary,
  high gyro ALONE prevents ZUPT even when accel alone would satisfy it
  (verifies the AND, not just that ZUPT fires at all), non-positive dt
  holds the previous value rather than integrating garbage;
  evaluate: MAE/RMSE against hand-calculated values; 
  evaluate_deduplicated_by_fix: only evaluates at real label-change
  rows, returns None when a label never changes; a real
  training-reproducibility test (PRD.md Section 27 — two
  RandomForestRegressors trained on identical data with the same fixed
  seed produce byte-identical predictions, not just "looked the same
  once"). Also re-ran `python ml/train_velocity_model.py` against the
  real dataset a second time and confirmed identical MAE/RMSE
  (1.244/1.593 m/s) to the first run — real-data reproducibility, not
  just synthetic-data unit tests. `python -m pytest
  tests/ml/test_train_velocity_model.py` — 14/14 pass.

train_motion_classifier.py
Status: PLANNED
Purpose: Train + evaluate the motion classifier (PRD.md Section 14 —
  Stationary/Pothole/Turning/Phone Moved/etc.), report metrics per
  PRD.md Section 28. Blocked on the self-captured supplementary data
  the Phase 4 findings above call for (Pothole/Phone Moved have no
  ground-truth signal in IO-VNBD) — Stationary alone could proceed now
  via weak-labeling from GPS speed ≈ 0, but the other classes cannot.

export_model.py
Status: IMPLEMENTED (velocity model only — motion classifier export
  waits on train_motion_classifier.py)
Purpose: Retrains the velocity model on ALL 72 trips (not just the
  58-trip train split train_velocity_model.py used for honest
  evaluation — standard practice once the approach is already
  validated: ship a model trained on every available example, but
  report accuracy from the held-out evaluation, never from a
  full-data model's training score), exports it to ONNX via skl2onnx,
  and runs a real output-parity check (CLAUDE.md Rule 20) between the
  sklearn prediction path and ONNX Runtime — not just "it exported
  without an exception."
Inputs: --features, --output (default: models/velocity_v1.onnx,
  matching models/README.md's `<model>_v<N>.onnx` convention),
  --parity-sample-size (default 5000).
Outputs: models/velocity_v1.onnx (gitignored per models/README.md —
  verified via `git check-ignore`; only this convention doc is
  committed, not the binary). Exits non-zero if parity fails, so this
  is a real gate, not just an informational print.
Important functions: check_parity (runs the SAME rows through both the
  sklearn model and an onnxruntime InferenceSession, compares outputs
  directly — PARITY_TOLERANCE_MPS = 1e-3 m/s, since ONNX Runtime
  computes in float32 while sklearn's RandomForest is exact in
  float64, so tiny numerical drift is expected and not itself a
  failure).
Real result (2026-08-25): exported model is 20.7 MB (100 trees,
  max_depth=12) — within PRD.md Section 9's "low tens of MB at most"
  target but not "far less," worth revisiting (fewer/shallower trees)
  once on-device latency is actually measured (Section 26, not done
  yet — this is a desktop-only number so far, CLAUDE.md Rule 12 forbids
  calling on-device performance "done" from a desktop measurement).
  Parity check on 5,000 held-out rows: max abs diff 0.000001 m/s, mean
  0.000000 m/s, 5000/5000 within the 0.001 m/s tolerance — passed
  cleanly. `python ml/export_model.py` exits 0.
Unit tests: tests/ml/test_export_model.py (2 cases, added 2026-08-25) —
  a fast, real (not mocked) sklearn -> ONNX -> onnxruntime round-trip
  on a tiny synthetic model, so this doesn't depend on the real
  gitignored dataset or the multi-minute full training run: (1)
  export + parity on a matching model passes; (2) a deliberately
  MISMATCHED model (trained on different synthetic labels) checked
  against the first model's ONNX export correctly FAILS parity — this
  proves check_parity actually detects a real mismatch, not just that
  it never fires (a parity check that can't fail isn't a check).
  `python -m pytest tests/ml/test_export_model.py` — 2/2 pass.

### data/, models/, tests/, scripts/

```
Status: PLANNED — populated as Phases 3–9 proceed. models/ artifacts
must be versioned (e.g. velocity_v1.onnx, velocity_v2.onnx) so a
regression can be traced to a specific model version.
```

---

## Phase 4 — Dataset inspection findings (2026-08-25)

IO-VNBD downloaded (Synchronised V+S set, ~194 MB via Git LFS — see
`data/README.md` for the re-download command) and inspected directly:
360 files total on disk (paired `S-<trip>.csv` smartphone +
`V-<trip>.csv` vehicle ECU/GPS + a route-map `.jpg` per trip), plus the
dataset's own data descriptor PDF (`README_1.pdf`, Onyekpe et al. 2020)
read in full for authoritative column/units/setup documentation rather
than guessed from the CSVs alone. **Correction, found while building
`ml/inspect_dataset.py`**: those 360 files are NOT 72 unique trips —
the archive contains the same 72 trips twice, once under "Categorised
IOVNB Dataset" and once under "Uncategorised IOVNB Dataset" (see that
script's entry under `## Planned File Map` -> `ml/` below for the full
detail). The actual usable dataset is **72 unique trips, 1,070,745
rows, 25.1 hours** — smaller than this section originally implied, and
importantly, training must use only the "Categorised" tree or risk
duplicating every trip across a train/val split. Findings below,
resolving the three questions this section used to ask, are otherwise
unaffected (schema, axis convention, and label availability are
properties of the trip data itself, not of which tree it's read from):

- **Sensor channels, units, rate**: Smartphone CSVs have 24 columns
  (verified against a real file, `S-S1.csv`): GPS lat/lon/altitude/
  speed(km/h)/accuracy(m)/orientation(deg)/satellites-in-range
  ("27 / 28" format, not a plain number), a millisecond
  "TIME SINCE START" counter, a human-readable date string,
  accelerometer XYZ (m/s²), gravity XYZ (m/s², Android's TYPE_GRAVITY
  virtual sensor — recorded separately from raw accel, unlike our app
  which only has a fixed STANDARD_GRAVITY_MPS2 constant; see risk note
  below), gyroscope "Yaw/Pitch/Roll" (rad/s — NOT labeled X/Y/Z, see
  axis-convention finding below), magnetic field XYZ (µT — we don't
  read the magnetometer at all yet), and phone-computed orientation
  Yaw/Pitch/Roll (degrees — directly comparable to our own
  OrientationMath output as an on-device sanity check). Confirmed
  sample interval from real timestamp deltas (2922ms -> 3022ms ->
  3121ms) is ~100ms, i.e. ~10 Hz, matching PRD.md Section 11's target.
  Row count for one ~86-minute trip (S-S1) is 51,747 — consistent with
  ~10 Hz over that duration. **Correction (measured by
  `ml/inspect_dataset.py`, not just trusted from the PDF)**: GPS does
  NOT update at 1 Hz as documented — the gps_latitude_deg column only
  actually CHANGES value roughly every 9.0 seconds (measured across
  68/72 trips consistently, not an isolated anomaly; a handful of
  dedicated stationary/parked trips show 0.0s because the fix never
  changes at all, which is expected there, not a measurement failure).
  Ten intervening 10 Hz rows share an identical held GPS
  speed/lat/lon value between each real fix. This is the same lesson
  our own Android app already learned the hard way in Slice 1
  (CLAUDE.md Rule 10): a requested rate is not a delivered rate: the
  paper's "1 Hz" was evidently the AndroSensor app's requested rate,
  not what the phone's GPS provider actually delivered. Real
  implication for Slice 6: "elapsed time since last GNSS fix"
  (PRD.md Section 13's listed velocity-model input) must be computed
  from ACTUAL fix-change timestamps, not assumed to be ~1s; and
  training row/label independence needs care, since ~90 consecutive
  10 Hz rows share one ground-truth GPS speed value, not 90 independent
  ground-truth readings.
  Vehicle CSVs have 29 columns (GPS, wheel speeds, steering angle, yaw
  rate, indicated accelerations, gear, brake/clutch/accelerator state,
  etc.) also at 10 Hz via a Racelogic VBOX CAN-bus logger — this is
  external-hardware ground truth we don't have live, useful only for
  offline training/validation, never for the on-device app.

- **CRITICAL axis-convention finding (device-frame mismatch)**:
  extracted and viewed the dataset's own Figure 2 (smartphone sensor
  axis diagram) directly from the PDF rather than assuming. It shows
  the phone lying flat, and its labeled X/Y/Z axes do NOT match
  Android's standard SensorEvent device-frame convention that our own
  `sensors/SensorSample.kt` uses (device X = right edge, Y = toward top
  of screen, Z = out of the screen face). In the dataset's diagram: the
  axis they call "Y" points along the direction of travel (out the
  right edge of the phone as mounted) and is explicitly labeled
  "Direction of travel" in the figure; the axis they call "X" points
  straight up out of the screen face; the axis they call "Z" runs along
  the phone's long edge. This means IO-VNBD's accelerometer/gyroscope
  X/Y/Z columns are a PERMUTED relabeling relative to raw Android
  SensorEvent axes, not the same convention — training a model directly
  on IO-VNBD's raw X/Y/Z (or Yaw/Pitch/Roll) columns without accounting
  for this would silently mismatch our live device-frame samples
  (CLAUDE.md Rule 9/14's exact concern). Practical implication for
  Slice 6: `FeatureExtractor.kt` must not feed raw device-frame columns
  from either source into the same model un-reconciled — either remap
  IO-VNBD's columns to Android's SensorEvent convention during
  preprocessing, or better, compute features in a mounting-convention-
  independent frame. **RESOLVED (2026-08-25)**: `ml/feature_extraction.py`
  took the "mounting-convention-independent frame" path (device-frame
  Gram-Schmidt against gravity, not raw axis remapping); the Kotlin
  `features/FeatureExtractor.kt` sidesteps the question entirely by
  receiving ALREADY vehicle-frame-rotated input from
  `ml/MlVelocityRepository.kt` (a different, WORLD-frame-heading-based
  rotation — see that file's entry for the resulting, honestly-
  documented parity gap between the two approaches). This is actually
  a second, independent reason PRD.md Section 11's planned vehicle-frame
  transform is necessary before feature extraction, not just a
  nice-to-have — it launders away exactly this kind of raw-axis
  mismatch between training data and live
  device data). Also confirms the dataset's own smartphone was mounted
  in a fixed, known, consistent orientation throughout collection (a
  windshield-mounted holder, per the setup photo also extracted from the
  PDF) — i.e. IO-VNBD implicitly assumes a solved phone-to-vehicle
  alignment, which our live app does not have yet (PRD.md Section 15,
  still PLANNED).

- **Motion-classifier labels**: NO per-timestamp event labels exist in
  the CSVs (no "Pothole"/"Turning"/"Phone Moved"/"Stationary" column
  with a 0/1 or class value per row). The PDF's Appendix tables (A1-1
  through A7) instead give TRIP-LEVEL scenario tags in a free-text
  "Features" column (e.g. "Hard Brake, Round-About (x9), Reverse (x5),
  Hilly Road... Potholes" for one whole ~86-minute trip) — these say
  what happened SOMEWHERE during that drive, not when. This directly
  answers PRD.md Section 24's open question: IO-VNBD is well-suited to
  the velocity regression model (GPS/wheel-speed ground truth exists at
  every timestamp) but NOT directly usable as-is for Section 14's
  per-sample motion classifier (Stationary/Pothole/Turning/Phone Moved)
  without either (a) weak/heuristic labeling from other columns for
  some classes (e.g. Stationary is derivable from GPS speed ≈ 0 — the
  dataset even includes 20+ minutes of dedicated "Stationary (No
  Motion)" trips, e.g. V-Vw1/V-Vw15, explicitly recorded for sensor-bias
  estimation, which is directly useful for validating/tuning
  `dr/StationaryDetector.kt`'s thresholds against real recorded noise),
  or (b) self-captured supplementary data for classes with no clean
  derivable signal (Pothole, Phone Moved have no dedicated ground-truth
  channel at all). **Decision**: proceed with IO-VNBD as primary for
  the velocity model and for Stationary-class weak-labeling; plan a
  short self-captured recording specifically for Pothole/Phone-Moved
  examples before Slice 6's classifier training, per PRD.md Section 24's
  anticipated fallback — this is not yet done, flagged for Slice 6.

- **Other findings not previously anticipated, worth recording**:
  (1) the PDF documents that Driver F's trips (S-T1...S-T11) are
  missing orientation/magnetic-field data (a 20-column variant) — but
  the "Synchronised V and S datasets" archive we actually downloaded
  only contains Drivers A, B, D, and E (`ml/inspect_dataset.py`'s real
  run confirms: 72/72 trips are the full 24-column schema, zero
  20-column files found). Driver F/G/H's independently-captured trips
  (Table A7) live only in the separate "Unsynchronised V and S Dataset"
  archive, which is NOT downloaded — `inspect_dataset.py` still handles
  the 20-column variant defensively (in case that archive is ever
  added), but it's currently dead code against our actual data, not
  something we've verified against real files. (2) 4 drivers present
  (not 8 — the other 4 from Table 1 only appear in non-synchronised or
  vehicle-only recordings we don't have), 3 "Defensive" (A, B, D) +
  1 "Aggressive" (Driver E) — Driver E alone contributes the large
  Vta/Vtb/Vw/Vf families of trips (most of the 72), so a naive random
  train/val split could accidentally concentrate aggressive-driving
  examples in one split; PRD.md Section 25's "split respects trip/
  session boundaries" already guards against within-trip leakage, but
  driver-level stratification is a related concern worth considering
  during actual training, given how driver-imbalanced this subset is.
  (3) recorded across multiple UK regions per the PDF's per-trip city
  listings, with real GPS-loss periods noted in an accompanying txt
  file (not yet located/inspected in our download) — potentially
  directly useful as real, non-simulated GNSS-outage examples for later
  validation, worth locating in Slice 9.

## Change log

Moved to `summary.txt` at the repo root — see that file for the full
chronological history (every slice, bug fix, scope decision, and
on-device verification, in the order it actually happened).
