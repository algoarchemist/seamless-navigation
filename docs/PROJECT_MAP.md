# PROJECT_MAP.md — SIH26168 Intelligent Dead Reckoning

Living document. Update this in the same change as any file that is
added, removed, or has its responsibility/interface changed
(CLAUDE.md Rule 6/21). This is written to teach the pipeline, not just
list files — when in doubt, explain *why*, not just *what*.

Status as of last update: **Phase 0 scaffold + Slice 1-6 (Kotlin half
included) implemented and on-device verified.** The folder/build-system
skeleton (`## Scaffold Status`) and Slice 1-5 (Android sensor -> live
sensor display, sensor -> orientation, sensor -> baseline physics
velocity/position, GNSS outage detection, dead reckoning state-machine
wiring + ZUPT + non-holonomic constraint) are real, build-verified, and
confirmed running on a real Samsung Galaxy S24 FE (Android 16 / One UI
8.5). **Phase 4 (dataset inspection) and Slice 6's full pipeline —
Python training AND Kotlin on-device wiring — are now also done.**
Python side: `ml/inspect_dataset.py`, `ml/feature_extraction.py`,
`ml/train_velocity_model.py`, `ml/export_model.py` — real, tested, run
against the actual downloaded IO-VNBD dataset. Real measured result:
the trained velocity model beats the app's own physics+ZUPT baseline by
~4.2x (MAE 1.244 m/s vs 5.205 m/s on held-out trips — CLAUDE.md Rule 3
satisfied with a real comparison), exported to `models/velocity_v1.onnx`
with a clean output-parity check. Kotlin side (2026-08-25): AlignmentEstimator,
FeatureExtractor, VelocityModel (ONNX Runtime Mobile), and
MlVelocityRepository are all implemented, tested (29 new unit tests),
and confirmed RUNNING LIVE on the same S24 FE — the ONNX model produces
real-time predictions on-device with no crash, displayed side by side
with the physics-only estimate. Several real bugs were caught and fixed
along the way, each before it could silently corrupt something
downstream: a duplicate-trip-tree risk in the raw dataset, a
forward-axis sign error caught by correlating against real GPS ground
truth, and a Compose screen-overflow bug (the ML section was rendering
but genuinely invisible off the bottom of the screen — only caught by
looking at the real device output, not by compiling) — see `## Phase 4
— Dataset inspection findings` and the relevant file entries below for
the full writeups. **UPDATE (2026-08-25, same day)**: ML velocity is
now ALSO wired into a real position estimate —
`ml/MlPositionIntegrator.kt` propagates it per PRD.md Section 16,
running parallel to (not replacing) the physics position. On-device
testing surfaced a real, honestly-documented finding: this ML position
path is measurably more sensitive to brief disturbances than the
physics path (no momentum damping — see MlPositionIntegrator.kt's
entry), deliberately left unfixed for now as an explicit, discussed
decision. **Still not done**: `train_motion_classifier.py` (blocked on
self-captured Pothole/Phone-Moved data), true GNSS/DR position fusion
(Slice 7). Everything else under `## Planned File Map` is still a
target derived from PRD.md — no vehicle-frame alignment for the
physics position estimate (only for ML features), fusion, or
map-matching code exists yet. Each entry gets flipped from `PLANNED` to
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
Purpose: Slice 1+2+3+4+5+6 entry point — instantiates SensorRepository,
  LocationRepository, GnssModeRepository, BaselineDeadReckoningRepository,
  and (Slice 6) loads VelocityModel from assets and instantiates
  MlVelocityRepository, in that dependency order; starts/stops all on
  onResume/onPause; requests ACCESS_FINE_LOCATION at runtime via
  ActivityResultContracts if not already granted; renders the latest
  accel/gyro/orientation sample, observed Hz, live corrected DR
  position/velocity, live GNSS mode/fix/transition, AND (Slice 6) the
  live ML-predicted velocity + alignment status, side by side, via a
  Compose screen (IdrSensorScreen) — now scrollable (see bug note below).
