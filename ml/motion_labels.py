"""Derives real, non-heuristic ground-truth motion-class labels (PRD.md
Section 14) from IO-VNBD's VEHICLE CSV (V-<trip>.csv) — a real VBOX
CAN-bus recording of actual driver input, logged independently of the
phone. Scoped to the 6 of 8 PRD classes this dataset can genuinely
support: Stationary, Turning, Braking, Accelerating, Cruising, Moving.
Pothole and Phone Moved have NO signal anywhere in this dataset (no
event markers, nothing CAN-bus-related to either) and stay on their
existing deterministic stand-ins (motion/PotholeShockDetector.kt,
motion/PhoneMovedDetector.kt) until a real self-captured drive exists.

Single responsibility (CLAUDE.md Rule 5): load vehicle CAN data and
derive labels from it. Deliberately separate from
train_motion_classifier.py for the same reason feature_extraction.py is
separate from train_velocity_model.py — this module knows nothing about
models/training, only about turning real CAN signals into ground truth.

REAL DATA-QUALITY FINDING (CLAUDE.md Rule 13 — a documented header is a
claim, not a verified fact, same lesson as feature_extraction.py's
forward-axis sign correction and inspect_dataset.py's GPS-1Hz finding):
"Accelerator Pedal Position (0 or 1)" is NOT binary despite its own
column header — checked directly across all 72 trips (1,071,035 rows):
it's a continuous 0-99 throttle position (mean 9.8, median 7.0, 42% of
rows exactly 0). "Brake Position (0 or 1)" IS genuinely binary, confirmed
the same way (exactly {0.0, 1.0} in every sampled file). The 20.0
threshold below was picked empirically, not guessed: at pedal > 20,
94.6% of matching rows have real positive Indicated Longitudinal
Acceleration (vs. 62.7% at pedal > 0) — a clean, precision-validated
cutoff.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

# Canonical column schema, in file order, per a real V-*.csv header read
# directly (see this module's docstring) — assigned by POSITION, same
# philosophy as inspect_dataset.py's SMARTPHONE_COLUMNS_24 (that file's
# own header text has inconsistent encoding; positional assignment sidesteps
# trusting header text at all). Units noted per CLAUDE.md Rule 15.
VEHICLE_COLUMNS_29 = [
    "gps_satellites_available",
    "time_since_start_of_day_s",
    "latitude_deg",
    "longitude_deg",
    "velocity_kmh",
    "heading_deg",
    "height_km",
    "vertical_velocity_kmh",
    "sample_period_s",
    "steering_angle_deg",
    "wheel_speed_fl_radps",
    "wheel_speed_fr_radps",
    "wheel_speed_rl_radps",
    "wheel_speed_rr_radps",
    "yaw_rate_degps",
    "indicated_vehicle_speed_kmh",
    "indicated_longitudinal_accel_g",
    "indicated_lateral_accel_g",
    "handbrake",
    "gear_requested",
    "gear",
    "engine_speed_rpm",
    "coolant_temperature_deg",
    "clutch_position",
    "brake_pressure_psi",
    "brake_position",
    "battery_voltage_v",
    "air_temperature_deg",
    # NOT actually 0/1 despite the raw column's own header text — see
    # module docstring's REAL DATA-QUALITY FINDING. Named
    # "_percent" here (not "_position") so every downstream reader sees
    # the true, verified nature of this column, not the misleading claim.
    "accelerator_pedal_position_percent",
]

MOTION_CLASSES = ["Stationary", "Turning", "Braking", "Accelerating", "Cruising", "Moving"]

# Engineering thresholds. Where a Kotlin default already exists on-device,
# reused deliberately (not re-guessed) so this offline label scheme is
# directly comparable to what's actually shipping — CLAUDE.md Rule 13.
STATIONARY_MAX_VELOCITY_KMH = 1.0  # ~0.28 m/s
TURNING_MIN_ABS_YAW_RATE_DEGPS = 8.6  # matches motion/TurningDetector.kt's DEFAULT_MIN_YAW_RATE_RADPS (0.15 rad/s)
ACCEL_PEDAL_MIN_PERCENT = 20.0  # empirically picked -- see module docstring
CRUISING_MAX_VELOCITY_ROLLING_STD_KMH = 0.5  # "steady enough to call cruising"


def vehicle_csv_path_for(smartphone_csv: Path) -> Path:
    """The paired V-<trip>.csv for a given S-<trip>.csv, same folder --
    the same one-liner inspect_dataset.py's inspect_trip() already uses
    inline (`smartphone_csv.parent / smartphone_csv.name.replace("S-",
    "V-", 1)`), duplicated here rather than importing a private helper
    from that module. replace(..., 1) only touches the FIRST "S-"
    occurrence, matching the dataset's own S-<name>.csv / V-<name>.csv
    naming convention even if <name> itself later contains "S-".
    """
    return smartphone_csv.parent / smartphone_csv.name.replace("S-", "V-", 1)


def load_vehicle_csv(path: Path) -> pd.DataFrame:
    """Reads one V-*.csv by column POSITION (see module docstring for
    why), returning a dataframe with canonical column names. Raises if
    the file doesn't have the expected 29-column shape rather than
    silently guessing at a different schema -- unlike the smartphone
    side (which has a documented 20-column variant for Driver F), no
    vehicle-file schema variant has ever been observed in this dataset.
    """
    raw = pd.read_csv(path, encoding="latin-1", skipinitialspace=True)
    if raw.shape[1] != len(VEHICLE_COLUMNS_29):
        raise ValueError(
            f"{path}: expected {len(VEHICLE_COLUMNS_29)} columns, got {raw.shape[1]}",
        )
    raw.columns = VEHICLE_COLUMNS_29
    return raw


def derive_motion_labels(vehicle_df: pd.DataFrame, window_samples: int) -> pd.Series:
    """Pure, vectorized precedence chain (first-match-wins) over a
    vehicle dataframe's real CAN signals -- one mutually-exclusive label
    per row, matching PRD.md Section 28's confusion-matrix framing
    (a standard confusion matrix needs one label per sample, not
    several simultaneously-true ones).

    Precedence, highest first:
      1. Stationary   -- velocity ~= 0 overrides everything else, same
                          as StationaryDetector.kt's ZUPT not caring about
                          pedal/steering state once truly at rest.
      2. Turning       -- real yaw-rate ground truth.
      3. Braking       -- real brake-pedal ground truth (genuinely binary).
      4. Accelerating  -- real throttle-position ground truth (see the
                          ACCEL_PEDAL_MIN_PERCENT finding in this module's
                          docstring -- NOT a 0/1 check).
      5. Cruising      -- moving, none of the above, AND velocity is
                          steady (low rolling std) -- textbook steady-state
                          driving.
      6. Moving        -- fallback: moving but not cleanly "steady" (e.g.
                          coasting, engine braking, road-grade drift) and
                          not turning/braking/accelerating either.

    HONEST LIMITATION (CLAUDE.md Rule 13, matches PRD.md Section 31's
    already-documented finding): the trained classifier's own
    FEATURE_COLUMNS (train_velocity_model.py, shared with the velocity
    model per PRD's on-device-cost requirement) has NO velocity feature
    -- so this label scheme's Stationary/Cruising distinction (which
    leans on real velocity) is not fully recoverable from IMU features
    alone. Expected, not fixed here.
    """
    velocity_kmh = vehicle_df["velocity_kmh"]
    yaw_rate_degps = vehicle_df["yaw_rate_degps"].abs()
    braking = vehicle_df["brake_position"] == 1
    accelerating = vehicle_df["accelerator_pedal_position_percent"] > ACCEL_PEDAL_MIN_PERCENT
    stationary = velocity_kmh < STATIONARY_MAX_VELOCITY_KMH
    turning = yaw_rate_degps >= TURNING_MIN_ABS_YAW_RATE_DEGPS
    velocity_rolling_std_kmh = velocity_kmh.rolling(window_samples, min_periods=1).std().fillna(0.0)
    steady = velocity_rolling_std_kmh < CRUISING_MAX_VELOCITY_ROLLING_STD_KMH

    conditions = [
        stationary,
        (~stationary) & turning,
        (~stationary) & (~turning) & braking,
        (~stationary) & (~turning) & (~braking) & accelerating,
        (~stationary) & (~turning) & (~braking) & (~accelerating) & steady,
    ]
    choices = ["Stationary", "Turning", "Braking", "Accelerating", "Cruising"]
    labels = np.select(conditions, choices, default="Moving")
    return pd.Series(labels, index=vehicle_df.index, name="motion_label")
