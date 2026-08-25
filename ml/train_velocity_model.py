"""Trains + evaluates the velocity regression model (PRD.md Section 13),
and — critically, per CLAUDE.md Rule 3 — measures it against the same
naive physics-integration baseline the Android app actually ships
(BaselinePhysicsIntegrator + a ZUPT gate, mirroring Slice 5's
dr/StationaryDetector.kt) on the SAME held-out trips. ML is not assumed
better; it is measured.

Feature/label leakage note (why previous_gps_speed_mps is NOT used as
an input here, despite PRD.md Section 13 listing "previous velocity
estimate" as a candidate input): docs/PROJECT_MAP.md's Phase 4 findings
record that GPS fixes in this dataset are held constant for ~90
consecutive 10 Hz rows between real updates. previous_gps_speed_mps
(built in feature_extraction.py as label.shift(1)) is therefore
IDENTICAL to the current label for ~89 of every 90 rows — a model could
just copy it and score near-perfectly without learning anything from
the IMU. That's a real deployment-relevant feature (the live app's
"previous ML-predicted velocity" during an outage), but not a fair
OFFLINE evaluation feature against this dataset's specific held-value
artifact. Excluded here; revisit once there's a real Kotlin
AlignmentEstimator/inference path to properly autoregress against.

Train/val split respects TRIP boundaries (PRD.md Section 25 — no
leakage of the same drive across train and test), not row boundaries.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestRegressor
from sklearn.metrics import mean_absolute_error, mean_squared_error

FEATURE_COLUMNS = [
    "accel_forward_mean_mps2",
    "accel_forward_std_mps2",
    "accel_forward_energy_mps2sq",
    "accel_lateral_mean_mps2",
    "accel_lateral_std_mps2",
    "accel_up_mean_mps2",
    "accel_up_std_mps2",
    "gyro_yaw_rate_mean_radps",
    "gyro_yaw_rate_std_radps",
    "jerk_forward_mean_mps3",
    "jerk_forward_std_mps3",
    "accel_forward_zero_crossing_rate",
    "elapsed_since_last_gnss_fix_s",
]
LABEL_COLUMN = "label_gps_speed_mps"

RANDOM_SEED = 42  # fixed for reproducibility (PRD.md Section 27 — training reproducibility is a required ML test)
VAL_FRACTION_OF_TRIPS = 0.2

# Mirrors dr/StationaryDetector.kt's defaults (android/app/.../dr/StationaryDetector.kt) —
# kept in sync manually for now; a real cross-language parity test is
# PRD.md Section 20/CLAUDE.md Rule 20 territory, not yet built.
ZUPT_MAX_ACCEL_MPS2 = 0.25
ZUPT_MAX_GYRO_RADPS = 0.05


def split_trips(dataset: pd.DataFrame, val_fraction: float, seed: int) -> tuple[list[str], list[str]]:
    """Splits by unique trip_name, not by row (PRD.md Section 25)."""
    trips = sorted(dataset["trip_name"].unique())
    rng = np.random.default_rng(seed)
    shuffled = rng.permutation(trips)
    n_val = max(1, round(len(trips) * val_fraction))
    val_trips = sorted(shuffled[:n_val].tolist())
    train_trips = sorted(shuffled[n_val:].tolist())
    return train_trips, val_trips


def physics_baseline_velocity(trip: pd.DataFrame) -> np.ndarray:
    """Naive semi-implicit-Euler integration of accel_forward_mps2 with
    a ZUPT gate — the same shape of computation as
    dr/BaselinePhysicsIntegrator.kt + dr/StationaryDetector.kt, re-implemented
    in Python for this offline comparison (not literally shared code —
    different runtimes, same conceptual algorithm, PRD.md Section 23).
    Resets to 0 at the start of each trip (mirroring the Android app
    resetting the DR odometer whenever GNSS was last trustworthy —
    Slice 5's GNSS-mode-gated reset).
    """
    dt_s = trip["time_since_start_ms"].diff().to_numpy() / 1000.0
    accel = trip["accel_forward_mps2"].to_numpy()
    gyro = trip["gyro_yaw_rate_radps"].to_numpy()

    velocity = np.zeros(len(trip))
    v = 0.0
    for i in range(1, len(trip)):
        dt = dt_s[i]
        if dt <= 0:
            velocity[i] = v
            continue
        v = v + accel[i] * dt
        if abs(accel[i]) <= ZUPT_MAX_ACCEL_MPS2 and abs(gyro[i]) <= ZUPT_MAX_GYRO_RADPS:
            v = 0.0
        velocity[i] = v
    return velocity


def evaluate(y_true: np.ndarray, y_pred: np.ndarray) -> dict[str, float]:
    return {
        "mae_mps": float(mean_absolute_error(y_true, y_pred)),
        "rmse_mps": float(np.sqrt(mean_squared_error(y_true, y_pred))),
    }


def evaluate_deduplicated_by_fix(trip: pd.DataFrame, y_pred: np.ndarray) -> dict[str, float] | None:
    """Same metrics, but only at rows where the GPS fix actually changed
    — avoids the ~90-consecutive-near-duplicate-label distortion noted
    in the module docstring, for a second, more independent-samples
    honest view of the same result."""
    changed = (trip[LABEL_COLUMN] != trip[LABEL_COLUMN].shift()).to_numpy()
    if changed.sum() < 2:
        return None
    return evaluate(trip.loc[changed, LABEL_COLUMN].to_numpy(), y_pred[changed])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--features",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "data" / "processed" / "io_vnbd_features.parquet",
    )
    args = parser.parse_args()

    if not args.features.exists():
        print(f"Features file not found: {args.features}. Run ml/feature_extraction.py first.", file=sys.stderr)
        return 1

    dataset = pd.read_parquet(args.features)
    n_before = len(dataset)
    dataset = dataset.dropna(subset=[LABEL_COLUMN])
    if len(dataset) != n_before:
        # A handful of rows (4/1,070,745 in the real dataset, all one
        # trip) have a missing GPS speed — likely the "communication
        # difficulties between GPS receiver and satellites" README_1.pdf
        # mentions. Dropped rather than imputed, since a wrong-but-plausible
        # imputed speed is worse than one less training example.
        print(f"Dropped {n_before - len(dataset)} row(s) with missing {LABEL_COLUMN}.")

    train_trips, val_trips = split_trips(dataset, VAL_FRACTION_OF_TRIPS, RANDOM_SEED)
    print(f"Trips: {len(train_trips)} train, {len(val_trips)} val (seed={RANDOM_SEED})")
    print(f"Val trips: {val_trips}")

    train_df = dataset[dataset["trip_name"].isin(train_trips)]
    val_df = dataset[dataset["trip_name"].isin(val_trips)]

    model = RandomForestRegressor(
        n_estimators=100,
        max_depth=12,
        random_state=RANDOM_SEED,
        n_jobs=-1,
    )
    model.fit(train_df[FEATURE_COLUMNS], train_df[LABEL_COLUMN])

    all_ml_pred = []
    all_physics_pred = []
    all_true = []
    per_trip_rows = []

    for trip_name, trip in val_df.groupby("trip_name"):
        trip = trip.sort_values("time_since_start_ms").reset_index(drop=True)
        ml_pred = model.predict(trip[FEATURE_COLUMNS])
        physics_pred = physics_baseline_velocity(trip)
        y_true = trip[LABEL_COLUMN].to_numpy()

        all_ml_pred.append(ml_pred)
        all_physics_pred.append(physics_pred)
        all_true.append(y_true)

        ml_metrics = evaluate(y_true, ml_pred)
        physics_metrics = evaluate(y_true, physics_pred)
        per_trip_rows.append(
            {
                "trip_name": trip_name,
                "n_rows": len(trip),
                "ml_mae_mps": ml_metrics["mae_mps"],
                "physics_mae_mps": physics_metrics["mae_mps"],
            }
        )

    all_ml_pred = np.concatenate(all_ml_pred)
    all_physics_pred = np.concatenate(all_physics_pred)
    all_true = np.concatenate(all_true)

    ml_overall = evaluate(all_true, all_ml_pred)
    physics_overall = evaluate(all_true, all_physics_pred)

    print("\n=== Overall (all held-out rows, ~10 Hz, includes ~90x repeated labels per GPS fix) ===")
    print(f"ML model      MAE={ml_overall['mae_mps']:.3f} m/s  RMSE={ml_overall['rmse_mps']:.3f} m/s")
    print(f"Physics+ZUPT  MAE={physics_overall['mae_mps']:.3f} m/s  RMSE={physics_overall['rmse_mps']:.3f} m/s")

    print("\n=== Per-trip (deduplicated to real GPS fix changes only) ===")
    for trip_name, trip in val_df.groupby("trip_name"):
        trip = trip.sort_values("time_since_start_ms").reset_index(drop=True)
        ml_pred = model.predict(trip[FEATURE_COLUMNS])
        physics_pred = physics_baseline_velocity(trip)
        ml_dedup = evaluate_deduplicated_by_fix(trip, ml_pred)
        physics_dedup = evaluate_deduplicated_by_fix(trip, physics_pred)
        if ml_dedup and physics_dedup:
            print(
                f"  {trip_name}: ML MAE={ml_dedup['mae_mps']:.3f}  "
                f"Physics+ZUPT MAE={physics_dedup['mae_mps']:.3f}  (n_rows={len(trip)})"
            )

    importances = sorted(
        zip(FEATURE_COLUMNS, model.feature_importances_), key=lambda x: -x[1]
    )
    print("\n=== Feature importances ===")
    for name, importance in importances:
        print(f"  {name}: {importance:.3f}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
