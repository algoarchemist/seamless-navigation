# Capability Status & Roadmap

**Snapshot date:** 2026-08-30, branch `hackathon-round2`.

This file checks the current codebase against the 6 capabilities the final
deliverable is required to exhibit. It is a synthesis, not a new source of
truth — `PRD.md` (scope/requirements) and `docs/PROJECT_MAP.md` (actual
current file-by-file status) remain canonical; every claim below traces
back to one of those two documents or to reading the source directly, and
no benchmark number here is invented (per `CLAUDE.md` Rule 13).

## Status at a glance

| # | Capability | Status | Where |
|---|---|---|---|
| 1 | In-Vehicle Alignment & Calibration Engine | 🟡 Yaw shared across both DR paths + auto re-calibration + roll/pitch mounting baseline + motorcycle-lean confidence flag implemented (2026-09-02), see below | `alignment/AlignmentRepository.kt`, `alignment/AlignmentEstimator.kt`, `motion/PhoneMovedDetector.kt` |
| 2 | AI Speed & Vibration Filter | 🟡 Velocity ✅, vibration filter + Accelerating/Braking implemented (2026-08-30), trained classifier still ❌ (blocked on real data), see below | `ml/VelocityModel.kt`, `dr/LowPassFilter.kt`, `motion/LongitudinalMotionClassifier.kt` |
| 3 | Advanced Map-Matching & Kinematic Constraints | 🟡 MVP-level map snap + Turning exemption implemented (2026-08-30), see below | `map/MapConstraint.kt`, `motion/TurningDetector.kt`, `dr/NonHolonomicConstraint.kt` |
| 4 | GNSS+INS Fusion Engine | 🟡 AI-based adaptive REACQUISITION blend + continuous-accuracy velocity weighting + GNSS jitter smoothing implemented (2026-09-02), see below | `ml/ReacquisitionDriftModel.kt`, `fusion/PositionFusion.kt`, `fusion/GnssJitterFilter.kt`, `fusion/VelocityBiasCalibrator.kt` |
| 5 | Seamless GNSS Deficit Handler | 🟡 Implemented, timing unvalidated | `gnss/GnssOutageDetector.kt` |
| 6 | Real-time Navigation Interface | 🟡 Marker now animates + rotates with heading (2026-08-31), symmetric GNSS-reacquired banner added, see below | `ui/map/StreetMapView.kt`, `ui/screens/MapScreen.kt`, `ui/components/GnssModeChangeBanner.kt` |

## 1. In-Vehicle Alignment & Calibration Engine — 🟡 yaw + roll/pitch baseline + auto-recalibration, implemented 2026-09-02

**What exists:** `AlignmentEstimator.kt` still computes a **yaw offset
only** — a circular mean of `(device azimuth − GNSS course-over-ground)`,
gated to moments the vehicle is moving >5 m/s in a straight line (yaw rate
≤0.1 rad/s), requiring ≥20 samples before `isAligned` is true. That math
is unchanged; what changed is who runs and consumes it:

- **Extracted into `alignment/AlignmentRepository.kt`**, its own
  Android/coroutine repository driven only by orientation + GNSS
  bearing/speed — no ML dependency. Previously this estimation ran
  privately inside `MlVelocityRepository.kt`, which meant it silently
  stopped existing whenever the ONNX model failed to load.
- **Now feeds BOTH DR paths.** `dr/BaselineDeadReckoningRepository.kt`
  reads the shared estimate and corrects the heading passed to
  `NonHolonomicConstraint.suppressLateralVelocity` (device azimuth minus
  yaw offset) — previously it used raw device azimuth unconditionally,
  even once real alignment had converged for the ML path.
  `ml/MlVelocityRepository.kt`'s own feature-path correction is
  unchanged, just now reading the shared repository instead of owning a
  private `AlignmentEstimator`.
- **Automatic re-calibration** on a detected "Phone Moved" event now
  exists: `motion/PhoneMovedDetector.kt`, a deterministic stand-in
  (sustained WORLD-frame pitch/roll change vs. a remembered reference,
  same "no labeled classifier data" precedent as `MotionStateClassifier`/
  `PotholeShockDetector`/`TurningDetector`) resets the shared estimator
  automatically, logged for traceability. The manual "recalibrate"
  button now calls `AlignmentRepository.reset()` directly, decoupled
  from ML load success.

