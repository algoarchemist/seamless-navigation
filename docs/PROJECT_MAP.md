# PROJECT_MAP.md — SIH26168 Intelligent Dead Reckoning

Living document. Update this in the same change as any file that is
added, removed, or has its responsibility/interface changed
(CLAUDE.md Rule 6/21). This is written to teach the pipeline, not just
list files — when in doubt, explain *why*, not just *what*.

Status as of last update: **Phase 0 scaffold + Slice 1+2+3+4+5
implemented and on-device verified.** The folder/build-system skeleton
(`## Scaffold Status`) and Slice 1+2+3+4+5 (Android sensor -> live
sensor display, sensor -> orientation, sensor -> baseline physics
velocity/position, GNSS outage detection, dead reckoning state-machine
wiring + ZUPT + non-holonomic constraint, `## Slice 1+2+3+4+5`) are
real, build-verified, and now confirmed running on a real Samsung
Galaxy S24 FE (Android 16 / One UI 8.5) with live accel/gyro readout at
~12.5-16.7 Hz observed, live azimuth/pitch/roll orientation, a live
GNSS_AIDED/TRANSITION/DEAD_RECKONING/REACQUISITION mode readout, and a
live WORLD-frame position/velocity estimate that now visibly stays near
zero while the phone is at rest (confirmed over 15+ seconds — the
ZUPT correction working, versus Slice 4's -0.85/-1.56 m drift over a
similar period) all confirmed updating on screen. Everything else
under `## Planned File Map` is still a target derived from PRD.md — no
vehicle-frame alignment, ML, fusion, or map-matching code exists yet.
Each entry gets flipped from `PLANNED` to
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

No ML, fusion, or map-matching code exists yet — everything below this
line (past `## Slice 1+2+3+4+5`) is still the Slice-6-onward target,
unchanged from the original plan.

---

## Slice 1+2+3+4+5 — live sensor display, sensor -> orientation, sensor -> baseline physics velocity/position, GNSS outage detection, dead reckoning state-machine wiring + ZUPT + non-holonomic constraint (implemented)

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
  GnssModeRepository -> BaselineDeadReckoningRepository (mode-gated reset)
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
  drift since app launch. This does NOT fuse GNSS and DR positions
  together (no blending math) — that's Slice 7.
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
  The two clocks are explicitly NOT reconciled against each other yet
  (CLAUDE.md Rule 9/14); that alignment is deferred to Slice 7 (fusion).

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
Purpose: Slice 1+2+3+4+5 entry point — instantiates SensorRepository,
  LocationRepository, GnssModeRepository, and (depending on
  GnssModeRepository, Slice 5) BaselineDeadReckoningRepository in that
  dependency order; starts/stops all on onResume/onPause (GnssModeRepository
  before BaselineDeadReckoningRepository, so its mode-gated reset reads
  an already-ticking mode); requests ACCESS_FINE_LOCATION at runtime via
  ActivityResultContracts if not already granted; renders the latest
  accel/gyro/orientation sample, observed Hz, live corrected DR
  position/velocity, and live GNSS mode/fix/transition via a Compose
  screen (IdrSensorScreen).
Connected to: SensorRepository -> MainActivity -> IdrSensorScreen (Compose);
  SensorRepository -> BaselineDeadReckoningRepository -> MainActivity -> IdrSensorScreen;
  LocationRepository -> GnssModeRepository -> MainActivity -> IdrSensorScreen;
  GnssModeRepository -> BaselineDeadReckoningRepository (Slice 5)
Important functions/classes: requestLocationPermission
  (registerForActivityResult(RequestPermission()), registered as a
  property initializer since it must be registered before the activity
  reaches STARTED) — starts LocationRepository once granted; if already
  granted on resume, starts it directly instead of re-prompting.
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
  or hiding the section. No ML, no GNSS/DR position fusion yet — purely
  a live readout proving the sensor + orientation + corrected-physics-
  integration + GNSS-outage-detection pipeline works end-to-end without
  blocking the UI thread.

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

