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
| 1 | In-Vehicle Alignment & Calibration Engine | 🟡 Yaw shared across both DR paths + auto re-calibration implemented (2026-08-30), see below | `alignment/AlignmentRepository.kt`, `alignment/AlignmentEstimator.kt`, `motion/PhoneMovedDetector.kt` |
| 2 | AI Speed & Vibration Filter | 🟡 Split — velocity ✅, filter/classifier ❌ | `ml/VelocityModel.kt`, `models/velocity_v1.onnx` / `motion/MotionStateClassifier.kt` |
| 3 | Advanced Map-Matching & Kinematic Constraints | 🟡 MVP-level map snap + Turning exemption implemented (2026-08-30), see below | `map/MapConstraint.kt`, `motion/TurningDetector.kt`, `dr/NonHolonomicConstraint.kt` |
| 4 | GNSS+INS Fusion Engine | 🟡 Implemented, but classical not AI-based | `fusion/PositionFusion.kt`, `fusion/VelocityBiasCalibrator.kt` |
| 5 | Seamless GNSS Deficit Handler | 🟡 Implemented, timing unvalidated | `gnss/GnssOutageDetector.kt` |
| 6 | Real-time Navigation Interface | 🟡 Mostly implemented, icon doesn't animate | `ui/map/StreetMapView.kt`, `ui/screens/MapScreen.kt` |

## 1. In-Vehicle Alignment & Calibration Engine — 🟡 yaw shared + auto-recalibration, implemented 2026-08-30

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

**Still missing relative to the literal ask:** pitch/roll are still not
separately estimated — an unchanged, documented design choice (Android's
rotation-vector sensor already gravity-references pitch/roll), so there
is still no true device→vehicle 3-axis rotation matrix, only a scalar
yaw correction. `PhoneMovedDetector`'s 15°/1s thresholds are engineering
defaults, unvalidated against real "phone picked up mid-drive" data
(CLAUDE.md Rule 13) — no real-world false-positive/false-negative rate
can be quoted yet.

## 2. AI Speed & Vibration Filter — 🟡 velocity done, filter/classifier missing

**What exists (done and measured):** a `RandomForestRegressor` trained on
the IO-VNBD dataset (`ml/train_velocity_model.py`), exported to ONNX
(`models/velocity_v1.onnx`, ~20.7 MB) and run on-device
(`ml/VelocityModel.kt`). Measured result: **MAE 1.244 m/s / RMSE
1.593 m/s**, vs. a physics+ZUPT baseline of **MAE 5.205 m/s / RMSE
6.345 m/s** — roughly **4.2× more accurate**, beating the baseline on
13 of 14 held-out trips. Sklearn↔ONNX output parity is verified to
1e-6 m/s.

**What's missing:**
- **No actual vibration/noise filter.** There is no low-pass,
  complementary, or Kalman filtering of raw accelerometer/gyroscope
  signal anywhere in the codebase. Gravity removal is a fixed constant
  subtraction. What exists instead is rolling-window statistical
  features (mean/std) feeding the regressor — useful, but not a signal
  filter.
- **The 8-class motion/event classifier is not built.**
  `train_motion_classifier.py` is explicitly PLANNED, blocked on
  self-captured labeled data (the public IO-VNBD dataset has no
  Pothole/Phone-Moved ground truth). Two small deterministic
  (non-ML) stand-ins exist and are labeled as such in code:
  `MotionStateClassifier.kt` (Stationary vs. Cruising only) and
  `PotholeShockDetector.kt` (a single vertical-accel threshold,
  unvalidated against real pothole data). Together they cover 3 of the
  8 required classes — Turning, Accelerating, Braking, Phone-Moved
  detection, and general Moving are not classified at all.

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

## 4. GNSS+INS Fusion Engine — 🟡 implemented, but classical, not AI-based

`fusion/PositionFusion.kt` is a rule-based state machine: position is
**frozen** during `TRANSITION`, then **linearly interpolated** from the
last DR position to the newly reacquired GNSS fix over a 1-second window
during `REACQUISITION`. `fusion/VelocityBiasCalibrator.kt` runs an EWMA
correction of the ML velocity model's bias against GNSS speed while GNSS
is trusted — its own code comment states this is explicitly **"NOT a
Kalman filter."**

This matches what the PRD itself promises (§17: a deliberately simplified
"loosely-coupled complementary approach," explicitly not EKF/Kalman,
chosen for feasibility) — so relative to this project's own spec, it's
faithfully delivered. But it does **not** satisfy the literal
capability-list wording of an *"innovative AI based Sensor Fusion
Algorithm."* This is a scope decision, not an oversight — see the
decision point at the end of this document.

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

## 6. Real-time Navigation Interface — 🟡 mostly implemented, icon doesn't animate

**What exists and is genuinely real-time:** a live OpenStreetMap
(`osmdroid`) map, destination search (Nominatim), turn-by-turn routing
(OSRM), offline tile pre-fetch, and a status overlay (GNSS mode, speed,
motion label, alignment) all driven by live `StateFlow`s updating at
sensor/GNSS tick rate.

**Gap against the literal ask:** the position marker in
`StreetMapView.kt` is a static dot/halo that **snaps** to each new
position via `setCenter` rather than animating between points, and there
is no directional vehicle-icon rotation tied to heading on the marker
itself (heading-up map rotation exists separately but is flagged in code
as "unverified on a real device"). The GNSS-lost banner
(`GnssModeChangeBanner.kt`) is one-directional — there's no symmetric
"GNSS reacquired" banner.

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
1. **Animate the position marker + rotate it with heading** in
   `StreetMapView.kt` (replace the `setCenter` snap with interpolated
   movement). Directly closes the "smooth, uninterrupted vehicle icon"
   gap and is the single highest-visibility fix for a demo.
2. **Add the symmetric "GNSS reacquired" banner** next to the existing
   `GnssModeChangeBanner.kt`, so both directions of the mode transition
   are visibly announced.
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
6. **Add a real low-pass/complementary pre-filter** on raw accel/gyro
   ahead of feature extraction (PRD §11), independent of the ML velocity
   model — this is the literal "vibration filter" half of capability #2
   that's currently missing entirely.
7. **Collect a small self-captured labeled dataset** (Pothole,
   Phone-Moved, Turning) and train `train_motion_classifier.py`,
   replacing the two deterministic stand-ins. Report a real confusion
   matrix and per-class precision/recall as PRD §14 requires — not an
   assumed one.

### Decision point — capability #4's "AI-based" wording
The shipped GNSS+INS fusion is classical/rule-based by deliberate PRD
decision (§17), not AI-based. Upgrading it to a real learned or
Kalman-family filter would be the single largest change needed to
literally match the "innovative AI based Sensor Fusion Algorithm"
wording in the capability list — but it's also the change most likely to
blow the Round 2 timebox and drift into the excluded EKF/UKF territory
above. This is flagged here as a decision for you to make explicitly
(per CLAUDE.md Rule 3/4), not something to silently build or silently
leave as-is.
