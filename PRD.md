# SIH26168 Intelligent Dead Reckoning MVP — Product Requirements Document

Status: Round 1 complete (Slices 1-8b implemented, evaluation cleared,
tagged `round1-submission` on `main`). Round 2 in progress on the
`hackathon-round2` branch.
Owner: Nithin R
Timebox: Round 1 ~36 hours (complete). Round 2 ~6 days (in progress) —
see Section 33.

---

## 1. Executive Summary

This document defines the MVP scope for SIH Problem Statement 26168 (ISRO): an
AI-ML based Intelligent Dead Reckoning (IDR) system that keeps a smartphone
navigating a vehicle through GNSS-denied stretches (tunnels, underpasses,
multi-level parking, urban canyons, dense forest roads, jamming) using only
the phone's own IMU, augmented by lightweight ML and classical sensor-fusion
physics.

The MVP is a real Android application that reads live accelerometer,
gyroscope and GNSS data, detects a GNSS outage, switches into dead-reckoning
mode, estimates position using an AI-assisted velocity estimate plus
heading integration, and re-fuses with GNSS on reacquisition. The system is
intentionally hybrid: physics and a state machine carry the core logic, and
ML is used only where it demonstrably improves accuracy (velocity
estimation, motion/event classification, noise rejection) — not because
"AI/ML" appears in the problem title.

## 2. Problem Definition

Smartphone navigation degrades or fails whenever GNSS signal is unavailable
or degraded. Standard consumer navigation apps freeze, jump, or silently
extrapolate using naive dead reckoning that drifts rapidly. SIH PS 26168
asks for a smartphone-only (no external hardware) system that fuses IMU
data with GNSS/INS techniques, applies AI/ML for speed and event
estimation, filters vibration/road noise, performs automatic phone-to-
vehicle alignment, applies non-holonomic constraints, and produces
seamless GNSS↔DR transitions with map-constrained output.

## 3. SIH Requirements (as given)

- Smartphone IMU-based dead reckoning
- GNSS + INS fusion
- Automatic phone-to-vehicle alignment/calibration
- AI/ML-based speed estimation
- IMU noise/vibration filtering
- Pothole/bump/non-navigation-motion detection
- Map matching
- Non-holonomic constraints
- Seamless GNSS ↔ dead-reckoning transition
- Real-time mobile navigation UI
- Lightweight edge-deployable inference
- Preliminary testing on the IO-VNBD dataset
- ~10 Hz smartphone sensor rate
- Conceptual extensibility to external IMU input
- Target: drift < 10% of distance travelled during GNSS blackout
  (illustrative figures given by SIH: <5 m over 50 m in <1 min; <100 m over
  1 km at 60 km/h)

These targets are the **official SIH target**, not a promise for this MVP.
Section 28 (Performance Metrics) and Section 30 (SIH WOW Factor) define
what we will actually attempt and measure.

## 4. MVP Objective

Build and demonstrate an Android application that (Round 1 shipped this
within ~36 hours; Round 2 extends it over 6 days per Section 33):

1. Continuously reads accelerometer, gyroscope and GNSS at ~10 Hz.
2. Detects when GNSS becomes unavailable/degraded and switches to dead
   reckoning automatically.
3. Estimates forward velocity using a lightweight trained ML model
   (fallback: physics-only integration) and integrates heading from the
   gyroscope to propagate position.
4. Applies a motion/event classifier to gate the above (reject
   stationary drift, pothole spikes, phone-movement artifacts).
5. Re-fuses smoothly with GNSS when it returns, without a visible jump.
6. Shows the whole process live on a map-based UI: GNSS status, current
   mode, speed, motion state, and (if feasible) both the GNSS and the
   dead-reckoned track for comparison.
7. Reports its own measured drift over the outage, honestly.

## 5. Target Users / Use Cases

- **Primary demo user**: SIH evaluators watching a live or recorded
  vehicle run through a real GNSS-denied stretch (tunnel/underpass/multi-
  level parking).
- **Downstream real-world users** (post-hackathon framing, not built now):
  drivers using standard phone navigation who currently lose guidance in
  tunnels/urban canyons; fleet/logistics tracking in GNSS-poor areas.

## 6. Scope (MVP)

- Native Android app (Kotlin) with direct Sensor/Location API access.
- On-device inference for velocity + motion classification (ONNX or
  LiteRT), trained offline in Python on IO-VNBD (+ our own captured data
  if time allows).
- Deterministic GNSS↔DR state machine with hysteresis.
- Simple map-constraint layer (snap-to-road using an existing map/nav SDK
  or lightweight OSM-based nearest-road logic — see Section 19).
- Non-holonomic lateral-velocity constraint.
- User-selected Car / Motorcycle mode (no automatic vehicle-type
  classification in the MVP).
- Live UI showing status, mode, speed, and trajectory.
- Basic automated unit tests for the deterministic math (coordinate
  transforms, state machine, filters) and a measured (not assumed) drift
  number from at least one real test run.

## 7. Out of Scope (MVP)

Explicitly deferred to Future Work (Section 34) unless a minimal version
is unavoidable for the demo:

- Custom deep-learning map matcher / transformer architectures
- 3D SLAM or computer-vision localization
- Custom vector-map renderer
- Lane-level localization
- Multi-phone support, FOG IMU, custom hardware, OBD-II integration
- Large backend/cloud infrastructure or large model training
- Automatic car-vs-motorcycle classification

