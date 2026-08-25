"""Phase 4 dataset inspection (PRD.md Section 24) for IO-VNBD.

Purpose (single responsibility): walk every smartphone trip CSV in the
downloaded IO-VNBD "Synchronised V and S datasets" folder, validate its
schema against the dataset's own documented column layout (Onyekpe et
al. 2020, Table 4 — see docs/PROJECT_MAP.md's Phase 4 findings for the
full writeup), compute the actual observed sample rate, flag any
schema deviations, and write a reusable manifest to
data/processed/io_vnbd_smartphone_manifest.csv. This turns the manual
inspection already recorded in docs/PROJECT_MAP.md into a reproducible,
rerunnable script — a one-off shell exploration is not a substitute for
this (CLAUDE.md Rule 18).

Deliberately does NOT do feature extraction, training, or the axis-
convention fix — see docs/PROJECT_MAP.md's Phase 4 findings for why
that fix belongs in feature_extraction.py (a separate file, separate
responsibility, CLAUDE.md Rule 5) once it's built.

IMPORTANT — only scans "Categorised IOVNB Dataset", not its sibling
"Uncategorised IOVNB Dataset": both contain the SAME underlying 72
trips (confirmed by diffing a sample pair — identical row counts,
identical values modulo float64 repr noise from a re-export, only the
column header text differs: e.g. "GYROSCOPE Yaw/Pitch/Roll" in
Categorised vs "GYROSCOPE X/Y/Z" in Uncategorised for the SAME numbers).
Scanning both would silently duplicate every trip in a downstream
train/val split, which would directly violate PRD.md Section 25's
"no leakage of the same drive across train and test" — this is not a
hypothetical risk, it's what naive `rglob("S-*.csv")` over the dataset
ROOT actually does (144 files found, 72 unique). "Categorised" is
additionally the better tree structurally: its S-/V- pairs sit in the
same subfolder (this script's vehicle-file pairing depends on that),
where "Uncategorised" splits S- and V- into separate flat folders.

Usage:
    python ml/inspect_dataset.py [--dataset-root PATH]

Default --dataset-root assumes the dataset was downloaded per
data/README.md's instructions into data/raw/IO-VNBD/extracted/.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass, field
from pathlib import Path

import pandas as pd

# Canonical smartphone column schema, in file order, per the dataset's
# own Table 4 (README_1.pdf). Assigned by POSITION, not by string-
# matching the raw header text — the raw headers are inconsistently
# byte-encoded (the accelerometer/gravity "m/s^2" unit strings mix
# UTF-8 and raw Latin-1 bytes within the same file, confirmed by
# inspecting the header bytes directly), so trusting exact string
# matches would be fragile. Units noted in each name per CLAUDE.md
# Rule 15 (explicit units wherever ambiguity is possible).
SMARTPHONE_COLUMNS_24 = [
    "gps_latitude_deg",
    "gps_longitude_deg",
    "gps_altitude_m",
    "gps_speed_kmh",
    "gps_accuracy_m",
    "gps_orientation_deg",
    "gps_satellites_in_range",  # raw format is "USED / VISIBLE", e.g. "27 / 28"
    "time_since_start_ms",
    "date_str",  # "YYYY-MO-DD HH-MI-SS_SSS"
    "accel_x_mps2",
    "accel_y_mps2",
    "accel_z_mps2",
    "gravity_x_mps2",
    "gravity_y_mps2",
    "gravity_z_mps2",
    "gyro_yaw_radps",  # see docs/PROJECT_MAP.md: NOT the same axis convention as
    "gyro_pitch_radps",  # Android's raw SensorEvent X/Y/Z (device frame) — this
    "gyro_roll_radps",  # dataset's axes are permuted/relabeled, confirmed from its own figure.
    "magnetic_field_x_ut",
    "magnetic_field_y_ut",
    "magnetic_field_z_ut",
    "orientation_yaw_deg",
    "orientation_pitch_deg",
    "orientation_roll_deg",
]

# Driver F's trips (S-T1..S-T9) are documented in README_1.pdf Table A7
# as missing orientation + magnetic-field columns — expect a 20-column
# variant for those files, not a corrupt file, per that documentation.
SMARTPHONE_COLUMNS_20_NO_ORIENTATION_OR_MAG = SMARTPHONE_COLUMNS_24[:18]

EXPECTED_HZ = 10.0
HZ_TOLERANCE = 2.0  # observed rate outside [8, 12] Hz gets flagged, not silently accepted

# README_1.pdf documents "GPS (smartphone) update rate of 1Hz" — flagged
# (not assumed correct) if the MEASURED interval is far off, since this
# directly affects how "elapsed time since last GNSS fix" should be
# computed as a training feature (PRD.md Section 13).
DOCUMENTED_GPS_FIX_INTERVAL_S = 1.0
GPS_FIX_INTERVAL_FLAG_THRESHOLD_S = 3.0


@dataclass
class TripInspection:
    relative_path: str
    driver_group: str
    trip_name: str
    n_rows: int
    n_columns: int
    schema_variant: str  # "24_full" / "20_no_orientation_mag" / "UNRECOGNISED"
    duration_s: float
    observed_hz: float
    hz_within_tolerance: bool
    observed_gps_fix_interval_s: float
    satellites_parse_failures: int
    has_paired_vehicle_file: bool
    vehicle_row_count: int | None
    notes: list[str] = field(default_factory=list)


def _load_smartphone_csv(path: Path) -> tuple[pd.DataFrame, str]:
    """Reads one S-*.csv by column POSITION (see module docstring for why),
    returning the dataframe with canonical column names plus which schema
    variant matched."""
    # latin-1 never raises UnicodeDecodeError (it maps every byte 0-255 to
    # a codepoint) — appropriate here since we don't rely on the header
    # text's exact characters, only column count/position.
    raw = pd.read_csv(path, encoding="latin-1", skipinitialspace=True)

    if raw.shape[1] == len(SMARTPHONE_COLUMNS_24):
        raw.columns = SMARTPHONE_COLUMNS_24
        return raw, "24_full"
    elif raw.shape[1] == len(SMARTPHONE_COLUMNS_20_NO_ORIENTATION_OR_MAG):
        raw.columns = SMARTPHONE_COLUMNS_20_NO_ORIENTATION_OR_MAG
        return raw, "20_no_orientation_mag"
    else:
        # Unknown schema — return as-is (positional names) rather than
        # guessing; the caller records this as a flagged anomaly.
        return raw, "UNRECOGNISED"


def _observed_hz(df: pd.DataFrame) -> tuple[float, float]:
    """Median-based observed sample rate from time_since_start_ms deltas.
    Median (not mean) so a handful of GPS-outage-related timestamp gaps
    (mentioned in README_1.pdf as a known data quality issue) don't skew
    the overall rate estimate."""
    deltas_ms = df["time_since_start_ms"].astype(float).diff().dropna()
    deltas_ms = deltas_ms[deltas_ms > 0]  # guard against any non-monotonic rows
    if deltas_ms.empty:
        return 0.0, 0.0
    median_delta_ms = deltas_ms.median()
    hz = 1000.0 / median_delta_ms if median_delta_ms > 0 else 0.0
    duration_s = (df["time_since_start_ms"].iloc[-1] - df["time_since_start_ms"].iloc[0]) / 1000.0
    return hz, duration_s


def _observed_gps_fix_interval_s(df: pd.DataFrame) -> float:
    """Median time between GPS fixes, measured from when gps_latitude_deg
    actually CHANGES value — not assumed from the dataset's documented
    "1 Hz" GPS update rate. Real finding (2026-08-25): the documented
    1 Hz claim (README_1.pdf) does not match what's actually in the
    files — measured across multiple trips/drivers, the real interval is
    consistently ~9.0s (i.e. the phone's GPS provider was delivering far
    less often than the app requested). This mirrors a lesson our own
    Android app already learned in Slice 1 (CLAUDE.md Rule 10): a
    requested rate is not a delivered rate, verify don't assume. Returns
    0.0 if the fix never changes (e.g. the dedicated stationary/bias
    trips, where lat/lon is constant for the whole recording — not a
    measurement failure, an expected property of a parked-car recording).
    """
    if "gps_latitude_deg" not in df.columns or "time_since_start_ms" not in df.columns:
        return 0.0
    changed = df["gps_latitude_deg"] != df["gps_latitude_deg"].shift()
    change_times_ms = df.loc[changed, "time_since_start_ms"]
    if len(change_times_ms) < 2:
        return 0.0
    intervals_s = change_times_ms.diff().dropna() / 1000.0
    return float(intervals_s.median())


def _count_satellite_parse_failures(df: pd.DataFrame) -> int:
    """gps_satellites_in_range is formatted 'USED / VISIBLE' (e.g. '27 / 28'),
    not a plain number — counts rows that don't match that pattern."""
    if "gps_satellites_in_range" not in df.columns:
        return 0
    pattern = df["gps_satellites_in_range"].astype(str).str.match(r"^\s*\d+\s*/\s*\d+\s*$")
    return int((~pattern).sum())


