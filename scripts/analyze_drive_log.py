"""Validate the app's "engineering default, not yet validated" thresholds
against one real test drive's log (single responsibility, CLAUDE.md
Rule 5) - the offline half of CLAUDE.md Rule 18's "build a small
prototype/test first rather than a large abstraction around an
unverified assumption": the ANDROID side of that prototype is
android/.../capture/DriveDataLogger.kt (Start/Stop button on the debug
screen), which writes a CSV of GNSS+DR state at ~10 Hz through a real
drive; this script is what turns that CSV into a real answer for the
three constants flagged as unvalidated:

  - gnss/GnssQuality.kt:      DEFAULT_MAX_FIX_AGE_MS, DEFAULT_MAX_ACCURACY_M
  - gnss/GnssOutageDetector.kt: outageEnterDwellMs, transitionDwellMs,
                                reacquisitionEnterDwellMs, reacquisitionDwellMs
  - dr/StationaryDetector.kt: DEFAULT_MAX_LINEAR_ACCEL_MPS2,
                              DEFAULT_MAX_GYRO_RAD_PER_SEC,
                              DEFAULT_MIN_STATIONARY_DWELL_MS

HONEST LIMITATION (CLAUDE.md Rule 13): this script only REPORTS what the
real drive's data suggests - it does not edit the Kotlin constants
itself. Changing a threshold that trades off false-positive vs
false-negative ZUPT (or a flappier vs slower-to-react GNSS state
machine) is a judgment call a human should make after seeing the real
numbers, not something to silently auto-apply.

Usage:
    python scripts/analyze_drive_log.py path/to/drive_log_<ts>.csv

The CSV comes from the phone via:
    adb pull /sdcard/Android/data/com.sih26168.idr/files/drive_log_<ts>.csv
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pandas as pd

# Same GNSS-good speed epsilon StatusOverlayContent.kt's own
# STATIONARY_SPEED_EPSILON_MPS uses for its "Stationary" motion label -
# reused here (not re-guessed) so this script's "GNSS says stopped" ground
# truth matches what the app itself already treats as stationary.
GNSS_STATIONARY_SPEED_EPSILON_MPS = 0.3

# The three constants this script exists to check, restated here ONLY as
# a fixed reference point for the report below - the source of truth
# remains each Kotlin file's own `DEFAULT_*` constant, not this copy.
CURRENT_ZUPT_MAX_ACCEL_MPS2 = 0.25
CURRENT_ZUPT_MAX_GYRO_RADPS = 0.05
CURRENT_ZUPT_MIN_DWELL_MS = 300
CURRENT_GNSS_MAX_ACCURACY_M = 25.0
# Raised from 3000 to 7000 on 2026-09-04 - see GnssQuality.kt's own doc and
# docs/gnss-indoor-window-degradation.md for the real drive log that found
# fresh fixes only arriving every ~6.2-6.5s indoors, not the accuracy bar,
# was the actual cause of GNSS_AIDED<->DEAD_RECKONING flapping near a window.
CURRENT_GNSS_MAX_FIX_AGE_MS = 7000
CURRENT_OUTAGE_ENTER_DWELL_MS = 2000
CURRENT_TRANSITION_DWELL_MS = 1000
CURRENT_REACQUISITION_ENTER_DWELL_MS = 2000
CURRENT_REACQUISITION_DWELL_MS = 1000


def load_log(csv_path: Path) -> pd.DataFrame:
    df = pd.read_csv(csv_path)
    required = {
        "elapsedMs", "gnssMode", "gnssFixAccuracyM", "gnssFixAgeMs", "gnssSpeedMps",
        "drVelocityEastMps", "drVelocityNorthMps", "linearAccelMagnitudeMps2",
        "gyroMagnitudeRadPerSec", "isStationary",
    }
    missing = required - set(df.columns)
    if missing:
        raise ValueError(f"{csv_path} is missing expected column(s): {sorted(missing)}")
    return df


# rawLinearAccelMagnitudeMps2/rawGyroMagnitudeRadPerSec were added 2026-09-01
# (after this drive log) so an older CSV won't have them - every report
# function that uses them must degrade gracefully, not crash.
def has_raw_columns(df: pd.DataFrame) -> bool:
    return {"rawLinearAccelMagnitudeMps2", "rawGyroMagnitudeRadPerSec"}.issubset(df.columns)


def report_overview(df: pd.DataFrame) -> None:
    duration_s = (df["elapsedMs"].iloc[-1] - df["elapsedMs"].iloc[0]) / 1000.0
    observed_hz = len(df) / duration_s if duration_s > 0 else float("nan")
    print("=== Overview ===")
    print(f"Ticks: {len(df)}   Duration: {duration_s:.1f}s   Observed rate: {observed_hz:.1f} Hz")
    print("Mode tick counts:")
    print(df["gnssMode"].value_counts().to_string())
    print()


def report_mode_segments(df: pd.DataFrame) -> None:
    # A "segment" is a maximal run of consecutive rows in the same
    # gnssMode - its real elapsed duration is the actual dwell the phone
    # experienced in that mode this drive, directly comparable against
    # GnssOutageDetector's fixed dwell constants (which decide WHEN a
    # segment is allowed to end, not how long a real one lasts).
    segment_id = (df["gnssMode"] != df["gnssMode"].shift()).cumsum()
    segments = df.groupby(segment_id).agg(
        mode=("gnssMode", "first"),
        start_ms=("elapsedMs", "first"),
        end_ms=("elapsedMs", "last"),
    )
    segments["duration_ms"] = segments["end_ms"] - segments["start_ms"]

    print("=== Real GNSS-mode segment durations ===")
    print("(compare against GnssOutageDetector's dwell constants: "
          f"outageEnter={CURRENT_OUTAGE_ENTER_DWELL_MS}ms, "
          f"transition={CURRENT_TRANSITION_DWELL_MS}ms, "
          f"reacquisitionEnter={CURRENT_REACQUISITION_ENTER_DWELL_MS}ms, "
          f"reacquisition={CURRENT_REACQUISITION_DWELL_MS}ms)")
    for mode in ["GNSS_AIDED", "TRANSITION", "DEAD_RECKONING", "REACQUISITION"]:
        mode_segments = segments[segments["mode"] == mode]["duration_ms"]
        if mode_segments.empty:
            print(f"  {mode}: no segments this drive")
            continue
        print(
            f"  {mode}: n={len(mode_segments)} "
            f"min={mode_segments.min():.0f}ms median={mode_segments.median():.0f}ms "
            f"max={mode_segments.max():.0f}ms",
        )
    print()


def report_gnss_quality(df: pd.DataFrame) -> None:
    aided = df[df["gnssMode"] == "GNSS_AIDED"]
    print("=== GNSS fix quality while GNSS_AIDED ===")
    print("(compare against GnssQuality's thresholds: "
          f"maxAccuracy={CURRENT_GNSS_MAX_ACCURACY_M}m, maxFixAge={CURRENT_GNSS_MAX_FIX_AGE_MS}ms)")
    if aided.empty or aided["gnssFixAccuracyM"].isna().all():
        print("  No GNSS_AIDED rows with a real accuracy value this drive.")
    else:
        acc = aided["gnssFixAccuracyM"].dropna()
        print(
            f"  accuracyM: p50={acc.quantile(.50):.1f} p90={acc.quantile(.90):.1f} "
            f"p99={acc.quantile(.99):.1f} max={acc.max():.1f}",
        )
    print()


def fresh_fix_intervals_ms(df: pd.DataFrame) -> list[float]:
    """Elapsed time between consecutive FRESH GNSS fixes, in order.

    A "fresh fix" is detected the same way GnssOutageDetector experiences
    one: gnssFixAgeMs otherwise climbs roughly in step with wall-clock time
    between updates (no new data arrived), so a row where it DROPS versus
    the previous row means a new fix just landed and reset the age counter.
    This is the pure/testable half of report_fix_refresh_cadence below
    (CLAUDE.md Rule 19) - it was this exact computation, run by hand against
    docs/gnss-indoor-window-degradation.md's real drive log, that found the
    ~6.2-6.5s indoor refresh cadence responsible for the flapping (not
    accuracy - see GnssQuality.kt's DEFAULT_MAX_FIX_AGE_MS doc).
    """
    fresh_fix_rows = df[df["gnssFixAgeMs"].diff() < 0]
    elapsed = fresh_fix_rows["elapsedMs"].tolist()
    return [b - a for a, b in zip(elapsed, elapsed[1:])]


def report_fix_refresh_cadence(df: pd.DataFrame) -> None:
    print("=== Real fix refresh cadence (time between fresh GNSS fixes) ===")
    print(f"(compare against GnssQuality.DEFAULT_MAX_FIX_AGE_MS={CURRENT_GNSS_MAX_FIX_AGE_MS}ms)")
    intervals = fresh_fix_intervals_ms(df)
    if len(intervals) < 2:
        print(f"  Only {len(intervals)} fresh-fix interval(s) observed - not enough to characterize cadence.")
        print()
        return
    s = pd.Series(intervals)
    print(f"  n={len(s)} intervals: min={s.min():.0f}ms median={s.median():.0f}ms max={s.max():.0f}ms")
    if s.max() >= CURRENT_GNSS_MAX_FIX_AGE_MS:
        print(
            f"  WARNING: observed max interval ({s.max():.0f}ms) meets or exceeds "
            f"DEFAULT_MAX_FIX_AGE_MS ({CURRENT_GNSS_MAX_FIX_AGE_MS}ms) - a fix can go stale "
            "before the next one arrives, which is exactly the flapping failure mode.",
        )
    else:
        print(
            f"  Observed max interval ({s.max():.0f}ms) stays under "
            f"DEFAULT_MAX_FIX_AGE_MS ({CURRENT_GNSS_MAX_FIX_AGE_MS}ms) - a fix should not go "
            "stale between updates at this drive's cadence.",
        )
    print()


def report_degraded_mode_accuracy(df: pd.DataFrame) -> None:
    # Added 2026-09-04 for docs/gnss-indoor-window-degradation.md (option A):
    # report_gnss_quality above only looks at accuracy while ALREADY
    # GNSS_AIDED - it can't tell us anything about the fixes GnssQuality
    # rejected, which is exactly what decided TRANSITION/DEAD_RECKONING/
    # REACQUISITION dwell time in the window-flapping case. This looks at
    # the OTHER side: every row isGood() called "bad", split into "no fix
    # at all" (accuracyM is null - genuinely nothing to work with) vs "a
    # real fix that just missed the bar" (accuracyM present but >
    # maxAccuracyM, e.g. multipath near a window) - only the second group
    # is evidence FOR loosening DEFAULT_MAX_ACCURACY_M; a mostly-null
    # degraded population would mean the accuracy bar isn't the problem.
    degraded = df[df["gnssMode"] != "GNSS_AIDED"]
    print("=== Fix quality during degraded modes (TRANSITION/DEAD_RECKONING/REACQUISITION) ===")
    print(f"(compare against GnssQuality.DEFAULT_MAX_ACCURACY_M={CURRENT_GNSS_MAX_ACCURACY_M}m)")
    if degraded.empty:
        print("  No non-GNSS_AIDED rows this drive - GNSS never degraded.")
        print()
        return

    no_fix = degraded[degraded["gnssFixAccuracyM"].isna()]
    weak_fix = degraded[degraded["gnssFixAccuracyM"].notna()]
    print(f"  Rows with no fix at all (accuracyM missing): {len(no_fix)}/{len(degraded)} "
          f"({100 * len(no_fix) / len(degraded):.1f}%)")
    print(f"  Rows with a real fix that missed the bar:    {len(weak_fix)}/{len(degraded)} "
          f"({100 * len(weak_fix) / len(degraded):.1f}%)")

    if weak_fix.empty:
        print("  No 'weak but present' fixes this drive - nothing here would justify loosening "
              "DEFAULT_MAX_ACCURACY_M; the degraded time was genuinely fix-less.")
        print()
        return

    acc = weak_fix["gnssFixAccuracyM"]
    print(f"  Weak-fix accuracyM: p50={acc.quantile(.50):.1f} p90={acc.quantile(.90):.1f} "
          f"p99={acc.quantile(.99):.1f} max={acc.max():.1f}")

    # How many degraded-mode rows would flip to "good" at each candidate
    # looser bar, so a threshold choice is read off real data, not guessed
    # (CLAUDE.md Rule 13) - mirrors sweep_zupt_thresholds' approach.
    print("  If DEFAULT_MAX_ACCURACY_M were raised, rows that would newly pass:")
    for candidate in [30.0, 35.0, 40.0, 50.0, 60.0, 75.0, 100.0]:
        newly_good = (weak_fix["gnssFixAccuracyM"] <= candidate).sum()
        print(f"    <= {candidate:>5.0f}m: {newly_good}/{len(degraded)} "
              f"({100 * newly_good / len(degraded):.1f}% of all degraded-mode rows)")
    print()


def report_zupt_validation(df: pd.DataFrame) -> None:
    # GNSS speed while GNSS_AIDED is used as an INDEPENDENT ground truth
    # for "was the vehicle actually moving" - independent of the
    # accel/gyro-based ZUPT decision being validated, which is the whole
    # point (validating ZUPT against ITS OWN inputs would be circular).
    aided = df[(df["gnssMode"] == "GNSS_AIDED") & df["gnssSpeedMps"].notna()]
    if aided.empty:
        print("=== ZUPT threshold check ===")
        print("  No GNSS_AIDED rows with a real speed value this drive - cannot cross-check "
              "ZUPT against an independent ground truth from this log.")
        print()
        return

    truly_stationary = aided[aided["gnssSpeedMps"] < GNSS_STATIONARY_SPEED_EPSILON_MPS]
    truly_moving = aided[aided["gnssSpeedMps"] >= GNSS_STATIONARY_SPEED_EPSILON_MPS]

    print("=== ZUPT threshold check (ground truth: GNSS speed while GNSS_AIDED) ===")
    print(f"(compare against StationaryDetector's thresholds: "
          f"maxAccel={CURRENT_ZUPT_MAX_ACCEL_MPS2}m/s^2, maxGyro={CURRENT_ZUPT_MAX_GYRO_RADPS}rad/s, "
          f"minDwell={CURRENT_ZUPT_MIN_DWELL_MS}ms)")
    print(f"  Rows where GNSS speed < {GNSS_STATIONARY_SPEED_EPSILON_MPS} m/s (truly stationary): {len(truly_stationary)}")
    print(f"  Rows where GNSS speed >= {GNSS_STATIONARY_SPEED_EPSILON_MPS} m/s (truly moving):     {len(truly_moving)}")

    if not truly_stationary.empty:
        a, g = truly_stationary["linearAccelMagnitudeMps2"], truly_stationary["gyroMagnitudeRadPerSec"]
        print(
            f"  Truly-stationary accel magnitude: p50={a.quantile(.50):.3f} p95={a.quantile(.95):.3f} "
            f"p99={a.quantile(.99):.3f} max={a.max():.3f} m/s^2",
        )
        print(
            f"  Truly-stationary gyro  magnitude: p50={g.quantile(.50):.3f} p95={g.quantile(.95):.3f} "
            f"p99={g.quantile(.99):.3f} max={g.max():.3f} rad/s",
        )
    if not truly_moving.empty:
        a, g = truly_moving["linearAccelMagnitudeMps2"], truly_moving["gyroMagnitudeRadPerSec"]
        print(
            f"  Truly-moving accel magnitude:     p01={a.quantile(.01):.3f} p05={a.quantile(.05):.3f} "
            f"p50={a.quantile(.50):.3f} m/s^2",
        )
        print(
            f"  Truly-moving gyro  magnitude:     p01={g.quantile(.01):.3f} p05={g.quantile(.05):.3f} "
            f"p50={g.quantile(.50):.3f} rad/s",
        )

    # Confusion matrix: does StationaryDetector's real isStationary flag
    # (already computed on-device, not recomputed here) agree with the
    # independent GNSS-speed ground truth?
    if not truly_stationary.empty and not truly_moving.empty:
        false_negatives = (~truly_stationary["isStationary"]).sum()  # truly stopped, ZUPT missed it
        false_positives = truly_moving["isStationary"].sum()  # truly moving, ZUPT wrongly zeroed velocity
        print(
            f"  ZUPT false negatives (truly stopped, not flagged): "
            f"{false_negatives}/{len(truly_stationary)} ({100 * false_negatives / len(truly_stationary):.1f}%)",
        )
        print(
            f"  ZUPT false positives (truly moving, wrongly flagged stationary): "
            f"{false_positives}/{len(truly_moving)} ({100 * false_positives / len(truly_moving):.1f}%)",
        )
    print()


# 2026-09-01: added after the first real outdoor drive found ZUPT 100%
# false-negative (see report_zupt_validation above) - before assuming a
# different fixed accel/gyro threshold would fix it (CLAUDE.md Rule 18:
# prototype/test an assumption before building on it), this sweeps a grid
# of candidate thresholds against the SAME independent GNSS-speed ground
# truth and reports whether ANY combination separates the two classes
# well, or whether the ceiling itself is the real finding.
def sweep_zupt_thresholds(
    df: pd.DataFrame,
    accel_grid: list[float] | None = None,
    gyro_grid: list[float] | None = None,
) -> pd.DataFrame:
    aided = df[(df["gnssMode"] == "GNSS_AIDED") & df["gnssSpeedMps"].notna()]
    truly_stationary = aided[aided["gnssSpeedMps"] < GNSS_STATIONARY_SPEED_EPSILON_MPS]
    truly_moving = aided[aided["gnssSpeedMps"] >= GNSS_STATIONARY_SPEED_EPSILON_MPS]

    if accel_grid is None:
        accel_grid = [round(0.25 + 0.25 * i, 2) for i in range(16)]  # 0.25 .. 4.00
    if gyro_grid is None:
        gyro_grid = [round(0.05 + 0.05 * i, 2) for i in range(20)]  # 0.05 .. 1.00

    rows = []
    for accel_thresh in accel_grid:
        for gyro_thresh in gyro_grid:
            if truly_stationary.empty or truly_moving.empty:
                continue
            predicted_stationary_ts = (
                (truly_stationary["linearAccelMagnitudeMps2"] <= accel_thresh)
                & (truly_stationary["gyroMagnitudeRadPerSec"] <= gyro_thresh)
            )
            predicted_stationary_tm = (
                (truly_moving["linearAccelMagnitudeMps2"] <= accel_thresh)
                & (truly_moving["gyroMagnitudeRadPerSec"] <= gyro_thresh)
            )
            false_negative_rate = 1.0 - predicted_stationary_ts.mean()
            false_positive_rate = predicted_stationary_tm.mean()
            rows.append(
                {
                    "accel_thresh_mps2": accel_thresh,
                    "gyro_thresh_radps": gyro_thresh,
                    "false_negative_rate": false_negative_rate,
                    "false_positive_rate": false_positive_rate,
                    "combined_error_rate": false_negative_rate + false_positive_rate,
                },
            )
    return pd.DataFrame(rows)


def report_zupt_threshold_sweep(df: pd.DataFrame) -> None:
    print("=== ZUPT threshold sweep (which accel/gyro combo minimizes real error?) ===")
    sweep = sweep_zupt_thresholds(df)
    if sweep.empty:
        print("  Not enough GNSS_AIDED rows with both stationary and moving ground truth to sweep.")
        print()
        return

    best_combined = sweep.sort_values("combined_error_rate").iloc[0]
    print(
        "  Best combined (FN+FP) point: accel<="
        f"{best_combined['accel_thresh_mps2']:.2f}m/s^2, gyro<={best_combined['gyro_thresh_radps']:.2f}rad/s -> "
        f"FN={100 * best_combined['false_negative_rate']:.1f}%, FP={100 * best_combined['false_positive_rate']:.1f}%",
    )

    tight_fp = sweep[sweep["false_positive_rate"] <= 0.01].sort_values("false_negative_rate")
    if tight_fp.empty:
        print(
            "  No swept combination keeps false positives <=1% - the two classes are not cleanly "
            "separable by accel/gyro magnitude alone on this drive (this matches "
            "StationaryDetector.kt's own documented 'constant-velocity motion looks stationary too' "
            "limitation, now measured rather than assumed).",
        )
    else:
        best_tight = tight_fp.iloc[0]
        print(
            "  Best FN at FP<=1%: accel<="
            f"{best_tight['accel_thresh_mps2']:.2f}m/s^2, gyro<={best_tight['gyro_thresh_radps']:.2f}rad/s -> "
            f"FN={100 * best_tight['false_negative_rate']:.1f}%, FP={100 * best_tight['false_positive_rate']:.1f}%",
        )
    print(
        "  Reminder: this sweep only re-scores the FILTERED signal already in this log against "
        "different thresholds - it cannot test a different LowPassFilter cutoffHz. That needs a "
        "log with rawLinearAccelMagnitudeMps2/rawGyroMagnitudeRadPerSec (added 2026-09-01) from a "
        "future drive - see report_raw_vs_filtered below.",
    )
    print()


def report_raw_vs_filtered(df: pd.DataFrame) -> None:
    print("=== Raw vs. filtered ZUPT signal (needs a 2026-09-01+ drive log) ===")
    if not has_raw_columns(df):
        print(
            "  This log predates rawLinearAccelMagnitudeMps2/rawGyroMagnitudeRadPerSec - only the "
            "post-filter signal was captured, so the LowPassFilter cutoffHz itself can't be "
            "re-tuned from this drive. Capture a new drive log to compare raw vs. filtered.",
        )
        print()
        return

    aided = df[(df["gnssMode"] == "GNSS_AIDED") & df["gnssSpeedMps"].notna()]
    truly_stationary = aided[aided["gnssSpeedMps"] < GNSS_STATIONARY_SPEED_EPSILON_MPS]
    if truly_stationary.empty:
        print("  No truly-stationary rows this drive to compare.")
        print()
        return

    raw_a = truly_stationary["rawLinearAccelMagnitudeMps2"]
    filt_a = truly_stationary["linearAccelMagnitudeMps2"]
    print(
        f"  Truly-stationary accel magnitude: raw p50={raw_a.quantile(.5):.3f} "
        f"filtered p50={filt_a.quantile(.5):.3f} m/s^2 "
        f"(filter removed {100 * (1 - filt_a.quantile(.5) / raw_a.quantile(.5)):.0f}% of median magnitude)"
        if raw_a.quantile(.5) > 0
        else "  Truly-stationary raw accel magnitude is ~0 - cannot compute a meaningful ratio.",
    )
    print()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_path", type=Path, help="Path to a drive_log_*.csv pulled from the device")
    args = parser.parse_args()

    if not args.csv_path.exists():
        print(f"error: {args.csv_path} does not exist", file=sys.stderr)
        return 1

    df = load_log(args.csv_path)
    report_overview(df)
    report_mode_segments(df)
    report_gnss_quality(df)
    report_fix_refresh_cadence(df)
    report_degraded_mode_accuracy(df)
    report_zupt_validation(df)
    report_zupt_threshold_sweep(df)
    report_raw_vs_filtered(df)

    print("Reminder: these numbers describe ONE drive. Treat single-drive results as a first "
          "signal, not a final calibration - CLAUDE.md Rule 13 still applies to whatever you do "
          "with this report (don't claim a threshold is 'validated' off one short test drive).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
