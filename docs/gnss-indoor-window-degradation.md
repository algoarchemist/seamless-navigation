# Why GNSS-aided mode drops to Dead Reckoning near an indoor window

## RESOLVED 2026-09-04 — option A ("capture real data, then tune") executed

**The original root-cause hypothesis below (accuracy/multipath) was WRONG,
and real data is what caught it.** A 135s drive log captured next to the
same window (`scripts/analyze_drive_log.py`, run against
`drive_log_1788515295967.csv` pulled via `adb pull`) shows:

- **Accuracy was never the problem.** Every single row during the flap —
  1347/1347 — had a real, present fix with accuracy between 3.5m and 10.5m,
  nowhere close to `GnssQuality.DEFAULT_MAX_ACCURACY_M`'s 25m bar. The
  "weak but real fix vs. fully lost" distinction this doc originally worried
  about never came up this session — GNSS reception near this window was
  actually excellent whenever a fix arrived.
- **The real cause was fix STALENESS.** Fresh fixes only arrived every
  ~6.2–6.5s indoors near this window (20 measured intervals:
  min=6219ms, median=6316ms, max=6539ms) — more than double the old
  `DEFAULT_MAX_FIX_AGE_MS` of 3000ms. That alone guarantees each fix goes
  "stale" by the state machine's own definition for roughly half of every
  refresh cycle, regardless of how accurate it is. Combined with
  `GnssOutageDetector`'s dwell timers, the "good" window after each fresh
  fix (~2.8s before the old 3000ms bar re-tripped) was too short to clear
  `reacquisitionEnterDwellMs` (2000ms) with enough of it left over to also
  clear `reacquisitionDwellMs` (1000ms) — REACQUISITION could never win the
  race back to GNSS_AIDED, so the mode oscillated between DEAD_RECKONING
  and REACQUISITION indefinitely (this capture: 21 DEAD_RECKONING segments,
  22 REACQUISITION segments, GNSS_AIDED reached zero times in 135s).

**Fix applied:** `GnssQuality.DEFAULT_MAX_FIX_AGE_MS` raised `3000ms →
7000ms` — comfortably past the measured 6539ms worst case, not an
arbitrarily large number (CLAUDE.md Rule 13: measured, not guessed).
Re-running this session's analysis with the new constant confirms the
observed max interval (6539ms) now stays under the bar, so this exact
capture would no longer have flapped.

**Disclosed tradeoff:** real total-GNSS-loss detection latency grows from
~3s to ~7s before a fix is even considered stale — a real cost, accepted
because the old value made this entire indoor scenario undemoable, which
is the worse failure mode. Still a single-drive, single-location
measurement — not yet validated on a different device/location, or against
a real full outdoor GNSS outage (that latency tradeoff is exactly what a
future outdoor test should check).

**What changed:**
- `android/.../gnss/GnssQuality.kt` — `DEFAULT_MAX_FIX_AGE_MS` 3000→7000ms,
  doc comment records the measured evidence.
- `android/.../gnss/GnssQualityTest.kt` — new regression test pinning the
  measured 6539ms/10.5m case as still "good."
- `scripts/analyze_drive_log.py` — two new report functions:
  `report_fix_refresh_cadence` (the one that actually found this) and
  `report_degraded_mode_accuracy` (checked the original accuracy hypothesis
  and ruled it out for this drive).
- `tests/scripts/test_analyze_drive_log.py` — unit tests for the new
  `fresh_fix_intervals_ms` helper (synthetic data, CLAUDE.md Rule 19).
- `docs/PROJECT_MAP.md` — `GnssQuality.kt` entry updated with this finding.

**Why the original hypothesis was a reasonable guess that turned out
wrong:** the code comments and prior bug fixes (2026-08-26, 2026-09-02)
really had seen accuracy-driven degradation before, so accuracy was the
natural first suspect. This session's data simply belongs to a different
failure mode at the same symptom (window-adjacent flapping) — both are
real, they just aren't the same bug. The rest of this document is kept
below, unedited, as the original (superseded) hypothesis and reasoning.

---


## Problem statement

Reported by the user: sitting indoors with a window open, the app switches
from GNSS-aided mode to DEAD_RECKONING — and can flap back and forth through
REACQUISITION — even though the open window should let *some* GPS signal
through.