Connected to: SensorRepository -> MainActivity -> IdrSensorScreen (Compose);
  SensorRepository -> BaselineDeadReckoningRepository -> MainActivity -> IdrSensorScreen;
  LocationRepository -> GnssModeRepository -> MainActivity -> IdrSensorScreen;
  GnssModeRepository -> BaselineDeadReckoningRepository (Slice 5);
  SensorRepository, GnssModeRepository -> MlVelocityRepository -> MainActivity -> IdrSensorScreen (Slice 6)
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
  or hiding the section. Still no actual GNSS/DR position fusion (Slice
  7) and ML velocity is NOT yet fed into the position integrator — it's
  displayed for comparison only (see MlVelocityRepository's doc).
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
Connected to: SensorRepository, GnssModeRepository -> MlVelocityRepository -> MainActivity (Compose UI)

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
- Phase 4 (dataset inspection) done (2026-08-25): downloaded IO-VNBD's
  Synchronised V+S dataset (~194 MB, Git LFS — see `data/README.md` for
  the exact re-download command and checksum) into `data/raw/IO-VNBD/`
  (gitignored, verified via `git check-ignore`), extracted it, and read
  360 files on disk plus the dataset's own descriptor PDF in full. See
  `## Phase 4 — Dataset inspection findings` above for the complete
  writeup. Headline results: (1) confirmed real 10 Hz smartphone / 1 Hz
  GPS sampling matching our target; (2) found a real, concrete
  axis-convention mismatch between IO-VNBD's smartphone accelerometer/
  gyroscope columns and Android's raw SensorEvent device frame (verified
  by extracting and viewing the dataset's own axis diagram from the
  PDF, not assumed) — this must be reconciled before Slice 6's
  FeatureExtractor.kt exists; (3) confirmed IO-VNBD has no per-timestamp
  motion-event labels, only trip-level free-text scenario tags — good
  enough for the velocity model and Stationary-class weak labeling
  (including 20+ minutes of dedicated stationary/bias-estimation
  recordings, useful for tuning StationaryDetector's thresholds against
  real data), not enough for Pothole/Phone-Moved without self-captured
  supplementary data, which is now planned before Slice 6 classifier
  training rather than assumed unnecessary. `data/README.md` updated
  with the re-download procedure. Next: Slice 6 (ML inference), now
  unblocked, starting with `ml/inspect_dataset.py` (turning this manual
  inspection into a reproducible script) and `ml/feature_extraction.py`
  (which must apply the axis-convention fix found here).
- `ml/inspect_dataset.py` implemented (2026-08-25), and in writing it,
  corrected the Phase 4 entry above: the 360 files on disk are NOT 72
  unique trips — the download contains the same 72 trips duplicated
  across "Categorised IOVNB Dataset" and "Uncategorised IOVNB Dataset"
  (confirmed by diffing a sample pair: identical data modulo float64
  repr noise and cosmetic header-name differences). A naive recursive
  scan would have silently duplicated every trip across a future train/
  val split — caught before any training happened, not after. Script
  now scans only "Categorised..." by default and documents why.
  Installed the rest of `ml/requirements.txt` (pandas/numpy/sklearn
  were already present in this environment; onnx/onnxruntime/skl2onnx/
  matplotlib/jupyter/pytest were not). Real run against the actual
  downloaded dataset: 72 unique trips, 1,070,745 rows, 25.1 hours, all
  24-column schema (Driver F's documented 20-column variant turns out
  to not be present in this archive at all — it only exists in the
  separate, not-downloaded "Unsynchronised" archive, corrected in the
  Phase 4 findings above), all within Hz tolerance, all correctly
  paired with a vehicle file. Also found (not previously known): ~800
  rows in one trip have their satellite-count field corrupted into
  date-like strings ("Dec-14") by what looks like an Excel auto-date-
  conversion artifact somewhere in the dataset's prep — harmless for us
  since satellite count isn't a feature either model uses. Also
  corrected the Phase 4 findings' driver-count claim from 8 to 4 (only
  Drivers A/B/D/E are present in the archive we have; the dataset PDF's
  Table 1 describes all 8 drivers across BOTH archives). Added
  `tests/ml/test_inspect_dataset.py` (5 cases, synthetic data, no
  dependency on the real download) for the pure helper functions.
  `python -m pytest tests/ml/test_inspect_dataset.py` — 5/5 pass.
  Manifest written to `data/processed/io_vnbd_smartphone_manifest.csv`
  (gitignored, reproducible). Next: `ml/feature_extraction.py`.
