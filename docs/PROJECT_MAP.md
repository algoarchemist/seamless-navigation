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
UPDATE (2026-09-01):
a real outdoor GNSS_AIDED lock has now happened (325.9s drive, 3
GNSS_AIDED segments up to 149.9s, 3 genuine DEAD_RECKONING stretches up
to 37.8s) — see DriveDataLogger.kt's entry below for the full result.
It surfaced a real, measured finding: StationaryDetector's ZUPT is 100%
false-negative on real urban-traffic data and no fixed accel/gyro
threshold fixes it (the two classes don't cleanly separate) — flagged,
not silently patched. Before that drive, every fix had come from
indoor/marginal-signal on-device testing, which had itself surfaced and
fixed several real bugs (noisy Doppler speed, REACQUISITION flapping, a
missing map anchor, tile-loading fights with `animateTo`, and others).
Same day: SensorRecorder.kt gained a `CaptureLabel`
(NONE/POTHOLE/PHONE_MOVED) marker mechanism and two debug-screen buttons
so a real self-captured labeled drive can now be recorded — tooling
only so far, no labeled drive has actually been captured yet. UPDATE
(2026-09-02): `train_motion_classifier.py` is now IMPLEMENTED for 6 of
PRD.md Section 14's 8 classes, using real (not heuristic) ground truth
from IO-VNBD's vehicle CAN-bus CSV instead of waiting on a self-captured
drive — see that file's own entry and `motion_labels.py`'s for the full
finding. Measured result: an unweighted RandomForestClassifier reaches
47.2% val accuracy, beating both a trivial majority-class guess (40.4%)
and the deterministic on-device stand-ins re-implemented in Python
(29.6%) — real but modest, and NOT wired into the app (two of six
classes still have near-zero recall). Pothole and Phone-Moved remain
blocked on a self-captured drive exactly as before — no signal for
either exists anywhere in IO-VNBD.
UPDATE (2026-09-04): the basemap was migrated from osmdroid/OpenStreetMap
tiles to the Mapbox Maps SDK (PRD.md Section 7 amendment — see that
section and `ui/map/StreetMapView.kt`'s own entry below for the full
reasoning and what did/didn't carry over). Compiles and assembles clean,
existing unit tests pass; NOT yet verified on a real device. The prior
osmdroid+OSRM build is preserved intact and independently buildable in a
sibling folder, `C:\projects\26168-osmdroid`, requiring no Mapbox
account/credentials — a working fallback if ever needed.

**The full chronological history — every dated bug fix, scope decision,
and on-device verification — now lives in `summary.txt` at the repo
root, not here.** This file stays focused on current-state structure:
what each file's responsibility, inputs/outputs, and non-obvious
assumptions are *right now* (CLAUDE.md Rule 21). Read `summary.txt`
for the story of how it got there.

**Round 2 Day 2 update (2026-08-28):** a real live outage test (GPS
toggled off mid-drive — Round 2's Day 1 validation run) surfaced a
position-snap + map-orientation-flip bug on GNSS reacquisition. Four
fixes landed the same day, all cross-referenced from PRD.md's own
dated amendments: (1) `alignment/AlignmentRepository.kt` (NEW) hoists
the yaw-alignment estimate out of `MlVelocityRepository` into its own
shared repository so `dr/BaselineDeadReckoningRepository` can read the
SAME estimate — Round 1 only applied alignment to the ML path, leaving
the physics path's heading unaligned; (2) `fusion/HeadingFusion.kt`
(NEW) blends heading/map-orientation over REACQUISITION the same way
`fusion/PositionFusion.kt` already blended position — Round 1 only
blended position, leaving heading a hard cutover; (3) `ml/VelocityGuard.kt`
(NEW) adds an OOD sanity clamp + EMA damping between the ML velocity
model's bias-corrected output and the position integrator; (4)
`gnss/GnssQuality.confidenceWeight()` (NEW function) + a
`confidenceWeight` parameter on `fusion/VelocityBiasCalibrator.update()`
make the GNSS bias-calibration trust a continuous function of fix
accuracy instead of the pre-existing binary `isGood` gate. See each
file's own entry below for detail, and PRD.md Section 15/17/18's
2026-08-28 amendments for the requirements these satisfy.

Later the same day: two more Round 2 Day 2-3 items landed, both
additive/instrumentation-only (neither touches the GNSS/DR/fusion
pipeline): (5) `motion/FloorChangeDetector.kt` + `FloorChangeRepository.kt`
(NEW) — barometer-based floor/level-change detection (PRD.md FR12),
shown on the debug screen; (6) `sensors/SensorRepository.kt` gained
optional TYPE_LINEAR_ACCELERATION/TYPE_GRAVITY listeners (PRD.md
Section 11), also shown on the debug screen as a cross-check against
`dr/WorldFrameAcceleration`'s manual gravity subtraction — not adopted
into the DR pipeline yet, per Section 11's "only if it measures out
better, decided empirically." (7) A UI smoothness pass —
`ui/map/PositionSmoother.kt` (NEW) — fixed the marker/map-rotation
stepping visibly between GNSS/DR ticks instead of gliding, on both the
Drive tab (`TrackCanvas.kt`) and Map tab (`StreetMapView.kt`); purely
cosmetic, no fusion math touched. (8) `fusion/RoadSnap.kt` (NEW) —
PRD.md Section 19's map-constraint layer, finally implemented: snaps
the Map tab's displayed marker onto the active route's geometry
(heading-compatible nearest-segment, not a full map matcher) — the last
remaining item from the original SIH requirements list that was still
just PLANNED. Deliberately display-only: `fusion/StateEstimator.kt`'s
canonical `fusedEastM`/`fusedNorthM` (which feeds the measured drift
number and route progress) is untouched by this correction. **REMOVED
2026-09-02**: a second, independently-built implementation of this exact
feature (`map/MapConstraint.kt`) landed two days later and started
correcting `fusedEastM`/`fusedNorthM` UPSTREAM in `StateEstimator.kt`
itself — nobody reconciled the two, so this class's own correction was
silently stacking on top of MapConstraint's every tick. See
`fusion/RoadSnap.kt`'s own (now REMOVED) entry below for the full bug
writeup; MapConstraint.kt is the sole surviving implementation.

**Context-aware ZUPT update:** `motion/StopEventClassifier.kt` (NEW)
replaces the flat "accel/gyro quiet -> ZUPT" gate both DR paths used —
the 2026-09-01 finding that accel/gyro-only detection was 100%
false-negative on real traffic stops is now addressed with a second,
independent signal (a sustained drop from a meaningful reference speed
to near-zero, preferring GNSS speed when trustworthy) rather than
retuning the same unreliable threshold. Rule-based, not ML (no labeled
stop-event data exists — CLAUDE.md Rule 3). Both
`dr/BaselineDeadReckoningRepository.kt` and `ml/MlVelocityRepository.kt`
now go through it; `dr/StationaryDetector.kt` itself is unchanged,
composed rather than replaced. See its own entry below for the full
design and honest limitations.

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
UPDATE (2026-09-04, PRD.md Section 7 amendment): `settings.gradle.kts`
  now also declares Mapbox's private Maven repo
  (`api.mapbox.com/downloads/v2/releases/maven`) inside
  `dependencyResolutionManagement`, authenticated with a hand-read
  `MAPBOX_DOWNLOADS_TOKEN` from `local.properties` (fixed username
  `"mapbox"`, per Mapbox's own convention — not a personal account name).
  Read manually via `java.util.Properties` rather than
  `providers.gradleProperty`, matching the existing `sdk.dir` pattern in
  this same file rather than introducing gradle.properties (which,
  unlike local.properties, IS git-tracked here) as a second config
  surface. `local.properties` itself now carries two real Mapbox tokens:
  `MAPBOX_DOWNLOADS_TOKEN` (secret, `DOWNLOADS:READ` scope only — used
  solely by Gradle to fetch the SDK, never reaches the compiled app) and
  `MAPBOX_PUBLIC_TOKEN` (public `pk.` token, safe client-side, consumed
  by `app/build.gradle.kts` — see below). Anyone cloning this repo must
  populate both from their own Mapbox account before the app resolves.

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
UPDATE (2026-09-04, PRD.md Section 7 amendment): adds
  `com.mapbox.maps:android:11.29.1` alongside the existing `osmdroid`
  dependency (comment on that line updated to point here rather than
  claiming Mapbox was rejected — see PRD.md's dated amendment for the
  full reasoning). `buildFeatures.buildConfig = true` newly enabled so
  `BuildConfig.MAPBOX_PUBLIC_TOKEN` (read from `local.properties`, empty
  string if absent) is available to app code.
UPDATE (same day, later): the actual UI migration landed too —
  `ui/map/StreetMapView.kt` (below) now renders via this Mapbox
  dependency, not `osmdroid`. `osmdroid-android:6.1.20` is still declared
  (nothing in the live app path calls it anymore, but it is not yet
  removed — no functional reason to touch it this session, and the
  preserved `C:\projects\26168-osmdroid` sibling folder is the actual
  fallback if osmdroid is ever needed again, not this now-dead
  dependency). Verified via `:app:assembleDebug` (full APK, Mapbox SDK
  resolved/linked) and `:app:testDebugUnitTest` (all pass) — NOT yet
  installed/run on a real device (CLAUDE.md Rule 13 — a clean build is
  not on-device verification).
UPDATE (2026-09-05, PRD.md Section 7 amendment, developer override —
  see that amendment for the full record): adds the Mapbox Navigation
  SDK, six modules (`com.mapbox.navigationcore:android/ui-maps/voice/
  tripdata/ui-components/navigation:3.30.0`) plus
  `androidx.constraintlayout:constraintlayout:2.1.4` (a real, required
  transitive — `MapboxManeuverView` extends `ConstraintLayout` and
  nothing else on the classpath provided it; the build failed with
  "Supertypes... cannot be resolved" until this was added). Verified via
  `:app:assembleDebug`/`:app:testDebugUnitTest` AND installed/run on a
  real device this time — see `nav/NavigationSessionRepository.kt`'s
  entry for the full on-device verification writeup.

android/app/src/main/AndroidManifest.xml
Status: IMPLEMENTED
Purpose: Declares ACCESS_FINE/COARSE_LOCATION, HIGH_SAMPLING_RATE_SENSORS,
  and required accelerometer/gyroscope/GPS hardware features; registers
  MainActivity as launcher.
UPDATE (2026-09-05, Mapbox Navigation SDK): FOREGROUND_SERVICE,
  FOREGROUND_SERVICE_LOCATION, POST_NOTIFICATIONS, ACCESS_WIFI_STATE,
  WAKE_LOCK, RECEIVE_BOOT_COMPLETED, and a foreground-service declaration
  are auto-merged in from the Navigation SDK's own manifest (active
  guidance runs as a foreground service with a persistent notification)
  — confirmed via the actual merged manifest output, not assumed from
  the SDK's docs claim of bundling them. POST_NOTIFICATIONS still needs
  a RUNTIME request on API 33+ (a manifest declaration alone doesn't
  grant it there) — added to `MainActivity.kt`'s permission-request flow
  alongside the existing location-permission request.

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
UPDATE (Round 2, 2026-08-28 — PRD.md FR12): added PressureSample(timestampNs,
  pressureHpa) — units hPa, per Android's TYPE_PRESSURE convention. Not
  all devices have a barometer (see SensorRepository.hasBarometer()
  below); consumers must treat its absence as a normal, honest case.
  AccelSample is also now reused verbatim (same x/y/z/timestampNs shape)
  for Android's own TYPE_LINEAR_ACCELERATION/TYPE_GRAVITY readings — see
  SensorRepository.kt's entry below.

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
  latestOrientation, accelHz, gyroHz, orientationHz, latestPressure,
  pressureHz, latestLinearAcceleration, latestGravity (last four, Round 2,
  2026-08-28)}.
Connected to: MainActivity -> SensorRepository -> (StateFlow) -> Compose UI
  ; SensorRepository -> OrientationMath (pure function, orientation
  conversion only, no reverse dependency);
  SensorRepository -> motion/FloorChangeRepository (Round 2, 2026-08-28,
  reads latestPressure)
Important functions/classes: start()/stop() (lifecycle-tied — called
  from onResume/onPause, not onCreate/onDestroy, so sensors aren't
  active while backgrounded); hasRequiredSensors() (now also requires
  TYPE_ROTATION_VECTOR); hasBarometer() (Round 2, 2026-08-28 — separate
  from hasRequiredSensors() since the barometer is OPTIONAL, unlike
  accel/gyro/rotation-vector — see PressureSample's entry above).
UPDATE (Round 2, 2026-08-28 — PRD.md FR12 + Section 11): registers three
  new OPTIONAL listeners (all `?.let { registerListener(...) }`, same
  nullable-safe pattern as the required three): TYPE_PRESSURE (barometer,
  publishes latestPressure/pressureHz, same Hz-tracking pattern as
  accel/gyro/orientation), TYPE_LINEAR_ACCELERATION and TYPE_GRAVITY
  (Android's own fused, already-gravity-removed accelerometer and
  smoothed gravity vector — publish latestLinearAcceleration/
  latestGravity with NO Hz tracking, deliberately: these are a one-off
  instrumentation cross-check against dr/WorldFrameAcceleration.kt's
  manual gravity subtraction, shown side-by-side on MainActivity's debug
  screen, NOT wired into the DR pipeline — PRD.md Section 11 explicitly
  says "adopted only if it measures out better... decided empirically,"
  which hasn't happened yet, so nothing was swapped, only instrumented).
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
  existed. UPDATE (Slice 6): AlignmentEstimator.kt is now IMPLEMENTED —
  this file itself remains deliberately alignment-free (it only produces
  WORLD-frame acceleration; the alignment-corrected heading is applied
  downstream, by whichever caller projects onto a heading — see
  dr/NonHolonomicConstraint.kt and ml/MlVelocityRepository.kt's own
  forward/lateral projection). UPDATE (2026-08-30): that downstream
  correction now reaches BOTH callers, not just the ML feature path — see
  alignment/AlignmentRepository.kt.
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
Connected to: BaselineDeadReckoningRepository -> StopEventClassifier -> StationaryDetector (wrapped, unchanged);
  MlVelocityRepository -> StopEventClassifier -> StationaryDetector (wrapped, unchanged)
UPDATE: no longer called directly by either DR repository — both now go
  through motion/StopEventClassifier.kt (see its own entry below), which
  composes this class UNCHANGED as one of its two signals. This class's
  own behavior, signature, and tests are untouched.

android/app/src/main/kotlin/com/sih26168/idr/motion/StopEventClassifier.kt
Status: IMPLEMENTED
Purpose: Context-aware replacement for gating ZUPT on "accel/gyro quiet
  for N ms" alone (PRD.md Section 14's Stationary effect) — distinguishes
  MOVING / SUDDEN_STOP / BRIEF_STOP / LONG_IDLE instead of a flat
  stationary/not-stationary boolean. REAL FINDING driving this class's
  existence (2026-09-01 real outdoor drive, see StationaryDetector.kt's
  own doc): cross-checked against GNSS speed as ground truth,
  accel/gyro-only stationary detection was 100% false-negative on real
  urban-traffic stops — engine-idle/road vibration keeps accel/gyro
  elevated even while genuinely stopped in traffic, so no fixed threshold
  on that signal alone separates the classes. Adds a SECOND, independent
  signal: a sustained drop from a meaningful reference speed to
  near-zero, corroborated by GNSS speed when it's actually trustworthy —
  deliberately NOT gated on the same strict accel/gyro threshold, since
  that's exactly the signal measured unreliable for this case.
Inputs: nowMs, linearAccelMagnitudeMps2, gyroMagnitudeRadPerSec (same as
  StationaryDetector, passed straight through to the wrapped instance),
  currentSpeedEstimateMps (caller's own pre-ZUPT speed — the physics
  integrator's pre-override speed, or the ML model's damped prediction),
  gnssSpeedMps (nullable — only passed by callers once they've verified
  GNSS_AIDED + GnssQuality.isGood; preferred over currentSpeedEstimateMps
  when present).
Outputs: StopClassification(context, shouldApplyZupt, stationaryDurationMs,
  recentPeakSpeedMps, currentSpeedEstimateMps, dwellConfirmedStationary,
  reason) — shouldApplyZupt is the actual gate (true for every context
  except MOVING); dwellConfirmedStationary exposes StationaryDetector's
  own plain accel/gyro-dwell result so a caller that still needs that
  exact signal (ml/MlVelocityRepository.kt's MotionStateClassifier
  contract) doesn't need a second, redundant StationaryDetector instance.
Important functions/classes: evaluate() — SUDDEN_STOP requires BOTH a
  recent (within suddenStopLookbackMs=2000ms) reference speed
  >= suddenStopPriorSpeedMps (1.5 m/s) AND the reference speed sustaining
  <= nearZeroSpeedMps (0.3 m/s, matching scripts/analyze_drive_log.py's
  own report_zupt_validation ground-truth bound) for
  nearZeroConfirmMs (150ms — deliberately shorter than
  StationaryDetector's 300ms dwell, since the prior-speed evidence
  already raises confidence). BRIEF_STOP/LONG_IDLE fall back to
  StationaryDetector's own accel/gyro dwell (split at
  longIdleDurationMs=8000ms) — the sole signal when there's no meaningful
  prior-speed evidence, e.g. right after launch. All five thresholds are
  engineering defaults, not yet validated against a real labeled
  stop-event drive (CLAUDE.md Rule 13) — no such drive has been captured
  (see capture/SensorRecorder.kt's CaptureLabel tooling, still unused for
  a real drive). reset() clears this class's own history/state but
  deliberately does NOT reset the wrapped StationaryDetector, which has
  no reset() of its own — matches existing app behavior (neither
  repository reset it before this class existed either).
Important concepts/assumptions: Rule-based by design, not ML — no
  labeled stop-event data exists in this project
  (train_motion_classifier.py stays PLANNED, blocked on self-captured
  labels), and CLAUDE.md Rule 3 requires a measured reason to reach for
  ML over a simple deterministic solution. HONEST LIMITATION: when no
  GNSS speed is available (mid-outage, exactly when ZUPT matters most),
  the reference speed is this app's own DR/ML estimate, not independent
  ground truth — a sustained speed-estimate glitch during real continued
  motion could in principle misfire into SUDDEN_STOP; preferring GNSS
  speed whenever trustworthy minimizes but does not eliminate this.
Connected to: dr/BaselineDeadReckoningRepository.kt, ml/MlVelocityRepository.kt
  -> StopEventClassifier -> StationaryDetector (wrapped)
Unit tests: tests/.../motion/StopEventClassifierTest.kt (11 cases) — long
  idle after sustained no-prior-motion quiet; a brief stop stays
  BRIEF_STOP under the idle bound; sudden stop after high-speed movement
  fires before the accel/gyro dwell alone would; stop after low-speed
  movement does NOT qualify for the fast path (falls back to the dwell
  path); a single noisy near-zero glitch during real continued motion
  does not false-ZUPT; resuming motion from LONG_IDLE returns to MOVING
  and resets duration; GNSS speed is preferred over the own estimate when
  supplied; falls back to the own estimate when GNSS speed isn't supplied
  (mid-outage); SUDDEN_STOP decays to BRIEF_STOP once the lookback window
  ages out; reset() forgets prior speed history.

android/app/src/main/kotlin/com/sih26168/idr/dr/NonHolonomicConstraint.kt
Status: IMPLEMENTED
Purpose: PRD.md Section 20's non-holonomic constraint (a road vehicle
  can't move sideways relative to its heading) — pure vector projection
  suppressing the velocity component perpendicular to heading.
Inputs: velocityEastMps, velocityNorthMps, headingRad (device azimuth).
Outputs: Pair<Double, Double> (forward-only East/North velocity).
Important concepts/assumptions: PRD.md Section 20 specifies this in
  VEHICLE frame with a Turning exemption from the ML motion classifier.
  The VEHICLE-frame half still doesn't exist (phone-to-vehicle yaw
  alignment, alignment/AlignmentEstimator.kt, is wired into the ML
  feature path only, not this physics path — Capability #1's own tracked
  gap), so this remains a deliberately simplified WORLD-frame stand-in:
  it uses the device's own WORLD-frame heading as a proxy for vehicle
  heading, under the explicit assumption the phone's yaw tracks the
  vehicle's yaw (true if rigidly mounted; false if loose, e.g. a cup
  holder).
  UPDATE (2026-08-30): the Turning exemption now exists — see
  motion/TurningDetector.kt below — applied by this function's one
  caller (BaselineDeadReckoningRepository), which now skips calling
  suppressLateralVelocity() on any tick TurningDetector flags as turning,
  the same way it already skips it for walkingModeEnabled. This
  function's own signature/tests are unchanged; the exemption is pure
  caller-side gating.
  UPDATE (2026-08-30): the heading passed to suppressLateralVelocity() is
  now alignment-corrected (device azimuth minus AlignmentRepository's
  shared yaw offset), not raw device azimuth unconditionally — see
  alignment/AlignmentRepository.kt and this file's own doc for the full
  reasoning. This function's signature is still unchanged; only what its
  one caller passes in changed.
Connected to: BaselineDeadReckoningRepository -> NonHolonomicConstraint -> BaselinePhysicsIntegrator.overrideVelocity;
  BaselineDeadReckoningRepository -> TurningDetector -> (gates whether NonHolonomicConstraint is called at all);
  AlignmentRepository -> BaselineDeadReckoningRepository -> (corrects the heading NonHolonomicConstraint projects onto)

android/app/src/main/kotlin/com/sih26168/idr/motion/TurningDetector.kt
Status: IMPLEMENTED (2026-08-30)
Purpose: A DETERMINISTIC stand-in for PRD.md Section 14's `Turning` class
  — same "no labeled classifier data yet" precedent as
  MotionStateClassifier.kt/PotholeShockDetector.kt — built specifically to
  close NonHolonomicConstraint.kt's PRD.md Section 20 gap ("except during
  classifier-flagged Turning windows, where the constraint is relaxed").
  Reuses alignment/YawRate.kt — the SAME WORLD-frame-azimuth-derived
  yaw-rate signal AlignmentEstimator already computes to detect "moving
  straight" — inverted to flag Turning ABOVE a threshold instead of
  straight-line motion below one, so "is this vehicle turning" is
  answered consistently by one signal everywhere in the pipeline instead
  of a second, differently-tuned yaw-rate computation.
Inputs: nowNs, azimuthRad (device WORLD-frame azimuth, per orientation tick).
Outputs: Boolean (isTurning) — false on the first call (no previous
  sample to diff against) and on any sample below the threshold.
Important functions/classes: evaluate() — stateful (tracks previous
  azimuth/timestamp); reset() clears that history for a new DR session.
  minYawRateForTurningRadPerSec defaults to 0.15 rad/s (~8.6 deg/s),
  deliberately above AlignmentEstimator's own 0.1 rad/s "straight"
  ceiling so there's a small dead zone between the two rather than them
  touching exactly — engineering default, unvalidated against a real
  outdoor test drive (CLAUDE.md Rule 13).
Connected to: BaselineDeadReckoningRepository -> TurningDetector -> gates NonHolonomicConstraint's call site

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
  motion/PotholeShockDetector -> BaselineDeadReckoningRepository (2026-08-25);
  alignment/AlignmentRepository -> BaselineDeadReckoningRepository (Round 2, 2026-08-28, see below)
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
  UPDATE (Round 2, 2026-08-28): now optionally takes an
  `alignmentRepository: AlignmentRepository?` constructor param
  (nullable, defaults to null). When present, the non-holonomic
  constraint's heading input is `orientation.azimuthRad -
  alignmentRepository.state.value.yawOffsetRad` (falling back to raw
  azimuth if the offset isn't available yet) instead of raw device
  azimuth — the SAME correction ml/MlVelocityRepository already applied
  in Round 1. REAL FINDING (2026-08-28 live outage test): leaving this
  path unaligned let the physics-derived heading (used by
  fusion/StateEstimator.kt for the map's heading-up rotation) drift far
  enough from true vehicle heading to flip the map ~180 degrees on GNSS
  reacquisition — see alignment/AlignmentRepository.kt's entry above and
  PRD.md Section 15's 2026-08-28 amendment.
  UPDATE (2026-09-01, following the real outdoor drive's ZUPT finding —
  see DriveDataLogger.kt's entry): also computes
  rawLinearAccelMagnitudeMps2/rawGyroMagnitudeRadPerSec from the SAME
  east/north/up + gyro components the filtered magnitude uses, just
  BEFORE LowPassFilter.filter() runs on them, and publishes both onto
  DeadReckoningState. StationaryDetector still gates on the FILTERED
  fields only — the raw fields exist purely so a future drive log can
  compare raw vs. filtered separability offline (see
  scripts/analyze_drive_log.py's report_raw_vs_filtered).
  UPDATE (context-aware ZUPT): the `stationary` decision now comes from
  `motion/StopEventClassifier.kt` (see its own entry) instead of calling
  `StationaryDetector` directly — this class supplies the classifier's
  two extra inputs (this tick's pre-ZUPT integrated speed, captured right
  after `integrator.update()`; GNSS speed when `GnssMode.GNSS_AIDED` and
  `GnssQuality.isGood`) and acts on `StopClassification.shouldApplyZupt`
  exactly where the old boolean was read. `DeadReckoningState.isStationary`
  keeps its exact prior meaning (was ZUPT applied this tick); the new
  `stationaryContext` field carries the richer classification for
  logging/debug/UI only. Logs (`Log.d`) on every context change, not
  every tick (CLAUDE.md Rule 17, same "log transitions, not the stream"
  convention `gnss/GnssModeRepository.kt` already uses).
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
  REAL BUG FIX (2026-09-01, on-device test — phone stationary indoors,
  History tab logging 0.3-30m of "drift" every reacquisition cycle):
  DEFAULT_MAX_ACCURACY_M (25m) answers "is GNSS available at all," but
  Android's self-reported Location accuracy doesn't detect indoor
  multipath — successive fixes can each individually claim <=25m while
  actually landing 5-30m apart. Added a second, stricter constant,
  DEFAULT_MAX_ACCURACY_FOR_GROUND_TRUTH_M (10m), used ONLY by
  fusion/StateEstimator.kt to decide whether a fix is trustworthy
  enough to (a) move the outage anchor or (b) be recorded as a measured
  drift result — isGood()'s own 25m bar for state-machine timing is
  unchanged, so reacquisition attempt cadence doesn't change.
Connected to: GnssModeRepository -> GnssQuality -> GnssOutageDetector;
  fusion/StateEstimator.kt -> GnssQuality.DEFAULT_MAX_ACCURACY_FOR_GROUND_TRUTH_M
  (anchor-setting and drift-recording ground-truth gate)

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
  reacquisitionDwellMs / reacquisitionExitDwellMs (constructor params,
  defaults 2000/2000/1000/1000/2000ms — engineering defaults, not yet
  empirically validated).
Important concepts/assumptions: hysteresis (CLAUDE.md Rule 16) — a
  single bad/good sample cannot flip the mode; leaving GNSS_AIDED
  requires GNSS bad continuously for outageEnterDwellMs, leaving
  DEAD_RECKONING requires GNSS good continuously for
  reacquisitionEnterDwellMs, REACQUISITION advances to GNSS_AIDED only
  with GNSS good continuously for reacquisitionDwellMs, and bails back
  to DEAD_RECKONING only with GNSS bad continuously for
  reacquisitionExitDwellMs — all four transitions use the same
  streak-tracked dwell pattern. TRANSITION/REACQUISITION are
  state-machine bookkeeping ONLY in this slice — they do NOT yet blend
  GNSS and DR position estimates together (PRD.md Section 18's
  "freeze/average"/"blend" behavior is Slice 7, Fusion / re-alignment
  on GNSS reacquisition).
  REAL BUG FIX (2026-09-02, on-device test via ADB screenshots + logcat
  during a live session — phone stationary indoors, mode visibly
  flapping DEAD_RECKONING<->REACQUISITION every ~7s across 6+ outage
  cycles in under a minute): REACQUISITION's exit used to bail to
  DEAD_RECKONING on the FIRST bad sample after entry, no dwell at all —
  itself the single-noisy-sample flip Rule 16 prohibits (a leftover from
  an earlier 2026-08-26 fix for the opposite symptom, which over-
  corrected). Marginal indoor GNSS accuracy flickers faster than
  reacquisitionDwellMs, so REACQUISITION never once reached GNSS_AIDED
  in practice, which also corrupted fusion/StateEstimator.kt's
  lastAidedAtMs bookkeeping (never updated) and made its AI-predicted
  drift number climb unboundedly across "outages" that were never
  really distinct (7.8m -> 49m predicted over six cycles while measured
  drift stayed ~1-1.5m each time). Fixed by giving the exit its own
  streak-tracked dwell (reacquisitionExitDwellMs), symmetric with every
  other transition in this class — see GnssOutageDetectorTest.kt's
  2026-09-02 tests for the locked-in behavior.
Connected to: GnssModeRepository -> GnssOutageDetector -> GnssModeUiState;
  GnssOutageDetector.mode -> fusion/StateEstimator.kt's lastAidedAtMs
  bookkeeping -> ml/ReacquisitionDriftModel's predicted-drift input

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
  MainActivity -> ui/theme/IdrTheme -> ui/screens/MapScreen (DEFAULT/home tab, since the 2026-08-30 DriveScreen removal) or
  ui/screens/HistoryScreen (via BottomNavBar) / IdrSensorScreen (debug toggle)
UPDATE (Round 2, 2026-08-28): now also instantiates a single
  `alignment/AlignmentRepository`, constructed right after
  `gnssModeRepository` and BEFORE `deadReckoningRepository` (so the
  latter's optional constructor param can reference it) — independent
  of the ONNX try/catch block, so it works even if the velocity model
  fails to load. `deadReckoningRepository` and (inside the try block)
  `mlVelocityRepository` both now take it as a constructor param.
  `startPipeline()`/`stopPipeline()` start/stop it alongside the other
  repositories (started before, stopped after, the two DR repositories —
  both read its `.state.value` synchronously each tick, same ordering
  rationale as `gnssModeRepository`). Both DriveScreen's and MapScreen's
  `onRecalibrate` callbacks now call `alignmentRepository.reset()`
  directly instead of `mlVelocityRepository?.resetAlignment()` (removed
  — see `alignment/AlignmentRepository.kt`'s entry).
UPDATE (Round 2, 2026-08-28 — PRD.md FR12 + Section 11): also
  instantiates `motion/FloorChangeRepository` (independent of everything
  else — only needs `sensorRepository`) and starts/stops it alongside the
  other repositories. `IdrSensorScreen` (the debug screen) now takes a
  `floorState: FloorChangeUiState` param and renders: (1) live relative
  barometric altitude + total floors changed + a "FLOOR CHANGE detected"
  flash, or an honest "this device has no barometer" message; (2) a
  gravity-removal CROSS-CHECK line comparing
  `dr/WorldFrameAcceleration`'s manual gravity subtraction (recomputed
  inline from `state.latestAccel`/`latestOrientation`, purely for
  display — does NOT touch the DR pipeline) against Android's own
  `TYPE_LINEAR_ACCELERATION` reading (`state.latestLinearAcceleration`)
  side by side. Neither the Drive/Map polished screens nor any DR/fusion
  logic reads either of these yet — this is instrumentation on the debug
  screen only, so a future real drive can decide (per PRD.md Section 11's
  "adopted only if it measures out better") whether either is worth
  wiring further.
UPDATE (2026-08-30): the DRIVE tab and `ui/screens/DriveScreen.kt` /
  `ui/map/TrackCanvas.kt` (its abstract local-East/North-meter grid) were
  removed — MapScreen's real street map + routing made them redundant,
  since MapScreen already shows the same `StatusOverlayContent` over real
  street tiles instead of a fake grid. `AppTab` now has only MAP and
  HISTORY. See `ui/screens/MapScreen.kt`'s and `ui/map/StreetMapView.kt`'s
  entries below for what replaced it (real routing/search, road-snap
  constraint, animated marker).
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
  UPDATE (2026-08-30, user-reported "the drive page ... is doing
  nothing"): `ui/screens/DriveScreen.kt` and its `ui/map/TrackCanvas.kt`
  base layer were DELETED — see those files' entries above for the
  reasoning (MapScreen already provided everything DriveScreen did, over
  a real map instead of an abstract grid). `AppTab.DRIVE` was removed
  from `ui/components/BottomNavBar.kt`; `selectedTab` now defaults to
  `AppTab.MAP`, and the "back button returns to the home tab" BackHandler
  (originally keyed on `AppTab.DRIVE`) now targets `AppTab.MAP`. The
  `when (selectedTab)` block in `setContent` lost its `AppTab.DRIVE ->
  DriveScreen(...)` branch entirely; `onRecalibrate`'s nullable-safe-call
  pattern is now only wired through to `MapScreen`.
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
Inputs: record() takes one sensor tick (timestampNs + accel/gyro/orientation
  floats + a label String, added 2026-09-01, default CaptureLabel.NONE).
Outputs: toJsonArray() — hand-written JSON (no new dependency, CLAUDE.md
  Rule 2), a flat array of flat per-tick objects, each now including its
  `label`.
Important functions/classes: record() (elapsedMs computed relative to
  the FIRST recorded tick's timestampNs, same boot-time-monotonic clock
  family as every other sensor timestamp in this codebase — CLAUDE.md
  Rule 9/14, NOT wall-clock); reset(); recordedCount.
  UPDATE (2026-09-01, following the real outdoor drive's ZUPT finding —
  see DriveDataLogger.kt's entry): added `CaptureLabel` (NONE/POTHOLE/
  PHONE_MOVED — the only two PRD.md Section 14 classes IO-VNBD has no
  ground truth for, per the Phase 4 finding below) and a `label` field on
  SensorRecordEntry, set from OUTSIDE this class by whichever label
  MainActivity's marker buttons say is active at record() time — this
  class stays a dumb recorder, it doesn't derive labels from the sensor
  values itself. This is the tooling for PRD.md Section 24's "self-
  captured labelled data" step, not the captured data itself — no real
  labeled drive has been recorded with it yet.
Connected to: MainActivity (Start/Stop Recording button + Mark
  Pothole/Phone Moved buttons, 2026-09-01, own
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
REAL OUTDOOR DRIVE (2026-09-01): first genuine outdoor test drive,
  325.9s / 3246 rows at a real ~10 Hz. GNSS_AIDED achieved a real
  multi-minute lock (3 segments, up to 149.9s), with 3 genuine
  DEAD_RECKONING stretches (up to 37.8s) and clean TRANSITION/
  REACQUISITION segments (878-1003ms, close to the 1000ms dwell
  constants) — the "no true outdoor GNSS_AIDED lock yet" gap noted
  elsewhere in this file is now closed. GNSS fix accuracy was good
  (p50=3.0m, p90=5.9m) with one outlier (max=114.9m) GnssQuality's
  25m ceiling correctly should reject.
  REAL FINDING — ZUPT is not usable as-is: cross-checked against GNSS
  speed (independent ground truth) while GNSS_AIDED, StationaryDetector's
  isStationary flag was 100% false-negative (280/280 truly-stopped rows
  not flagged) and 0% false-positive. The filtered accel/gyro magnitude
  distributions for truly-stationary vs. truly-moving rows overlap
  heavily (stationary accel p50=1.517 m/s^2 vs. moving accel p50=1.656
  m/s^2) — see scripts/analyze_drive_log.py's new
  report_zupt_threshold_sweep, which grid-searched accel/gyro thresholds
  against this same log and found NO combination keeps both false
  negatives and false positives low (best combined-error point:
  accel<=2.25 m/s^2, gyro<=0.10 rad/s -> still ~23.6% FN / ~23.1% FP).
  This matches StationaryDetector.kt's own documented "constant-velocity
  motion looks stationary too" limitation, now measured on real urban-
  traffic data rather than assumed. NOT fixed by retuning the fixed
  threshold — CLAUDE.md Rule 18 prototype-first approach was applied
  (grid search before any code change) and the honest conclusion is this
  needs either GNSS-speed gating (only available while GNSS_AIDED,
  i.e. not during the outages ZUPT exists for) or the real ML motion
  classifier's Stationary class (train_motion_classifier.py, still
  PLANNED) — not a better fixed accel/gyro threshold. Left unchanged in
  Kotlin pending that; flagged here rather than silently shipped as
  "validated." Full narrative in summary.txt's 2026-09-01 entry.
  Follow-up instrumentation added the same day: DeadReckoningState/
  DriveLogEntry now also carry rawLinearAccelMagnitudeMps2/
  rawGyroMagnitudeRadPerSec (PRE-filter, computed in
  BaselineDeadReckoningRepository right before LowPassFilter.filter()
  runs) so a FUTURE drive log can compare raw vs. filtered separability
  and let LowPassFilter's cutoffHz itself be tuned — today's log
  predates these fields, so that comparison isn't possible yet (see
  scripts/analyze_drive_log.py's report_raw_vs_filtered, which degrades
  gracefully on older logs missing these columns).

scripts/analyze_drive_log.py
Status: IMPLEMENTED
Purpose: Single responsibility (CLAUDE.md Rule 5): turn a DriveDataLogger
  CSV into a real, printed answer for whether the app's "engineering
  default, not yet validated" thresholds (GnssQuality's max-accuracy/
  max-fix-age, GnssOutageDetector's four dwell constants,
  StationaryDetector's ZUPT accel/gyro/dwell thresholds) hold up against
  one real drive — the offline half of the DriveDataLogger.kt prototype
  described in that file's own doc (CLAUDE.md Rule 18). Deliberately only
  REPORTS — never edits the Kotlin constants itself, since trading off
  false-positive vs. false-negative ZUPT (or a flappier vs. slower GNSS
  state machine) is a human judgment call, not something to auto-apply.
Inputs: one positional csv_path (a drive_log_<ts>.csv pulled via
  `adb pull` from the app's Start/Stop debug-screen logger).
Outputs: stdout report only — overview (tick count/duration/observed
  Hz), real GNSS-mode segment durations, GNSS fix-quality percentiles,
  a ZUPT false-positive/false-negative confusion check against GNSS
  speed as independent ground truth, a ZUPT threshold grid-sweep, and a
  raw-vs-filtered accel/gyro comparison. Exit 1 if the CSV doesn't exist
  or is missing a required column.
Important functions: load_log (schema check), report_mode_segments
  (groups consecutive same-mode rows into real dwell segments),
  report_zupt_validation (GNSS speed < 0.3 m/s while GNSS_AIDED as
  ground truth for "was it really stationary" — independent of the
  accel/gyro signal being validated, so not circular),
  sweep_zupt_thresholds (added 2026-09-01, after the first real drive
  found ZUPT 100% false-negative — grid-searches accel/gyro threshold
  combinations against the SAME ground truth to check whether a
  different FIXED threshold would fix it, or whether the two classes
  just aren't separable this way on real data; see the REAL OUTDOOR
  DRIVE finding in DriveDataLogger.kt's entry above for the answer),
  report_raw_vs_filtered (added 2026-09-01, compares
  rawLinearAccelMagnitudeMps2 against the filtered magnitude when a log
  has both — lets LowPassFilter's cutoffHz be tuned from a future log,
  not just the StationaryDetector threshold; degrades gracefully via
  has_raw_columns on older logs that predate these fields).
Unit tests: tests/scripts/test_analyze_drive_log.py (6 cases, added
  2026-09-01, synthetic data only — CLAUDE.md Rule 19) — sweep finds a
  zero-error threshold when classes are perfectly separable; sweep
  correctly finds NO zero-error threshold when classes fully overlap
  (proves the sweep can detect "not separable," not just always find a
  win); DEAD_RECKONING-mode rows (no independent ground truth) are
  excluded from the sweep; has_raw_columns true/false on logs with/
  without the 2026-09-01 raw fields. `python -m pytest
  tests/scripts/test_analyze_drive_log.py` — 6/6 pass; full suite
  (`python -m pytest tests/`) — 43/43 pass, no regressions.

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
Status: IMPLEMENTED (Slice 6). UPDATE (2026-09-02): 5 new tests added for
  the roll/pitch mounting baseline + reducedConfidenceDueToRoll flag —
  see AlignmentEstimator.kt's entry.
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
UPDATE (Round 2, 2026-08-28) — GnssQuality gained a second function,
  `confidenceWeight(accuracyM, maxAccuracyM)`, returning a continuous
  [0,1] trust weight (linear falloff from `maxAccuracyM`) rather than
  `isGood`'s binary accept/reject. `isGood` is UNCHANGED and remains the
  state machine's own enter/exit trigger (GnssOutageDetector,
  unaffected); `confidenceWeight` is a separate, additional signal
  consumed by `fusion/VelocityBiasCalibrator.update()` (PRD.md
  FR13/Section 17 — see that class's entry).

android/app/src/main/kotlin/com/sih26168/idr/alignment/{YawRate,AlignmentEstimator}.kt
Status: IMPLEMENTED (Slice 6, 2026-08-25) — see `## Slice 1-6` build
  verification below for full detail. UPDATE (2026-09-02): see the
  `reset() also clears...` note below — AlignmentEstimator now also
  estimates a roll/pitch mounting baseline and a motorcycle-lean
  confidence flag.
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
Outputs: AlignmentEstimate(yawOffsetRad, sampleCount, isAligned,
  rollOffsetRad, pitchOffsetRad, pitchRollSampleCount,
  isPitchRollAligned, reducedConfidenceDueToRoll — the last five added
  2026-09-02).
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
  not re-estimate while GNSS is unavailable.
  UPDATE (2026-08-30): reset() IS now invoked automatically — see
  alignment/AlignmentRepository.kt below, which also moved this class's
  one caller from being MlVelocityRepository-only to a shared repository
  both DR paths read.
  UPDATE (2026-09-02, PRD.md Section 15's motorcycle-lean carve-out):
  evaluate() now also takes pitchRad/rollRad and accumulates a roll/pitch
  MOUNTING baseline (same circular-mean technique as yaw) while the
  vehicle is near-stationary with a GNSS fix (speed <= 1.0 m/s — a parked
  vehicle's own roll/pitch is ~0, so the device's roll/pitch at that
  moment IS the mounting tilt). Once that baseline is established
  (>= 20 samples, same convention as yaw's minSamplesForAligned),
  reducedConfidenceDueToRoll flags true whenever the CURRENT roll
  deviates from it by more than ~20 degrees (DEFAULT_MAX_ROLL_EXCURSION_RAD,
  engineering default, CLAUDE.md Rule 13) — a real lean, a slipped mount,
  etc. This is deliberately a FLAG only, not a lean-dynamics correction —
  PRD.md Section 15 explicitly excludes modeling the lean itself. Both
  pitchRad/rollRad params default to 0f so all pre-existing yaw-only call
  sites/tests remain valid unmodified. A true device->vehicle 3-axis
  rotation matrix is still NOT built beyond this baseline — this
  project's 2D horizontal navigation only ever needs a heading (yaw),
  which mounting pitch/roll tilt doesn't change (see this file's own
  updated class doc for the full reasoning).
Connected to: SensorRepository, GnssModeRepository -> AlignmentRepository -> AlignmentEstimator

android/app/src/main/kotlin/com/sih26168/idr/alignment/AlignmentRepository.kt
Status: IMPLEMENTED (2026-08-30)
Purpose: The Android/coroutine glue that turns the pure AlignmentEstimator
  into a live, SHARED estimate — PRD.md Section 15. PREVIOUSLY this
  estimation ran privately inside ml/MlVelocityRepository.kt alone, which
  meant (a) it silently stopped existing whenever the ONNX model failed
  to load (MainActivity only constructs MlVelocityRepository on a
  successful model load) even though alignment has nothing to do with ML
  inference, and (b) dr/BaselineDeadReckoningRepository.kt's physics path
  had no access to it at all, so dr/NonHolonomicConstraint.kt used raw
  device azimuth as its vehicle-heading proxy unconditionally. Extracted
  as its own repository, driven only by SensorRepository (orientation)
  and GnssModeRepository (GNSS bearing/speed) — no ML dependency — so
  BOTH BaselineDeadReckoningRepository and MlVelocityRepository now read
  the SAME AlignmentEstimator instance's estimate.
  Also wires PRD.md Section 15's "Ongoing validation... Phone Moved...
  triggers re-initialization": motion/PhoneMovedDetector.kt's
  deterministic pitch/roll-change stand-in resets AlignmentEstimator
  automatically on a detected remount, logged (CLAUDE.md Rule 17-style
  traceability) via Log.i, in addition to the existing manual
  "recalibrate" button (now calling this class's reset() directly instead
  of reaching through MlVelocityRepository.resetAlignment(), which no
  longer exists).
Inputs: SensorRepository.state (orientation), GnssModeRepository.state
  (GNSS bearing/speed/latestFix).
Outputs: AlignmentUiState(yawOffsetRad, sampleCount, isAligned,
  rollOffsetRad, pitchOffsetRad, pitchRollSampleCount,
  isPitchRollAligned, reducedConfidenceDueToRoll) — same fields
  AlignmentEstimate now has (the last five added 2026-09-02), republished
  as this repository's own StateFlow.
Connected to: SensorRepository, GnssModeRepository -> AlignmentRepository ->
  dr/BaselineDeadReckoningRepository (vehicle-heading correction for
  NonHolonomicConstraint) AND ml/MlVelocityRepository (unchanged feature-
  path correction, PLUS (2026-09-02) republishes reducedConfidenceDueToRoll
  into MlVelocityUiState -> ui/screens/StatusOverlayContent.kt /
  MainActivity's debug screen, PRD.md Section 31's "alignment/confidence
  indicator"); MainActivity's "recalibrate" button ->
  AlignmentRepository.reset(); motion/PhoneMovedDetector ->
  AlignmentRepository (automatic reset trigger)

android/app/src/main/kotlin/com/sih26168/idr/motion/PhoneMovedDetector.kt
Status: IMPLEMENTED (2026-08-30)
Purpose: A DETERMINISTIC stand-in for PRD.md Section 14's `Phone Moved`
  class — same "no labeled classifier data yet" precedent as
  MotionStateClassifier.kt/PotholeShockDetector.kt/TurningDetector.kt.
  PRD.md Section 15's own basis for alignment is the phone's fixed
  mounting orientation — this detects a SUSTAINED change in the device's
  own WORLD-frame pitch/roll (from OrientationSample, already gravity-
  referenced) relative to a remembered reference orientation, i.e. "the
  mount itself changed," not just road vibration.
Inputs: nowMs, pitchRad, rollRad (per orientation tick).
Outputs: Boolean (moved) — a one-shot edge on the tick a sustained
  deviation first crosses the dwell threshold; false on the first-ever
  call (establishes the initial reference) and on any tick below
  threshold or not yet sustained long enough.
Important functions/classes: evaluate() — pitch compared with a plain
  difference (bounded to [-pi/2, pi/2], never wraps); roll compared via a
  circular difference (atan2(sin(delta), cos(delta))), same wrap-safety
  technique alignment/YawRate.kt already uses for azimuth deltas.
  Thresholds: pitchRollChangeThresholdRad defaults to 0.26 rad (~15
  degrees), minSustainedDeviationMs defaults to 1000ms — both engineering
  defaults, unvalidated against real "phone picked up mid-drive" data
  (CLAUDE.md Rule 13). reset() clears the remembered reference.
Connected to: AlignmentRepository -> PhoneMovedDetector -> (gates AlignmentEstimator.reset())

android/app/src/test/kotlin/com/sih26168/idr/motion/PhoneMovedDetectorTest.kt
Status: IMPLEMENTED (2026-08-30)
Purpose: JUnit4 unit tests for PhoneMovedDetector.evaluate() — first
  sample only establishes reference; small deviation within threshold;
  large deviation not yet sustained; large deviation sustained past
  dwell fires; reference updates after firing so it doesn't immediately
  refire; a deviation that clears before the dwell elapses resets the
  streak; roll deviation near the +-pi wrap boundary isn't a false
  positive; reset() discards the reference.

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
  GnssModeRepository's streams through WorldFrameAcceleration (reused
  from Slice 3/5), FeatureExtractor, VelocityModel, and (follow-up)
  MlPositionIntegrator, republishing the live ML-predicted velocity AND
  an ML-driven WORLD-frame position as its own StateFlow.
  UPDATE (2026-08-30): alignment estimation moved OUT of this class into
  alignment/AlignmentRepository.kt (a required constructor param now,
  not an owned AlignmentEstimator) — this class reads
  alignmentRepository.state.value each tick instead of driving its own
  estimator; resetAlignment() was removed (callers now use
  AlignmentRepository.reset() directly). See AlignmentRepository's own
  entry for why (ML-load-failure coupling, physics path having no access
  at all).
  UPDATE (2026-08-30): motion/LongitudinalMotionClassifier.kt added,
  classifying PRD Section 14's Accelerating/Braking from the SAME
  accelForwardMps2 already computed for the ONNX feature vector —
  published as MlVelocityUiState.isAccelerating/isBraking. See that
  class's own entry.
  at all).
  Deliberately a SEPARATE, PARALLEL repository to
  BaselineDeadReckoningRepository (CLAUDE.md Rule 5) — does NOT modify
  or replace the physics position integrator; Slice 5's tested physics
  pipeline is completely untouched. Both run and display side by side,
  so the ML-vs-physics comparison PRD.md Section 30 wants for the demo
  is directly visible on-device, not just a desktop-measured claim.
Outputs: StateFlow<MlVelocityUiState> — {predictedVelocityMps,
  isAligned, yawOffsetDeg, alignmentSampleCount, positionEastM,
  positionNorthM, reducedConfidenceDueToRoll (added 2026-09-02, republished
  from AlignmentRepository.state — see that class's entry)}.
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
  corroborated against the current velocity estimate via
  `MotionStateClassifier.classify()` — a physically-still tick with a
  meaningful velocity estimate gets overridden to NOT ZUPT
  (`MlVelocityUiState.isCruising = true`). ORIGINALLY passed
  `rawPredictedVelocityMps` here — CHANGED 2026-09-05, see this class's
  own "REAL BUG FOUND AND FIXED" entry below and MotionStateClassifier.kt's
  entry for why that was wrong (it must be the bias-corrected/damped
  value, not the raw pre-correction one). Before the forward/lateral
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
  manual "hold phone flat, tap to calibrate" fallback.
  UPDATE (Round 2, 2026-08-28) — REMOVED `resetAlignment()`, REMOVED the
  owned `AlignmentEstimator` field: this class now takes an
  `alignmentRepository: AlignmentRepository` constructor param and reads
  `alignmentRepository.state.value` each tick instead of evaluating its
  own estimator — see `alignment/AlignmentRepository.kt`'s entry for why
  (the physics path needed the SAME estimate, not a second independent
  one). The recalibrate `FloatingIconButton`
  (`StatusOverlayContent.kt`, used by `MapScreen.kt` since 2026-08-30's
  DriveScreen removal) now calls `AlignmentRepository.reset()` directly
  through `MainActivity` instead of reaching through this class.
  UPDATE (Round 2, 2026-08-28) — PRD.md FR3/Section 13's damping/OOD
  guard: after `biasCalibrator.correctedVelocity()`, the result now
  passes through a new `velocityGuard: VelocityGuard` (see that class's
  entry below) BEFORE reaching `positionIntegrator.update()`. The
  bias-corrected-but-undamped value is still published as
  `predictedVelocityCorrectedMps` (renamed meaning: "before Round 2
  damping," not "what feeds the integrator" anymore); the guard's output
  is the NEW `predictedVelocityDampedMps` field, which is what actually
  feeds the integrator now. `isVelocityOutOfDistribution` surfaces
  whether the guard rejected this tick's raw prediction. REAL FINDING
  (2026-08-28 live outage test): a single anomalous ML prediction (e.g. a
  desk-bump during bench testing) previously reached the position
  integrator with no smoothing at all — unlike the physics path's
  double-integration, which has "memory" that absorbs one bad accel
  sample — producing a visible position jump.
  UPDATE (Round 2, 2026-08-28) — PRD.md FR13/Section 17's continuous GNSS
  confidence weighting: the `biasCalibrator.update()` call now passes
  `confidenceWeight = GnssQuality.confidenceWeight(fix.accuracyM)`
  instead of relying on `GnssQuality.isGood`'s binary gate alone — see
  `fusion/VelocityBiasCalibrator.kt`'s entry for the effective-alpha math.
  UPDATE (context-aware ZUPT): the owned `StationaryDetector` field is
  replaced by `motion/StopEventClassifier.kt` (see its own entry) —
  `dampedVelocityMps` and (when GNSS_AIDED + GnssQuality.isGood) GNSS
  speed feed it as the two extra inputs. `MotionStateClassifier` still
  reads `StopClassification.dwellConfirmedStationary` instead of a
  second, redundant `StationaryDetector.evaluate()` call for its
  physically-still input, same as before. The actual ZUPT gate fed to
  `positionIntegrator.update()` is
  `classification.shouldApplyZupt && !motionClassification.isCruising` —
  StopEventClassifier's context-aware decision, still overridable by the
  cruising signal. `MlVelocityUiState.stationaryContext` (new field)
  carries the classification for logging/debug/UI only, same as
  `DeadReckoningState.stationaryContext` on the physics side. Logs
  (`Log.d`) on every context change, not every tick.
  BUG FIXED (2026-09-05): the cruising override's SECOND input
  (velocity) was `rawPredictedVelocityMps` until this date — that
  defeated the override whenever VelocityBiasCalibrator had learned a
  large bias (exactly what happened on a real outdoor bike test: raw
  model near-0 while bias-corrected `dampedVelocityMps` was ~11 m/s),
  because the override compared the wrong, stale value against
  `minCruisingSpeedMps` and never fired, so ZUPT froze the ML position
  marker despite genuine motion. Now passes `dampedVelocityMps` instead
  — see MotionStateClassifier.kt's entry for the full writeup. NOT YET
  re-verified against a fresh outdoor GPS-off drive.
Connected to: SensorRepository, GnssModeRepository -> MlVelocityRepository -> MainActivity (Compose UI);
  MlVelocityRepository -> fusion/StateEstimator (Slice 7, reads its position + isAligned);
  motion/MotionStateClassifier, motion/PotholeShockDetector, motion/LongitudinalMotionClassifier
  (2026-08-30) -> MlVelocityRepository;
  alignment/AlignmentRepository -> MlVelocityRepository (shared alignment estimate, replaces the
  owned AlignmentEstimator); ml/VelocityGuard -> MlVelocityRepository (Round 2, 2026-08-28);
  motion/StopEventClassifier -> MlVelocityRepository (context-aware ZUPT);
  StatusOverlayContent's recalibrate button (via MapScreen) -> MainActivity ->
  AlignmentRepository.reset() (moved off this class)

android/app/src/main/kotlin/com/sih26168/idr/ml/VelocityGuard.kt
Status: IMPLEMENTED (Round 2, 2026-08-28)
Purpose: PRD.md Section 13/FR3's damping + out-of-distribution guard on
  the ML velocity path, sitting between VelocityBiasCalibrator's
  bias-corrected output and ml/MlPositionIntegrator. REAL FINDING
  (2026-08-28 live outage test): dr/BaselinePhysicsIntegrator
  double-integrates from acceleration, so it has "memory" that absorbs
  one bad accel sample; MlPositionIntegrator instead applies the model's
  FULL predicted speed each tick with no such memory (see that class's
  own documented burst-sensitivity finding from 2026-08-25), so a single
  anomalous prediction previously reached the position integrator
  completely unguarded.
Inputs: rawVelocityMps (here: the bias-corrected, pre-damping
  prediction) per call to apply().
Outputs: Result(velocityMps, wasOutOfDistribution) — a data class, not a
  bare Float, so the caller can surface BOTH the usable value and
  whether this tick's raw input was rejected.
Important functions/classes: apply() — two independent corrections in
  order: (1) OOD rejection — a NaN/infinite prediction, or one exceeding
  `maxPlausibleSpeedMps` (default 55 m/s, ~200 km/h, deliberately
  generous) in magnitude, is discarded outright and the last
  accepted+smoothed value is held instead; (2) damping — an accepted
  prediction is exponentially smoothed against the previous
  accepted+smoothed value (`emaAlpha` default 0.3, deliberately more
  responsive than VelocityBiasCalibrator's 0.05 — this smooths
  tick-to-tick noise over ~1s at ~10 Hz, not a slowly-varying systematic
  offset over tens of seconds); reset().
Important concepts/assumptions: HONEST LIMITATION (CLAUDE.md Rule 13) —
  this is a coarse sanity clamp, NOT full training-distribution
  out-of-distribution detection; no per-feature training bounds are
  exported from the Python training pipeline yet, so a prediction that's
  merely "unlikely" (not wildly implausible) passes through undetected.
Connected to: ml/MlVelocityRepository -> VelocityGuard -> ml/MlPositionIntegrator
Unit tests: tests/.../ml/VelocityGuardTest.kt — first sample accepted
  as-is; subsequent samples smoothed by a hand-derived EMA value; values
  beyond the plausible bound (both directions) and NaN/infinite are
  rejected and the last accepted value is held; a value exactly at the
  bound is accepted; a rejection before any accepted sample holds zero;
  reset() clears state so the next accepted sample isn't blended against
  pre-reset history.

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
Important functions/classes: update(gnssSpeedMps, rawPredictedVelocityMps,
  confidenceWeight = 1f) (no-ops below the speed gate); correctedVelocity(raw)
  (adds the learned bias); reset().
UPDATE (Round 2, 2026-08-28 — PRD.md FR13/Section 17): `update()` gained
  the `confidenceWeight` parameter above (defaults to 1, so pre-existing
  callers/tests are unaffected). The EFFECTIVE alpha for a given sample
  is `emaAlpha * confidenceWeight.coerceIn(0f, 1f)` — a marginal fix
  (e.g. right at GnssQuality's 25m threshold, weight near 0) barely
  moves the bias; a very accurate fix (weight near 1) moves it at the
  full configured rate. The FIRST-EVER sample still initializes the bias
  directly regardless of weight (no prior estimate to blend a
  low-confidence correction against). `ml/MlVelocityRepository` now
  passes `GnssQuality.confidenceWeight(fix.accuracyM)` here instead of
  relying on `GnssQuality.isGood`'s binary gate alone.
Connected to: ml/MlVelocityRepository -> VelocityBiasCalibrator -> ml/MlPositionIntegrator;
  gnss/GnssQuality.confidenceWeight -> VelocityBiasCalibrator.update (Round 2, 2026-08-28)

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
  newFixEastM?, newFixNorthM?, gnssJitterOffsetEastM=0.0,
  gnssJitterOffsetNorthM=0.0 — the last two added 2026-09-02, see UPDATE
  below) — GNSS_AIDED returns (gnssJitterOffsetEastM, gnssJitterOffsetNorthM),
  which is (0,0) — the exact original hard-coded behavior — at every
  pre-existing call site/test, since both default to 0.0;
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
Connected to: fusion/StateEstimator -> PositionFusion -> FusedPositionUiState;
  ml/ReacquisitionDriftModel -> PositionFusion.setReacquisitionBlendMs() (2026-08-30)
UPDATE (Round 2, 2026-08-28): gained a heading-level sibling,
  `fusion/HeadingFusion.kt` (see its own entry below) — REACQUISITION
  used to blend position via this class but leave heading a hard
  cutover; both are now blended the same way.
UPDATE (2026-08-30, PRD.md Section 17's "AI-based" GNSS+INS fusion —
  previously entirely classical, per STATUS_AND_ROADMAP.md's own flagged
  decision point): `reacquisitionBlendMs` is now a `var`, settable via
  `setReacquisitionBlendMs()` — StateEstimator.kt calls it with
  `blendDurationForDriftMs()`'s output right before the tick that first
  enters REACQUISITION, fed by ml/ReacquisitionDriftModel.kt's predicted
  along-track drift for that specific outage.
  `blendDurationForDriftMs(predictedDriftMeters)` (companion function):
  MIN_ADAPTIVE_REACQUISITION_BLEND_MS=500ms +
  BLEND_MS_PER_METER_OF_PREDICTED_DRIFT=30.0 * predictedDriftMeters,
  clamped to [500, 3000]ms — engineering defaults, unvalidated against a
  real outdoor test drive (CLAUDE.md Rule 13). Deliberately NOT a
  Kalman/EKF state update (CLAUDE.md's "What Not To Build"/PRD.md
  Section 7) — still the same simple linear-interpolation blend as
  before, just with a data-informed duration. If
  ReacquisitionDriftModel failed to load, `reacquisitionBlendMs` simply
  stays at DEFAULT_REACQUISITION_BLEND_MS (1000ms) — the exact previous
  classical behavior, same resilience pattern as the ML velocity path.
UPDATE (2026-09-02, PRD.md Section 17's OTHER still-open fusion piece —
  "smooth short GNSS gaps/jitter"): `update()` gained
  `gnssJitterOffsetEastM`/`gnssJitterOffsetNorthM` params (both default
  0.0), returned directly by the `GNSS_AIDED` branch instead of a
  hard-coded `(0, 0)`. `fusion/StateEstimator.kt` computes this small
  correction via the new `fusion/GnssJitterFilter.kt` (see its own
  entry) and passes it in every tick — this class itself stays
  unchanged in spirit (still a pure per-mode dispatcher), just no longer
  hard-codes an assumption about what "GNSS is trusted directly" means
  numerically.

fusion/GnssJitterFilter.kt
Status: IMPLEMENTED (2026-09-02)
Purpose: PRD.md Section 17's "the IMU-derived velocity/heading are used
  to smooth short GNSS gaps/jitter" — the one piece of Section 17 that
  was still genuinely unbuilt (the velocity-bias half,
  fusion/VelocityBiasCalibrator.kt, and FR13's continuous accuracy
  weighting, gnss/GnssQuality.confidenceWeight, were both already wired
  in on 2026-08-28, before this file existed). Without this, the map
  marker snapped to each new raw GNSS fix directly while GNSS_AIDED —
  Android's Location.getAccuracy() is a 68%-confidence RADIUS, not a
  promise of fix-to-fix repeatability, so consecutive "good" fixes can
  still visibly jitter a few meters. Pure Kotlin, no Android dependency,
  unit-testable (CLAUDE.md Rule 19). A simple COMPLEMENTARY filter,
  deliberately NOT a Kalman filter (CLAUDE.md's "What Not To Build" /
  PRD.md Section 7) — no covariance propagation, just a fixed blend.
Inputs (per update() call): nowMs; rawFixEastM/rawFixNorthM (a GNSS fix
  in a FIXED local-meter frame — caller's responsibility to keep the
  reference point constant across calls); velocityEastMps/velocityNorthMps
  (current IMU/DR-derived WORLD-frame velocity, for the short-term
  prediction step between fixes); confidenceWeight in [0,1] (typically
  gnss/GnssQuality.confidenceWeight — how hard to pull the prediction
  toward the raw fix this tick; 1 = trust it completely, 0 = pure IMU
  dead reckoning).
Outputs: (smoothedEastM, smoothedNorthM) — same local-meter frame as the
  input.
Important functions/classes: update() — first-ever sample is trusted
  outright (no prior estimate to blend against, same convention
  fusion/VelocityBiasCalibrator's own first sample already uses);
  otherwise predicts forward from the last smoothed position using
  velocity * dt, then blends that prediction toward the raw fix by
  confidenceWeight (predicted + w * (raw - predicted)). reset() clears
  all state — callers must call it whenever GNSS is freshly (re)trusted
  after not being GNSS_AIDED, so the next update() doesn't predict
  across a stale multi-second/-minute gap from a pre-outage position.
Important concepts/assumptions: operates entirely in whatever local-
  meter frame the caller supplies — carries no lat/lon or GeoProjection
  dependency itself (kept in the caller, fusion/StateEstimator.kt).
Connected to: fusion/StateEstimator (owns the instance, computes its
  inputs, resets it on GNSS_AIDED re-entry) -> PositionFusion.update()'s
  gnssJitterOffsetEastM/NorthM params -> FusedPositionUiState.fusedEastM/
  fusedNorthM (via the GNSS_AIDED branch) and
  FusedPositionUiState.gnssJitterOffsetM (debug-only magnitude).

fusion/HeadingFusion.kt
Status: IMPLEMENTED (Round 2, 2026-08-28)
Purpose: PRD.md Section 18's REACQUISITION heading/map-orientation
  blend — the heading-level counterpart to PositionFusion above, same
  per-mode structure (GNSS_AIDED/TRANSITION/DEAD_RECKONING/REACQUISITION),
  same `reacquisitionBlendMs` default (read from
  `PositionFusion.DEFAULT_REACQUISITION_BLEND_MS`, not duplicated).
  REAL FINDING (2026-08-28 live outage test): Round 1's
  `ui/screens/MapScreen.kt` computed the map's heading-up rotation ad
  hoc, with a hard cutover between GNSS bearing and a DR-derived bearing
  at the GNSS_AIDED/DEAD_RECKONING boundary — no interpolation, which
  produced a visible ~180 degree map flip on reacquisition whenever the
  two disagreed (compounded by the physics-heading-alignment gap fixed
  in dr/BaselineDeadReckoningRepository the same day).
Inputs: nowMs, mode (GnssMode), drHeadingDeg (live DR-derived compass
  bearing), newFixHeadingDeg (nullable — the newly reacquired GNSS fix's
  bearing).
Outputs: Float (fused compass heading, degrees, 0-360).
Important functions/classes: update() (per-mode logic, mirrors
  PositionFusion.update() exactly); lerpDegreesCircular (private) —
  treats each bearing as a unit vector via sin/cos, linearly blends the
  VECTORS, then recovers the angle via atan2. This is the standard
  technique for interpolating angles without a wraparound discontinuity
  at +-180/0-360 degrees — a plain linear lerp between e.g. 350 deg and
  10 deg would produce a -340-degree spin the LONG way around instead of
  the correct +20-degree short way; directly unit-tested for this exact
  case (HeadingFusionTest's wrap-boundary test).
Connected to: fusion/StateEstimator -> HeadingFusion -> FusedPositionUiState.fusedHeadingDeg
  -> ui/screens/MapScreen.kt (StreetMapView's headingDeg param)
Unit tests: tests/.../fusion/HeadingFusionTest.kt — per-mode behavior
  mirrors PositionFusionTest's coverage (freeze/passthrough/blend/
  reset), plus a dedicated 350deg->10deg wrap-boundary case verifying
  the short-way interpolation.

fusion/RunningStats.kt
Status: IMPLEMENTED (2026-08-30)
Purpose: A running (online, Welford's algorithm) mean/population-
  standard-deviation accumulator over a stream of scalar samples — built
  to feed ml/ReacquisitionDriftModel.kt's avgPredictedSpeedMps/
  predictedSpeedStdMps features with "the mean/std of DR speed samples
  seen so far during the CURRENT GNSS outage," without storing the full
  sample history. Welford's algorithm computes the SAME population
  variance numpy.std()'s default (ddof=0) computes in
  ml/train_reacquisition_model.py — this class exists specifically so
  StateEstimator's live accumulation matches that training-time
  statistic, not a different one.
Inputs: accumulate(value: Double) per sample.
Outputs: mean(), populationStdDev(), sampleCount.
Pure Kotlin, no Android dependency, unit-testable (CLAUDE.md Rule 19) —
  see RunningStatsTest.kt (5 cases, including an exact match against a
  textbook numpy mean=5.0/std=2.0 dataset).
Connected to: fusion/StateEstimator -> RunningStats -> ml/ReacquisitionDriftModel.predict()

ml/ReacquisitionDriftModel.kt
Status: IMPLEMENTED (2026-08-30)
Purpose: ONNX Runtime Mobile wrapper for the trained reacquisition-drift
  LinearRegression model (ml/train_reacquisition_model.py/
  ml/export_reacquisition_model.py) — PRD.md Section 17's "AI-based" half
  of the GNSS+INS Fusion Engine. Predicts EXPECTED along-track DR
  position drift (meters) at the moment GNSS reacquires. LinearRegression,
  not RandomForestRegressor, was the MEASURED choice (CLAUDE.md
  Rule 3/11) — see ml/train_reacquisition_model.py's own printed
  comparison: with only 3 features and ~1,200 simulated training
  samples, the linear model's held-out MAE (14.224m)/RMSE (17.895m) beat
  both RandomForestRegressor (MAE 15.060m/RMSE 18.813m) and the best
  1-parameter physics-formula baseline (MAE 14.887m/RMSE 18.663m) — a
  real but modest improvement, honestly reported, not oversold.
Inputs: outageDurationS, avgPredictedSpeedMps, predictedSpeedStdMps
  (Float each).
Outputs: Float — predicted along-track drift, meters, clamped to >= 0
  (the fitted LinearRegression has no non-negativity constraint; a
  short/slow outage can produce a small negative raw prediction from the
  fitted intercept, confirmed during training).
Important functions/classes: predict() — loads
  models/reacquisition_drift_v1.onnx (bundled asset, ~0.25KB, committed
  like velocity_v1.onnx — see models/README.md) once at construction via
  loadFromAssets(context), then single-row inference per REACQUISITION
  event (NOT per tick). Input/output tensor names ("input"/"variable")
  verified via onnxruntime's Python API before hardcoding, same skl2onnx
  defaults VelocityModel.kt already uses regardless of regressor type.
Connected to: MainActivity (loads it, separate try/catch from the
  velocity model — an independent failure mode) -> StateEstimator ->
  PositionFusion.setReacquisitionBlendMs()

ml/train_reacquisition_model.py
Status: IMPLEMENTED (2026-08-30)
Purpose: Trains + evaluates the reacquisition-drift regressor. IO-VNBD
  has no real GNSS outages (continuously GNSS-aided recording), so
  outages are SIMULATED — a random start row + random duration (5-60s,
  an engineering range unvalidated against real outdoor outage lengths,
  CLAUDE.md Rule 13) within one trip, using the ALREADY-TRAINED velocity
  model's predictions over that window as the "live on-device
  prediction" signal. Target is ALONG-TRACK drift
  (cumsum(|predicted_speed - true_speed| * dt) over the window) — NOT
  full 2D position drift, deliberately: this dataset has no reliable
  WORLD-frame heading to reconstruct a synthetic 2D trajectory from (only
  vehicle-frame forward/lateral, and GNSS course only changes every ~9s
  — far too coarse), and along-track integration error is the dominant
  real-world DR drift component anyway once the non-holonomic constraint
  already suppresses lateral drift.
  Split discipline (same as train_velocity_model.py): outage samples
  drawn ONLY from the 14 trips already held out from velocity-model
  TRAINING (so drift labels reflect genuinely unseen-trip prediction
  quality), then those 14 trips split AGAIN (10 drift-train/4 drift-val)
  so this model's own reported accuracy is ALSO on held-out trips.
Inputs: data/processed/io_vnbd_features.parquet (same file
  feature_extraction.py produces for the velocity model).
Outputs: printed MAE/RMSE comparison — constant-mean baseline, two
  1-parameter physics-formula baselines (std*duration and
  avgspeed*duration), LinearRegression, RandomForestRegressor. Explicitly
  measures ML against simple deterministic baselines before choosing one
  (CLAUDE.md Rule 3) — see ml/ReacquisitionDriftModel.kt's entry for the
  real numbers and which one won.
Important functions/classes: simulate_outage_samples() (one trip's
  random outage windows -> 3 features + drift label),
  build_drift_dataset() (runs that over a list of trips and concatenates).
Unit tests: tests/ml/test_train_reacquisition_model.py (6 cases,
  synthetic trips only — perfect-prediction gives exactly zero drift;
  constant-offset prediction gives the analytically expected
  `error * duration`; minimum window-size enforcement; a too-short trip
  yields zero samples; deterministic with a fixed rng seed; output
  column set matches the declared feature/label columns).
Connected to: data/processed/io_vnbd_features.parquet -> train_reacquisition_model.py
  -> (printed comparison only — export_reacquisition_model.py produces the shipped artifact)

ml/export_reacquisition_model.py
Status: IMPLEMENTED (2026-08-30)
Purpose: Exports the FINAL LinearRegression drift model to ONNX +
  output-parity check (CLAUDE.md Rule 20), mirroring export_model.py's
  own "retrain on ALL data once train/val has validated the approach"
  pattern — retrains the velocity model on all 72 trips, simulates
  outages across all 72 using ITS predictions, trains LinearRegression on
  the full simulated set (5,600 samples), exports, and verifies parity.
Outputs: models/reacquisition_drift_v1.onnx (~0.25KB). Measured parity:
  max abs diff 0.000011m, mean abs diff 0.000002m, 500/500 samples within
  the 1e-3m tolerance.
Unit tests: tests/ml/test_export_reacquisition_model.py (2 cases,
  mirroring test_export_model.py's pattern exactly — exported ONNX
  matches sklearn predictions; the parity check itself correctly detects
  a real, deliberately-introduced mismatch).
Connected to: train_reacquisition_model.py's helpers (reused directly,
  not duplicated) -> export_reacquisition_model.py -> models/reacquisition_drift_v1.onnx
  -> android/app/src/main/assets/reacquisition_drift_v1.onnx (manually
  copied, same convention as velocity_v1.onnx) -> ml/ReacquisitionDriftModel.kt

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
  UPDATE (Round 2, 2026-08-28): now also owns a `fusion/HeadingFusion`
  instance and a `lastConfidentHeadingDeg` field (moved here, unchanged
  in value, from `ui/screens/MapScreen.kt` — same 0.5 m/s
  confident-bearing threshold, same "hold the last value below that"
  behavior). Each tick computes `drHeadingDeg` from the PHYSICS velocity
  vector's bearing (`atan2(physicsState.velocityEastMps,
  physicsState.velocityNorthMps)`), then blends it against
  `fix.bearingDeg` via `headingFusion.update()`, publishing the result as
  the NEW `FusedPositionUiState.fusedHeadingDeg` field. This is now the
  SINGLE source of truth for the map's heading-up rotation — see
  `fusion/HeadingFusion.kt`'s entry and `ui/screens/MapScreen.kt`'s
  UPDATE note for why Round 1's per-screen ad hoc computation was wrong.
  UPDATE (2026-08-30): PRD.md Section 19's MVP map constraint
  (map/MapConstraint.kt) is now applied to `fused` here, AFTER
  PositionFusion.update() but before publishing — a per-tick correction
  to this class's OUTPUT position only, same architectural boundary
  PositionFusion's own freeze/interpolate corrections already use; it
  never writes back into BaselineDeadReckoningRepository/
  MlVelocityRepository's own accumulated state. `setActiveRouteGeometry`
  is a plain mutable field (lat/lon pairs, not osmdroid's GeoPoint, to
  keep this fusion-layer class free of a map-library dependency) set
  once from ui/screens/MapScreen.kt whenever its active route changes,
  and read every tick on this same collecting coroutine — the SAME
  "settable field read on the collecting coroutine" pattern
  BaselineDeadReckoningRepository.walkingModeEnabled already establishes.
  The road-geometry-to-local-meters projection is cached against the
  anchor it was computed with, and only re-projected when the route or
  the anchor changes, not every ~10Hz tick. Only attempted while
  gnssState.mode != GNSS_AIDED (a real GPS fix needs no road-snap
  "correction"), an anchor + active route exist, and DR speed is above
  0.5 m/s (below that, the physics-velocity-vector heading used for the
  compatibility check is noise, not signal — same floor
  ui/screens/MapScreen.kt's own heading-up map rotation fallback
  already uses). `FusedPositionUiState.roadSnapped`/`distanceToRoadM`
  expose whether/how far this tick's snap moved the estimate, surfaced
  in MainActivity's debug screen for demo honesty (CLAUDE.md Rule 13) —
  not shown as a separate map overlay, to avoid growing MapScreen's UI
  surface for what is fundamentally a debug/verification signal.
  UPDATE (2026-08-30, PRD.md Section 17's "AI-based" fusion): a new
  `outageSpeedStats` (fusion/RunningStats.kt) accumulates whichever DR
  source is active each tick (same selection as drEastM/drNorthM),
  reset the instant GNSS is good again. At the SAME "entering
  REACQUISITION" instant DriftSummary is already snapshotted, this class
  now ALSO computes the real elapsed outage duration and calls
  `reacquisitionDriftModel?.predict(...)`, then
  `positionFusion.setReacquisitionBlendMs(PositionFusion.blendDurationForDriftMs(...))`
  — BEFORE `positionFusion.update()` sees REACQUISITION mode for the
  first time this outage, so the very first blend tick already uses the
  adaptive duration. `reacquisitionDriftModel` is a new nullable
  constructor param, same resilience pattern as `mlVelocityRepository` —
  null (ONNX load failure) means `positionFusion` simply keeps its fixed
  1-second default, the exact previous classical behavior. Logged
  (`Log.i`) alongside the existing drift-summary log line.
  UPDATE (2026-09-01, REAL BUG FIX — on-device test, phone stationary
  indoors, History tab logging 0.3-30m of "drift" every reacquisition
  cycle): both `outageAnchorLatDeg`/`outageAnchorLonDeg` and the
  REACQUISITION fix were only required to pass GnssQuality's 25m "is
  GNSS available" bar, which Android's self-reported fix accuracy can
  satisfy even under indoor multipath (successive fixes individually
  claim <=25m while landing 5-30m apart) — so the recorded "drift" was
  really GNSS position noise, not DR error. A new field,
  `outageAnchorAccuracyM`, is captured alongside the anchor whenever the
  strict (non-provisional) anchor-set branch fires; at the "entering
  REACQUISITION" instant, a `DriftSummary` is now only computed/recorded
  (`driftHistory.add`, `Log.i`) when BOTH the anchor's tracked accuracy
  and the current fix's accuracy independently clear the new, stricter
  `GnssQuality.DEFAULT_MAX_ACCURACY_FOR_GROUND_TRUTH_M` (10m) bound —
  see that constant's own doc. The state machine's own mode-transition
  timing and the adaptive `positionFusion.setReacquisitionBlendMs()`
  call are UNCHANGED (still run every REACQUISITION entry regardless of
  ground-truth quality) — this only gates whether a number gets
  PRESENTED to the user as a measured drift result, not the live
  position-fusion behavior.
  UPDATE (2026-09-02, PRD.md Section 17's other still-open fusion piece —
  "smooth short GNSS gaps/jitter"): two new fields,
  `tripOriginLatDeg`/`tripOriginLonDeg`, set ONCE from the first-ever
  good fix this run and never moved again — deliberately a SEPARATE
  frame from the continuously-moving `outageAnchorLatDeg`/
  `outageAnchorLonDeg` above, so this addition cannot touch the
  anchor-accuracy drift-measurement fix from the 2026-09-01 UPDATE just
  above. A new `gnssJitterFilter` (`fusion/GnssJitterFilter.kt`) runs
  every tick a trustworthy fix arrives while GNSS_AIDED (the SAME strict
  branch that already sets the outage anchor): converts the fix to local
  meters relative to the trip origin, feeds it plus the current physics
  velocity and `GnssQuality.confidenceWeight(fix.accuracyM)` into the
  filter, and the resulting (smoothed - raw) delta is passed to
  `positionFusion.update()` as `gnssJitterOffsetEastM`/
  `gnssJitterOffsetNorthM` — see PositionFusion.kt's own UPDATE note for
  how its GNSS_AIDED branch now returns this instead of a hard-coded
  `(0,0)`. `gnssJitterFilter.reset()` fires whenever `previousMode !=
  GNSS_AIDED` at the moment a good fix arrives (a fresh outage just
  ended, or this run's very first tick), so the filter never predicts
  across a stale gap. New `FusedPositionUiState.gnssJitterOffsetM` field
  (magnitude, debug-only) surfaces the correction size for verification.
Connected to: GnssModeRepository, BaselineDeadReckoningRepository,
  MlVelocityRepository -> StateEstimator -> MainActivity (Compose UI);
  fusion/DriftSummary -> StateEstimator.driftSummary -> ui/components/DriftSummaryCard (Slice 8);
  fusion/HeadingFusion -> StateEstimator.fusedHeadingDeg -> ui/screens/MapScreen.kt (Round 2, 2026-08-28);
  ui/screens/MapScreen.kt -> StateEstimator.setActiveRouteGeometry() -> map/MapConstraint.snapToRoad() -> FusedPositionUiState.fusedEastM/fusedNorthM;
  fusion/RunningStats, ml/ReacquisitionDriftModel -> StateEstimator -> fusion/PositionFusion.setReacquisitionBlendMs() (2026-08-30);
  gnss/GnssQuality.confidenceWeight, fusion/GnssJitterFilter -> StateEstimator -> fusion/PositionFusion.update()'s gnssJitterOffsetEastM/NorthM (2026-09-02)

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
Status: IMPLEMENTED (2026-08-25); BUG FIXED (2026-09-05)
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
  model's prediction (ml/VelocityModel.kt). Same "deterministic
  stand-in before the real ML classifier" precedent as StationaryDetector
  itself.
  REAL BUG FOUND AND FIXED (2026-09-05, real outdoor bike drive test,
  GPS-off segment): this used to receive the RAW, pre-bias-correction
  model output as its corroborating signal. That output stayed near 0
  m/s the entire outage even though the bike was genuinely riding at
  ~11 m/s, because the raw ONNX model (trained on car data) badly
  under-predicts this bike's real speed — the domain gap
  VelocityBiasCalibrator exists to paper over. VelocityBiasCalibrator had
  correctly learned a ~11 m/s compensating bias from GNSS ground truth
  before the outage, so ml/MlVelocityRepository's displayed
  `dampedVelocityMps` correctly showed ~11 m/s — but this classifier
  never saw that number, only the stale near-zero raw one, so it never
  crossed minCruisingSpeedMps and never fired isCruising. Net effect on
  a smooth road (low accel/gyro, matching StationaryDetector's own
  documented ambiguity): StopEventClassifier fell into BRIEF_STOP/
  LONG_IDLE, nothing overrode it, ZUPT fired, and
  ml/MlPositionIntegrator zeroed the position update despite real
  motion — the on-screen ML position marker froze while the velocity
  readout kept showing ~11 m/s. Fix: this classifier's second parameter
  (renamed rawPredictedVelocityMps -> velocityEstimateMps) must be the
  SAME bias-corrected/damped velocity MlVelocityRepository actually feeds
  to the position integrator, not the pre-correction raw model output —
  see this file's own class doc and MlVelocityRepository.kt's call site
  for the full writeup. Rebuilt, unit tests updated
  (MotionStateClassifierTest.kt), installed on-device via `adb install`.
  NOT YET re-verified against a fresh outdoor GPS-off drive — CLAUDE.md
  Rule 13/"How Claude Should Work" #3 still applies until that re-run
  happens and the ML position marker is confirmed to actually advance
  during smooth constant-speed cruising with GNSS off.
Inputs: physicallyStill (StationaryDetector's own accel/gyro-only
  output), velocityEstimateMps (bias-corrected/damped ML velocity — the
  SAME value fed to ml/MlPositionIntegrator, NOT the raw model output).
Outputs: MotionClassification(isStationary, isCruising) — mutually
  exclusive; both false if not physicallyStill (no ambiguity to resolve
  there — this class only resolves the ONE stationary-vs-cruising tie).
Important functions/classes: classify() — physicallyStill=false -> both
  false; physicallyStill=true and velocityEstimateMps below
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

android/app/src/main/kotlin/com/sih26168/idr/motion/FloorChangeDetector.kt
Status: IMPLEMENTED (Round 2, 2026-08-28)
Purpose: PRD.md FR12 — barometer-based floor/level-change detection,
  directly supporting the multi-level-parking scenario named in the SIH
  problem statement (Section 2/3). Pure Kotlin, no Android dependency,
  unit-testable on the plain JVM (CLAUDE.md Rule 19). Deterministic
  threshold detector — same "physics-only stand-in" precedent as
  StationaryDetector/PotholeShockDetector, but satisfies its OWN FR
  directly (there's no PRD Section 14 motion class for this).
Inputs: nowMs (boot-time ms, relative durations only), pressureHpa.
Outputs: Result(relativeAltitudeM, floorChangeDetected, floorDelta).
Important functions/classes: relativeAltitudeMeters(baselineHpa,
  currentHpa) (companion, pure) — the international barometric formula
  `h = 44330*(1-(P/P0)^(1/5.255))`, a standard atmospheric-physics
  approximation (not an invented/measured figure — CLAUDE.md Rule 13's
  "no invented numbers" concerns claimed benchmarks, not textbook
  constants), only accurate for modest altitude changes near the
  baseline (ICAO standard-atmosphere assumption) — adequate for a few
  parking-structure floors, not claimed accurate at km scale; evaluate()
  — tracks a baseline pressure (established on the first-ever reading),
  computes relative altitude against it, and signals a floor change only
  once a threshold crossing has been SUSTAINED for minDwellMs (same
  hysteresis principle as StationaryDetector/GnssOutageDetector,
  CLAUDE.md Rule 16's spirit — a brief pressure blip, e.g. a car door
  slamming, must not flip this); on a confirmed change, the baseline
  RE-ANCHORS to the current pressure so a SUBSEQUENT floor change (e.g.
  descending multiple levels) can also be detected, not just the first;
  reset().
Important concepts/assumptions: engineering defaults
  (floorHeightThresholdM=2.5m, minDwellMs=2000ms), not yet validated
  against a real multi-level-parking test drive (CLAUDE.md Rule 13).
Connected to: motion/FloorChangeRepository -> FloorChangeDetector -> FloorChangeUiState
Unit tests: tests/.../motion/FloorChangeDetectorTest.kt — the formula
  itself hand-verified against a known pressure-ratio case (0.5 ratio ~=
  5478m, derived via the formula's own Taylor expansion, not just
  round-tripped through the code); sustained crossings up/down signal the
  correct floorDelta; a crossing that doesn't last the full dwell, or is
  interrupted mid-streak, does NOT signal a change; staying within the
  threshold band never signals a change; re-anchoring after a confirmed
  change allows a second change to also be detected; reset() clears state.

android/app/src/main/kotlin/com/sih26168/idr/motion/FloorChangeRepository.kt
Status: IMPLEMENTED (Round 2, 2026-08-28)
Purpose: Android/coroutine glue driving FloorChangeDetector off
  SensorRepository's barometer stream. A SEPARATE repository (CLAUDE.md
  Rule 5) rather than folded into BaselineDeadReckoningRepository/
  MlVelocityRepository — floor detection is entirely independent of
  GNSS/DR position estimation; nothing about it gates or is gated by
  either DR path, so it only depends on SensorRepository.
Inputs: SensorRepository (read-only, via its StateFlow), a CoroutineScope.
Outputs: StateFlow<FloorChangeUiState> — {relativeAltitudeM,
  floorChangeDetected, floorDelta, totalFloorsChanged, hasBarometer}.
Important functions/classes: start()/stop() (lifecycle-tied, same
  pattern as every other repository here); totalFloorsChanged — a
  running signed count this class accumulates across confirmed floor
  changes (the pure detector only reports ONE change at a time, this
  repository is what turns that into a running trip total, same
  "pure math + stateful glue" split as everywhere else in this codebase).
Important concepts/assumptions: not all devices have a barometer —
  `hasBarometer` reflects `SensorRepository.hasBarometer()` honestly
  (CLAUDE.md Rule 13) rather than silently showing "no floor change,
  ever" on a device that structurally can't detect one.
Connected to: SensorRepository -> FloorChangeRepository -> MainActivity
  (debug screen only so far — see MainActivity.kt's entry)

android/app/src/main/kotlin/com/sih26168/idr/fusion/RoadSnap.kt
Status: REMOVED (2026-09-02) — was IMPLEMENTED (Round 2, 2026-08-28)
REAL BUG FOUND AND FIXED: the 2026-08-30 NOTE this entry used to carry
  ("a real duplication worth a deliberate follow-up decision") turned out
  to be an ACTIVE bug, not just latent duplication risk — MapScreen.kt's
  `currentLatDeg`/`currentLonDeg` computation called `RoadSnap.snap()` on
  `fusedState.fusedEastM`/`fusedNorthM`, but `map/MapConstraint.kt` (its
  own entry below) had ALREADY corrected that exact same field upstream
  inside `fusion/StateEstimator.kt`, before this screen ever saw it —
  every tick a route was active outside GNSS_AIDED, TWO independent
  nearest-segment projections were stacking, with slightly different
  tolerances (this class's 25m/45deg vs MapConstraint's 30m/45deg),
  risking a marker position neither implementation alone would have
  computed (and wasting a per-tick projection twice). The "deliberately
  does NOT correct fusion/StateEstimator.kt's fusedEastM/fusedNorthM"
  claim this entry used to make was accurate when written (2026-08-28,
  before MapConstraint existed) and became FALSE the moment
  MapConstraint started writing into that same field two days later —
  nobody reconciled the two Round 2 branches that each built this
  feature independently before both got merged. Consolidated on
  MapConstraint.kt (the architecturally cleaner spot — corrects the
  canonical output ONCE, upstream, matching PRD.md Section 16's own
  framing of map-constraint as "a correction... rather than the primary
  estimator"); this file and `tests/.../fusion/RoadSnapTest.kt` are
  deleted, and `ui/screens/MapScreen.kt`'s `currentLatDeg`/`currentLonDeg`
  now simply projects `fusedState.fusedEastM`/`fusedNorthM` (already
  MapConstraint-corrected) back to lat/lon — see that file's own updated
  comment. `fusion/DriftSummary.kt`'s measured drift number was and
  remains unaffected either way — `fusion/StateEstimator.kt` snapshots it
  from `drEastM`/`drNorthM` and the newly-reacquired GNSS fix BEFORE
  `PositionFusion.update()`/`MapConstraint` run that tick.

android/app/src/main/kotlin/com/sih26168/idr/dr/LowPassFilter.kt
Status: IMPLEMENTED (2026-08-30)
Purpose: PRD.md Section 11's "low-pass filtering... to remove high-
  frequency vibration noise before feature extraction" — capability "AI
  Speed & Vibration Filter"'s previously entirely-missing filter half. A
  single-pole (exponential moving average) low-pass filter, standard RC
  discretization (`alpha = dt / (rc + dt)`, `rc = 1/(2*pi*cutoffHz)`),
  computed per-sample so it stays correct at this project's real,
  non-constant ~10Hz sample rate.
  SCOPE DECISION (narrows PRD.md Section 11's literal "before feature
  extraction" wording, CLAUDE.md Rule 4/20): wired into
  dr/BaselineDeadReckoningRepository.kt (physics baseline) ONLY, NOT into
  ml/FeatureExtractor.kt's input. The already-trained, exported, and
  MEASURED ONNX velocity model (MAE 1.244 m/s) was trained on
  ml/feature_extraction.py's windowed statistics over RAW, unfiltered
  accel/gyro. Filtering that signal now, on-device only, without
  retraining + re-validating against a matched Python-side filter, would
  silently shift the live feature distribution away from the training
  distribution and could quietly regress the already-measured accuracy
  with no new measurement to catch it. Retraining on filtered features is
  legitimate future work, not done here. The physics baseline has no such
  constraint (no trained parameters to keep in sync with), so it's safe
  and self-contained there. The already-published physics+ZUPT baseline
  MAE/RMSE in ml/train_velocity_model.py comes from an independent PYTHON
  re-implementation over IO-VNBD, not this Kotlin class, so this addition
  doesn't retroactively change that number — it does mean this on-device
  path no longer matches that Python mirror exactly, which is now the
  honest, disclosed state.
Inputs: value (Double), dtSeconds (elapsed time since the previous sample).
Outputs: Double (the filtered value) — `<= 0.0` dtSeconds returns the raw
  value unfiltered and resets internal state (first sample / clock reset,
  same guard convention as BaselinePhysicsIntegrator.update()).
Important functions/classes: filter(), reset(). Pure Kotlin, no Android
  dependency, unit-testable (CLAUDE.md Rule 19) — see LowPassFilterTest.kt
  (7 cases: passthrough on first sample, dt<=0 reset behavior, DC/constant
  input stays constant, step response moves gradually not instantly,
  higher cutoff tracks a step faster, oscillating noise is attenuated,
  reset() clears state).
Connected to: dr/BaselineDeadReckoningRepository.kt instantiates SIX
  instances (accelEast/accelNorth/accelUp/gyroX/gyroY/gyroZ, one shared
  DEFAULT_VIBRATION_FILTER_CUTOFF_HZ=2.0Hz, engineering default
  unvalidated per CLAUDE.md Rule 13) -> filters WORLD-frame linear accel
  + raw gyro components AFTER the pothole discount (PotholeShockDetector
  needs the RAW vertical-accel spike to detect it at all — filtering
  first would blunt it) and BEFORE BaselinePhysicsIntegrator.update() /
  the ZUPT magnitude calculation. DeadReckoningState's published
  linearAccelMagnitudeMps2/gyroMagnitudeRadPerSec are now the FILTERED
  magnitude, not raw — a real, disclosed change (see that class's own
  doc and capture/DriveDataLogger.kt's updated field docs, since these
  feed scripts/analyze_drive_log.py's offline threshold validation).

android/app/src/main/kotlin/com/sih26168/idr/motion/LongitudinalMotionClassifier.kt
Status: IMPLEMENTED (2026-08-30)
Purpose: A DETERMINISTIC stand-in for PRD.md Section 14's `Accelerating`/
  `Braking` classes — same "no labeled classifier data yet" precedent as
  MotionStateClassifier.kt/PotholeShockDetector.kt/TurningDetector.kt/
  PhoneMovedDetector.kt. A simple sign/magnitude threshold on vehicle-
  frame FORWARD acceleration.
  ML-path only, same precedent MotionStateClassifier already establishes
  (CLAUDE.md Rule 3: physics-only baseline stays untouched by any
  ML-derived signal) — reads ml/MlVelocityRepository.kt's already-
  computed, alignment-corrected accelForwardMps2 (the SAME vehicle-frame
  forward-acceleration feature that also feeds the ONNX model), rather
  than reimplementing a separate vehicle-frame projection for the
  physics path (which has no alignment-corrected forward/lateral split
  at all).
Inputs: accelForwardMps2 (Float).
Outputs: LongitudinalMotionClassification(isAccelerating, isBraking) —
  mutually exclusive (opposite-sign thresholds), both false in between.
Important functions/classes: classify() — minLongitudinalAccelMps2
  defaults to 1.0 m/s^2 (~0.1g), engineering default, unvalidated against
  real labeled data (CLAUDE.md Rule 13). Stateless — no dwell/hysteresis,
  unlike TurningDetector/PhoneMovedDetector, since this only drives a
  display label (PRD.md Section 14: "context for the state machine...
  and non-holonomic constraint"), not a correction that would misfire
  badly on one noisy sample.
Pure Kotlin, no Android dependency, unit-testable (CLAUDE.md Rule 19) —
  see LongitudinalMotionClassifierTest.kt (6 cases).
Connected to: ml/MlVelocityRepository -> LongitudinalMotionClassifier ->
  MlVelocityUiState.isAccelerating/isBraking ->
  ui/screens/StatusOverlayContent.kt's motion label AND MainActivity's
  debug screen.

android/app/src/main/kotlin/com/sih26168/idr/map/MapConstraint.kt
Status: IMPLEMENTED (2026-08-30) — now the SOLE road-snap implementation
  (2026-09-02): `fusion/RoadSnap.kt`, a separately-built duplicate of
  this exact PRD.md Section 19 feature from an independent Round 2
  branch, was deleted after its own call site in `ui/screens/MapScreen.kt`
  was found double-snapping on top of this class's upstream correction —
  see `fusion/RoadSnap.kt`'s (now REMOVED) entry above for the full bug
  writeup.
Purpose: PRD.md Section 19's MVP-level map constraint — nearest-road-snap
  plus a heading-compatibility check, explicitly NOT a Hidden Markov
  Model or general map-matching engine (PRD.md Section 19/CLAUDE.md's
  "What Not To Build"). Scope reduction from PRD.md Section 19's two
  listed options: reuses the real OSM/OSRM route geometry this project
  already fetches for the active route's turn-by-turn directions
  (routing/RouteModels.kt's RouteResult.geometry) instead of separately
  fetching a general OSM road dataset for the whole demo area — no new
  network call, library, or offline dataset (CLAUDE.md Rule 2). Honest
  tradeoff: only does anything while a route is active.
Inputs: eastM/northM (WORLD-frame local meters, the pre-snap estimate),
  headingRad, a List<Segment> (road-geometry edges in the SAME local
  frame), maxSnapDistanceM (default 30.0 m) and maxHeadingDeltaRad
  (default 45 deg) — both engineering defaults, unvalidated against a
  real outdoor test drive (CLAUDE.md Rule 13).
Outputs: SnapResult(eastM, northM, snapped, distanceToRoadM) — returns
  the ORIGINAL point unsnapped if no segment is both close enough and
  heading-compatible.
Important functions/classes: snapToRoad() — for each segment, projects
  the point onto it (clamped to the segment, not its infinite line),
  keeps the nearest segment that also passes the heading check (checked
  MODULO PI/180 degrees, since a route polyline's point order says
  nothing about which way traffic flows — a vehicle heading along OR
  exactly against a segment's stored direction both count as
  compatible).
Pure Kotlin, no Android dependency, unit-testable (CLAUDE.md Rule 19) —
  see MapConstraintTest.kt (8 cases: basic snap, too-far rejection,
  perpendicular-heading rejection, opposite-heading acceptance,
  endpoint-clamping, nearest-of-multiple-segments regardless of list
  order, degenerate-segment skip, no-segments no-op).
Connected to: fusion/StateEstimator.kt -> MapConstraint.snapToRoad() ->
  FusedPositionUiState.fusedEastM/fusedNorthM (only while GNSS isn't
  already trusted, an active route + GNSS anchor exist, and DR speed is
  above a reliable-heading floor) — see StateEstimator.kt's own entry.

UI (Compose screens)
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: Live map + status header (GNSS state, speed, motion class,
  alignment confidence), vehicle-mode selector (PRD.md Section 22). See
  the new `ui/theme/`, `ui/components/`, `ui/map/`, `ui/screens/` entries
  immediately below for the full per-file writeup.
UPDATE (Round 2, 2026-08-28): `ui/screens/MapScreen.kt`'s
  `currentLatDeg`/`currentLonDeg` computation now applies `fusion/RoadSnap`
  (see its entry above) when an active route exists and the position
  isn't a live GNSS_AIDED fix — the last remaining PLANNED piece of
  PRD.md Section 22's screen (real road-snapping) is now real. `activeRoute`'s
  `remember` declaration was moved earlier in the composable (was
  previously declared with the rest of the search/routing state, further
  down) so this computation can read it.
UPDATE (2026-09-02, REAL BUG FIX): the `RoadSnap` call above was
  double-snapping on top of `map/MapConstraint.kt`'s own upstream
  correction to the exact same `fusedEastM`/`fusedNorthM` field — see
  `fusion/RoadSnap.kt`'s (now REMOVED) entry above for the full writeup.
  `currentLatDeg`/`currentLonDeg` now simply projects
  `fusedState.fusedEastM`/`fusedNorthM` (already MapConstraint-corrected)
  back to lat/lon via `GeoProjection.toLatLon` — no second snap. The
  `activeRoute` dependency was dropped from this `remember` block (no
  longer read here) but its `remember` declaration stays where it was
  moved to, since `LaunchedEffect(activeRoute)` still needs it to feed
  `onActiveRouteGeometryChanged` -> `StateEstimator.setActiveRouteGeometry()`
  (MapConstraint's own geometry input, unaffected by this change).

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
Connected to: MainActivity.kt -> IdrTheme -> MapScreen/HistoryScreen (IdrSensorScreen,
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
Connected to: Figma node 16-1601 (exported 2026-08-25) -> ic_recenter.xml ->
  StatusOverlayContent's recalibrate FloatingIconButton (via MapScreen)

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
  (used via StatusOverlayContent.kt -> MapScreen.kt, since 2026-08-30's DriveScreen removal)

ui/components/StatusChip.kt
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: Pill/chip primitive (Figma's pill-button convention,
  generalized into a colored-dot + label status indicator). Used for
  GNSS mode and the motion-state readout (FR10).
Connected to: StatusOverlayContent.kt (used by MapScreen.kt, since 2026-08-30's
  DriveScreen removal) -> StatusChip (GNSS mode, speed, motion state)

ui/components/FloatingIconButton.kt
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: Circular floating icon button — Figma's "Navigation Button"
  component (Simple Components frame's search/recenter/settings cluster
  on the map screen), inspected directly: solid `#383E42` circle, 44dp.
  Used once, for the manual recalibrate action (PRD Section 15/31/32).
Connected to: StatusOverlayContent.kt (used by MapScreen.kt, since 2026-08-30's
  DriveScreen removal) -> FloatingIconButton(ic_recenter) -> MainActivity's
  onRecalibrate -> alignment/AlignmentRepository.reset() (2026-08-30; previously
  MlVelocityRepository.resetAlignment(), removed)

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
Connected to: StatusOverlayContent.kt (used by MapScreen.kt, since 2026-08-30's
  DriveScreen removal) -> VehicleModeSelector -> (local state only, no downstream consumer yet)

ui/components/DriftSummaryCard.kt
Status: IMPLEMENTED (Slice 8, 2026-08-25)
Purpose: PRD.md Section 30 WOW-factor #4 — shows the REAL measured
  drift number fusion/DriftSummary.kt computed, on a GlassCard at the
  large (40dp, directly-inspected) radius. Dismissible — the caller
  (StatusOverlayContent) owns the dismissed/shown state, this component
  has no internal visibility logic.
Connected to: fusion/StateEstimator.kt (FusedPositionUiState.driftSummary) ->
  StatusOverlayContent.kt (used by MapScreen.kt, since 2026-08-30's
  DriveScreen removal) -> DriftSummaryCard

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
UPDATE (2026-08-31, STATUS_AND_ROADMAP.md Tier-1 item #2): the symmetric
  "GNSS reacquired" direction is now built too —
  `GnssReacquiredBanner`, a second public composable in this same file,
  sharing layout/dismiss behavior with `GnssModeChangeBanner` via a new
  private `ModeChangeBanner(title, message, backgroundColor, onDismiss,
  ...)` both now call (extracted once a second real caller existed).
  Solid `GnssAidedColor` background (the same color already used for the
  GNSS_AIDED status chip) marks it as the "good news" counterpart to
  `GnssModeChangeBanner`'s `DeadReckoningColor` alert. Triggered by
  StatusOverlayContent's new `showReacquiredBanner`, narrowed the same
  way as `showModeChangeBanner`: `fromMode == REACQUISITION && toMode ==
  GNSS_AIDED` (a GENUINE outage actually ending), excluding a
  `TRANSITION -> GNSS_AIDED` recovery blip that was never long enough to
  have shown the "lost" banner in the first place. Own
  `dismissedReacquisitionAtMs` state, same per-timestamp-dismiss pattern.
Connected to: gnss/GnssModeRepository.kt (GnssModeUiState.lastTransition) ->
  StatusOverlayContent.kt -> GnssModeChangeBanner / GnssReacquiredBanner

ui/map/StreetMapView.kt
Status: IMPLEMENTED (Slice 8b) — marker/heading smoothing +
  directional heading arrow, both merged 2026-08-30 from two
  independently-built implementations (see MERGE NOTE below)
Purpose: Real street-map base layer plus the current-position
  halo/ring/directional-arrow marker, outage-anchor dashed line, active-
  route line, and destination pin.
MIGRATED TO MAPBOX (2026-09-04, PRD.md Section 7 amendment): this file's
  entire osmdroid implementation was replaced with the Mapbox Maps SDK —
  the public composable signature (name, every parameter) is UNCHANGED,
  so every UPDATE note below this point describes the OLD osmdroid
  implementation, kept as historical record of the bugs/decisions that
  shaped the CURRENT marker/smoothing/follow-camera behavior, which this
  rewrite preserved feature-for-feature except where noted. Concretely,
  in the new implementation: `CurrentPositionOverlay`'s Canvas-drawn
  halo/ring/dot/arrow/anchor/pin became bitmaps rendered once and
  displayed via Mapbox's `PointAnnotationManager` (rotation is now a live
  `iconRotate` property Mapbox's GPU compositor applies — no per-frame
  canvas redraw needed, unlike the old arrow, which had to be); the
  osmdroid `Polyline`/dashed-line-via-`DashPathEffect` became a raw
  `GeoJsonSource`+`LineLayer` pair (the only style layer with real
  `line-dasharray` support) for the route line and outage-anchor line;
  `MapListener.onScroll` became `OnMoveListener` (gestures plugin) for
  follow/user-pan detection; `zoomToBoundingBox` became
  `cameraForCoordinates`+`setCamera`. `PositionSmoother` (below) is
  REUSED UNCHANGED — pure lat/lon/heading math, no osmdroid dependency,
  so the same per-frame chase loop and its whole bug history (below)
  still applies verbatim. Dark mode is now Mapbox's own `Style.DARK` (a
  real dark cartography style), replacing the old `INVERT_COLORS` filter
  hack the osmdroid version needed because its one tile source had no
  native dark variant. SCOPED OUT, not silently dropped: offline tile
  pre-fetch/download — see `ui/screens/MapScreen.kt`'s own UPDATE entry
  for why (it was already a permanent no-op against osmdroid's MAPNIK
  source before this migration).
VERIFIED ON A REAL DEVICE (2026-09-04, same S24 FE this project always
  tests on): installed via `installDebug`, launched, empty crash buffer
  (`adb logcat -b crash`) and no FATAL EXCEPTION in logcat. Screenshot
  confirms the dark Mapbox style genuinely rendering real Chennai street
  geometry (PUZHUTHIVAKKAM/MADIPAKKAM/ADAMBAKKAM labels, Inner Ring Road),
  with the Mapbox wordmark + attribution control visible (proof it's
  really Mapbox, not a blank/fallback view), scale bar, search bar, and
  the ported recenter button all laid out correctly. Some
  ClassNotFoundException System.err spam for
  com.mapbox.common.location.*/MovementMonitor*/BatteryMonitor* classes
  appears at startup -- Mapbox's own optional-capability probing
  (background location/telemetry extras this project didn't add), caught
  and swallowed internally by the SDK, not a real error; app is stable.
REAL BUG FOUND + FIXED (2026-09-04, user report: "I cant see the current
  location indicator blue icon ... the path does not show orange line"):
  confirmed the marker/route-line/anchor-line gap above was NOT just "no
  GNSS fix yet" — reproduced live on the real search -> destination ->
  route flow. Root cause: TWO separate `LaunchedEffect`s both called
  `mapboxMap.loadStyle(...)`, one keyed on `mapView` (with the real setup
  callback — addSource/addLayer for the route/anchor lines,
  createPointAnnotationManager for the marker) and a second, keyed on
  `isDarkTheme`, with NO callback. Compose runs every `LaunchedEffect` at
  least once on first composition regardless of its key, so BOTH fired on
  launch — and since Mapbox's `loadStyle` fully REPLACES the style
  (sources, layers, and the annotation plugin's own internal layer all
  get torn down), whichever landed second silently wiped out everything
  the first had just added. No error anywhere: base map tiles render
  fine either way (a plain style load always succeeds), only the CUSTOM
  layers were the casualty — which is exactly why "the map renders" but
  "nothing on it shows" looked contradictory until traced through.
  FIXED by keying the WHOLE style setup (load + every addSource/addLayer/
  createPointAnnotationManager call) on `isDarkTheme` alone, so a theme
  change correctly REDOES the setup instead of losing it, with only one
  `loadStyle` call site left in the file. Stale annotation handles
  (`currentPositionAnnotation`/`anchorAnnotation`/`destinationAnnotation`)
  are reset to null inside the same callback so a later theme change
  creates fresh ones instead of calling `.update()` on orphaned
  references from the torn-down style.
VERIFIED ON A REAL DEVICE, same S24 FE, same day: rebuilt, reinstalled,
  relaunched — the current-position marker (blue ring + directional
  arrow) now renders on the real MAP tab with a live DEAD_RECKONING mode
  reading. Repeated the user's exact real flow (search -> tapped a recent
  destination -> route computed) and the real OSRM route now renders as
  a solid red line correctly following actual roads through
  Puzhuthivakkam/Adambakkam/Alandur toward Nandambakkam — not a straight
  line, genuinely road-snapped geometry from RoutingRepository's OSRM
  call. Empty crash buffer, no FATAL EXCEPTION. NOT YET exercised: the
  outage-anchor dashed line specifically (needs a real DEAD_RECKONING
  transition with a set anchor point, not just the mode reading DR),
  heading-up camera rotation, and the follow/recenter gesture logic —
  `ui/screens/MapVerificationScreen.kt` (new file, see its own entry) was
  built specifically to exercise these without waiting on a real GNSS
  outage, and is the next thing to click through.

ALL REMAINING PIECES VERIFIED ON A REAL DEVICE (2026-09-04, same
  session, via MapVerificationScreen): two more real bugs found and
  fixed, one false alarm ruled out.
  1. REAL BUG: the marker-arrow's iconRotate was set directly from raw
     heading with no correction -- Mapbox's icon-rotate turned out to be
     VIEWPORT-relative (a fixed on-screen direction), not map-relative,
     confirmed by testing heading-up mode at heading=180: the arrow
     pointed DOWN (wrong) instead of UP (correct, since the map itself
     rotates 180 in heading-up mode, putting that heading at the top of
     the screen). Same correction the osmdroid version's marker rotation
     already needed, just the opposite sign -- Mapbox's bearing
     convention already matches "heading points up" directly, no
     negation needed unlike osmdroid's setMapOrientation. Fixed by
     subtracting the current smoothed map bearing from the target
     heading before setting iconRotate. Re-verified: arrow now stays
     pointing up correctly through the whole simulated heading sweep.
  2. REAL BUG: the smoothing loop's bearing-only setCamera call (for
     heading-up rotation) was UNGUARDED by isProgrammaticMove, unlike
     the center-recenter calls. Mapbox's OnMoveListener turned out to
     fire for ANY camera change, not just genuine touch gestures (this
     file's earlier assumption that the guard was redundant
     belt-and-braces was wrong) -- every ~60fps bearing update was
     re-triggering onMoveBegin and forcing isFollowingLocation back
     to false the frame after the recenter button set it true, making
     the button appear broken. Fixed by wrapping that call in the same
     guard. Re-verified with temporary Log.d instrumentation (removed
     after use) confirming onMoveBegin no longer misfires during
     programmatic bearing updates.
  3. FALSE ALARM, not a code bug: after fix #2, the recenter button
     STILL appeared unresponsive in manual testing -- traced with
     uiautomator dump to a testing-methodology error, not application
     code: the button's real on-screen bounds were nowhere near where
     screenshot-pixel-math had placed them (a scaling error converting
     the downscaled screenshot's coordinates back to real device
     pixels). Tapping the button's ACTUAL bounds worked correctly the
     whole time -- isFollowingLocation flips true, the button
     disappears, and the camera snaps back to the marker. No code
     change was needed for this one; recorded here so the false lead
     isn't repeated.
  Final state: `:app:testDebugUnitTest` and `:app:assembleDebug` both
  pass; installed fresh, empty crash buffer, no FATAL EXCEPTION. All six
  pieces (marker, directional arrow + rotation, route line, destination
  pin, outage-anchor line, heading-up camera rotation, follow/recenter)
  confirmed working via MapVerificationScreen; marker + route line
  additionally confirmed on the real MapScreen flow (search ->
  destination -> route) earlier the same session.
UPDATE (Round 2 UI smoothness pass, 2026-08-28): the marker position and
  map rotation (`setMapOrientation`) used to be set directly inside the
  `AndroidView` `update` block, which only re-runs on a real GNSS/DR tick
  (~5-10Hz) — visibly stepping/teleporting rather than gliding, since the
  display itself refreshes at ~60Hz. `update` now only writes
  `targetPosition`/`targetHeadingDeg` (MutableState); a separate
  `LaunchedEffect(mapView) { while(true) { withFrameNanos {} ... } }` loop
  polls them every display frame and chases them via
  `ui/map/PositionSmoother` (see its own entry below), setting
  `overlay.position`/`mapView.setMapOrientation()` from the SMOOTHED
  value. The recenter-button's `onClick` snaps to `targetPosition` (the
  TRUE position) rather than `overlay.position` (now a lagged cosmetic
  value). The follow/pan recenter logic (`isFollowingLocation`,
  `MIN_RECENTER_DISTANCE_M`, `lastCenteredPoint`) is UNCHANGED and still
  keys off the raw currentLatDeg/currentLonDeg, not the smoothed display
  value — camera recentering decisions stay based on real movement.
UPDATE (STATUS_AND_ROADMAP.md Tier-1 item #1): a `markerHeadingDeg:
  Float?` param (fed from MapScreen's already-computed `headingDeg`, not
  gated to `isNavigating` the way the map-rotation `headingDeg` param is)
  rotates the marker into a directional chevron/arrow
  (`CurrentPositionOverlay.iconRotationDeg`, drawn in place of the plain
  dot) instead of a non-directional dot. A `targetMarkerHeadingDeg`
  MutableState feeds this into the SAME per-frame smoothing loop above
  (a `stepPosition`/`stepHeading` sibling, not a THIRD independent
  animation path) — the rotation subtraction (`markerHeadingDeg -
  mapOrientationDeg`, so the arrow always points at the REAL device
  heading regardless of whether the map itself is currently north-up or
  heading-up-rotated) has to use the SMOOTHED `mapOrientationDeg` that
  loop just applied, not a value computed synchronously in `update`,
  since the map's actual on-screen rotation lags the raw target by
  design. UNVERIFIED ON A REAL DEVICE (CLAUDE.md Rule 13), same caveat
  the pre-existing map-rotation `headingDeg` param already carried.
UPDATE (Round 2, 2026-08-28, user report: "glitchy buffer... large pixel
  tiles of some random places" after pressing Go, needing a manual
  recenter tap to fix): REAL BUG — the `LaunchedEffect(routeGeometry)`
  block's `zoomToBoundingBox()` call (route-preview zoom-to-fit) fired
  WITHOUT the `isProgrammaticMove` guard the marker-recenter logic
  already used, so osmdroid's own onScroll/onZoom callbacks (fired BY
  that call itself) were misclassified by the `MapListener` as a REAL
  user gesture, permanently flipping `isFollowingLocation` off the
  moment a route was computed. Fixed by wrapping the call in the SAME
  `isProgrammaticMove` guard, AND switching `zoomToBoundingBox`'s
  `animated` argument from true to false — an ANIMATED call fires its
  scroll/zoom callbacks asynchronously over several frames, after a
  synchronously-reset flag would already be back to false, so only an
  instant jump lets the guard actually cover every callback it causes.
UPDATE (Round 2, 2026-08-28, user report: "line terminating vaguely" —
  no destination marker): `CurrentPositionOverlay` gained a `destination:
  GeoPoint?` field (set from `routeGeometry?.lastOrNull()` in `update` —
  the route's own last geometry point IS the destination, no separate
  geocode lookup needed) and a `drawPin()` private method — a classic
  map-pin silhouette (circular head + triangular tail) drawn with Canvas
  primitives. The tail's POINT (not the head's center) lands exactly on
  the destination coordinate, matching how every real map app anchors a
  pin at its tip.
MERGE NOTE (2026-08-30): the two Round 2 branches independently built
  DIFFERENT marker-smoothing mechanisms — this file's own
  `ui/map/PositionSmoother`-based continuous 60fps chase loop (above),
  vs. a separate `LaunchedEffect(currentLatDeg, currentLonDeg)` one-shot
  300ms tween (`MARKER_ANIMATION_DURATION_MS`) that also wrote directly
  to `overlay.position`. Kept only the PositionSmoother version — it
  also smooths map rotation (the tween didn't) and already covered the
  directional-arrow rotation math once `targetMarkerHeadingDeg` was
  folded in; running both would have left two writers fighting over
  `overlay.position` every frame. The tween's `LaunchedEffect` and the
  now-unused `MARKER_ANIMATION_DURATION_MS` constant were deleted.
Connected to: ui/screens/MapScreen.kt (currentLatDeg/currentLonDeg,
  headingDeg, markerHeadingDeg) -> StreetMapView -> Mapbox
  PointAnnotationManager/GeoJsonSource+LineLayer (osmdroid's
  CurrentPositionOverlay/MapListener/Polyline no longer exist here — see
  MIGRATED TO MAPBOX note above); ui/map/PositionSmoother -> StreetMapView.kt

ui/map/TrackCanvas.kt
Status: REMOVED (2026-08-30) — was IMPLEMENTED (Slice 8, 2026-08-25)
Purpose (historical): The map layer for the DRIVE tab — a Compose
  `Canvas`, NOT a real map SDK (decision made with the user during
  planning: zero new dependencies, works fully offline for a demo about
  GNSS-DENIED navigation, plots directly in the local East/North meters
  fusion/StateEstimator already produces — no new geodesy needed).
  Styling borrowed Google Maps' LAYOUT pattern (dot-with-ring current
  position, accuracy halo, polyline route) but rendered entirely in the
  Figma-extracted dark palette. Deleted along with ui/screens/
  DriveScreen.kt (its only caller) once ui/screens/MapScreen.kt's real
  street map + routing made the abstract grid redundant — MapScreen
  already showed the same StatusOverlayContent, so the DRIVE tab added
  no capability MapScreen didn't already have (user-reported: "the drive
  page ... is doing nothing").

android/app/src/main/kotlin/com/sih26168/idr/ui/map/PositionSmoother.kt
Status: IMPLEMENTED (Round 2, 2026-08-28)
Purpose: Frame-rate marker/heading smoothing for `ui/map/StreetMapView.kt`
  (originally also shared by `ui/map/TrackCanvas.kt`'s Drive-tab abstract
  grid — see that file's REMOVED entry above; that consumer is gone, this
  class isn't). GNSS/DR position updates
  arrive at ~5-10Hz, but the display refreshes at ~60Hz, so drawing
  directly from the latest tick made the marker/anchor/map-rotation
  visibly step in small discrete jumps instead of gliding. Pure Kotlin,
  no Android/Compose dependency, unit-testable (CLAUDE.md Rule 19 — a
  filter, even a cosmetic one, gets a test).
Inputs: stepPosition(targetLatOrEastM, targetLonOrNorthM) — units-
  agnostic (lat/lon degrees for StreetMapView, local meters for
  TrackCanvas, same exponential-smoothing math either way);
  stepHeading(targetHeadingDeg) — degrees, 0-360.
Outputs: the smoothed position/heading, one step closer to the target
  each call.
Important functions/classes: stepPosition() — exponential ("chase")
  smoothing: `new = prev + (target - prev) * smoothingFactor`
  (default 0.25/frame, tuned by feel per CLAUDE.md Rule 13, not measured
  — ~94% of any gap closed within ~10 frames/~166ms at 60fps); the
  FIRST-EVER target snaps directly (nothing to glide from yet).
  stepHeading() — same idea, but interpolates CIRCULARLY via sin/cos
  (duplicates the technique fusion/HeadingFusion.kt already uses for its
  own, UNRELATED, REACQUISITION-blend concern — accepted small
  duplication rather than coupling the ui/map package to fusion/'s
  private internals for two ~10-line functions, CLAUDE.md Rule 2's
  "smallest practical stack" cuts both ways); reset().
Important concepts/assumptions: deliberately NOT a Kalman filter or
  predictive smoothing — a cosmetic display concern, not a position-
  estimation one (the real fusion math is untouched, lives entirely in
  fusion/PositionFusion.kt/HeadingFusion.kt). HONEST LIMITATION: after a
  long gap with no target updates (e.g. the Pause button), the next real
  target can be far from the last displayed one, producing a brief fast
  slide rather than an instant snap — not specially handled, an accepted
  minor cosmetic edge case.
Connected to: ui/map/StreetMapView.kt -> PositionSmoother
Unit tests: tests/.../ui/map/PositionSmootherTest.kt — null target
  returns null; first-ever target snaps; a second step closes the gap by
  exactly the configured smoothingFactor (hand-derived); repeated
  identical-target steps converge; heading interpolates the SHORT way
  across the 0/360 wrap boundary (same wrap case HeadingFusionTest
  covers for its own class); reset() clears both position and heading
  state so the next step snaps again.

ui/screens/DriveScreen.kt
Status: REMOVED (2026-08-30) — was IMPLEMENTED (Slice 8, 2026-08-25)
Purpose (historical): Slice 8's primary screen (PRD.md Section 22's
  "single main screen") — composed TrackCanvas as the base layer, plus
  StatusOverlayContent (StatusChip for GNSS mode/speed/motion state, an
  alignment readout — FR10), VehicleModeSelector, the recalibrate
  FloatingIconButton, and DriftSummaryCard. Superseded by ui/screens/
  MapScreen.kt (Slice 8b, real street tiles + routing) which reuses the
  same StatusOverlayContent — see TrackCanvas.kt's entry above for the
  removal reason. AppTab.DRIVE was removed from ui/components/
  BottomNavBar.kt at the same time; MainActivity now defaults to
  AppTab.MAP and treats MAP as the back-button "home" tab.
```

```
ui/screens/MapScreen.kt
Status: IMPLEMENTED
Purpose: The MAP tab (now the app's default/home tab — see
  MainActivity.kt entry) — real OpenStreetMap tiles
  (ui/map/StreetMapView), destination search + routing
  (routing/GeocodingRepository Nominatim + routing/RoutingRepository
  OSRM), and turn-by-turn navigation, using StatusOverlayContent for the
  GNSS/DR status readout; search/routing is layered on top as its own
  state machine (idle -> destination selected -> route active ->
  navigating).
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
Automatic tile prefetch (added 2026-08-29, user-requested "smoother
  working"): the instant `RoutingRepository.computeRoute` succeeds
  (Start button's onClick), silently calls
  `routing/OfflineRouteCache.prefetchLiveZoomTiles` on `route.geometry`
  before the routing UI even updates — separate from, and lighter than,
  ActiveRouteCard's existing explicit "Download offline" button (see
  OfflineRouteCache.kt's own doc for the single-zoom-level-vs-full-range
  tradeoff this was deliberately scoped against, a user decision). Best-
  effort: a null `mapViewRef` (shouldn't happen — the user is looking at
  the map to reach this button — but not asserted) just skips the
  prefetch silently, same "optimization, not a promise" spirit as that
  function's own silent-failure behavior. Verified on-device by clearing
  the app's osmdroid tile cache, computing one route, and confirming via
  a pulled+inspected cache.db that tile rows went from 0 to 24 with the
  explicit download button never touched.
REAL CRASH FOUND + FIXED (2026-08-29, user report: "if i start the
  destination the app is closing"): the automatic prefetch above turned
  out to crash the whole app on EVERY route computation.
  `CacheManager.downloadAreaAsync()` runs on an AsyncTask and throws
  `TileSourcePolicyException` from INSIDE that background task's
  `doInBackground()` when the tile source's policy rejects bulk
  downloads — the only try/catch `OfflineRouteCache.downloadTiles` had
  (around the `CacheManager(mapView)` constructor call) can never catch
  that, since the exception happens later, asynchronously, on the
  AsyncTask's own thread. Root cause confirmed via `adb logcat -b crash`
  + decompiling the bundled osmdroid-android-6.1.20 runtime jar with
  `javap`: `TileSourceFactory.MAPNIK` is built with
  `new TileSourcePolicy(2, 15)` — flags=15 sets `FLAG_NO_BULK`, so
  osmdroid PERMANENTLY refuses bulk/CacheManager downloads against
  MAPNIK, honoring OpenStreetMap's own real tile usage policy
  (operations.osmfoundation.org/policies/tiles — "no bulk downloading"
  against the free tile.openstreetmap.org server). This was silently
  broken since `ui/map/StreetMapView.kt`'s 2026-08-26 CARTO->MAPNIK
  switch — ActiveRouteCard's pre-existing explicit "Download offline"
  button has carried this EXACT SAME crash ever since, just apparently
  never tapped/tested against MAPNIK until the automatic prefetch above
  started exercising this code path again on every route. Fixed in
  `routing/OfflineRouteCache.kt`'s `downloadTiles` by checking
  `OnlineTileSourceBase.getTileSourcePolicy().acceptsBulkDownload()`
  BEFORE calling `downloadAreaAsync` (mirroring CacheManager's own
  internal `preCheck()`), failing synchronously via `onFailed()` instead
  of crashing asynchronously. HONEST CONSEQUENCE (CLAUDE.md Rule 13):
  since this restriction is PERMANENT for MAPNIK, both
  `downloadRouteTiles` and `prefetchLiveZoomTiles` now always/only call
  `onFailed()` and never actually cache anything — bulk tile pre-fetch
  cannot work AT ALL against the current tile source. MapScreen.kt's
  explicit-button failure message was also corrected from "check
  network" (implies a retriable transient cause) to "isn't available
  for this map source right now" (the real, permanent cause). NOT YET
  DECIDED: whether to keep this as a permanently-safe no-op, remove the
  offline-download feature entirely, or switch to a tile source whose
  policy allows bulk download — flagged to the user, not decided here.
  Verified on-device: reproduced the crash pre-fix (`ps` showed the
  process gone; the crash buffer had the full
  `TileSourcePolicyException` stack trace), then confirmed post-fix that
  computing the same route succeeds end-to-end (ActiveRouteCard renders
  a real distance/duration/steps) with the process still alive and the
  crash buffer empty.
UPDATE (2026-09-04, PRD.md Section 7 Mapbox migration): `mapViewRef`'s
  type changed from `org.osmdroid.views.MapView` to `com.mapbox.maps.MapView`
  (StreetMapView.kt's `onMapViewReady` now hands back the Mapbox
  instance). Two ripples from that: (1) the navigation-start zoom-in now
  calls `mapboxMap.setCamera(CameraOptions.Builder().zoom(19.0).build())`
  instead of osmdroid's `controller.setZoom`; (2) BOTH the automatic
  prefetch (above) and ActiveRouteCard's explicit "Download offline"
  button's tile-download call were REMOVED, not adapted — they were built
  against osmdroid's `CacheManager`, which this screen no longer has a
  reference to, and (per the crash-fix finding directly above) that call
  path could never succeed anyway. The explicit button now just persists
  the route JSON (`OfflineRouteCache.saveRoute`, unaffected — no
  MapView/tiles involved) and shows an honest "isn't implemented for the
  Mapbox map yet" status (CLAUDE.md Rule 13) rather than silently doing
  nothing or calling a function with a type it can't provide. Building a
  real Mapbox-native offline system (`OfflineManager`/`TileStore`) is
  explicitly NOT done here — a separately-scoped feature, not a port.
UPDATE (2026-09-05, PRD.md Section 7 amendment, Mapbox Navigation SDK,
  developer override): `isNavigating` now means the FULL-SCREEN
  `ui/screens/ActiveGuidanceScreen.kt` overlay is showing (drawn last,
  own MapView) — the old inline `NavigationInstructionCard`/
  `NavigationEtaBar` overlay on top of THIS screen's `StreetMapView` is
  REMOVED (both components and the `routeProgress`/`RouteProgress.compute`
  local-East/North-frame calculation that fed them are now dead code,
  deleted rather than left orphaned). `ActiveRouteCard`'s "Go" button no
  longer just flips `isNavigating` — it first calls
  `nav/NavigationSessionRepository.requestRoute` (a REAL Mapbox
  Directions API call, separate from the OSRM route already shown in the
  card — see that file's header doc for why) and only enters the overlay
  once that route is ready; a failure surfaces via the existing
  `routingError` text instead of silently doing nothing. New "Free
  Drive" button (idle state, no destination needed) starts a Mapbox trip
  session with no route via the same overlay. HONEST CONSEQUENCE, not
  silently dropped: the DR-aware route progress this screen used to
  compute (`RouteProgress.compute` projecting the route into the local
  East/North frame so "distance remaining" kept counting through a GNSS
  outage via physics/ML dead reckoning, not just live GNSS) does NOT
  carry over to active guidance — Mapbox's own `RouteProgress` (now
  driving the ETA text in `ActiveGuidanceScreen.kt`) is GNSS-only, same
  as any standard nav app. The route PREVIEW state (before tapping "Go")
  is completely unaffected by any of this.
REAL BUG FOUND + FIXED (2026-09-04, user report: "the moment I entered
  indoors another current location arrow was duplicated and was
  stationary while the other was moving"): the 2026-09-05 update above
  assumed `ui/map/StreetMapView.kt`'s own `MapView` "keeps running
  underneath, unseen" once `ActiveGuidanceScreen`'s full-screen overlay
  is showing — true for ordinary Compose content, but FALSE for two live
  Mapbox `MapView`s stacked in the same composition tree. Each owns its
  own GPU-composited surface (TextureView/SurfaceView), and those don't
  reliably respect Compose's z-order — both were rendering
  simultaneously. StreetMapView's marker kept tracking this app's own
  DR-fused position (still moving indoors, GNSS outage or not), while
  ActiveGuidanceScreen's separate Mapbox-SDK location puck tracks raw/
  enhanced GPS (frozen the instant GNSS is lost indoors) — exactly the
  "one stationary, one moving" duplicate reported. Fixed with a
  `LaunchedEffect(isNavigating, isFreeDriving, mapViewRef)` that sets the
  underlying `mapViewRef`'s Android View `visibility` to `GONE`/`VISIBLE`
  directly, rather than relying on Compose draw order — the `MapView`
  itself is never disposed, so its camera position/annotations survive
  the overlay opening and closing exactly as the original "keep it
  running underneath" intent wanted. NOT YET VERIFIED on-device after
  this specific fix (CLAUDE.md Rule 13) — the original duplicate-marker
  report was from a real on-device indoor test; this fix needs the same
  before being called done.
Connected to: routing/GeocodingRepository, routing/RoutingRepository,
  routing/OfflineRouteCache, fusion/GeoProjection, ui/screens/SearchScreen
  (new, opened on demand) -> ui/components/ActiveRouteCard;
  nav/NavigationSessionRepository -> ui/screens/ActiveGuidanceScreen
  (full-screen overlay, isNavigating || isFreeDriving)
```

```
ui/screens/MapVerificationScreen.kt
Status: IMPLEMENTED (new file, 2026-09-04)
Purpose: TEST TOOLING ONLY (CLAUDE.md Android Rule 8 / PRD.md Section
  31/32's "controlled simulated" testing path) — exercises
  `ui/map/StreetMapView.kt`'s Mapbox rendering (position marker,
  directional arrow, outage-anchor dashed line, route line, destination
  pin, heading-up camera rotation, follow/recenter gesture logic) with
  entirely client-side simulated data, so it can be verified on a real
  device without waiting on a real GNSS fix or an outdoor drive. Built
  the same day the real marker/route-line bug above was found and fixed,
  specifically so the remaining unverified pieces (outage-anchor line,
  heading-up rotation, follow/recenter) don't need another real bug
  report to surface — click through it instead.
Important concept: simulates a small fixed SQUARE LOOP (not a straight
  line) so heading changes at each corner exercise the marker-arrow
  rotation and, when heading-up is toggled, the map bearing rotation,
  without needing any button taps — movement alone covers it. Never
  reads or writes any real GNSS/DR/fusion state; `ui/screens/MapScreen.kt`'s
  real pipeline is completely untouched by this file (CLAUDE.md Rule 8's
  "clearly separated from the shipped demo path" — verified by
  construction, this file has no import of any real repository).
Reached only via the existing debug screen (`MainActivity.kt`'s
  `IdrSensorScreen`, same "Debug" entry point already used for the
  sensor-recording/drive-logging test tools) through a new
  `onShowMapVerification` callback and a `showMapVerificationScreen`
  boolean, mirroring the existing `showDebugScreen` pattern exactly
  (including its own `BackHandler`).
Connected to: MainActivity.kt (IdrSensorScreen's "Verify Mapbox UI
  (simulated)" button) -> MapVerificationScreen -> ui/map/StreetMapView
```

```
nav/NavigationSessionRepository.kt
Status: IMPLEMENTED (new file, 2026-09-05)
Purpose: Owns the Mapbox Navigation SDK's active-guidance/free-drive
  session (PRD.md Section 7 2026-09-05 amendment, developer-requested
  override of CLAUDE.md Rule 2/4's normal discussion-first process — the
  developer's own words were "disregard claude.md"; the tradeoffs
  (separate billing SKUs, separate SDK footprint, explicitly a "general
  maps competitor" capability) were surfaced first and the developer
  confirmed proceeding — see PRD.md's dated amendment for the full
  record). A singleton object, not a per-screen instance, matching
  [MapboxNavigationApp]/[MapboxNavigation]'s own process-wide-singleton
  design (one native navigator per app) rather than fighting that shape.
REAL ROUTING-BACKEND SPLIT (CLAUDE.md Rule 9 — naming this explicitly,
  it's a non-obvious boundary): [requestRoute] calls Mapbox's own
  Directions API via this SDK, NOT `routing/RoutingRepository.kt`'s
  OSRM call — voice/banner/lane instruction TEXT is generated
  server-side by Mapbox's routing engine and does not exist in a plain
  OSRM response. The OSRM-based route PREVIEW (search,
  distance/duration, `ActiveRouteCard`) is completely unchanged; only
  entering active guidance (`startActiveGuidance`) or free-drive
  (`startFreeDrive`) touches this file.
Real API surface confirmed by decompiling the actual downloaded SDK
  jars (`com.mapbox.navigationcore` v3.30.0), not guessed from
  memory or an outdated doc page — see the REAL API SURFACE NOTE below.
REAL API SURFACE NOTE: this SDK generation (v3) has NO single
  "NavigationView"/"Drop-In UI" widget — that class only exists in an
  older v2 doc page that was initially (incorrectly) assumed to still
  apply. v3's real architecture is individual components
  (`MapboxManeuverView` for banner+lane, the `voice` module's
  `MapboxSpeechApi`/`MapboxVoiceInstructionsPlayer`, the core
  `MapboxNavigation` session) assembled by the app — this file is that
  assembly.
REAL BUG FOUND + FIXED (2026-09-05, before first successful install):
  `MapboxVoiceInstructionsPlayer`'s second constructor parameter is a
  LANGUAGE CODE ("en"), not an access token — copied
  `MapboxSpeechApi`'s constructor shape (where the second param really
  IS a token) onto this class without checking, and it crashed on every
  launch (`NullPointerException` inside Android's own
  `TextToSpeech.isLanguageAvailable` trying to parse the Mapbox token
  string as a `Locale`). Fixed by passing `"en"`.
Automatic rerouting on off-route (developer-requested feature) is
  Mapbox's own built-in `MapboxRerouteController` — `onAttached`
  confirms `setRerouteEnabled(true)` rather than assuming the SDK
  default (CLAUDE.md Rule 13). NOT independently verified triggering a
  real reroute — needs an actual off-route deviation, which this
  session's stationary/simulated testing could not produce.
Connected to: MainActivity.kt (MapboxNavigationApp.setup, initIfNeeded)
  -> ui/screens/MapScreen.kt (requestRoute/startActiveGuidance/
  startFreeDrive) -> ui/screens/ActiveGuidanceScreen.kt (consumes
  `state` StateFlow + `locationProvider`)
```

```
ui/screens/ActiveGuidanceScreen.kt
Status: IMPLEMENTED (new file, 2026-09-05)
Purpose: Mapbox Navigation SDK active-guidance/free-drive full-screen
  overlay (PRD.md Section 7 2026-09-05 amendment). Simpler than the
  idle/route-preview map (`ui/map/StreetMapView.kt`) in one respect —
  the current-position marker is the Maps SDK's own built-in location
  puck (`mapView.location`), fed by
  `NavigationSessionRepository.locationProvider`, not a custom
  bitmap/annotation — but the route line IS drawn (same
  GeoJsonSource+LineLayer pattern StreetMapView.kt uses, CtaRed to
  match; see the REAL BUG note below for why this needed a fix).
  `MapboxManeuverView` (an Android View, wrapped via `AndroidView`)
  handles banner text + lane guidance as one bundled component. Voice
  announcements play automatically via
  `NavigationSessionRepository`'s own observers — nothing in this file
  triggers audio directly.
REAL BUG FOUND + FIXED (2026-09-05, user report: "the orange line path
  to destination is not coming... just a free drive window with the
  blue circle"): this screen originally drew NO route line at all — a
  deliberate scope-cut documented as "simpler than StreetMapView.kt",
  but that read as a real broken feature to a user starting guidance,
  not an acceptable simplification. Fixed by adding
  `NavUiState.routeGeometryPoints` to `nav/NavigationSessionRepository.kt`
  (decoded once from the active route's polyline6 geometry in
  `startActiveGuidance`, see that file's entry) and drawing it here.
  SECOND REAL BUG caught while fixing the first, same race class
  `ui/map/StreetMapView.kt`'s style-load bug already was: the route's
  geometry is set in the repository BEFORE this screen even composes,
  so a naive `LaunchedEffect(navState.routeGeometryPoints)` alone could
  run (and find `getSource(...)` still null, since style loading is
  async) BEFORE the style-load callback that creates the source has
  fired — and since that value never changes again afterward, nothing
  would retry. Fixed by ALSO seeding the source's initial geometry
  directly from the live repository state (`NavigationSessionRepository.
  state.value`, not the possibly-stale captured `navState`) inside the
  style-load callback itself, so a route present at screen-open time is
  drawn regardless of which finishes first.
VERIFIED ON A REAL DEVICE (2026-09-05, same session, same S24 FE): real
  guidance to Voltas Colony, Chennai — the route line now renders
  correctly, visibly following the actual road geometry (traced down
  13th extension Street then turning, matching the "Turn right, 200 ft"
  banner shown at the same moment).
Important concept: `isFreeDrive` hides the maneuver/ETA UI (free-drive
  has no route) and shows only the live map-matched position + Exit.
  `DisposableEffect` calls `NavigationSessionRepository.stop()` on
  dispose as a defensive backstop — `ui/screens/MapScreen.kt`'s own
  `onExit` callback also calls `stop()` before this composable leaves
  composition, so it's called twice in the normal exit path (harmless,
  idempotent) but guarantees the session tears down even if this screen
  is ever dismissed some other way.
VERIFIED ON A REAL DEVICE (2026-09-05, same S24 FE): free-drive —
  real location puck + compass control visible, live map-matched
  position tracked as the phone (device, stationary but with GNSS
  drift/dead-reckoning still running underneath) moved between ticks,
  clean Exit back to the normal MapScreen with the GNSS/DR pipeline
  unaffected. Active guidance on a real destination (Voltas Colony,
  Chennai) — real Mapbox-routed "Turn right, 100 ft" banner instruction
  rendered by `MapboxManeuverView` with the correct turn icon, live ETA
  text (3834 m / 14 min) from Mapbox's own `RouteProgress`, clean Exit.
  Empty crash buffer both times. See `nav/NavigationSessionRepository.kt`'s
  own entry for what's NOT independently verified (audible voice, a
  real reroute trigger).
UPDATE (2026-09-05, same session, developer-requested): added
  follow/recenter — same `OnMoveListener`/`isFollowingLocation`/
  `isProgrammaticMove` pattern `ui/map/StreetMapView.kt` uses, applied
  correctly from the start this time (the per-tick auto-follow
  `setCamera` call is guarded, learning directly from
  StreetMapView.kt's own dated bug where an unguarded bearing-only
  `setCamera` call fought its recenter button). Recenter button placed
  `BottomEnd` (not `TopEnd`, which would collide with Mapbox's own
  compass control there by default). VERIFIED ON A REAL DEVICE: panned
  the map manually mid-guidance, recenter button appeared, tapping it
  snapped the camera back and the button disappeared — worked correctly
  on the first try (no repeat of StreetMapView's bug).
UPDATE (2026-09-05, same session, developer-requested: "make it an
  arrow pointing towards the direction the front of the phone is
  facing"): REAL BUG FOUND — `puckBearingEnabled`/`puckBearing` alone
  only select WHICH heading source rotates the puck; they don't change
  its SHAPE, and the plugin's actual default `locationPuck` is a plain
  non-directional dot regardless. Fixed with an explicit
  `mapView.location.locationPuck = createDefault2DPuck(withBearing = true)`,
  Mapbox's own bearing-aware 2D puck (a dot + arrow/chevron indicator).
  `PuckBearing.HEADING` (device compass, already set — not `.COURSE`,
  GPS direction of travel) is what "front of the phone" actually means
  here. VERIFIED ON A REAL DEVICE: the puck now shows a visible
  directional arrow attached to the dot during active guidance.
REAL BUG FOUND + FIXED (2026-09-05, user report: "the gnss aided ->
  transition -> dead reckoning -> reacquisition -> gnss aided card is
  gone, add that along with speed, moving/stationary/turning cards as
  well"): this screen is a completely separate full-screen overlay from
  `ui/screens/MapScreen.kt` (own MapView, drawn last on top) and — unlike
  `StatusOverlayContent.kt`, which MapScreen keeps rendering underneath
  it — never received the underlying GNSS/DR pipeline state
  (`DeadReckoningState`/`GnssModeUiState`/`MlVelocityUiState`/
  `FusedPositionUiState`) in the first place, only
  `NavigationSessionRepository`'s Mapbox-side state. So FR10's status
  readout simply didn't exist here from day one, not a regression in the
  narrow sense, but a real gap once a user is actually using the feature.
  Fixed by adding `drState`/`gnssState`/`mlState`/`fusedState` params
  (threaded through from `ui/screens/MapScreen.kt`, which already holds
  all four) and rendering the same three chips `StatusOverlayContent.kt`
  shows — GNSS state-machine mode (color-coded
  GNSS_AIDED/TRANSITION/DEAD_RECKONING/REACQUISITION, same palette), 
  speed, motion label (Stationary/Moving/Turning/Accelerating/Braking/
  Cruising/Pothole) — computed via that same file's `estimateSpeedMps`/
  `estimateMotionLabel` (both already `internal` in `ui.screens`, shared
  rather than duplicated, so the two screens can't silently disagree).
  Placed `TopStart` (guided mode's `MapboxManeuverView` already owns
  `TopCenter`).
REAL BUG FOUND + FIXED (2026-09-05, same session, follow-up user report:
  "in the current guided destination window I cant see those cards. make
  sure those cards are small and right to the end trip button"): the
  `TopStart` placement above was wrong for GUIDED navigation specifically
  (free-drive, which has no `MapboxManeuverView`, showed them fine) —
  `MapboxManeuverView` is an `AndroidView` with its own opaque background,
  `fillMaxWidth()`'d at `TopCenter`, and since it's added to the `Box`
  AFTER the chips it draws on top of that entire width, burying anything
  underneath regardless of the chips' own alignment being nominally
  "TopStart" not "TopCenter" — Compose alignment governs position, not
  z-order/paint-over. Fixed by moving the three chips off the top
  entirely: they now sit in a `Column` to the right of the Exit button,
  inside the same bottom `Row` (`SpaceBetween`, `Alignment.Bottom`) —
  a region `MapboxManeuverView` never reaches. Shrunk to a new
  `MiniStatusChip` (private to this file: 8dp/4dp padding, 6dp dot,
  `labelSmall` text vs. `StatusChip`'s 14dp/8dp/8dp/`bodySmall`) per the
  "make sure those cards are small" ask. The recenter `FloatingIconButton`
  below (still `BottomEnd`) had its fixed bottom offset bumped from 140dp
  to 190dp to clear the now-taller bottom row (Exit button + 3-chip
  column stacked beside it).
VERIFIED ON A REAL DEVICE (2026-09-05, same S24 FE): real guided
  navigation to Voltas Colony, Chennai (via History's Recent Voltas
  Colony entry, same destination prior sessions used) — "Turn right,
  200 ft" banner rendered correctly at TopCenter, DEAD_RECKONING/0.0 m/s/
  Stationary chips now visibly stacked to the right of the Exit button at
  the bottom, no overlap with the banner or the Exit button itself. Clean
  Exit back to MapScreen, empty crash buffer throughout both this and the
  free-drive re-check.
Connected to: ui/screens/MapScreen.kt (isNavigating || isFreeDriving
  overlay, drawn last, now also passes drState/gnssState/mlState/
  fusedState) -> ActiveGuidanceScreen -> nav/NavigationSessionRepository
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

motion_labels.py
Status: IMPLEMENTED
Purpose: Derives real, non-heuristic ground-truth motion-class labels
  from IO-VNBD's VEHICLE CSV (V-<trip>.csv, a real VBOX CAN-bus
  recording of actual driver input, independent of the phone) —
  train_motion_classifier.py's dedicated labeling module (CLAUDE.md
  Rule 5, same split as feature_extraction.py/train_velocity_model.py).
REAL DATA-QUALITY FINDING (2026-09-02, CLAUDE.md Rule 13 — same lesson
  as feature_extraction.py's forward-axis sign correction and
  inspect_dataset.py's GPS-1Hz finding): "Accelerator Pedal Position
  (0 or 1)"'s own column header is wrong — checked directly across all
  72 trips (1,071,035 rows): it's actually a continuous 0-99 throttle
  position (mean 9.8, 42% exactly 0), not binary. "Brake Position
  (0 or 1)" IS genuinely binary, confirmed the same way. The
  Accelerating threshold (20.0) was picked empirically, not guessed: at
  pedal > 20, 94.6% of matching rows have real positive Indicated
  Longitudinal Acceleration (vs. 62.7% at pedal > 0).
Outputs: MOTION_CLASSES = [Stationary, Turning, Braking, Accelerating,
  Cruising, Moving] — the 6 of PRD.md Section 14's 8 classes this
  dataset can genuinely support. Pothole and Phone Moved have NO signal
  anywhere in IO-VNBD (no event markers, nothing CAN-bus-related to
  either) and stay on their existing deterministic stand-ins
  (motion/PotholeShockDetector.kt, motion/PhoneMovedDetector.kt) until a
  real self-captured drive exists (capture/SensorRecorder.kt's
  CaptureLabel tooling, still unused).
Important functions/classes: vehicle_csv_path_for() (the S-/V- filename
  swap, duplicated from inspect_dataset.py's inline expression rather
  than importing a private helper); load_vehicle_csv() (positional load,
  29 columns, same philosophy as _load_smartphone_csv); derive_motion_labels()
  — a pure, vectorized, first-match-wins precedence chain: Stationary
  (velocity < 1.0 km/h) > Turning (|yaw rate| >= 8.6 deg/s, matching
  motion/TurningDetector.kt's own default) > Braking (real brake-pedal
  ground truth) > Accelerating (throttle > 20%) > Cruising (steady
  velocity, rolling std < 0.5 km/h) > Moving (fallback).
Unit tests: tests/ml/test_motion_labels.py (18 cases) — each condition
  fires correctly in isolation; precedence order (Stationary beats
  Turning/Braking/Accelerating; Turning beats Braking/Accelerating;
  Braking beats Accelerating); Cruising/Moving split respects the
  window_samples parameter, not hardcoded; vehicle_csv_path_for only
  replaces the first "S-" occurrence.

train_motion_classifier.py
Status: IMPLEMENTED — training/evaluation only, no export/on-device
  wiring yet (see MotionClassifierModel.kt's own entry, still PLANNED)
Purpose: Trains + evaluates a RandomForestClassifier over the velocity
  model's EXACT shared FEATURE_COLUMNS (PRD.md Section 14's own "shared
  feature extraction reduces on-device cost" requirement) against
  motion_labels.py's real vehicle-CAN ground truth — join is by
  (trip_name, row position); 63/72 trips have byte-identical smartphone/
  vehicle row counts, the other 9 differ only by a small trailing amount
  (confirmed, worst case, to be exactly explained by one logger running
  slightly longer — 232 extra rows x 0.1s sample period = 23.2s,
  matching that trip's real wall-clock duration difference) — truncated
  to min(len), a disclosed simplification, not a silent one.
REAL MEASURED RESULT (2026-09-02, S24-adjacent session, 58 train / 14 val
  trips, seed=42, 159,145 val rows): reported FOUR ways side by side
  (CLAUDE.md Rule 3 — no cherry-picked single number), because the first
  configuration tried was misleading on its own:
    Trivial majority-class baseline ("Cruising" always): 40.4% accuracy
    Deterministic baseline (Python re-impl of what ships on-device):    29.6% accuracy
    Trained RandomForestClassifier, UNWEIGHTED:                          47.2% accuracy
    Trained RandomForestClassifier, class_weight="balanced":             30.5% accuracy
  REAL FINDING while building this: class_weight="balanced" alone (the
  first thing tried) reported 30.5% — BELOW simply always guessing the
  majority class (40.4%). That is not a complete comparison on its own;
  balanced weighting trades overall accuracy for minority-class recall,
  it does not make the model worse in an absolute sense. All four are
  now trained/reported together so the real tradeoff is visible, not
  hidden behind one convenient setting.
  HONEST VERDICT: the unweighted trained classifier (47.2%) is the only
  configuration that beats BOTH the deterministic baseline AND the
  trivial majority-class baseline — a real, measured, if modest,
  justification for ML on THIS metric (CLAUDE.md Rule 3) — but its own
  per-class report shows why it wins on raw accuracy: Accelerating and
  Moving recall are catastrophic (2% and 1%) — the model is mostly just
  learning to say "Cruising" (94% recall there) and "Stationary" (83%
  recall), which happen to be a large enough share of the val set to
  inflate the accuracy number. PRD.md Section 28's "no class assumed to
  just work" is not satisfied by any of the four configurations tested —
  NOT wired into the app on this result. HONEST, ACCEPTED LIMITATION
  (matches PRD.md Section 31's own documented StationaryDetector.kt
  finding): FEATURE_COLUMNS has no velocity feature, so every
  configuration here shares the same "can't fully tell parked from
  steady coasting" blind spot — expected, not fixed by smuggling in a
  velocity feature that wouldn't reflect what ships on-device.
  Feature importances (unweighted model): elapsed_since_last_gnss_fix_s
  (0.210) and accel_up_std_mps2 (0.198) dominate — the SAME vibration-
  scales-with-something signal already flagged as a generalization risk
  for the velocity model (PRD.md Section 31), now showing up here too.
Important functions/classes: build_labeled_dataset() (the row-position
  join + truncation described above); deterministic_baseline_labels()
  (Python re-implementation of what's shipping today, reusing already-
  defined thresholds — ZUPT_MAX_ACCEL_MPS2/ZUPT_MAX_GYRO_RADPS from
  train_velocity_model.py, TurningDetector.kt's 0.15 rad/s,
  LongitudinalMotionClassifier.kt's 1.0 m/s^2 — not re-guessed); reuses
  split_trips() by IMPORT from train_velocity_model.py (not duplicated),
  same precedent train_reacquisition_model.py already established.
Unit tests: tests/ml/test_train_motion_classifier.py (10 cases) —
  build_labeled_dataset truncates correctly in both directions (smartphone
  longer / vehicle longer); a trip with no paired vehicle file is skipped
  and reported, not silently dropped or crashed; raises when NO trip can
  be labeled at all; deterministic_baseline_labels' four branches plus its
  median Cruising/Moving split; split_trips import wiring. `python -m
  pytest tests/ml/test_motion_labels.py tests/ml/test_train_motion_classifier.py`
  — 28/28 pass; full suite `python -m pytest tests/` — 71/71 pass, no
  regressions.
Connected to: data/processed/io_vnbd_features.parquet, motion_labels.py
  -> train_motion_classifier.py -> (printed comparison report only — no
  model artifact saved, same pattern as train_velocity_model.py; export
  is explicitly a separate, later, not-yet-built step)

export_model.py
Status: IMPLEMENTED (velocity model only — motion classifier export
  waits on a measured result that actually justifies it, see
  train_motion_classifier.py's own entry above for why this one doesn't
  yet)
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