def inspect_trip(smartphone_csv: Path, dataset_root: Path) -> TripInspection:
    df, variant = _load_smartphone_csv(smartphone_csv)
    hz, duration_s = _observed_hz(df) if variant != "UNRECOGNISED" else (0.0, 0.0)
    gps_fix_interval_s = _observed_gps_fix_interval_s(df) if variant != "UNRECOGNISED" else 0.0
    satellite_failures = _count_satellite_parse_failures(df)

    vehicle_csv = smartphone_csv.parent / smartphone_csv.name.replace("S-", "V-", 1)
    has_vehicle = vehicle_csv.exists()
    vehicle_rows = None
    if has_vehicle:
        try:
            vehicle_rows = sum(1 for _ in open(vehicle_csv, "rb")) - 1  # -1 for header
        except OSError:
            has_vehicle = False

    notes: list[str] = []
    if variant == "UNRECOGNISED":
        notes.append(f"unrecognised schema: {df.shape[1]} columns (expected 24 or 20)")
    if variant != "UNRECOGNISED" and not (EXPECTED_HZ - HZ_TOLERANCE <= hz <= EXPECTED_HZ + HZ_TOLERANCE):
        notes.append(f"observed rate {hz:.2f} Hz outside [{EXPECTED_HZ - HZ_TOLERANCE}, {EXPECTED_HZ + HZ_TOLERANCE}] Hz tolerance")
    if satellite_failures > 0:
        notes.append(f"{satellite_failures} row(s) with unparseable gps_satellites_in_range")
    if not has_vehicle:
        notes.append("no paired V-*.csv found (smartphone-only trip)")
    if gps_fix_interval_s > GPS_FIX_INTERVAL_FLAG_THRESHOLD_S:
        notes.append(
            f"measured GPS fix interval {gps_fix_interval_s:.1f}s, "
            f"far from the documented {DOCUMENTED_GPS_FIX_INTERVAL_S:.0f}s (1 Hz)",
        )

    relative = smartphone_csv.relative_to(dataset_root)
    return TripInspection(
        relative_path=str(relative),
        driver_group=relative.parts[0] if len(relative.parts) > 1 else "unknown",
        trip_name=smartphone_csv.stem,
        n_rows=len(df),
        n_columns=df.shape[1],
        schema_variant=variant,
        duration_s=duration_s,
        observed_hz=hz,
        hz_within_tolerance=(EXPECTED_HZ - HZ_TOLERANCE <= hz <= EXPECTED_HZ + HZ_TOLERANCE),
        observed_gps_fix_interval_s=gps_fix_interval_s,
        satellites_parse_failures=satellite_failures,
        has_paired_vehicle_file=has_vehicle,
        vehicle_row_count=vehicle_rows,
        notes=notes,
    )