- Second correction, same day (2026-08-25): while sanity-checking label
  design ahead of `feature_extraction.py`, spot-checked whether GPS
  speed genuinely updates every 10 Hz row and found it doesn't — the
  `gps_latitude_deg`/`gps_speed_kmh` columns are held constant for ~90
  consecutive rows (~9s) between real fix updates, not 1 Hz as
  README_1.pdf documents. Verified across 4 different trips/drivers
  before trusting it as systemic rather than a one-off. Added
  `_observed_gps_fix_interval_s` to `inspect_dataset.py` (measures from
  actual value-change timestamps) plus 2 new unit tests, reran against
  the full dataset: 68/72 trips confirm ~9.0s, matching the earlier
  spot-check. Corrected the Phase 4 findings and `inspect_dataset.py`'s
  file entry above accordingly. This directly changes how
  `feature_extraction.py` must compute PRD.md Section 13's "elapsed
  time since last GNSS fix" feature (from measured ~9s intervals, not
  assumed ~1s) and how training labels should be constructed (many
  consecutive rows share one ground-truth value, not 90 independent
  readings) — caught before writing that script, not after.
  `python -m pytest tests/ml/test_inspect_dataset.py` — 7/7 pass. Next:
  `ml/feature_extraction.py`, now designed with the correct GPS-timing
  assumption from the start.
- `ml/feature_extraction.py` implemented (2026-08-25) — vehicle-frame
  windowed features via per-timestamp Gram-Schmidt against the recorded
  gravity vector (see that file's entry above for the full design and
  math). Before writing `train_velocity_model.py` against this output,
  sanity-checked it by correlating windowed forward-acceleration against
  real GPS speed changes across all 72 trips (13,902 segments) — found
  it NEGATIVE (-0.136), backwards from physics. Root cause: the
  dataset's own figure (trusted in the Phase 4 findings above) had the
  forward-axis sign wrong relative to the actual data — the figure was
  a claim, checking it against real ground truth is what caught it
  (CLAUDE.md Rule 13). Fixed by flipping the forward axis to -device-Y;
  re-verified correlation flips to +0.137 across the same 13,905
  segments. Updated the Phase 4 findings above and this file's
  `feature_extraction.py` entry with the correction. All 6 unit tests
  updated to match the corrected sign and still pass; note the unit
  tests alone (self-consistency checks) could never have caught this —
  it required checking against real, independent ground truth. This is
  exactly why CLAUDE.md Rule 18 says prototype small and verify before
  building a large abstraction on an unverified assumption: a wrong
  sign here would have silently produced a velocity model trained
  backwards, likely undetected until real-world testing much later.
  Next: `train_velocity_model.py`, now building on a vehicle-frame
  feature set actually validated against real ground truth, not just
  assumed correct from a diagram.
- `train_velocity_model.py` implemented (2026-08-25) — RandomForest
  velocity regressor, trip-level train/val split, evaluated against a
  Python re-implementation of the app's own physics+ZUPT baseline on
  the same held-out trips (CLAUDE.md Rule 3). Deliberately excluded
  previous_gps_speed_mps as a feature despite PRD.md Section 13 listing
  it — it's near-identical to the label itself given this dataset's
  ~90-row GPS hold pattern, which would have let the model cheat by
  copying it rather than learning from the IMU (caught before training,
  not after seeing suspiciously perfect numbers). **Real result: ML
  MAE=1.244 m/s vs Physics+ZUPT MAE=5.205 m/s — ~4.2x more accurate**,
  winning on 13/14 held-out trips. See that file's entry above for the
  full writeup including the one trip where physics won, the dominant
  `accel_up_std_mps2` feature-importance finding (plausibly a real
  vibration-based speed proxy, not necessarily a bug, but a
  generalization risk worth flagging honestly), and the evaluation-scope
  caveat (this measures per-tick accuracy, not simulated multi-minute
  outage drift — that's Slice 9). This is the project's first real,
  measured ML-vs-baseline comparison — CLAUDE.md Rule 3 is satisfied:
  ML is justified for the velocity model, not assumed. Next:
  `export_model.py` (ONNX export + parity check) now that there's a
  result worth exporting.
- `export_model.py` implemented (2026-08-25) — retrains on all 72
  trips, exports to `models/velocity_v1.onnx` (20.7 MB, gitignored),
  runs a real output-parity check (sklearn vs onnxruntime on 5,000 held-
  out rows, not just "did it export"): max diff 0.000001 m/s, passed.
  CLAUDE.md Rule 20 satisfied for the velocity model. On-device
  latency/size are still unmeasured (desktop-only so far, CLAUDE.md
  Rule 12) — that needs Slice 6's Kotlin VelocityModel.kt to exist
  first. Slice 6's ml/ half (dataset inspection, feature extraction,
  velocity model training + export) is now complete and real —
  `train_motion_classifier.py` remains PLANNED (blocked on self-
  captured Pothole/Phone-Moved data per the Phase 4 findings), and the
  Kotlin half (FeatureExtractor.kt, AlignmentEstimator.kt,
  VelocityModel.kt, wiring into BaselineDeadReckoningRepository) is
  entirely still PLANNED — Slice 6 is not finished, only its Python
  training pipeline is.