**Amended (Slice 8b, 2026-08-26)**: "Full offline routing engine" and "a
general-purpose maps competitor" were explicitly removed from this list
by developer override — real turn-by-turn navigation (search, routing,
offline tile caching) was requested and shipped against real
OSM/Nominatim/OSRM services. See `docs/PROJECT_MAP.md`'s 2026-08-26
"full routing" entry (now in `summary.txt`, moved during the changelog
split) for the full reasoning. This is a permanent scope amendment, not
a one-off exception — the remaining items above still stand.

**Amended (2026-09-04)**: basemap vendor decision revisited by developer
request. `android/app/build.gradle.kts`'s `osmdroid` dependency originally
carried a comment explaining it was chosen over Google Maps Compose/Mapbox
specifically to avoid an API key/billing dependency. That tradeoff no
longer holds as stated: `osmdroid`'s MAPNIK tile source structurally
cannot support bulk/offline tile download (`TileSourcePolicy` flags
permanently set `FLAG_NO_BULK` — see `summary.txt`'s dated entry on the
`CacheManager.downloadAreaAsync` crash), and Mapbox's basemap, unlike
Google Maps, is itself OSM-derived — so it stays geometrically consistent
with the OSM/OSRM road data `routing/RoutingRepository.kt` and Section 19's
map-snap logic already depend on, where Google's own proprietary road
graph would not. **Mapbox is now the approved basemap replacement for
`osmdroid`**, alongside the existing pre-approved Google Maps SDK option
(CLAUDE.md Rule 2) — whichever actually gets implemented first stays the
one in use; this amendment does not mandate migrating both.
A Mapbox account (billing configured, usage notifications set at 1,000
monthly active users as a $0-spend guard — Mapbox has no hard spending
cap, only usage alerts, confirmed on its own billing/notifications page)
and two scoped access tokens (a public runtime token, and a secret
`DOWNLOADS:READ` token for Gradle to authenticate against Mapbox's
private Maven repo) were created for this. The Gradle dependency +
credential plumbing landed first (`settings.gradle.kts`,
`app/build.gradle.kts`, git-ignored `local.properties`), verified by a
real dependency resolution + compile before any UI code was written.

