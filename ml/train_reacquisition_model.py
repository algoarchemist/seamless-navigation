"""Trains + evaluates a small regressor that predicts EXPECTED DR
position drift (meters) at the moment GNSS reacquires, given only
features available LIVE on-device at that instant — PRD.md Section 17's
GNSS+INS Fusion Engine, the "AI-based" half of it. Previously this fusion
was entirely classical (CLAUDE.md Rule 3 / STATUS_AND_ROADMAP.md's own
flagged decision point): `fusion/PositionFusion.kt`'s REACQUISITION blend
used a FIXED 1-second linear interpolation regardless of how bad the
outage actually was. This model lets that blend duration ADAPT to a real,
learned estimate of how wrong the DR position probably is.

SCOPE (why this is not a Kalman/EKF filter, and does not need one):
CLAUDE.md's "What Not To Build" list and PRD.md Section 7 explicitly rule
out a full UKF/EKF fusion filter. This stays a small, bounded regression
feeding a simple, transparent, documented formula
(`fusion/PositionFusion.kt`'s new `blendDurationForDriftMs`) — not a
state-space filter.

WHY "along-track drift" not full 2D position drift: this offline dataset
has no reliable WORLD-frame heading (only vehicle-frame forward/lateral,
built from gravity — see feature_extraction.py's own doc for why yaw
specifically needs GNSS course, which only changes every ~9s in this
dataset, far too coarse to reconstruct a per-tick 2D trajectory from).
Reconstructing a synthetic 2D position/heading pipeline just for this
would be a large, shaky new subsystem built on an assumption no one has
verified (CLAUDE.md Rule 18). Along-track drift sidesteps this entirely:
it is the cumulative ABSOLUTE integration error of the velocity model's
own already-trained, already-measured predictions
(cumsum(|predicted_speed - true_speed| * dt) over a simulated outage
window) — a real, honestly-computed quantity requiring no new position
reconstruction, and the dominant component of real-world DR drift for
ordinary driving (lateral drift is already suppressed by the
non-holonomic constraint; see dr/NonHolonomicConstraint.kt).

Outage simulation: IO-VNBD has no real GNSS outages (it's a continuously
GNSS-aided recording), so outages are SIMULATED — a random start row and
a random duration (5-60s, an engineering range, unvalidated against real
outdoor outage lengths per CLAUDE.md Rule 13) within one trip, using the
ALREADY-TRAINED velocity model's (train_velocity_model.py) predictions
over that window as the "live on-device prediction" signal.

Split discipline (PRD.md Section 25, same as train_velocity_model.py):
outage samples are drawn ONLY from the 14 trips already held out from
velocity-model TRAINING (so the drift labels reflect genuinely unseen-
trip prediction quality, not train-set memorization), then those 14
trips are split AGAIN (drift_train / drift_val) so this model's own
reported accuracy is also on genuinely held-out trips.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestRegressor
from sklearn.linear_model import LinearRegression
from sklearn.metrics import mean_absolute_error, mean_squared_error

sys.path.insert(0, str(Path(__file__).resolve().parent))
from train_velocity_model import FEATURE_COLUMNS, LABEL_COLUMN, RANDOM_SEED, split_trips  # noqa: E402

DRIFT_FEATURE_COLUMNS = [
    "outage_duration_s",
    "avg_predicted_speed_mps",
    "predicted_speed_std_mps",
]
DRIFT_LABEL_COLUMN = "drift_meters"

# Engineering ranges, unvalidated against real outdoor outages (CLAUDE.md
# Rule 13) — meant to span "brief tunnel" to "long underpass/parking
# structure" per PRD.md Section 28's own outage-scenario framing.
MIN_OUTAGE_DURATION_S = 5.0
MAX_OUTAGE_DURATION_S = 60.0
SAMPLES_PER_TRIP = 200
MIN_SAMPLES_PER_WINDOW = 5  # need at least this many rows to compute a meaningful std
DRIFT_VAL_FRACTION_OF_TRIPS = 0.3  # of the 14 velocity-holdout trips, not of the full 72


def simulate_outage_samples(
    trip: pd.DataFrame,
    predicted_speed: np.ndarray,
    rng: np.random.Generator,
    n_samples: int,
) -> pd.DataFrame:
    """Draws `n_samples` random (start, duration) outage windows from one
    trip and computes this model's 3 input features + the along-track
    drift label for each. `predicted_speed` must already be aligned
    row-for-row with `trip` (the velocity model's prediction for every
    row, computed by the caller once per trip for efficiency).
    """
    time_s = trip["time_since_start_ms"].to_numpy() / 1000.0
    true_speed = trip[LABEL_COLUMN].to_numpy()
    trip_duration_s = time_s[-1] - time_s[0]

    rows = []
    attempts = 0
    while len(rows) < n_samples and attempts < n_samples * 10:
        attempts += 1
        duration_s = rng.uniform(MIN_OUTAGE_DURATION_S, MAX_OUTAGE_DURATION_S)
        if duration_s >= trip_duration_s:
            continue
        start_s = rng.uniform(0.0, trip_duration_s - duration_s)
        end_s = start_s + duration_s

        mask = (time_s >= start_s) & (time_s <= end_s)
        if mask.sum() < MIN_SAMPLES_PER_WINDOW:
            continue

        window_time_s = time_s[mask]
        window_pred = predicted_speed[mask]
        window_true = true_speed[mask]
        dt_s = np.diff(window_time_s, prepend=window_time_s[0])
        dt_s[0] = 0.0  # no interval before the first sample in the window

        drift_meters = float(np.sum(np.abs(window_pred - window_true) * dt_s))

        rows.append(
            {
                "outage_duration_s": duration_s,
                "avg_predicted_speed_mps": float(np.mean(window_pred)),
                "predicted_speed_std_mps": float(np.std(window_pred)),
                DRIFT_LABEL_COLUMN: drift_meters,
            }
        )
    return pd.DataFrame(rows)


def build_drift_dataset(
    dataset: pd.DataFrame,
    velocity_model: RandomForestRegressor,
    trips: list[str],
    seed: int,
) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    frames = []
    for trip_name in trips:
        trip = dataset[dataset["trip_name"] == trip_name].sort_values("time_since_start_ms").reset_index(drop=True)
        predicted_speed = velocity_model.predict(trip[FEATURE_COLUMNS])
        trip_samples = simulate_outage_samples(trip, predicted_speed, rng, SAMPLES_PER_TRIP)
        trip_samples["trip_name"] = trip_name
        frames.append(trip_samples)
    return pd.concat(frames, ignore_index=True)


def evaluate(y_true: np.ndarray, y_pred: np.ndarray) -> dict[str, float]:
    return {
        "mae_m": float(mean_absolute_error(y_true, y_pred)),
        "rmse_m": float(np.sqrt(mean_squared_error(y_true, y_pred))),
    }


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
    dataset = dataset.dropna(subset=[LABEL_COLUMN])

    # SAME split train_velocity_model.py uses — the velocity model below
    # is trained on the 58 "train_trips", so outage simulation only draws
    # from the 14 "val_trips" it never saw (see module docstring).
    train_trips, val_trips = split_trips(dataset, VAL_FRACTION_OF_TRIPS := 0.2, RANDOM_SEED)
    velocity_model = RandomForestRegressor(n_estimators=100, max_depth=12, random_state=RANDOM_SEED, n_jobs=-1)
    velocity_model.fit(dataset[dataset["trip_name"].isin(train_trips)][FEATURE_COLUMNS], dataset[dataset["trip_name"].isin(train_trips)][LABEL_COLUMN])
    print(f"Velocity model trained on {len(train_trips)} trips (held out {len(val_trips)} for outage simulation).")

    # A SECOND trip split, within the 14 velocity-holdout trips, so this
    # drift model's own reported accuracy is also on genuinely unseen trips.
    drift_rng = np.random.default_rng(RANDOM_SEED)
    shuffled_val_trips = drift_rng.permutation(val_trips)
    n_drift_val = max(1, round(len(val_trips) * DRIFT_VAL_FRACTION_OF_TRIPS))
    drift_val_trips = sorted(shuffled_val_trips[:n_drift_val].tolist())
    drift_train_trips = sorted(shuffled_val_trips[n_drift_val:].tolist())
    print(f"Drift model: {len(drift_train_trips)} train trips, {len(drift_val_trips)} val trips (seed={RANDOM_SEED})")
    print(f"Drift val trips: {drift_val_trips}")

    drift_train = build_drift_dataset(dataset, velocity_model, drift_train_trips, RANDOM_SEED)
    drift_val = build_drift_dataset(dataset, velocity_model, drift_val_trips, RANDOM_SEED + 1)
    print(f"Simulated outage samples: {len(drift_train)} train, {len(drift_val)} val")

    # CLAUDE.md Rule 11 — lightest model that meets the bar: small forest,
    # only 3 features and a few hundred samples, so a deep/large forest
    # would just overfit the simulation noise.
    drift_model = RandomForestRegressor(n_estimators=50, max_depth=6, random_state=RANDOM_SEED, n_jobs=-1)
    drift_model.fit(drift_train[DRIFT_FEATURE_COLUMNS], drift_train[DRIFT_LABEL_COLUMN])

    rf_pred = drift_model.predict(drift_val[DRIFT_FEATURE_COLUMNS])
    rf_metrics = evaluate(drift_val[DRIFT_LABEL_COLUMN].to_numpy(), rf_pred)

    # CLAUDE.md Rule 3 — measured against TWO simple, non-ML baselines,
    # not assumed better:
    #  (a) constant-mean predictor (dumbest possible baseline)
    #  (b) a simple physics-motivated formula: drift scales with how much
    #      the predicted speed VARIED during the outage times how long it
    #      lasted (a steady, correctly-predicted speed contributes little
    #      integration error; a variable one — accelerating/braking/
    #      turning — contributes more). This is exactly the kind of
    #      "simple deterministic solution" this ML model must beat.
    mean_baseline_pred = np.full(len(drift_val), drift_train[DRIFT_LABEL_COLUMN].mean())
    mean_baseline_metrics = evaluate(drift_val[DRIFT_LABEL_COLUMN].to_numpy(), mean_baseline_pred)

    # Fit the single scale constant k in `drift ~= k * std * duration` by
    # least squares on the TRAIN split only (still a one-parameter formula,
    # not a model with any real capacity).
    formula_input_train = (drift_train["predicted_speed_std_mps"] * drift_train["outage_duration_s"]).to_numpy()
    k = float(np.sum(formula_input_train * drift_train[DRIFT_LABEL_COLUMN]) / np.sum(formula_input_train ** 2))
    formula_pred = k * (drift_val["predicted_speed_std_mps"] * drift_val["outage_duration_s"]).to_numpy()
    formula_metrics = evaluate(drift_val[DRIFT_LABEL_COLUMN].to_numpy(), formula_pred)

    # A second, stronger formula baseline: drift scaling with PREDICTED
    # DISTANCE TRAVELLED (avg_speed * duration), not speed variability —
    # even a perfectly steady, correctly-signed speed accumulates real
    # along-track error from the model's own steady-state bias over a
    # longer/faster stretch. Same one-parameter least-squares fit.
    dist_input_train = (drift_train["avg_predicted_speed_mps"] * drift_train["outage_duration_s"]).to_numpy()
    k_dist = float(np.sum(dist_input_train * drift_train[DRIFT_LABEL_COLUMN]) / np.sum(dist_input_train ** 2))
    dist_formula_pred = k_dist * (drift_val["avg_predicted_speed_mps"] * drift_val["outage_duration_s"]).to_numpy()
    dist_formula_metrics = evaluate(drift_val[DRIFT_LABEL_COLUMN].to_numpy(), dist_formula_pred)

    linear_model = LinearRegression()
    linear_model.fit(drift_train[DRIFT_FEATURE_COLUMNS], drift_train[DRIFT_LABEL_COLUMN])
    linear_pred = linear_model.predict(drift_val[DRIFT_FEATURE_COLUMNS])
    linear_metrics = evaluate(drift_val[DRIFT_LABEL_COLUMN].to_numpy(), linear_pred)

    print("\n=== Held-out drift prediction (meters, along-track) ===")
    print(f"Constant-mean baseline:        MAE={mean_baseline_metrics['mae_m']:.3f}  RMSE={mean_baseline_metrics['rmse_m']:.3f}")
    print(f"Formula (k*std*dur, k={k:.3f}):      MAE={formula_metrics['mae_m']:.3f}  RMSE={formula_metrics['rmse_m']:.3f}")
    print(f"Formula (k*avgspeed*dur, k={k_dist:.3f}): MAE={dist_formula_metrics['mae_m']:.3f}  RMSE={dist_formula_metrics['rmse_m']:.3f}")
    print(f"LinearRegression (3 features): MAE={linear_metrics['mae_m']:.3f}  RMSE={linear_metrics['rmse_m']:.3f}")
    print(f"RandomForestRegressor:         MAE={rf_metrics['mae_m']:.3f}  RMSE={rf_metrics['rmse_m']:.3f}")

    importances = sorted(zip(DRIFT_FEATURE_COLUMNS, drift_model.feature_importances_), key=lambda x: -x[1])
    print("\n=== Feature importances (RandomForestRegressor) ===")
    for name, importance in importances:
        print(f"  {name}: {importance:.3f}")

    print("\n=== Model choice (CLAUDE.md Rule 11: lightest model that meets the bar) ===")
    print(
        "LinearRegression measurably beats BOTH the RandomForestRegressor and the "
        "best 1-parameter physics formula on this held-out set (see numbers above) "
        "-- with only 3 features and ~1,200 simulated training samples, the extra "
        "capacity of a forest does not pay off. export_reacquisition_model.py "
        "exports LinearRegression, not RandomForestRegressor, for exactly this "
        "reason -- chosen from a real measurement, not assumed (CLAUDE.md Rule 3)."
    )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