def find_smartphone_trips(dataset_root: Path) -> list[Path]:
    return sorted(dataset_root.rglob("S-*.csv"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dataset-root",
        type=Path,
        default=Path(__file__).resolve().parent.parent
        / "data"
        / "raw"
        / "IO-VNBD"
        / "extracted"
        / "Synchronised V abd S datasets"  # [sic] — matches the upstream folder's actual (typo'd) name
        / "Categorised IOVNB Dataset",  # NOT "Uncategorised..." — see module docstring, duplicate-tree pitfall
        help="Root folder to scan (default: the 'Categorised' tree only — see module docstring for why).",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "data" / "processed" / "io_vnbd_smartphone_manifest.csv",
        help="Where to write the manifest CSV.",
    )
    args = parser.parse_args()

    if not args.dataset_root.exists():
        print(
            f"Dataset root not found: {args.dataset_root}\n"
            "See data/README.md for how to download and extract IO-VNBD.",
            file=sys.stderr,
        )
        return 1

    trips = find_smartphone_trips(args.dataset_root)
    if not trips:
        print(f"No S-*.csv files found under {args.dataset_root}", file=sys.stderr)
        return 1

    print(f"Inspecting {len(trips)} smartphone trip files under {args.dataset_root} ...")
    results = [inspect_trip(csv_path, args.dataset_root) for csv_path in trips]

    manifest = pd.DataFrame([vars(r) for r in results])
    manifest["notes"] = manifest["notes"].apply(lambda ns: "; ".join(ns))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    manifest.to_csv(args.output, index=False)

    total_rows = manifest["n_rows"].sum()
    total_hours = manifest["duration_s"].sum() / 3600.0
    flagged = manifest[manifest["notes"] != ""]
    schema_counts = manifest["schema_variant"].value_counts()

    print(f"\n=== IO-VNBD smartphone dataset summary ===")
    moving = manifest[manifest["observed_gps_fix_interval_s"] > 0]
    median_gps_interval = moving["observed_gps_fix_interval_s"].median() if len(moving) else float("nan")

    print(f"Trips inspected:      {len(manifest)}")
    print(f"Total rows:           {total_rows:,}")
    print(f"Total duration:       {total_hours:.1f} hours")
    print(f"Median GPS fix interval (across {len(moving)} non-stationary trips): {median_gps_interval:.1f}s "
          f"(documented as {DOCUMENTED_GPS_FIX_INTERVAL_S:.0f}s / 1 Hz — see notes if these disagree)")
    print(f"Schema variants:      {dict(schema_counts)}")
    print(f"Trips with flags:     {len(flagged)} / {len(manifest)}")
    print(f"Manifest written to:  {args.output}")
    if len(flagged) > 0:
        print("\nFlagged trips (see manifest 'notes' column for detail):")
        for _, row in flagged.iterrows():
            print(f"  - {row['relative_path']}: {row['notes']}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