- Test coverage hardening pass (2026-08-25), requested before starting
  the Kotlin half of Slice 6: `train_velocity_model.py` and
  `export_model.py` had shipped with zero unit tests, only validated by
  one real run each — a real gap against CLAUDE.md Rule 19 ("gets a
  unit test before it is relied upon"). Added
  `tests/ml/test_train_velocity_model.py` (14 cases, including a real
  training-reproducibility test per PRD.md Section 27 — two models
  trained on identical data with the same seed produce byte-identical
  predictions) and `tests/ml/test_export_model.py` (2 cases, a real
  sklearn->ONNX->onnxruntime round-trip on a tiny synthetic model,
  including a negative test proving the parity check can actually
  detect a real mismatch, not just that it never fires). One test's
  hand-derived expectation was initially wrong (mental model of which
  row's acceleration produces which row's velocity was off by one) —
  caught by the test failing against the real function, fixed the test
  not the function. Also re-ran `python ml/train_velocity_model.py`
  against the real dataset a second time and got byte-identical MAE/
  RMSE to the first run, and re-ran the full Kotlin `./gradlew test`
  suite to confirm nothing on the Android side had regressed. Combined
  suite: 29 Python ML tests + 46 Kotlin tests, all passing. This is the
  testing checkpoint before starting the Kotlin half of Slice 6
  (FeatureExtractor.kt, AlignmentEstimator.kt, VelocityModel.kt, and
  wiring `models/velocity_v1.onnx` into the actual app).
- Slice 6 Kotlin wiring implemented (2026-08-25): built the on-device
  half of Slice 6. Key design decision made up front: since Android's
  rotation-vector sensor already fuses gravity into azimuth/pitch/roll,
  PRD.md Section 15's only genuinely missing piece is the YAW offset
  between device compass heading and true vehicle direction of travel —
  `alignment/AlignmentEstimator.kt` estimates exactly that (circular-
  mean of device-azimuth-minus-GNSS-course during straight, fast
  driving), backed by `alignment/YawRate.kt`'s angle-unwrap-aware
  turning-rate math. `features/{RollingWindow,FeatureExtractor}.kt`
  mirrors `ml/feature_extraction.py`'s windowed statistics, including
  two genuinely easy-to-miss parity details replicated on purpose:
  pandas' rolling `.std()` is SAMPLE std (ddof=1, not population std),
  and the zero-crossing-rate calculation inherits a pandas NaN-
  comparison quirk that counts the very first sample as a "change."
  `ml/VelocityModel.kt` wraps ONNX Runtime Mobile — its input/output
  tensor names ("input"/"variable") were verified against the actual
  exported file via onnxruntime's Python API before hardcoding, not
  guessed. `ml/MlVelocityRepository.kt` wires it all together as a
  repository PARALLEL to (not replacing) BaselineDeadReckoningRepository,
  so the ML-vs-physics comparison is directly visible on-device, not
  just a desktop claim — this also means the physics position estimate
  and Slice 5's test coverage were completely untouched by this change.
  Copied `models/velocity_v1.onnx` into `android/app/src/main/assets/`
  (gitignored, like the rest of the model artifacts — documented
  re-copy step added to `.gitignore`).
  3 test bugs caught and fixed during development, each by the test
  failing against otherwise-correct code: a sign error in
  `AlignmentEstimatorTest`'s hand-derived wrap-around expectation
  (179 vs -179 degrees) — caught by cross-checking with Python's
  `math.atan2` before trusting it; two `FeatureExtractorTest` cases
  that forgot the rolling window mixes in earlier ticks' values rather
  than reflecting only the latest instantaneous one. `./gradlew test` —
  all 76 tests pass (46 + 29 new, net of the fixes).
  On-device (2026-08-25, same S24 FE): `./gradlew installDebug`, no
  crash, ONNX Runtime loaded the bundled model and produced live
  predictions (0.11-0.74 m/s observed while stationary — plausible
  small noise floor). Found a real Compose bug while verifying: the
  screen's Column had no scroll modifier, so as content had grown
  across Slices 4-6 it silently overflowed past the bottom edge with
  NO way to reach it — confirmed by swiping, which did nothing (proof
  it wasn't scrollable, not that a different gesture was needed). This
  meant Slice 6's entire ML section was in the composition but
  genuinely invisible. Fixed with `.verticalScroll(rememberScrollState())`;
  re-verified by scrolling to and screenshotting the previously-hidden
  section, which showed the live ML velocity prediction and an honest
  "alignment not yet established" status (correct, given stationary +
  no GNSS fix indoors). A real lesson recorded here deliberately: a
  successful build and a non-crashing app are not the same as "the
  content is actually visible" — only looking at the real device caught
  this. Also swept the rest of `docs/PROJECT_MAP.md` for now-stale
  "still PLANNED" references to files this change just implemented
  (found and fixed 5 of them) — a build.gradle.kts scaffold note, a
  WorldFrameAcceleration.kt note, a Phase-4-findings note, and a
  train_velocity_model.py note, per CLAUDE.md's "never leave it
  describing something that no longer exists."
  Slice 1-6 (Kotlin half included) now fully verified end-to-end on
  real hardware. Explicitly not done: ML velocity is comparison-only,
  not fed into the position integrator; the motion classifier; a true
  cross-language feature-parity test (a known, documented gap — see
  FeatureExtractor.kt's and MlVelocityRepository's entries). Next:
  either wire ML velocity into the actual position integrator, or
  Slice 7 (GNSS/DR fusion) — not yet decided.
- ML velocity wired into an actual position integrator (2026-08-25,
  follow-up — user chose to wire ML velocity into position tracking
  over starting Slice 7). Added `ml/MlPositionIntegrator.kt`
  (PRD.md Section 16's `v[t]=VelocityModel(...)` propagation, no
  momentum/acceleration state — position depends only on each tick's
  predicted speed + heading; non-holonomic constraint satisfied by
  construction since no lateral component is ever predicted) and wired
  it into `MlVelocityRepository.kt` alongside a reused
  `dr/StationaryDetector.kt` instance for ZUPT — same GNSS-mode-gated
  reset as the physics path, for a directly comparable readout. Added
  `MlVelocityUiState.positionEastM/positionNorthM` and a new UI section
  in `MainActivity.kt`. `./gradlew test` — all 83 tests pass (76 + 7
  new `MlPositionIntegratorTest` cases). Installed on the same S24 FE:
  no crash, both position readouts visible and updating.
  REAL FINDING from on-device testing, not from the unit tests: the
  physics position stayed flat at rest (matching Slice 5's earlier
  result) but the ML-based position jumped ~1.6m over ~45s in a burst,
  then flattened again — traced to a genuine design asymmetry (ZUPT
  zeroes a bounded, carried momentum for physics, but only gates an
  unbounded per-tick prediction for the ML path, since that path has
  no momentum concept at all) rather than a coding bug. Discussed with
  the user; explicit decision made to document this now and defer a
  fix (deadband, smoothing, or stricter dwell time were all considered
  candidates) rather than reflexively patch it without more thought.
  See `ml/MlPositionIntegrator.kt` and `ml/MlVelocityRepository.kt`'s
  entries above for the full technical writeup. This is exactly the
  kind of gap that only shows up from real sensor noise on real
  hardware, not from synthetic unit tests — all 7 new unit tests pass
  cleanly despite the integrator having this real sensitivity, because
  the tests (correctly) verify the design does what it was built to
  do; they can't by themselves reveal that the design choice itself
  has this consequence under real-world noise.