This is not a crash and not random noise. It is the GNSS↔DR state machine
(`PRD.md` Section 18) doing exactly what it is designed to do, reacting to a
real, measured drop in the phone's self-reported GPS accuracy. The gap is
that the app currently has only one signal to judge GNSS quality by
(accuracy in meters), and that signal cannot tell "weak but real fix" apart
from "fix fully lost."

## Live evidence captured today

Pulled directly from the user's phone via `adb logcat -s GnssOutageDetector`
during a live indoor test next to an open window (2026-09-02, times are
device-local):

```
19:25:47.012  GNSS_AIDED -> TRANSITION      : GNSS degraded/lost for >= 2000ms
19:25:48.017  TRANSITION -> DEAD_RECKONING  : TRANSITION window elapsed, GNSS still degraded/lost
19:26:00.898  DEAD_RECKONING -> REACQUISITION: GNSS good for >= 2000ms
19:26:03.720  REACQUISITION -> DEAD_RECKONING: GNSS degraded continuously for >= 2000ms during REACQUISITION window
```

A full outage → recovery attempt → re-outage cycle in under 17 seconds,
while the phone was stationary indoors. This matches a scenario the code's
own comments already anticipated (see below) — it is real, reproduced,
device-captured behavior, not a hypothesis.

## Root cause

**The quality gate is a hard binary check on one signal: self-reported GPS accuracy.**

`android/app/src/main/kotlin/com/sih26168/idr/gnss/GnssQuality.kt:55-65`
```kotlin
fun isGood(
    fixAgeMs: Long,
    accuracyM: Float?,
    maxFixAgeMs: Long = DEFAULT_MAX_FIX_AGE_MS,   // 3_000L  (GnssQuality.kt:17)
    maxAccuracyM: Float = DEFAULT_MAX_ACCURACY_M, // 25f     (GnssQuality.kt:21)
) : Boolean {
    if (accuracyM == null) return false
    if (fixAgeMs > maxFixAgeMs) return false
    if (accuracyM > maxAccuracyM) return false
    return true
}
```

A fix is "bad" the instant `Location.getAccuracy()` exceeds 25 m, or the fix
is older than 3 s, or accuracy is missing entirely. There is **no
satellite-count, CN0 (signal-to-noise), or HDOP/PDOP check anywhere in the
codebase** — confirmed by search across `android/app/src/main/kotlin`.

**Only `FusedLocationProviderClient` output is read — no raw GNSS status.**

`android/app/src/main/kotlin/com/sih26168/idr/gnss/LocationRepository.kt:24,58,84`
```kotlin
private const val GNSS_UPDATE_INTERVAL_MS = 1_000L
...
accuracyM = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE,
...
val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GNSS_UPDATE_INTERVAL_MS).build()
```

At 1 Hz, via Google's Fused Location Provider only. Missing accuracy is
deliberately mapped to `Float.MAX_VALUE` (worst case) rather than a
misleading 0 — that part is correct defensive coding — but there's no
`GnssStatus.Callback` registered anywhere, so satellite count / CN0 data is
architecturally unavailable to the app even if `GnssQuality` wanted to use
it.

**The state machine's hysteresis itself is working correctly.**

`android/app/src/main/kotlin/com/sih26168/idr/gnss/GnssOutageDetector.kt:81-85`
```kotlin
private val outageEnterDwellMs: Long = 2_000L,        // GNSS_AIDED -> TRANSITION
private val reacquisitionEnterDwellMs: Long = 2_000L, // DEAD_RECKONING -> REACQUISITION
private val transitionDwellMs: Long = 1_000L,         // TRANSITION -> DEAD_RECKONING (fixed window)
private val reacquisitionDwellMs: Long = 1_000L,      // REACQUISITION -> GNSS_AIDED
private val reacquisitionExitDwellMs: Long = 2_000L,  // REACQUISITION -> DEAD_RECKONING (bail-out)
```

Every transition requires a *continuous* streak of good/bad samples for its
dwell time — a single noisy sample can't flip the mode, satisfying CLAUDE.md
Rule 16. This part of the system is not the bug. The bug potential is
entirely upstream, in the coarseness of the one signal (`accuracyM`) feeding
these dwell timers.

**This gap is already known and documented, not new.**

- `PRD.md` FR2 explicitly specifies detecting degradation via *"fix loss,
  HDOP/accuracy threshold, **satellite count where available**"* — only the
  accuracy half of this requirement is implemented today.