**UPDATE, same day**: the actual UI migration also landed —
`ui/map/StreetMapView.kt` now renders via the Mapbox Maps SDK (annotation
plugin for the current-position marker/anchor point/destination pin,
raw GeoJsonSource+LineLayer for the route line and dashed outage-anchor
line, matching CLAUDE.md Rule 9's "name every coordinate transform"
requirement at the one GeoPoint→Mapbox-Point conversion point). The
public composable signature is unchanged; `ui/screens/MapScreen.kt`'s two
call sites that touched the raw platform `MapView` type directly (camera
zoom-in on navigation start, offline tile download) were updated
accordingly. **Scoped out, not silently dropped (Rule 13)**:
`routing/OfflineRouteCache.kt`'s bulk tile pre-fetch/download
(`downloadRouteTiles`/`prefetchLiveZoomTiles`) was osmdroid-CacheManager-
specific and was ALREADY a permanent no-op there (MAPNIK's
`FLAG_NO_BULK`, Section 19/`summary.txt`'s own dated finding) — Mapbox's
own offline system (`OfflineManager`/`TileStore`) is a genuinely
different, separately-scoped feature, not ported here. MapScreen.kt now
shows an honest "not available in this build" status instead of calling
a function built for a platform type this screen no longer has. Route
geometry/steps JSON persistence (`saveRoute`/`loadSavedRoute`, no tiles
involved) is unaffected.

**Verification status (Rule 13)**: `:app:assembleDebug` succeeds and
existing unit tests pass. Installed and launched on the project's real
S24 FE test device (2026-09-04). A real bug WAS found this way, not
missed: the position marker and route line didn't render (user report:
"I cant see the current location indicator ... the path does not show
orange line") — root cause was two competing `loadStyle` calls racing on
first composition, one of them bare and silently wiping the other's
custom layers/annotation manager (see `docs/PROJECT_MAP.md`'s
`ui/map/StreetMapView.kt` entry for the full root-cause writeup). Fixed
and re-verified live: the marker now renders, and a real OSRM route
(search -> destination -> route, the user's own exact flow) draws as a
correctly road-following line through Puzhuthivakkam/Adambakkam/Alandur.
Empty crash buffer, no FATAL EXCEPTION. `ui/screens/MapVerificationScreen.kt`
(new test-tooling file, CLAUDE.md Android Rule 8) simulates a moving
position without needing a real GNSS outage or drive, and was used to
close out the remaining gap in the same session: the outage-anchor
dashed line, heading-up camera rotation, and follow/recenter gesture
logic are now all confirmed working, after finding and fixing two more
real bugs (marker-arrow rotation not compensating for map bearing; an
unguarded per-frame camera update fighting the recenter button — full
root-cause writeup in `docs/PROJECT_MAP.md`'s `ui/map/StreetMapView.kt`
entry) and ruling out a third suspected bug as a testing-methodology
error, not a code defect. `:app:testDebugUnitTest` and
`:app:assembleDebug` both pass; final build installed fresh with an
empty crash buffer. The previous, unmodified osmdroid+OSRM build is
preserved intact in a sibling folder (`C:\projects\26168-osmdroid`,
independently buildable, no Mapbox credentials required) as a working
fallback/comparison.

**Amended (2026-09-05)**: full Mapbox Navigation SDK turn-by-turn —
voice guidance, banner + lane instructions, automatic rerouting on
off-route, free-drive mode — added by explicit developer override,
requested with the words "disregard claude.md" (CLAUDE.md Rule 2/4's
normal discussion-first process was skipped at the developer's direct
instruction, not silently bypassed). Before implementing, the tradeoffs
were surfaced and the developer confirmed proceeding anyway: this is a
SEPARATE Mapbox product from the Maps SDK already integrated (its own
6-module dependency footprint, its own billing SKUs — Navigation SDK
Core Framework and Active Guidance Trips specifically, metered per
trip/session, NOT covered by the Maps-SDK-scoped usage alert set up in
the earlier amendment above), and it is explicitly the kind of "general
maps competitor" capability Section 7's original list named — this
amendment is that override, on the record, same convention the
2026-08-26 OSRM routing amendment above already established.

REAL ROUTING-BACKEND SPLIT (see `nav/NavigationSessionRepository.kt`'s
own header doc for the full reasoning): voice/banner/lane instruction
TEXT is generated server-side by Mapbox's own Directions API and does
not exist in a plain OSRM response, so ACTIVE GUIDANCE now requests its
route from Mapbox directly — the OSRM-based route PREVIEW (search,
distance/duration estimate, `ActiveRouteCard`) is UNCHANGED and still
OSRM; only the moment "Go" is tapped switches backends. Free-drive mode
is a Mapbox trip session with no route set, map-matching the live
position to the road network — a new, separate entry point (not gated
behind having a destination).

Two new files: `nav/NavigationSessionRepository.kt` (owns the
`MapboxNavigation` session — route requests, trip session start/stop,
voice/banner/reroute observers) and `ui/screens/ActiveGuidanceScreen.kt`
(the full-screen overlay: Mapbox's own map + location puck +
`MapboxManeuverView` for banner/lane + ETA text + Exit). Deliberately
NOT built on Mapbox's older "Drop-In UI" (a single pre-built widget from
Navigation SDK v2) — that class doesn't exist in the current SDK
generation (`com.mapbox.navigationcore` v3.30.0, confirmed by
decompiling the actual downloaded modules rather than trusting an older
doc page that turned out to describe a different major version); v3's
real architecture is individual components (`MapboxManeuverView`, the
core `MapboxNavigation` session) assembled by the app, which is what was
actually built.

REAL BUG FOUND + FIXED before first successful install (2026-09-05):
`MapboxVoiceInstructionsPlayer`'s second constructor parameter is a
LANGUAGE CODE, not an access token — copied from `MapboxSpeechApi`'s
constructor shape (where the second param really is a token) without
checking the difference, and it crashed on every launch trying to parse
the Mapbox token string as a `Locale` inside Android's own
`TextToSpeech.isLanguageAvailable`. Fixed by passing `"en"` instead.

VERIFIED ON A REAL DEVICE, same session: free-drive mode confirmed
working (real Mapbox location puck, compass control, map-matched
position, clean Exit back to the app's normal GNSS/DR pipeline with no
side effects). Full active guidance confirmed working end-to-end on a
real destination (Voltas Colony, Chennai): a real Mapbox Directions API
route request succeeded, `MapboxManeuverView` rendered a real "Turn
right, 100 ft" banner instruction with the correct turn icon, live ETA
text updated from Mapbox's own `RouteProgress` (3834 m / 14 min), clean
Exit. `:app:testDebugUnitTest` and `:app:assembleDebug` both pass; empty
crash buffer on the final build. NOT independently verified: voice
instructions actually being audible (the crash that would have proven
they weren't wired at all is fixed, and the same observer-registration
pattern that verifiably drives the banner/ETA UI also drives voice, but
no announcement happened to fire audibly during this session's stationary
testing) and automatic rerouting actually triggering a real reroute (the
SDK's default `MapboxRerouteController` is confirmed enabled via
`setRerouteEnabled(true)`, but exercising a genuine off-route deviation
needs real movement, not something this session's stationary/simulated
testing could trigger).

## 8. Functional Requirements

FR1. App shall sample accelerometer, gyroscope, and location at ~10 Hz
and timestamp each sample using a single consistent clock base.

FR2. App shall detect GNSS unavailability/degradation (fix loss, HDOP/
accuracy threshold, satellite count where available) within a bounded
latency and transition to DEAD_RECKONING state.

FR3. App shall estimate forward velocity via the trained velocity model
(ML) during DR, with a physics-integration fallback if the model is
unavailable or its input is out of expected range. The live ML velocity
output shall be damped (exponential smoothing or a min-speed cutoff)
before being integrated into position — unlike the physics path's
integrator "memory," the ML path had no such damping, and a single
anomalous sample produced a visible position jump (Round 2 finding,
2026-08-28).

FR4. App shall classify motion state per sample/window (stationary,
moving, accelerating, braking, pothole, turning, cruising, phone-moved)
and use that classification to gate/adjust the state estimator (e.g.
zero-velocity update when stationary, discount acceleration spikes on
pothole, flag recalibration on phone-moved).

FR5. App shall integrate heading (gyroscope, optionally aided by GNSS
course while GNSS is available) and propagate 2D position during DR.

FR6. App shall apply a non-holonomic constraint that suppresses
non-physical lateral velocity for a road vehicle.

FR7. App shall constrain the propagated position to the nearest
plausible road geometry (map-matching, MVP-level — Section 19).

FR8. App shall re-fuse with GNSS on reacquisition without a visible
teleport in the UI (blend/interpolate over a short window) — this covers
BOTH position and heading/map-orientation. Round 1 only blended position
(`fusion/PositionFusion.kt`); heading was a hard cutover, which produced a
visible ~180° map-orientation flip on reacquisition during the Round 2
Day 1 live outage test (2026-08-28) — see Section 18.

FR9. App shall perform an initial phone-to-vehicle alignment
(pitch/roll/yaw) automatically while GNSS + motion are available, and
shall detect gross phone movement thereafter. The resulting alignment
correction shall be applied to BOTH the ML velocity path's feature
extraction AND the physics DR path's heading integration — Round 1 only
wired it into the former, which let the physics path's heading drift far
enough from true heading to contribute to the reacquisition flip above
(Round 2 finding, 2026-08-28). See Section 15.

FR10. UI shall show, at minimum: GNSS status, current mode
(GNSS_AIDED/TRANSITION/DEAD_RECKONING/REACQUISITION), estimated speed,
motion state, and the live position on a map.

FR11. App shall run entirely on-device; no continuous dependency on a
laptop for inference.

FR12 (Round 2 addition, 2026-08-28). App shall detect floor/level changes
using the barometer (pressure sensor), directly supporting the
multi-level-parking scenario named in the SIH problem statement
(Section 2/3) — no altitude signal is used anywhere in the MVP today.

FR13 (Round 2 addition, 2026-08-28). GNSS + INS fusion shall weight the
GNSS contribution as a continuous function of fix accuracy, not a binary
good/bad gate — a 24 m fix should not be trusted identically to a 2 m fix.
See Section 17.

## 9. Non-Functional Requirements

- Inference latency: must not visibly stall the UI thread; run on a
  background thread/coroutine.
- Model size: small enough to bundle in the APK (target: low tens of MB
  at most, ideally far less).
- Sampling: ~10 Hz sustained without dropped samples during a several-
  minute demo run.
- Reliability: state machine must not flap between GNSS_AIDED and
  DEAD_RECKONING on noisy/borderline GNSS accuracy (hysteresis required).
- Explainability: every AI output must be traceable to a documented
  input/feature set (no black-box claims in the demo).
- Honesty: no performance numbers are shown or reported to judges that
  were not actually measured on a real run (Rule 13 in CLAUDE.md).

## 10. System Architecture

```
ANDROID PHONE
     |
     v
Accelerometer + Gyroscope + GNSS  (~10 Hz)
     |
     v
Sensor Preprocessing (sync, filtering, gravity removal)
     |
     +----> Orientation / Phone-Vehicle Alignment
     |
     +----> Motion Classification (ML)
     |
     +----> Velocity Estimation (ML, physics fallback)
     |
     v
State Estimator (heading integration, ZUPT, NHC)
     |
     +---- GNSS available ----> GNSS + INS fusion
     |
     +---- GNSS unavailable ---> Dead Reckoning
     |
     v
Map-Constraint Layer (snap-to-road)
     |
     v
Navigation State (position, mode, confidence)
     |
     v
Android Map UI
```

This mirrors the pipeline given in the problem brief; no structural change
was judged necessary for the MVP scope.

## 11. Sensor Pipeline

- **Raw inputs**: accelerometer (m/s²), gyroscope (rad/s), GNSS fix
  (lat/lon, speed, course, accuracy/HDOP where exposed), all timestamped
  on Android's `SensorEvent.timestamp` (monotonic, boot-time base) —
  reconciled against wall-clock/GNSS time explicitly, per Rule 7/8
  (CLAUDE.md): sensor timestamps are **not** wall-clock and must not be
  silently treated as such. **(Round 2 addition, 2026-08-28)**: barometer
  (pressure, hPa) is also read, for floor/level-change detection — see
  FR12.
- **Synchronization**: nearest-timestamp alignment of accel/gyro (same
  sensor thread, so effectively synchronous) against GNSS fixes (much
  lower rate, ~1 Hz), interpolated/held between fixes.
- **Filtering**: low-pass filtering (or a light complementary/Kalman
  filter for orientation) to remove high-frequency vibration noise before
  feature extraction; a matched approach is used for gravity removal.
  **(Round 2, 2026-08-28)**: gravity removal may use Android's own
  `TYPE_GRAVITY`/`TYPE_LINEAR_ACCELERATION` fused sensors in place of, or
  as a cross-check against, the current manual gravity-subtraction step —
  adopted only if it measures out better (CLAUDE.md Rule 13: no invented
  numbers), not assumed superior up front.
- **Orientation estimation**: device rotation vector (Android sensor
  fusion) as the base orientation source, refined by the phone-to-vehicle
  alignment offset from Section 12.
- **Vehicle-frame transformation**: rotate raw accelerometer readings
  from device frame → vehicle frame (forward/lateral/vertical) using the
  estimated alignment, so "forward acceleration" means forward
  acceleration regardless of how the phone is mounted.
- **Feature extraction**: windowed statistics (mean/variance/energy of
  vehicle-frame acceleration and gyro, jerk, zero-crossing rate, etc.)
  computed on a rolling window at the same ~10 Hz output cadence, feeding
  both ML models.
- **Velocity/position estimation**: ML-estimated forward velocity,
  integrated with heading to produce a 2D position delta per tick; this
  feeds the state estimator (Section 16).

All units are explicit throughout the codebase per CLAUDE.md Rule 8 (m,
m/s, m/s², rad, rad/s, degrees, Hz, ms) — no unlabeled magic numbers.

## 12. AI/ML Architecture

Two lightweight, on-device models, trained offline in Python and exported
via ONNX or LiteRT:

1. **Velocity Estimator** (regression) — Section 13
2. **Motion/Event Classifier** (classification) — Section 14

Both are deliberately conventional ML (tree ensembles / small MLP), not
deep architectures, per the feasibility and explainability constraints in
Section 5–6 of the source brief. A temporal model (e.g. small 1D-CNN/RNN)
is only considered if the tabular/windowed-feature approach is measurably
insufficient once real data is inspected — this is a decision to make
during Phase 4, not to assume up front.

## 13. Velocity Estimation

- **Inputs**: windowed vehicle-frame acceleration features, gyro
  features, previous velocity estimate, elapsed time since last GNSS fix,
  optionally GNSS speed (when available, used both as a training label
  and as a live sanity check).
- **Output**: estimated forward velocity (m/s).
- **Candidate models**: Gradient Boosted Trees or Random Forest as the
  primary candidate (fast to train, robust to modest data volume,
  explainable via feature importance, trivial to export); a small MLP as
  a secondary candidate if a smoother output is needed. Final choice is
  made in Phase 4 after inspecting IO-VNBD (Section 24) — this PRD does
  not pre-commit to one over the other before the data is seen.
- **Fallback**: if the model's input features fall outside the training
  distribution (e.g. extreme values, missing GNSS aiding for too long),
  fall back to constant-acceleration physics integration with a
  documented confidence penalty, rather than trusting an out-of-
  distribution ML output.
- **Damping (Round 2 addition, 2026-08-28)**: the live ML velocity output
  is damped (exponential smoothing or a min-speed cutoff) before being
  integrated into position. The physics path's integrator has "memory"
  that absorbs a single bad reading; the ML path had none, so one jolt
  (e.g. a desk-bump during bench testing) produced a visible position
  jump — observed directly, not hypothesized. See FR3.

## 14. Motion Classification

Classes: `Stationary, Moving, Accelerating, Braking, Pothole, Turning,
Cruising, Phone Moved`.

Each class has a defined downstream effect, not just a label for display:

- **Stationary** → apply a zero-velocity update (ZUPT) to stop
  integration drift.
- **Pothole** → discount the acceleration sample(s) so a vertical/shock
  spike doesn't get misread as forward acceleration.
- **Phone Moved** → invalidate the current phone-vehicle alignment,
  raise the confidence penalty, and flag for recalibration.
- **Accelerating / Braking / Cruising / Turning / Moving** → context for
  the state machine (Section 15) and for the non-holonomic constraint
  (turning periods get different lateral-velocity handling than straight
  cruising).

Candidate model: Random Forest or Gradient Boosted Trees over the same
windowed feature set as the velocity estimator (shared feature
extraction reduces on-device cost). Confusion matrix, precision/recall
per class are mandatory reported metrics (Section 28) — no class is
assumed to "just work."

## 15. Phone Alignment

Automatic pitch/roll/yaw estimation of phone-relative-to-vehicle,
established during an initialization window while GNSS is available and
the vehicle is moving in a reasonably straight line:

- **Pitch/roll**: from gravity vector while stationary or near-stationary
  (accelerometer low-pass output ≈ gravity in device frame).
- **Yaw**: from GNSS course-over-ground compared against device heading
  while moving above a minimum speed threshold (removes ambiguity that
  gravity alone can't resolve).
- **Ongoing validation**: the Motion Classifier's `Phone Moved` output
  triggers re-initialization; the system does not assume the alignment is
  valid forever.

Explicit limitations: this approach assumes at least one clean
straight-line moving segment with GNSS at trip start; it does not attempt
continuous re-estimation while GNSS is unavailable, and it does not model
motorcycle lean beyond flagging it as reduced confidence during large
roll excursions (Section 15/7 of source brief) rather than building a
lean-dynamics model.

**(Round 2, 2026-08-28)**: the alignment correction is applied to BOTH the
ML velocity path's vehicle-frame feature extraction AND the physics DR
path's heading integration (Section 16). Round 1 only wired it into the
ML path (`MlVelocityRepository`) — the physics path
(`BaselineDeadReckoningRepository`) kept using raw, unaligned device
azimuth. That gap is what let the physics-path heading drift far enough
from true heading to flip the map ~180° on GNSS reacquisition during the
Round 2 Day 1 live outage test.

## 16. Dead Reckoning

Core propagation while in `DEAD_RECKONING`:

```
heading[t] = heading[t-1] + gyro_z * dt        (with bias correction)
v[t]       = VelocityModel(features[t])         (or physics fallback)
dx[t]      = v[t] * cos(heading[t]) * dt
dy[t]      = v[t] * sin(heading[t]) * dt
pos[t]     = pos[t-1] + (dx[t], dy[t])
```

Gated by: ZUPT when Stationary, non-holonomic lateral suppression
(Section 20), and map-constraint snapping (Section 19) applied as a
correction rather than as the primary estimator.

**(Round 2, 2026-08-28)**: `heading[t]` integration now starts from the
aligned azimuth (Section 15's phone-to-vehicle correction applied), not
raw device azimuth as in Round 1 — see Section 15 for why.

## 17. GNSS + INS Fusion

While `GNSS_AIDED`: position and heading are taken primarily from GNSS;
the IMU-derived velocity/heading are used to smooth short GNSS gaps/jitter
and to continuously calibrate the velocity model's bias against GNSS
speed (a simple online correction, not a full Kalman filter — a
loosely-coupled complementary approach is preferred over a
tightly-coupled EKF for feasibility within this project's scope).

**(Round 2 addition, 2026-08-28 — FR13)**: the blending/correction weight
given to a GNSS fix is a continuous function of its reported accuracy
(e.g. inverse-accuracy weighting), not the binary good/bad gate
`GnssQuality.isGood` currently applies uniformly — a 24 m fix should not
be trusted identically to a 2 m fix. The binary gate remains as the state
machine's enter/exit trigger (Section 18); this only changes how much a
given "good" fix is trusted within the fusion blend itself.

## 18. GNSS Outage State Machine

```
GNSS_AIDED
   | GNSS accuracy/availability drops below threshold, sustained > N samples
   v
TRANSITION  (short blend window; freeze/average recent GNSS+DR estimate)
   |
   v
DEAD_RECKONING  (pure IMU + ML propagation, confidence decays over time)
   | GNSS reacquired and stable for > N samples
   v
REACQUISITION  (blend DR position toward new GNSS fix over a short window)
   |
   v
GNSS_AIDED
```

Hysteresis (separate enter/exit thresholds and a minimum dwell time in
each state) is required to prevent mode-flapping on borderline GNSS
accuracy. Outage-detection and recovery latency are both measured
metrics (Section 28), not assumed constants.

**(Round 2, 2026-08-28 — FR8)**: REACQUISITION blends BOTH position
(already implemented, `fusion/PositionFusion.kt`, Slice 7) AND
heading/map-orientation. Round 1 only blended position, leaving heading as
a hard cutover — during the Round 2 Day 1 live outage test this produced
a visible ~180° map-orientation flip at the moment of reacquisition, on
top of the position snap the position-only blend was supposed to prevent
(the position blend alone wasn't enough once heading also jumped).

## 19. Map Matching

MVP approach: **nearest-road snapping**, not a custom map-matching
engine.

**(Round 2, 2026-08-28)**: scheduled for Round 2 Day 4 (Section 33),
committing to the lightweight local method below — Slice 8b's routing
work already pulls OSM road geometry and talks to OSRM live, so this adds
no new service dependency, only the nearest-segment snapping logic
itself:

- Pull OSM road geometry for the demo area ahead of time, and snap the DR
  position to the nearest compatible road segment whose heading roughly
  matches the estimated heading (simple nearest-segment +
  heading-compatibility check, not an HMM).
- (Originally-considered alternative, not taken: an existing Android
  map/navigation SDK's own road-snapping capability — not pursued since
  the OSM-based approach above reuses infrastructure Slice 8b already
  built, at no added integration cost.)

Explicitly **not** attempting: a full HMM-based map matcher, a custom
renderer, or general-area routing. This is the MVP map constraint; full
SIH-grade map matching is Future Work (Section 34).

## 20. Non-Holonomic Constraints

A road vehicle cannot move sideways relative to its own heading. The MVP
applies this as a simple constraint: any ML/physics-estimated lateral
velocity component (in vehicle frame) is suppressed toward zero except
during classifier-flagged `Turning` windows, where the constraint is
relaxed. No full vehicle-dynamics simulation is built — this is a
one-line correction applied at the state-estimator step, not a new
subsystem.

## 21. Android Application

- Kotlin, Jetpack Compose UI.
- Android Sensor APIs (`SensorManager`) for accelerometer/gyroscope/
  rotation vector; `LocationManager`/Fused Location Provider for GNSS.
- Background sensor collection on a dedicated coroutine/thread; UI reads
  from a shared, thread-safe state holder — never blocks the main thread
  (CLAUDE.md Rule 9).
- On-device inference via ONNX Runtime Mobile or LiteRT, invoked at the
  same ~10 Hz cadence as sensor windows.

## 22. UI/UX

Single main screen, demo-oriented:

- Map view showing current position, and — if feasible — both the raw
  GNSS track and the dead-reckoned track overlaid for visual comparison
  during the outage.
- Status header: GNSS state (AIDED/TRANSITION/DR/REACQUISITION),
  estimated speed, current motion class, alignment/confidence indicator.
- Vehicle-mode selector (Car / Motorcycle) set before the drive starts.
- No unnecessary navigation-app chrome (turn-by-turn, search, etc.) —
  this is a demonstrator, not a consumer nav app.

## 23. Data Pipeline

Python-side, offline:

```
IO-VNBD raw files
   -> inspection (Phase 4, Section 24)
   -> cleaning / resampling to a consistent rate
   -> feature extraction (shared logic ported to Kotlin later)
   -> train/val/test split
   -> model training (velocity + classifier)
   -> evaluation
   -> export (ONNX / LiteRT)
   -> bundle into Android assets
```

Feature-extraction logic is written once conceptually and mirrored (not
literally shared, since Python and Kotlin are different runtimes) between
training and inference — any mismatch between the two is a top project
risk and must be explicitly tested (Section 27).

## 24. Dataset Strategy

Primary: **IO-VNBD** (Inertial and Odometry benchmark dataset for ground
vehicle positioning), as specified by SIH. Before any modeling begins
(Phase 4), the dataset must be inspected for:

- available sensor channels and their sampling rates,
- available ground-truth labels (position/velocity) and their accuracy,
- any gap between what IO-VNBD provides and what a live Android phone can
  produce (e.g. sensor placement assumptions, units, coordinate frames).

If IO-VNBD does not fully cover the motion-classifier labels needed
(pothole, phone-moved, etc.), a small amount of self-captured labelled
data (recorded on the team's own phone during a short test drive) will
supplement it. This PRD does not assume IO-VNBD is sufficient — that is
a Phase 4 finding, to be recorded in `docs/PROJECT_MAP.md` once known.

## 25. Model Training

Standard offline pipeline: scikit-learn (Random Forest / Gradient
Boosting) as the primary path; PyTorch only if a small MLP proves
necessary. Train/validation split respects trip/session boundaries (no
leakage of the same drive across train and test). Evaluation metrics per
Section 28. Models are exported to ONNX (or converted to LiteRT) and
validated for output-parity between the Python and on-device inference
paths before being trusted in the demo.

## 26. On-Device Inference

ONNX Runtime Mobile or LiteRT, invoked on a background thread at ~10 Hz.
Model size and latency are both measured on the actual target device
before Phase 8 integration is considered complete — not assumed from
desktop benchmarks.

## 27. Testing Strategy

- **Unit tests**: coordinate transforms, velocity/heading integration
  math, filters, state-machine transitions, GNSS-outage detection logic,
  confidence calculations.
- **ML tests**: dataset split correctness, training reproducibility,
  inference output-parity (Python vs on-device), latency, model size.
- **Integration tests**: sensors → preprocessing → ML → state estimator
  → navigation, run against a recorded sensor log (not just live).
- **Real-world tests**: stationary, straight driving, acceleration,
  braking, turning, potholes, stop-and-go, phone movement, GNSS loss,
  GNSS recovery — each run logged for later drift analysis.
- **(Round 2 addition, 2026-08-28)**: every DR session automatically logs
  accumulated position drift over the outage (not just per-tick velocity
  MAE) so a real number is captured on every run instead of being
  eyeballed — this is what Section 28's honesty requirement actually
  takes to satisfy in practice, not just a nice-to-have.

## 28. Performance Metrics

All numbers reported to judges must come from an actual measured run
(CLAUDE.md Rule 13 — never invent numbers). Metrics to capture:

- **Position error**: absolute and relative position error, drift % over
  a GNSS-denied segment. **(Round 2, 2026-08-28)**: captured via an
  automated per-session drift log (Section 27), not a manual/eyeballed
  read of the on-screen position.
- **Velocity**: MAE, RMSE against GNSS speed (when available) or ground
  truth (IO-VNBD).
- **Motion classifier**: accuracy, precision, recall, F1, confusion
  matrix.
- **Mobile performance**: inference latency, sensor-processing latency,
  and (where practical) CPU/memory/battery impact.
- **GNSS transition**: outage-detection latency, recovery latency.

## 29. Demo Scenario

```
Start outside, GNSS available
  -> vehicle begins moving, AI estimates motion
  -> GNSS outage (real tunnel/underpass, or a controlled simulated drop)
  -> app switches to DEAD_RECKONING
  -> vehicle travels through the GNSS-denied section
  -> position continues moving smoothly (no freeze, no jump)
  -> GNSS returns
  -> system re-fuses/re-aligns smoothly
```

The UI must make GNSS status, mode, speed, motion state, and (if shown)
the GNSS-vs-DR trajectory comparison visible throughout, so the judges
can see the transition happen, not just be told about it.

## 30. SIH WOW Factor

Core story: **"Your phone doesn't lose navigation when GPS disappears."**
High-impact features prioritized for the demo (visually obvious,
technically defensible, actually buildable, tied to the official
requirements):

1. Live, visible GNSS→DR→GNSS mode transition with no UI freeze/jump.
2. Overlaid GNSS-vs-dead-reckoned trajectory during a real outage.
3. Live motion-state readout (e.g. visibly flagging a pothole or a
   phone-movement event as it happens) showing the ML is doing real
   work, not just a label.
4. An honest, on-screen drift measurement at the end of the outage
   segment (e.g. "X m drift over Y m travelled") — credibility through
   measured numbers rather than a marketing claim.

No feature is added purely for show; each maps directly to an official
SIH requirement.

## 31. Risks

- **Dataset–device mismatch**: IO-VNBD sensor placement/units/rate may
  not match a live phone's mounting and output — mitigated by early
  Phase 4 inspection and, if needed, supplementary self-captured data.
- **Alignment robustness**: automatic phone-to-vehicle alignment may be
  unreliable on a short/imperfect initialization drive — mitigated by a
  manual re-calibrate action as a fallback.
- **Model/feature mismatch between training and on-device inference**
  — mitigated by an explicit output-parity test (Section 27) before
  integration is considered done.
- **Time overrun**: any subsystem in Section 7 (Out of Scope) creeping
  back in — mitigated by CLAUDE.md Rules 2–4.
- **GNSS outage hard to produce for a live demo** — mitigated by having
  both a real GNSS-denied location and a controlled/simulated-outage
  fallback path for testing (not for the final measured numbers, per
  Rule 12 — simulation is for testing only).
- **(Round 2, 2026-08-28) ML feature-importance generalization risk**:
  `accel_up_std_mps2` currently dominates the velocity model's feature
  importance (measured 0.672) — plausibly a road-vibration proxy rather
  than a true kinematic signal. Untested across different vehicles,
  mounts, and road surfaces; flagged, not yet mitigated.
- **(Round 2, 2026-08-28) Unexplained held-out-trip result**: the one
  held-out trip where physics beat the ML model (S-Vtb3) was never
  root-caused — unknown whether it's a one-off or a pattern (e.g. the
  model underperforming on short/low-speed trips). Flagged as an open
  item for Phase 4 follow-up, not yet investigated.
- **(Round 2, 2026-08-28) Stationary-vs-coasting ambiguity**:
  `StationaryDetector.kt`'s own documented limitation — accel+gyro alone
  cannot distinguish "parked" from "coasting at a perfectly steady
  speed." Known, not yet mitigated; a GNSS-speed or classifier-based gate
  is Future Work (Section 34) if it proves to matter in practice.
- **(Round 2, 2026-08-28) Cross-language feature-extraction drift**:
  `feature_extraction.py` (Python) and `FeatureExtractor.kt` (Kotlin) are
  still hand-mirrored only, with no automated parity check — any future
  edit to one and not the other would silently corrupt ML input. Explicitly
  deferred this round to Future Work (Section 34) rather than built now,
  kept here as an open risk rather than silently dropped.

## 32. Fallback Strategies

- If the velocity ML model underperforms physics integration on real
  data, ship physics integration as primary and keep ML as a documented,
  measured comparison rather than forcing ML into the critical path.
- If on-device inference latency is too high, reduce feature-window size
  or fall back to the classifier-only + physics-velocity combination.
- If map-SDK snapping integration proves too slow, ship without map
  constraint and rely on the state estimator + non-holonomic constraint
  alone, documenting the trade-off honestly to judges.
- If phone-to-vehicle auto-alignment proves unreliable, ship a manual
  "hold phone flat, tap to calibrate" fallback.
- **(Round 2, 2026-08-28)** If the remaining Round 2 timebox gets tight,
  the newly-scheduled Day 2-4 items (Section 33) drop in this order,
  newest/most speculative first, per CLAUDE.md's "working slice over
  sophistication" rule: reacquisition blending/alignment/damping bug
  fixes (already broken — must ship) > motion classifier (already
  Required) > snap-to-road (Section 19) > continuous GNSS confidence
  weighting (Section 17) > barometer floor detection (FR12) >
  linear-acceleration/gravity sensor swap (Section 11).

## 33. Development Timeline

### Round 1 — 36-Hour Development Plan (DONE, historical)

| Phase | Focus | Est. hours |
|---|---|---|
| 0 | Architecture (this document + CLAUDE.md + PROJECT_MAP.md) | 1–2 |
| 1 | Android sensor acquisition + live raw display | 3 |
| 2 | Sensor preprocessing (sync, filter, orientation, vehicle-frame) | 3 |
| 3 | Baseline physics dead reckoning (no ML yet) | 3 |
| 4 | Dataset inspection + model development (velocity + classifier) | 6 |
| 5 | Android ML inference integration (ONNX/LiteRT) | 4 |
| 6 | GNSS/DR state machine + fusion/re-alignment | 4 |
| 7 | Map constraint + UI | 4 |
| 8 | End-to-end integration | 4 |
| 9 | Real-world testing + metric capture | 3 |
| 10 | Demo hardening | 3 |

Total ≈ 33–36 hours, all DONE — evaluation cleared, tagged
`round1-submission` on `main`.

### Round 2 — 6-Day Development Plan (in progress, `hackathon-round2`)

**Updated 2026-08-28** after Day 1's live outage test (real drive,
GPS toggled off mid-trip to force an outage — see Section 18/15's dated
findings): the end-to-end GNSS→DR→GNSS transition worked, but surfaced a
position-snap + map-orientation-flip bug on reacquisition, plus a set of
scoped-in additions (below) chosen over deferring everything to Future
Work (Section 34).

| Day | Focus | Priority |
|---|---|---|
| 1 (today, in progress) | Doc/branch setup (done); live outage test — surfaced reacquisition-blend, alignment, damping/OOD bugs (Sections 15/17/18) | Required |
| 2 | Fix Day-1 bugs: heading+position blend on reacquisition (Section 18); apply `AlignmentEstimator` correction to the physics DR path (Section 15/16); damping/smoothing + OOD guard on ML velocity (Section 13); continuous GNSS confidence weighting in fusion (Section 17/FR13). Begin self-captured motion-classifier data collection | Required |
| 2–3 | Step-detector sensor + pedestrian dead reckoning (Walking mode); barometer floor/level detection (FR12); linear-acceleration/gravity sensor read (Section 11); UI smoothness pass | Required |
| 3–4 | `ml/train_motion_classifier.py`: train + measure against the deterministic stand-ins | Required |
| 4 | Export motion classifier to ONNX, wire into Kotlin (only if it measurably beats the stand-ins — Rule 3); map-constrained snap-to-road (Section 19) | Required |
| 5 | Outdoor GNSS validation drive #2 (confirm fixes, capture final demo numbers, incl. the automated drift log from Section 27) | Required |
| 6 | Docs pass (`docs/PROJECT_MAP.md`, `summary.txt`, `README.md`), demo rehearsal | Required |
| 6 (if time remains) | Google Maps SDK migration off `osmdroid` (needs a GCP project/API key first) | Optional / stretch |

If Days 2-4 run over the timebox, see Section 32's drop-order for what
gets deferred to Section 34 first.

Each day's detailed acceptance criteria, dependencies, and fallback are
tracked in `docs/PROJECT_MAP.md` as they are implemented (this PRD sets
targets; PROJECT_MAP.md tracks reality) — same convention Round 1 used.

## 34. Future Scope

Deep-learning map matching, 3D SLAM/CV localization, full offline routing,
lane-level localization, multi-phone fusion, FOG IMU support, OBD-II
integration, automatic vehicle-type classification, continuous
in-motion re-alignment without a GNSS window, and a full cloud backend —
all explicitly deferred (Section 7).

**(Round 2, 2026-08-28)** Also explicitly deferred this round (chosen
over scheduling into Section 33, given the remaining timebox):

- Game rotation vector + magnetometer read — a plausible fix for
  in-vehicle magnetic interference affecting heading accuracy, but not
  scheduled this round.
- An automated parity test harness between `feature_extraction.py`
  (Python) and `FeatureExtractor.kt` (Kotlin) — currently hand-mirrored
  only (Section 31's cross-language-drift risk).
- An audit for the `GeocodingRepository`-style silent-failure pattern
  (collapsing a failure into an empty result instead of surfacing an
  error) elsewhere in the codebase.

## 35. Definition of Done (MVP)

- Real Android app, running on a real device, using real sensors — no
  hard-coded trajectories in the shipped build (CLAUDE.md Rule 12).
- At least one real test drive with a genuine or controlled GNSS outage,
  with measured drift, velocity error, and classifier metrics recorded.
- Visible, smooth GNSS↔DR transition in the UI during that run.
- `docs/PROJECT_MAP.md` up to date with the actual implemented
  architecture, not just the planned one.
- No out-of-scope subsystem (Section 7) present in the shipped build.
