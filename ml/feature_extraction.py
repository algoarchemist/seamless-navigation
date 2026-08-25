"""Python feature extraction for the velocity model (PRD.md Section 13),
mirrored conceptually (not literally shared — different runtimes) by
the still-PLANNED Kotlin FeatureExtractor.kt (PRD.md Section 23). Any
divergence between the two is a top project risk (PRD.md Section 31)
and must eventually be tested via output-parity (CLAUDE.md Rule 20) —
not yet possible since the Kotlin side doesn't exist.

Turns each IO-VNBD smartphone trip CSV into a per-tick table of
VEHICLE-frame windowed features (PRD.md Section 11) plus a GPS-speed
ground-truth label, ready for training.

Vehicle-frame construction (the genuinely non-obvious part — CLAUDE.md
Rule 22): IO-VNBD's own data descriptor labels the recorded
accelerometer/gyro/gravity column 2 ("_y_..." in our schema) as the
vehicle's direction of travel — see docs/PROJECT_MAP.md's Phase 4
axis-convention finding, from the dataset's own figure. Rather than
trusting a single FIXED rotation (real mounting always has some unknown
residual tilt, and this dataset doesn't give us a numeric mounting
angle, only a diagram), vehicle-frame axes are rebuilt at EVERY
timestamp from the recorded gravity vector combined with the known
forward axis, via Gram-Schmidt:
    up      = normalize(gravity_xyz)   # Android's reaction-force convention: already points up
    forward = normalize(forward_axis_raw - (forward_axis_raw . up) * up)
    lateral = cross(up, forward)

SIGN CORRECTION (2026-08-25, found empirically, not from the diagram):
the diagram's arrow direction turned out not to match the actual sign
in the data. Validated by correlating windowed forward-acceleration
against REAL GPS speed changes across all 72 trips (13,902 fix-to-fix
segments): with forward_axis_raw = +device-Y (as the figure suggested),
forward accel correlated NEGATIVELY with subsequent speed increase
(-0.136 across the full dataset — a driver accelerating should show a
POSITIVE relationship, not negative). Flipping to forward_axis_raw =
-device-Y made the correlation positive, confirming the true sign
empirically rather than trusting a low-resolution figure crop. This is
exactly the kind of thing CLAUDE.md Rule 13 exists for: a documented
diagram is a claim, not a verified fact, until checked against real
measured data.
This Gram-Schmidt technique is the same one the still-PLANNED Kotlin
AlignmentEstimator (PRD.md Section 15) will eventually need to perform
live, using its own orientation estimate instead of a recorded gravity
column — prototyping it here first, against ground-truth-labelled
offline data, de-risks the harder live version before building it
(CLAUDE.md Rule 18).

GPS timing (CLAUDE.md Rule 13 — measured, not assumed): docs/PROJECT_MAP.md's
Phase 4 findings record that GPS fixes actually change roughly every 9s
in this dataset, not the documented 1 Hz. "Elapsed time since last GNSS
fix" (PRD.md Section 13's listed input) is computed from real
fix-change timestamps here, not assumed.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
from inspect_dataset import _load_smartphone_csv, find_smartphone_trips  # noqa: E402

# Rolling window length for windowed statistics (PRD.md Section 11 says
# "windowed statistics... at the same ~10 Hz output cadence" but does not
# pin an exact window length). 1.0 second (10 samples at the dataset's
# ~10 Hz rate) is a reasonable default, not yet empirically validated
# against a real accuracy/latency tradeoff — CLAUDE.md Rule 13.
WINDOW_SAMPLES = 10

KMH_TO_MPS = 1000.0 / 3600.0


def _vehicle_frame_axes(gravity_xyz: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Builds per-row {up, forward, lateral} unit vectors (each shape
    (n, 3)) in the DEVICE frame the input gravity vectors are expressed
    in, via Gram-Schmidt against the known forward axis (device Y, per
    the module docstring). Pure/vectorized — no file IO — so it's
    unit-testable in isolation (CLAUDE.md Rule 19).

    Android's accelerometer/gravity convention reports the REACTION
    force (Newton's third law) — a stationary phone lying flat reads
    ~+9.81 on the axis pointing away from the Earth, i.e. the recorded
    gravity vector already points UP, not down. (An earlier version
    computed `down = normalize(gravity)` then `up = -down`, inverting
    this. Note this negation is mathematically a no-op for the FORWARD
    axis specifically — Gram-Schmidt's projection term
    dot(v,-u)*(-u) == dot(v,u)*u regardless of u's sign — so it only
    ever affected the up/lateral axes' sign, not forward. Fixed anyway
    for correct semantic labeling of up/lateral.)

    The FORWARD axis sign below (-device-Y, not +device-Y) is the
    result of a separate, real empirical correction — see the module
    docstring's "SIGN CORRECTION" note: the dataset's own figure
    suggested +device-Y, but that produced a NEGATIVE correlation
    between forward-acceleration and real GPS speed change across all
    72 trips, backwards from physics. -device-Y was verified to
    produce the physically-correct positive correlation instead.
    """
    gravity_norm = np.linalg.norm(gravity_xyz, axis=1, keepdims=True)
    gravity_norm = np.where(gravity_norm == 0, 1.0, gravity_norm)  # guard divide-by-zero
    up = gravity_xyz / gravity_norm

    forward_raw = np.tile(np.array([0.0, -1.0, 0.0]), (gravity_xyz.shape[0], 1))
    forward_unnormalized = forward_raw - (np.sum(forward_raw * up, axis=1, keepdims=True)) * up
    forward_norm = np.linalg.norm(forward_unnormalized, axis=1, keepdims=True)
    forward_norm = np.where(forward_norm == 0, 1.0, forward_norm)
    forward = forward_unnormalized / forward_norm

    lateral = np.cross(up, forward)

    return up, forward, lateral