StateEstimator.kt
Status: PLANNED
Purpose: Integrate heading + ML velocity into position, blend with GNSS
  during fusion/reacquisition (actual position blending, Slice 7 — not
  just mode display). ZUPT (StationaryDetector) and non-holonomic
  constraint (NonHolonomicConstraint) are already IMPLEMENTED as of
  Slice 5, applied directly to BaselinePhysicsIntegrator rather than
  waiting for this file — see `## Slice 1+2+3+4+5` above. StateEstimator
  will eventually supersede BaselineDeadReckoningRepository as the
  primary estimator once ML velocity (Slice 6) and GNSS/DR fusion
  (Slice 7) exist; BaselinePhysicsIntegrator stays in the codebase as
  the measured physics-only comparison point (CLAUDE.md Rule 3), not
  deleted.
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
- Slice 4 implemented (2026-08-25): new `gnss/` package. `GnssFix.kt`
  (wall-clock-timestamped fix data, explicitly NOT reconciled against
  boot-time sensor timestamps yet, CLAUDE.md Rule 9/14).
  `GnssQuality.kt` (pure: is a fix good enough right now, by age +
  accuracy). `GnssOutageDetector.kt` (pure: the GNSS_AIDED/TRANSITION/
  DEAD_RECKONING/REACQUISITION hysteresis state machine, PRD.md
  Section 18 — separate enter/exit dwell times per CLAUDE.md Rule 16,
  every transition logged with trigger condition per Rule 17).
  `LocationRepository.kt` (Android glue: FusedLocationProviderClient at
  ~1 Hz, requires runtime ACCESS_FINE_LOCATION). `GnssModeRepository.kt`
  (Android/coroutine glue: evaluates the detector on its own 5 Hz timer,
  independent of the sensor pipeline so GNSS detection works even if
  IMU sensors are unavailable — CLAUDE.md Rule 5). `MainActivity.kt`
  now requests location permission at runtime
  (ActivityResultContracts.RequestPermission) and displays live GNSS
  mode/fix/last-transition. Added `GnssQualityTest.kt` and
  `GnssOutageDetectorTest.kt` (13 cases, CLAUDE.md Rule 19) — including
  explicit "one ms before the dwell threshold" boundary checks, not just
  "eventually transitions." `./gradlew test` — all 32 tests pass.
  TRANSITION/REACQUISITION are state-only in this slice; actual
  GNSS/DR position blending is deferred to Slice 7 (Fusion /
  re-alignment on GNSS reacquisition), matching CLAUDE.md's slice order.
  On-device: hit and fixed a real bug in `BaselineDeadReckoningRepository`
  (Slice 3 code) — the first accel sample of each run computed a
  multi-thousand-second dt against a `0L` sentinel instead of treating
  it as "no prior sample," sending the DR position to ~1e11 m
  immediately on launch. Fixed with a nullable-Long sentinel (see that
  file's entry above); re-verified via screenshot after the fix —
  position/velocity now stay small at rest as expected. GNSS mode
  readout confirmed via screenshot too: `DEAD_RECKONING` with the
  correct trigger description, matching unit-tested behavior. Also hit
  (and resolved) an unrelated ADB connectivity issue mid-session — the
  Windows ADB USB interface got stuck in a stale `CM_PROB_PHANTOM`
  driver state; restarting the ADB server and re-toggling USB debugging
  on the phone fixed it, not a code or cable problem. Slice 1+2+3+4
  complete and hardware-verified, no ML, no GNSS/DR fusion yet. Next:
  Slice 5 (dead reckoning: state machine + ZUPT + non-holonomic
  constraint).
- Slice 5 implemented (2026-08-25): added `dr/StationaryDetector.kt`
  (pure: sustained low accel+gyro magnitude, with the same dwell/
  hysteresis principle as GnssOutageDetector, gates ZUPT — honestly
  documented as unable to distinguish "at rest" from "coasting at
  constant velocity," an inherent limit of accel/gyro-only ZUPT) and
  `dr/NonHolonomicConstraint.kt` (pure: vector-projects velocity onto
  device heading, suppressing the lateral component — a simplified
  WORLD-frame stand-in for PRD.md Section 20's VEHICLE-frame constraint,
  since phone-to-vehicle alignment doesn't exist yet; no Turning
  exemption either, since the ML motion classifier is Slice 6).
  Added `BaselinePhysicsIntegrator.overrideVelocity()` (sets velocity
  only, leaves position untouched — distinct from `reset()`) as the
  mechanism both corrections use. `BaselineDeadReckoningRepository.kt`
  now depends on `GnssModeRepository` too: applies ZUPT/NHC every tick,
  and resets the integrator every tick GNSS mode is GNSS_AIDED, so the
  DR readout represents "distance traveled since GNSS was last good"
  (PRD.md Section 28's actual drift-measurement target) rather than
  drift since app launch — this is Slice 5's "state machine" wiring:
  connecting Slice 4's previously-cosmetic mode to actually affect the
  DR estimate, without yet blending GNSS and DR positions together
  (that's Slice 7). `MainActivity.kt` construction order changed —
  `GnssModeRepository` must now exist before `BaselineDeadReckoningRepository`.
  Added `StationaryDetectorTest.kt` and `NonHolonomicConstraintTest.kt`
  (12 cases total, CLAUDE.md Rule 19) plus 2 new BaselinePhysicsIntegratorTest
  cases for `overrideVelocity()`. `./gradlew test` — all 46 tests pass.
  Installed on the same S24 FE; two screenshots ~15 seconds apart at
  rest confirmed position stays near-zero throughout (not just at
  launch) — direct, visible proof ZUPT is working, versus Slice 4's
  -0.85/-1.56 m drift over a similar period. The GNSS-mode-gated reset
  couldn't be visually confirmed indoors (no real GPS lock available to
  reach GNSS_AIDED) — verified by code inspection + existing
  GnssOutageDetector tests instead; flagged for a direct outdoor check
  later. Slice 1+2+3+4+5 complete and hardware-verified, no ML, no
  GNSS/DR position fusion yet. Next: Slice 6 (ML inference: velocity +
  motion classifier wired in) — requires Phase 4 dataset inspection
  (PRD.md Section 24) first, since no training data has been looked at
  yet.
