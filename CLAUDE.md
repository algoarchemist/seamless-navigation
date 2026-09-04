# CLAUDE.md — SIH26168 Intelligent Dead Reckoning MVP

## Project Overview

Android app + offline Python ML pipeline implementing an AI-ML assisted
Intelligent Dead Reckoning system for SIH Problem Statement 26168 (ISRO):
smartphone-only navigation continuity through GNSS-denied stretches.

## Mission

Ship a real, working, on-device Android demo that visibly and honestly
shows GNSS→dead-reckoning→GNSS transitions with a measured (not assumed)
drift result. Round 1 did this within a ~36-hour hackathon window
(tagged `round1-submission` on `main`, evaluation cleared). Round 2
extends it over a 6-day window on the `hackathon-round2` branch — same
standard of honest, measured results over assumed ones, now with enough
runway for things Round 1 couldn't fit: real self-captured training
data and a real outdoor validation run.

## Source of Truth

`PRD.md` is the source of truth for scope, architecture, and requirements.
`docs/PROJECT_MAP.md` is the source of truth for the *actual, current*
state of the codebase. If code and PRD.md disagree, PRD.md wins unless the
developer explicitly amends it. If code and PROJECT_MAP.md disagree,
PROJECT_MAP.md is stale and must be updated immediately — never leave it
describing something that no longer exists.

## Development Timeline

**Round 1 (complete)**: ~36 hours, hackathon MVP. Frozen at the
`round1-submission` git tag on `main` — do not rewrite that history;
all Round 2 work happens on `hackathon-round2` instead.

**Round 2 (in progress)**: ~6 days, internal hackathon round. Still a
real constraint, just a longer one — the standard below still applies,
it's not a license to gold-plate:

Time is a primary constraint, ahead of theoretical completeness. A
working, demonstrable vertical slice always beats a sophisticated,
incomplete subsystem. When in doubt, ship the simpler version described
in PRD.md and record the deferred sophistication as Future Work. The
extra runway Round 2 has over Round 1 is meant for things that were
genuinely infeasible in 36 hours — real self-captured training data,
a real outdoor GNSS test — not for scope creep into PRD.md Section 7's
excluded items. Any expansion past Section 7 still needs the same
explicit-discussion-first treatment Rule 2/4 already require, evaluated
per Round 2's own timebox, not assumed just because more days exist.

## Architecture Rules