- Code comments in `GnssQuality.kt`, `StateEstimator.kt`, and `MapScreen.kt`
  already flag "an 8.8s-old/29.7m-accuracy indoor fix near a window" as a
  previously observed real case where mode correctly stays in
  DEAD_RECKONING/TRANSITION per the current design.
- `docs/PROJECT_MAP.md` records two prior real bug fixes (2026-08-26 and
  2026-09-02) both triggered by this same "stationary indoors" condition —
  the most recent one (`reacquisitionExitDwellMs`, added in commit
  `7f4bd99`) specifically stopped REACQUISITION from bailing out on the
  *first* bad sample, which is why this test showed one clean 17s cycle
  instead of the faster multi-flap the earlier bug produced.

**Why a window specifically triggers this:** an open window commonly lets a
real GPS lock through, but the receiver's own accuracy estimate is still
degraded by multipath (signal bouncing off the window frame, walls, ceiling
before reaching the antenna) or reduced satellite geometry. The receiver
reports this as "accuracy = 30–50 m," which `isGood()` cannot distinguish
from "no signal at all" — both simply fail the 25 m bar.

## Possible solutions

These are presented as options, not a single mandated fix — which one to
take is a product/timebox decision for Round 2, not something to assume.

### A. Capture real data, then tune thresholds — *recommended*
Use the app's existing "Start drive log" feature (already built —
`capture/DriveDataLogger.kt` writes CSV, `scripts/analyze_drive_log.py`
analyzes it) to record actual accuracy values near the window for a few
minutes. Then set `GnssQuality.DEFAULT_MAX_ACCURACY_M` and/or
`GnssOutageDetector.outageEnterDwellMs` based on the measured numbers.
- **Effort:** low — no new code, just a capture run + constant edit.
- **Risk:** low — the new threshold is defensible because it's measured,
  satisfying CLAUDE.md Rule 13 ("no invented benchmark/threshold numbers").
- **Downside:** requires one real capture session before the constants can
  be changed with confidence.

### B. Quick threshold tune now
Loosen `DEFAULT_MAX_ACCURACY_M` (currently 25 f) and/or raise
`outageEnterDwellMs` (currently 2000 ms) immediately, using an engineering
estimate.
- **Effort:** trivial — a one-line constant change.
- **Risk:** medium — these constants are already commented in the code as
  "engineering defaults, not yet validated against a real outage test run."
  Loosening them further on a guess risks masking genuine outages (defeats
  the purpose of Rule 16's hysteresis) until it's later validated against
  real data anyway.

### C. Add satellite-count / CN0 as a second signal
Implement the fuller design PRD FR2 already specifies: register a
`GnssStatus.Callback` in `LocationRepository.kt`, surface satellite
count/CN0 alongside accuracy, and feed both into `GnssQuality` as a graded
signal — so "weak but present" (e.g. 4 satellites locked, poor accuracy) is
distinguished from "fully lost" (0 satellites).
- **Effort:** medium/high — new repository hook, new fields on `GnssFix`,
  new unit tests (CLAUDE.md Rule 19), a `docs/PROJECT_MAP.md` update
  (Rule 21).
- **Risk:** needs its own before/after validation to prove it actually
  reduces false DR-triggers versus option A/B, per the same
  "measure, don't assume" standard CLAUDE.md applies to ML (Rule 3) — this
  isn't ML, but the bar is the same in spirit.
- **Upside:** closes the actual FR2 gap rather than just re-tuning the
  existing coarse signal.

### D. No change
Treat the current behavior as confirmed working-as-designed for this MVP
(consistent with the existing code comments already calling out this exact
scenario) and leave the thresholds untouched.

## References

- `android/app/src/main/kotlin/com/sih26168/idr/gnss/GnssQuality.kt:17,21,55-65`
- `android/app/src/main/kotlin/com/sih26168/idr/gnss/LocationRepository.kt:24,58,84`
- `android/app/src/main/kotlin/com/sih26168/idr/gnss/GnssOutageDetector.kt:81-155`
- `PRD.md` — FR2 (GNSS degradation detection), Section 18 (state machine)
- `docs/PROJECT_MAP.md` — GNSS↔DR state machine module entry, prior bug-fix
  history (2026-08-26, 2026-09-02)
- `CLAUDE.md` Rules 3, 13, 16, 19, 21