**Closed 2026-09-02:** PRD.md Section 15's own explicitly-scoped
remaining piece — "does not model motorcycle lean beyond flagging it as
reduced confidence during large roll excursions" — is now implemented.
`AlignmentEstimator.kt` accumulates a roll/pitch MOUNTING baseline (same
circular-mean technique as yaw) while the vehicle is near-stationary
with a GNSS fix (≤1.0 m/s — a parked vehicle's own roll/pitch is ~0, so
the device's roll/pitch at that moment IS the mounting tilt), then flags
`reducedConfidenceDueToRoll` whenever the CURRENT roll deviates from
that baseline by more than ~20° (engineering default, CLAUDE.md Rule 13,
unvalidated against real lean/remount data). Republished through
`AlignmentRepository` → `MlVelocityRepository`'s `MlVelocityUiState` →
shown as a warning in `StatusOverlayContent.kt` (PRD §31's "alignment/
confidence indicator") and in `MainActivity`'s debug screen. 5 new unit
tests in `AlignmentEstimatorTest.kt`.

**Still missing relative to the literal ask:** this is still a FLAG, not
a corrected lean estimate — PRD.md Section 15 explicitly excludes
building a lean-dynamics model, so that's a deliberate non-goal, not a
gap. A full device→vehicle 3-axis rotation matrix is still deliberately
NOT built beyond this baseline — this project's 2D horizontal navigation
only ever needs a heading (yaw), which mounting pitch/roll tilt doesn't
change; see `AlignmentEstimator.kt`'s own doc for the full reasoning.
`PhoneMovedDetector`'s 15°/1s thresholds and the new ~20° roll-excursion
threshold are both engineering defaults, unvalidated against real
"phone picked up mid-drive" / "real lean or remount" data (CLAUDE.md
Rule 13) — no real-world false-positive/false-negative rate can be
quoted yet for either.

## 2. AI Speed & Vibration Filter — 🟡 velocity done, filter/classifier missing

**What exists (done and measured):** a `RandomForestRegressor` trained on
the IO-VNBD dataset (`ml/train_velocity_model.py`), exported to ONNX
(`models/velocity_v1.onnx`, ~20.7 MB) and run on-device
(`ml/VelocityModel.kt`). Measured result: **MAE 1.244 m/s / RMSE
1.593 m/s**, vs. a physics+ZUPT baseline of **MAE 5.205 m/s / RMSE
6.345 m/s** — roughly **4.2× more accurate**, beating the baseline on
13 of 14 held-out trips. Sklearn↔ONNX output parity is verified to
1e-6 m/s.

**What was missing, now closed (2026-08-30):**
- **Vibration filter.** `dr/LowPassFilter.kt` — a real single-pole
  (RC-discretized) low-pass filter, wired into
  `dr/BaselineDeadReckoningRepository.kt` ONLY: filters the WORLD-frame
  linear-acceleration East/North/Up components and raw gyro X/Y/Z before
  they reach the double-integrator and the ZUPT magnitude check, applied
  AFTER the pothole discount (so `PotholeShockDetector` still sees the
  raw, unsmoothed spike it needs to detect). **Deliberately NOT wired
  into `ml/FeatureExtractor.kt`'s input** — the already-trained, exported
  ONNX model (MAE 1.244 m/s, a real measured number) was trained on
  `ml/feature_extraction.py`'s unfiltered windowed statistics; filtering
  the live signal now without retraining + re-validating against a
  matched Python-side filter would silently shift the inference-time
  feature distribution away from the training distribution (CLAUDE.md
  Rule 20) and could quietly regress that measured accuracy with nothing
  to catch it (Rule 13). Retraining on filtered features is legitimate
  future work, not folded into this change. `DeadReckoningState`'s
  published `linearAccelMagnitudeMps2`/`gyroMagnitudeRadPerSec` are now
  the FILTERED magnitude, not raw — a real, disclosed change since these
  feed `scripts/analyze_drive_log.py`'s offline threshold validation.
- **Two more classifier classes covered.** `motion/LongitudinalMotionClassifier.kt`
  (Accelerating/Braking, from the same alignment-corrected
  `accelForwardMps2` the ONNX model consumes, ML-path only per the same
  precedent `MotionStateClassifier` already sets) — plus `TurningDetector`
  and `PhoneMovedDetector` built in earlier Round 2 work — bring
  deterministic-stand-in coverage from 3/8 to **7 of the 8** PRD §14
  classes (only general "Moving" has no dedicated detector, but it
  already renders correctly as the UI's own fallback label). Wired into
  `ui/screens/StatusOverlayContent.kt`'s motion label and MainActivity's
  debug screen.

**Still missing relative to the literal ask:** the actual TRAINED 8-class
Random Forest/GBT classifier (`train_motion_classifier.py`) remains
correctly blocked on self-captured labeled data (the public IO-VNBD
dataset has no Pothole/Phone-Moved/Turning/Accelerating/Braking ground
truth) — no confusion matrix or per-class precision/recall can be
reported yet (CLAUDE.md Rule 13). Every "classifier" class above is a
deterministic threshold stand-in, explicitly not the PRD §14 classifier,
and every threshold used is an engineering default unvalidated against
real labeled data.

## 3. Advanced Map-Matching & Kinematic Constraints — 🟡 MVP-level, implemented 2026-08-30

- `map/MapConstraint.kt` now exists, at the PRD's own reduced-scope
  "nearest-road-snap + heading-compatibility check" level (PRD §19) —
  point-to-segment projection against the ACTIVE ROUTE's real OSRM
  geometry (reused, not a new fetch), with the heading check done modulo
  180° since a route polyline doesn't encode traffic direction. Wired
  into `fusion/StateEstimator.kt`: applied to the fused position every
  tick GNSS isn't already trusted, an anchor + active route exist, and
  DR speed is above a reliable-heading floor (0.5 m/s). 8 unit tests
  (`MapConstraintTest.kt`). Exposed for verification via
  `FusedPositionUiState.roadSnapped`/`distanceToRoadM`, shown in
  MainActivity's debug screen — not yet validated against a real outdoor
  test drive (CLAUDE.md Rule 13), and it only does anything while a
  route is active (no general "snap to nearest road anywhere" dataset,
  a deliberate scope reduction, not a partial implementation of the same
  ask).
- A full UKF or Hidden Markov Model map matcher was never intended —
  PRD §19 explicitly rejects that as infeasible for this project's
  timebox, and PRD §7/§34 list it under out-of-scope/Future Work. Still
  correctly unbuilt.
- The non-holonomic constraint (`dr/NonHolonomicConstraint.kt`) now has
  its "Turning" exemption: `motion/TurningDetector.kt` (a deterministic
  yaw-rate stand-in, same precedent as `MotionStateClassifier.kt`) flags
  a turn, and `BaselineDeadReckoningRepository` skips the lateral
  suppression on those ticks — no more over-suppressing real lateral
  velocity through a genuine turn. Still WORLD frame, not true vehicle
  frame (device heading still proxies vehicle heading) — that half
  needs `alignment/AlignmentEstimator.kt`'s yaw offset plumbed into the
  physics path, which stays a separate, larger change (Roadmap item #5
  below) rather than folded into this one.

## 4. GNSS+INS Fusion Engine — 🟡 AI-based adaptive blend + jitter smoothing, implemented 2026-09-02

`fusion/PositionFusion.kt` is still a rule-based state machine at its
core — position is **frozen** during `TRANSITION`, then **linearly
interpolated** from the last DR position to the newly reacquired GNSS fix
during `REACQUISITION`. What changed: that interpolation's DURATION is no
longer a fixed 1-second constant. `ml/ReacquisitionDriftModel.kt` — a
trained LinearRegression, exported to ONNX and run on-device — predicts
the EXPECTED along-track DR drift (meters) the instant REACQUISITION
begins, from the outage's real elapsed duration plus
`fusion/RunningStats.kt`'s running mean/std of DR speed accumulated
during it. `PositionFusion.blendDurationForDriftMs()` maps that
prediction to an adaptive blend duration (500ms–3000ms, larger predicted
drift → longer, less-jarring blend).

**Real, measured result (CLAUDE.md Rule 3), not assumed:** trained and
evaluated on IO-VNBD (outages simulated, since this dataset has no real
ones — see `ml/train_reacquisition_model.py`'s own doc for why along-
track drift, not full 2D position, is the target). On 4 held-out trips,
LinearRegression measurably beat both a RandomForestRegressor (MAE
14.224m vs 15.060m) and the best 1-parameter physics-formula baseline
(vs 14.887m) — a real but modest improvement, reported honestly, not
oversold. Per CLAUDE.md Rule 11 ("lightest model that meets the bar"),
LinearRegression is what's shipped, not the forest.

`fusion/VelocityBiasCalibrator.kt` is unchanged — still an EWMA
correction, explicitly documented as **"NOT a Kalman filter."** The
REACQUISITION blend above is also deliberately **not** a Kalman/EKF state
update (CLAUDE.md's "What Not To Build" / PRD §7 both explicitly exclude
that) — this stays a simple, transparent formula fed by a small learned
prediction, matching the "Learned adaptive REACQUISITION blend" option
chosen at this decision point (see below) over a full EKF/UKF filter.

**Closed 2026-09-02:** PRD.md Section 17's OTHER still-open fusion
piece — "the IMU-derived velocity/heading are used to smooth short GNSS
gaps/jitter" — is now implemented. This turned out to have TWO already-
built halves this doc hadn't previously called out by name (both landed
2026-08-28, before today's session): the velocity-bias half
(`VelocityBiasCalibrator`, unchanged) and FR13's continuous accuracy
weighting (`GnssQuality.confidenceWeight`, already wired into that
calibrator's `update()` calls). What was genuinely still missing was
POSITION jitter smoothing — the map marker snapped to each new raw GNSS
fix directly while `GNSS_AIDED`, with zero IMU smoothing, since
`PositionFusion`'s `GNSS_AIDED` branch was hard-coded to `(0, 0)`
(exactly the raw fix, unmodified). `fusion/GnssJitterFilter.kt` (new) is
a simple COMPLEMENTARY filter — deliberately not a Kalman filter, same
"What Not To Build" exclusion as the REACQUISITION blend above — that
predicts forward from the last smoothed position using the current
IMU/DR velocity, then pulls that prediction toward each new raw fix by
`GnssQuality.confidenceWeight` (reusing the exact same FR13 signal, so a
precise fix snaps close to raw GNSS almost immediately while a marginal-
but-still-"good" fix leans more on the IMU prediction). Runs in a FIXED
local frame (`StateEstimator.tripOriginLatDeg/LonDeg`, set once) kept
deliberately separate from the continuously-moving reacquisition anchor
(`outageAnchorLatDeg/LonDeg`), so this change cannot touch the anchor-
accuracy drift-measurement fix that landed hours earlier the same day.
6 new unit tests (`GnssJitterFilterTest.kt`) + 1 new `PositionFusionTest`
case.

**Still missing relative to the literal ask:** the REACQUISITION blend
mechanism is still linear interpolation, not a state-space estimator;
only its duration is now learned. The new jitter filter is a fixed-
formula complementary filter too, not a covariance-aware estimator —
both are deliberate, per CLAUDE.md's "What Not To Build" (no Kalman/EKF).
No real outdoor test drive has yet exercised either adaptive path — the
500–3000ms REACQUISITION bounds, the 30ms-per-meter scale factor, and
the jitter filter's own behavior are all engineering defaults/untuned
formulas, unvalidated against real reacquisition events or real GNSS
jitter (CLAUDE.md Rule 13).

## 5. Seamless GNSS Deficit Handler — 🟡 implemented, timing unvalidated

`gnss/GnssOutageDetector.kt` is a complete 4-state hysteresis machine —
`GNSS_AIDED → TRANSITION → DEAD_RECKONING → REACQUISITION → GNSS_AIDED` —
with separate enter/exit dwell timers (`outageEnterDwellMs=2000`,
`transitionDwellMs=1000`, `reacquisitionEnterDwellMs=2000`,
`reacquisitionDwellMs=1000`), unit-tested at exact dwell boundaries, and
every transition is logged with timestamp and trigger condition. Real
bugs (REACQUISITION flapping, a stale-fix anchor) were found and fixed
during indoor testing.

**Gap:** transitions take 1–2 seconds by design — not literally
milliseconds — and these dwell constants are explicitly flagged in
`docs/PROJECT_MAP.md` as *"engineering defaults, not yet validated
against a real outdoor test drive."* No outdoor GNSS-denied stretch has
been run yet, so there is currently no measured transition-latency or
drift number that can honestly be quoted (per CLAUDE.md Rule 13). The
tooling to capture one already exists (`capture/DriveDataLogger.kt` +
`scripts/analyze_drive_log.py`) — it just hasn't been run outdoors yet.

## 6. Real-time Navigation Interface — 🟡 marker animates + rotates, reacquired banner added (2026-08-31)

**What exists and is genuinely real-time:** a live OpenStreetMap
(`osmdroid`) map, destination search (Nominatim), turn-by-turn routing
(OSRM), offline tile pre-fetch, and a status overlay (GNSS mode, speed,
motion label, alignment) all driven by live `StateFlow`s updating at
sensor/GNSS tick rate.

**Closed 2026-08-31 (STATUS_AND_ROADMAP.md Tier-1 #1/#2):** the position
marker's DRAWN position now interpolates smoothly between ticks instead
of snapping (a `LaunchedEffect`/`withFrameNanos` tween in
`StreetMapView.kt`, deliberately kept separate from the camera-recenter
logic, which stays instant `setCenter` on purpose — see that file's own
doc for why), and rotates into a directional chevron once a real
GNSS-bearing/DR-velocity heading exists (`markerHeadingDeg`, fed
unconditionally, not gated to turn-by-turn navigation the way the
map's own heading-up rotation is). `GnssModeChangeBanner.kt` now also
has a symmetric `GnssReacquiredBanner`, shown on a genuine
`REACQUISITION -> GNSS_AIDED` transition.

**Still missing relative to the literal ask:** the marker's rotation math
(and the map's own heading-up rotation it shares the counter-rotation
math with) is UNVERIFIED ON A REAL DEVICE against a live compass heading
outdoors (CLAUDE.md Rule 13) — no confirmed-correct on-device screenshot
or video exists yet showing the arrow pointing the right way while
actually driving/turning.

## Explicitly out of scope — do not build these

Per `PRD.md` §7/§34 and `CLAUDE.md`'s "What Not To Build" list, the
following are deliberately excluded from this project and should **not**
appear on the roadmap below, however tempting they look next to the gaps
above: a full UKF/EKF fusion filter, a Hidden Markov Model or
deep-learning map matcher, 3D SLAM/CV localization, lane-level
localization, multi-phone fusion, FOG IMU or custom hardware, OBD-II
integration, a large cloud backend, or automatic vehicle-type
classification. If any roadmap item below starts to grow into one of
these "as a small version," stop and re-read PRD §32 (Fallback
Strategies) first.

## Roadmap

### Tier 1 — cheap, high demo impact
1. ~~**Animate the position marker + rotate it with heading** in
   `StreetMapView.kt` (replace the `setCenter` snap with interpolated
   movement).~~ **DONE (2026-08-31)** — see `docs/PROJECT_MAP.md`'s
   `ui/map/StreetMapView.kt` entry. The DRAWN marker position now
   interpolates smoothly between ticks (a `LaunchedEffect` +
   `withFrameNanos` tween, kept deliberately separate from the
   already-fixed camera-recenter logic, which stays instant `setCenter`
   on purpose) and rotates into a directional chevron once a real
   heading exists. Still needs a real outdoor test drive to confirm the
   rotation math reads correctly on a live compass heading (CLAUDE.md
   Rule 13) — the map-rotation math it shares this caveat with was
   already flagged unverified before this change.
2. ~~**Add the symmetric "GNSS reacquired" banner** next to the existing
   `GnssModeChangeBanner.kt`, so both directions of the mode transition
   are visibly announced.~~ **DONE (2026-08-31)** — `GnssReacquiredBanner`
   in that same file, triggered by `StatusOverlayContent.kt`'s new
   `showReacquiredBanner`, mirroring the existing lost-banner's
   `fromMode`/`toMode` narrowing so it only fires on a genuine outage
   actually ending (`REACQUISITION -> GNSS_AIDED`), not a brief
   `TRANSITION -> GNSS_AIDED` recovery blip.
3. **Run one real outdoor drive** with a deliberate GNSS-denied stretch
   (tunnel/underpass/parking structure) using the already-built
   `DriveDataLogger.kt` + `scripts/analyze_drive_log.py` tooling. This
   produces the project's first real, citable transition-latency and
   drift numbers — required by PRD §28 and CLAUDE.md Rule 13, and
   currently the biggest hole in the project's evidence base.

### Tier 2 — moderate effort, closes items already in scope but unbuilt
4. ~~**Build `MapConstraint.kt`** at the PRD's own reduced MVP scope:
   nearest-road-snap plus a heading-compatibility check against OSM
   geometry — not an HMM.~~ **DONE (2026-08-30)** — see capability #3
   above. Still needs a real outdoor test drive to validate the
   30m/45° defaults against actual DR drift magnitudes.
5. ~~**Feed the yaw-alignment output into the physics/DR position path**,
   not just the ML feature path, and wire automatic re-calibration off
   `MotionStateClassifier` once "Phone Moved" detection exists.~~
   **DONE (2026-08-30)** — see capability #1 above
   (`alignment/AlignmentRepository.kt`, `motion/PhoneMovedDetector.kt`).
   Re-calibration is off a NEW deterministic `PhoneMovedDetector`, not
   `MotionStateClassifier` (which only covers Stationary/Cruising, not
   Phone Moved) — still needs real "phone picked up mid-drive" data to
   validate its thresholds.
6. ~~**Add a real low-pass/complementary pre-filter** on raw accel/gyro
   ahead of feature extraction (PRD §11), independent of the ML velocity
   model — this is the literal "vibration filter" half of capability #2
   that's currently missing entirely.~~ **DONE (2026-08-30)** — see
   capability #2 above (`dr/LowPassFilter.kt`). Scoped to the physics
   path only, per that entry's own reasoning (ONNX train/inference parity)
   — still needs a real outdoor test drive to see whether it measurably
   reduces the physics baseline's own drift.
7. ~~**Flag reduced confidence during large roll excursions**
   (PRD.md Section 15's own explicitly-scoped motorcycle-lean carve-out)
   — establish a roll/pitch mounting baseline from gravity while
   near-stationary, then flag a deviation beyond it.~~ **DONE
   (2026-09-02)** — see capability #1 above (`AlignmentEstimator.kt`'s
   `reducedConfidenceDueToRoll`). Still needs a real outdoor test drive
   (or real motorcycle-lean/remount data) to validate the ~20° threshold.
8. ~~**Smooth short GNSS gaps/jitter with IMU velocity** (PRD.md
   Section 17's other still-open fusion piece — the map marker snapped
   to each raw GNSS fix directly while GNSS_AIDED, with zero smoothing)
   — a simple complementary filter, not a Kalman filter.~~ **DONE
   (2026-09-02)** — see capability #4 above (`fusion/GnssJitterFilter.kt`).
   Still needs a real outdoor test drive to validate whether it visibly
   reduces marker jitter without introducing a lag artifact.
9. **Collect a small self-captured labeled dataset** (Pothole,
   Phone-Moved, Turning, Accelerating, Braking) and train
   `train_motion_classifier.py`, replacing ALL FIVE deterministic
   stand-ins (`MotionStateClassifier`, `PotholeShockDetector`,
   `TurningDetector`, `PhoneMovedDetector`, `LongitudinalMotionClassifier`
   — the last two added 2026-08-30). Report a real confusion matrix and
   per-class precision/recall as PRD §14 requires — not an assumed one.
   Still blocked exactly as before; deterministic-stand-in COVERAGE grew
   (7/8 classes now have some real signal), but none of them is the
   actual trained classifier this item asks for.

### Decision point — capability #4's "AI-based" wording — RESOLVED 2026-08-30
The shipped GNSS+INS fusion was classical/rule-based by deliberate PRD
decision (§17). Presented with three options (a learned adaptive
REACQUISITION blend; a full EKF/UKF filter overriding CLAUDE.md's
explicit exclusion; or closing this out in docs only with no new code),
the developer chose the first — see capability #4 above for what was
built (`ml/ReacquisitionDriftModel.kt` + `fusion/PositionFusion.kt`'s
`blendDurationForDriftMs`) and the real measured comparison that decided
LinearRegression over a RandomForestRegressor. This satisfies the
capability list's "AI-based" wording via a small, bounded, measured
regression feeding a transparent formula — deliberately still not a
Kalman/EKF filter, which remains excluded per CLAUDE.md's "What Not To
Build" / PRD §7.