1. Never implement a large feature without first checking `PRD.md`.
2. Never introduce a new framework, library, or service not already
   named in `PRD.md` Section 6/19/21/23 without discussing it first — the
   smallest practical stack wins. (Pre-approved Round 2 exception: the
   Google Maps SDK, to replace `osmdroid`/OSM tiles once a GCP
   project/API key exists — already discussed, not yet implemented;
   `osmdroid` stays in place and fully functional until then. **Amended
   2026-09-04**: Mapbox is now an additional pre-approved alternative for
   the same `osmdroid` replacement — see `PRD.md` Section 7's dated
   amendment for the reasoning (OSM-derived basemap geometry stays
   consistent with the existing OSRM-based routing/map-snap logic, unlike
   Google's own road graph). **UPDATE, same day**: the actual UI migration
   has now landed — `ui/map/StreetMapView.kt` renders via the Mapbox Maps
   SDK, not `osmdroid`. Compiled, full debug APK assembles, and existing
   unit tests pass, but this has NOT yet been installed/run on a real
   device (CLAUDE.md Rule 13/"How Claude Should Work" #3 — do not treat a
   clean build as equivalent to on-device verification).
   **UPDATE, same session**: now installed and verified on the real S24
   FE test device. Marker, directional-arrow rotation, route line,
   destination pin, outage-anchor line, heading-up camera rotation, and
   follow/recenter all confirmed working, after finding and fixing three
   real on-device bugs (a style-load race that silently wiped custom
   layers; marker rotation not compensating for map bearing; an
   unguarded per-frame camera update fighting the recenter button — see
   `docs/PROJECT_MAP.md`'s `ui/map/StreetMapView.kt` entry for the full
   writeup). `:app:testDebugUnitTest` and `:app:assembleDebug` both pass;
   empty crash buffer on a fresh install. The preserved, unmodified
   osmdroid+OSRM version still lives in the sibling
   `C:\projects\26168-osmdroid` folder as a working fallback/comparison,
   currently the more battle-tested of the two.)
3. Never replace a simple deterministic solution with ML unless ML
   provides measurable value (a real comparison against the physics
   baseline, not an assumption).
4. Never build an out-of-scope feature (`PRD.md` Section 7) just because
   it sounds impressive for the demo.
5. Every new file must have one clearly stated responsibility — if you
   can't summarize what a file does in one sentence, split it.
6. Every architecturally significant change (new module, changed data
   flow, changed model I/O, changed state-machine behavior) must update
   `docs/PROJECT_MAP.md` in the same change, not "later."

## Android Rules

7. Do not block the Android main/UI thread with sensor processing,
   feature extraction, or inference — use a background
   thread/coroutine and hand results back via a thread-safe state
   holder.
8. Do not hard-code fake GPS trajectories or fake outages in the final
   app build. Simulated/injected outages are permitted only in clearly
   separated test tooling, never in the shipped demo path (see
   PRD.md Section 32 for the legitimate testing-fallback distinction).

## Sensor Processing Rules

9. Do not silently change sensor coordinate conventions (device frame
   vs. vehicle frame vs. world frame) — every transformation must be
   named and documented at the point it happens.
10. Sensor processing (filtering, feature extraction) must be scoped and
    tested for real-time mobile execution at ~10 Hz — no step that can't
    keep up with the live sample rate.

## ML Rules

11. Prefer the lightest model that meets the accuracy bar found during
    real evaluation (Random Forest / Gradient Boosting / small MLP
    before any temporal/deep architecture) — see PRD.md Section 12–14
    for the decision process.
12. ML inference must be lightweight enough for on-device execution;
    latency and model size are measured on the actual target device,
    not assumed from desktop numbers, before being called "done."
13. Do not claim any benchmark or accuracy number that has not been
    measured on this project's own data/device. No numbers are invented
    for the PRD, the code comments, or the demo script.

## Coordinate System Rules

14. State explicitly, at every function boundary that touches
    orientation or position, which frame the input/output is in (device,
    vehicle, or world/ENU) — see PRD.md Section 11.

## Units

15. Use explicit units in names/comments wherever ambiguity is possible:
    `m`, `m/s`, `m/s²`, `rad`, `rad/s`, `degrees`, `Hz`, `ms`. Never mix
    radians and degrees silently across a function boundary.

## State Machine Rules

16. The GNSS↔DR state machine (`PRD.md` Section 18) must use hysteresis
    (separate enter/exit thresholds, minimum dwell time) — a single
    noisy sample must never flip the mode.
17. Every state transition must be logged (state, timestamp, trigger
    condition) so a test run can be replayed and the transition timing
    verified after the fact.

## Testing Rules

18. When an implementation approach is uncertain, build a small
    prototype/test first rather than a large abstraction around an
    unverified assumption (`PRD.md` Section 27).
19. Any deterministic math (transforms, integration, filters, state
    machine) gets a unit test before it is relied on by a downstream
    module.
20. Any ML model gets an output-parity check between the Python training
    environment and the on-device (ONNX/LiteRT) inference path before
    being wired into the app.

## Documentation Rules

21. `docs/PROJECT_MAP.md` must be updated whenever a source file is
    added, removed, or has its responsibility/interface changed. It
    documents purpose, inputs, outputs, callers/callees, pipeline
    position, dependencies, and any non-obvious math or assumption — see
    the template already in that file.
22. Code should be written to teach the student developer, not just to
    work: meaningful names, comments that explain *why* (not restating
    the syntax), and explicit documentation of any non-obvious math,
    coordinate transform, fusion logic, ML I/O, threshold rationale, or
    state-machine transition.

## File Organization

```
android/    — Kotlin app (sensors, inference, state machine, UI)
ml/         — Python training/eval pipeline
data/       — raw + processed datasets (IO-VNBD, self-captured)
models/     — versioned exported model artifacts (ONNX/LiteRT)
docs/       — PROJECT_MAP.md and any other living documentation
scripts/    — one-off tooling (dataset inspection, conversion, etc.)
tests/      — unit / integration / ML tests
```

ML experimentation stays out of `android/`; only exported, versioned
model artifacts cross that boundary.

## Git / Change Management

- Commit in the vertical-slice increments defined in `PRD.md`/the
  slice plan below — not as one giant final commit.
- A commit that adds a new source file without a corresponding
  `docs/PROJECT_MAP.md` update is incomplete.

## What Not To Build

See `PRD.md` Section 7 for the authoritative list (custom deep-learning
map matcher, 3D SLAM, CV localization, full routing engine, custom map
renderer, lane-level localization, multi-phone support, FOG IMU, custom
hardware, OBD-II integration, large backend, large model training,
general maps competitor, automatic vehicle-type classification). If any
of these starts to appear as "just a small version," stop and check
`PRD.md` Section 32 (Fallback Strategies) first.

## Definition of Done

See `PRD.md` Section 35. In short: real device, real sensors, no faked
data in the shipped path, at least one measured real-world drift result,
`docs/PROJECT_MAP.md` current, no out-of-scope subsystem present.

## How Claude Should Work

1. Do not start implementing the whole application at once.
2. Work in small vertical slices, in this order (adjust only if a
   dependency genuinely forces reordering — note the reason in
   `docs/PROJECT_MAP.md` if so):
   1. Android sensor → live sensor display
   2. Sensor → orientation
   3. Sensor → baseline (physics) velocity/position
   4. GNSS outage detection
   5. Dead reckoning (state machine + ZUPT + non-holonomic constraint)
   6. ML inference (velocity + motion classifier) wired in
   7. Fusion / re-alignment on GNSS reacquisition
   8. Map constraint / UI
   9. End-to-end demo run + metric capture
3. After each slice: confirm it actually runs on-device against real
   sensors before moving to the next slice.
4. Before writing implementation code for a new subsystem, re-check the
   relevant `PRD.md` section and this file's rules.
5. Surface the highest-risk assumption for the next slice explicitly,
   and prototype that risk first if it's cheap to de-risk early.