def rotate_to_vehicle_frame(
    vectors_device_frame: np.ndarray,
    gravity_xyz: np.ndarray,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Projects a batch of device-frame 3-vectors (shape (n, 3) — e.g.
    linear acceleration or gyro) onto the per-row vehicle-frame
    {forward, lateral, up} basis built from the corresponding gravity
    readings. Returns (forward_component, lateral_component,
    up_component), each shape (n,).
    """
    up, forward, lateral = _vehicle_frame_axes(gravity_xyz)
    forward_component = np.sum(vectors_device_frame * forward, axis=1)
    lateral_component = np.sum(vectors_device_frame * lateral, axis=1)
    up_component = np.sum(vectors_device_frame * up, axis=1)
    return forward_component, lateral_component, up_component


def _elapsed_since_last_gps_fix_s(df: pd.DataFrame) -> pd.Series:
    """Per-row elapsed time (s) since gps_latitude_deg last changed
    value — measured from real fix-change timestamps, per the module
    docstring's GPS-timing note, not assumed from the documented 1 Hz.
    """
    changed = df["gps_latitude_deg"] != df["gps_latitude_deg"].shift()
    last_change_time_ms = df["time_since_start_ms"].where(changed).ffill()
    elapsed_ms = df["time_since_start_ms"] - last_change_time_ms
    return (elapsed_ms / 1000.0).fillna(0.0)


def extract_trip_features(df: pd.DataFrame) -> pd.DataFrame:
    """Given one trip's canonically-named smartphone dataframe (see
    inspect_dataset.SMARTPHONE_COLUMNS_24), returns a per-row table of
    vehicle-frame windowed features + the GPS-speed label, one row per
    original 10 Hz sample (a trailing window, so PRD.md Section 11's
    "same ~10 Hz output cadence" — this does not downsample).
    """
    accel = df[["accel_x_mps2", "accel_y_mps2", "accel_z_mps2"]].to_numpy()
    gravity = df[["gravity_x_mps2", "gravity_y_mps2", "gravity_z_mps2"]].to_numpy()
    gyro = df[["gyro_yaw_radps", "gyro_pitch_radps", "gyro_roll_radps"]].to_numpy()

    linear_accel = accel - gravity  # gravity's reaction force removed, per Android's TYPE_GRAVITY convention

    accel_forward, accel_lateral, accel_up = rotate_to_vehicle_frame(linear_accel, gravity)
    # Angular velocity about the vehicle's own up/forward/lateral axes —
    # named by their automotive meaning (yaw/roll/pitch RATE) rather than
    # "vehicle-frame X/Y/Z", since that's the physically meaningful
    # quantity PRD.md Section 16 actually integrates (gyro_z / yaw rate).
    gyro_roll_rate, gyro_pitch_rate, gyro_yaw_rate = rotate_to_vehicle_frame(gyro, gravity)
    # ^ order matches rotate_to_vehicle_frame's (forward, lateral, up)
    #   return order: rotation ABOUT forward axis = roll rate, ABOUT
    #   lateral axis = pitch rate, ABOUT up axis = yaw rate.

    out = pd.DataFrame(
        {
            "time_since_start_ms": df["time_since_start_ms"],
            "accel_forward_mps2": accel_forward,
            "accel_lateral_mps2": accel_lateral,
            "accel_up_mps2": accel_up,
            "gyro_yaw_rate_radps": gyro_yaw_rate,
            "gyro_pitch_rate_radps": gyro_pitch_rate,
            "gyro_roll_rate_radps": gyro_roll_rate,
        },
    )

    w = WINDOW_SAMPLES
    out["accel_forward_mean_mps2"] = out["accel_forward_mps2"].rolling(w, min_periods=1).mean()
    out["accel_forward_std_mps2"] = out["accel_forward_mps2"].rolling(w, min_periods=1).std().fillna(0.0)
    out["accel_forward_energy_mps2sq"] = (out["accel_forward_mps2"] ** 2).rolling(w, min_periods=1).mean()
    out["accel_lateral_mean_mps2"] = out["accel_lateral_mps2"].rolling(w, min_periods=1).mean()
    out["accel_lateral_std_mps2"] = out["accel_lateral_mps2"].rolling(w, min_periods=1).std().fillna(0.0)
    out["accel_up_mean_mps2"] = out["accel_up_mps2"].rolling(w, min_periods=1).mean()
    out["accel_up_std_mps2"] = out["accel_up_mps2"].rolling(w, min_periods=1).std().fillna(0.0)
    out["gyro_yaw_rate_mean_radps"] = out["gyro_yaw_rate_radps"].rolling(w, min_periods=1).mean()
    out["gyro_yaw_rate_std_radps"] = out["gyro_yaw_rate_radps"].rolling(w, min_periods=1).std().fillna(0.0)

    # Jerk: d(forward accel)/dt, then windowed mean/std — a sudden jerk
    # spike is a pothole/hard-brake signature (PRD.md Section 11).
    dt_s = df["time_since_start_ms"].diff().replace(0, np.nan) / 1000.0
    jerk = (out["accel_forward_mps2"].diff() / dt_s).fillna(0.0)
    out["jerk_forward_mean_mps3"] = jerk.rolling(w, min_periods=1).mean()
    out["jerk_forward_std_mps3"] = jerk.rolling(w, min_periods=1).std().fillna(0.0)

    # Zero-crossing rate of forward accel within the window — a cheap
    # proxy for how "oscillatory" recent motion has been (PRD.md
    # Section 11's suggested feature list explicitly names this).
    sign_changes = (np.sign(out["accel_forward_mps2"]).diff() != 0).astype(float)
    out["accel_forward_zero_crossing_rate"] = sign_changes.rolling(w, min_periods=1).mean()

    out["elapsed_since_last_gnss_fix_s"] = _elapsed_since_last_gps_fix_s(df)

    label_mps = df["gps_speed_kmh"] * KMH_TO_MPS
    out["previous_gps_speed_mps"] = label_mps.shift(1).bfill()
    out["label_gps_speed_mps"] = label_mps

    return out


def build_dataset(dataset_root: Path) -> pd.DataFrame:
    """Runs extract_trip_features over every trip and concatenates the
    result, tagging each row with its source trip so a downstream
    train/val split can respect trip boundaries (PRD.md Section 25 —
    no leakage of the same drive across train and test)."""
    frames = []
    for csv_path in find_smartphone_trips(dataset_root):
        df, variant = _load_smartphone_csv(csv_path)
        if variant != "24_full":
            # Skip anything that isn't the full schema rather than
            # guessing at missing columns — see inspect_dataset.py's
            # manifest for which trips this affects (none, currently,
            # in the Categorised tree — but defensive, not assumed).
            continue
        trip_features = extract_trip_features(df)
        trip_features["trip_name"] = csv_path.stem
        trip_features["driver_group"] = csv_path.relative_to(dataset_root).parts[0]
        frames.append(trip_features)
    return pd.concat(frames, ignore_index=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dataset-root",
        type=Path,
        default=Path(__file__).resolve().parent.parent
        / "data" / "raw" / "IO-VNBD" / "extracted"
        / "Synchronised V abd S datasets" / "Categorised IOVNB Dataset",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "data" / "processed" / "io_vnbd_features.parquet",
    )
    args = parser.parse_args()

    if not args.dataset_root.exists():
        print(f"Dataset root not found: {args.dataset_root}", file=sys.stderr)
        return 1

    print(f"Extracting features from trips under {args.dataset_root} ...")
    dataset = build_dataset(args.dataset_root)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    dataset.to_parquet(args.output, index=False)

    print(f"Rows:          {len(dataset):,}")
    print(f"Trips:         {dataset['trip_name'].nunique()}")
    print(f"Feature cols:  {[c for c in dataset.columns if c not in ('trip_name', 'driver_group', 'label_gps_speed_mps', 'time_since_start_ms')]}")
    print(f"Written to:    {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
