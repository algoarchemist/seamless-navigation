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
CURRENT_GNSS_MAX_FIX_AGE_MS = 3000
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
    report_zupt_validation(df)

    print("Reminder: these numbers describe ONE drive. Treat single-drive results as a first "
          "signal, not a final calibration - CLAUDE.md Rule 13 still applies to whatever you do "
          "with this report (don't claim a threshold is 'validated' off one short test drive).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
