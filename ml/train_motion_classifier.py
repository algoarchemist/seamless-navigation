"""Trains + evaluates the motion classifier (PRD.md Section 14), scoped
to the 6 of 8 classes IO-VNBD's real vehicle CAN-bus data actually
supports: Stationary, Turning, Braking, Accelerating, Cruising, Moving.
Pothole and Phone Moved stay on their existing deterministic stand-ins
(motion/PotholeShockDetector.kt, motion/PhoneMovedDetector.kt) -- no
event marker or CAN-bus proxy exists for either in this dataset (see
motion_labels.py's own docstring).

Ground-truth labels come from motion_labels.py's real, non-heuristic
derivation off the vehicle CSV's actual driver input (brake pedal,
throttle position, yaw rate, velocity) -- NOT the smartphone IMU. The
MODEL's inputs are the SAME windowed feature set the velocity model
already uses (FEATURE_COLUMNS, imported from train_velocity_model.py),
per PRD.md Section 14's explicit "shared feature extraction reduces
on-device cost" requirement -- this script does not re-extract IMU
features, only attaches vehicle-CAN-derived labels via a row-position
join and trains over the existing feature set.

CLAUDE.md Rule 3 (ML is only justified once measured against a real
non-ML baseline, never assumed): every result below is reported against
a `deterministic_baseline_labels()` Python re-implementation of what's
actually shipping on-device today, reusing the SAME already-shipping
threshold constants (imported where already defined, matched exactly
where not) -- not a new invented heuristic.

HONEST, ACCEPTED LIMITATION (matches PRD.md Section 31's own documented
finding for StationaryDetector.kt): FEATURE_COLUMNS has no velocity
feature, so BOTH the trained classifier and the deterministic baseline
share the same "can't fully tell parked from steady coasting" blind
spot. Expect Stationary/Cruising confusion in both confusion matrices --
reported honestly below, not fixed by smuggling in a velocity feature
that wouldn't reflect what ships on-device.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix

sys.path.insert(0, str(Path(__file__).resolve().parent))
from feature_extraction import WINDOW_SAMPLES  # noqa: E402
from inspect_dataset import find_smartphone_trips  # noqa: E402
from motion_labels import (  # noqa: E402
    MOTION_CLASSES,
    derive_motion_labels,
    load_vehicle_csv,
    vehicle_csv_path_for,
)
from train_velocity_model import (  # noqa: E402
    FEATURE_COLUMNS,
    RANDOM_SEED,
    VAL_FRACTION_OF_TRIPS,
    ZUPT_MAX_ACCEL_MPS2,
    ZUPT_MAX_GYRO_RADPS,
    split_trips,
)

LABEL_COLUMN = "motion_label"

# Mirrors motion/TurningDetector.kt's and motion/LongitudinalMotionClassifier.kt's
# own shipping defaults (android/app/.../motion/*.kt) -- kept in sync
# manually for now, same "not yet a real cross-language parity test"
# caveat train_velocity_model.py's own ZUPT constants already carry
# (CLAUDE.md Rule 20 territory, not built yet).
TURNING_MIN_YAW_RATE_RADPS = 0.15
LONGITUDINAL_ACCEL_THRESHOLD_MPS2 = 1.0


def build_labeled_dataset(features: pd.DataFrame, dataset_root: Path) -> pd.DataFrame:
    """Attaches a real, vehicle-CAN-derived motion_label to every row of
    an already-computed feature table (feature_extraction.py's output),
    by row-POSITION join against each trip's paired V-<trip>.csv.
    Row-position alignment (not timestamp re-alignment) is a deliberate,
    disclosed simplification -- 63/72 trips have byte-identical row
    counts between the smartphone and vehicle logs; the other 9 differ
    only by a small trailing amount (confirmed, in the worst case, to be
    exactly explained by one logger running slightly longer:
    232 extra rows x 0.1s sample period = 23.2s, matching that trip's
    real wall-clock duration difference) -- so truncating both to
    min(len) drops only a short, honest tail, never misaligns a middle
    row. Trips with no paired/loadable vehicle file are skipped and
    reported, not silently dropped.
    """
    trip_paths = {p.stem: p for p in find_smartphone_trips(dataset_root)}
    labeled_frames: list[pd.DataFrame] = []
    skipped: list[str] = []

    for trip_name, trip_features in features.groupby("trip_name", sort=False):
        smartphone_csv = trip_paths.get(trip_name)
        if smartphone_csv is None:
            skipped.append(trip_name)
            continue
        vehicle_csv = vehicle_csv_path_for(smartphone_csv)
        if not vehicle_csv.exists():
            skipped.append(trip_name)
            continue

        vehicle_df = load_vehicle_csv(vehicle_csv)
        # groupby preserves each group's original row order (the order
        # extract_trip_features produced it in, per-trip, unsorted) --
        # the SAME file order load_vehicle_csv reads the vehicle CSV in,
        # so position-i in each frame really is the same real-world
        # instant (modulo the trailing-tail caveat above).
        trip_features = trip_features.reset_index(drop=True)

        n = min(len(trip_features), len(vehicle_df))
        if n == 0:
            skipped.append(trip_name)
            continue
        trip_features = trip_features.iloc[:n].reset_index(drop=True)
        vehicle_df = vehicle_df.iloc[:n].reset_index(drop=True)

        labels = derive_motion_labels(vehicle_df, WINDOW_SAMPLES)
        trip_features[LABEL_COLUMN] = labels.to_numpy()
        labeled_frames.append(trip_features)

    if skipped:
        print(f"Skipped {len(skipped)} trip(s) with no usable paired vehicle CSV: {skipped}")
    if not labeled_frames:
        raise RuntimeError("No trips could be labeled -- check dataset_root and vehicle CSV pairing.")
    return pd.concat(labeled_frames, ignore_index=True)


def deterministic_baseline_labels(df: pd.DataFrame) -> pd.Series:
    """Python re-implementation of what's actually shipping on-device
    today, evaluated against the SAME real vehicle-CAN ground truth the
    trained model is (CLAUDE.md Rule 3) -- reuses already-shipping
    threshold constants rather than inventing new ones:
      - Stationary-ish: dr/StationaryDetector.kt's own ZUPT gate
        (ZUPT_MAX_ACCEL_MPS2 / ZUPT_MAX_GYRO_RADPS), approximated here
        from the closest available WINDOWED proxy (accel_forward_std_mps2 /
        gyro_yaw_rate_std_radps) since only windowed features -- not raw
        instantaneous magnitude -- are in FEATURE_COLUMNS. An honest
        approximation, not an exact reproduction (documented, not hidden).
      - Turning: motion/TurningDetector.kt's default (0.15 rad/s).
      - Accelerating/Braking: motion/LongitudinalMotionClassifier.kt's
        default (+-1.0 m/s^2 on vehicle-frame forward acceleration --
        accel_forward_mean_mps2 IS already vehicle-frame, per
        feature_extraction.py's Gram-Schmidt).
      - Cruising vs Moving: a plain MEDIAN split of the remainder pool's
        accel_forward_std_mps2 -- deliberately simple (this baseline's
        job is to stay comparable, not become a second small classifier).
    """
    accel_forward_mean = df["accel_forward_mean_mps2"]
    accel_forward_std = df["accel_forward_std_mps2"].abs()
    gyro_yaw_rate_mean = df["gyro_yaw_rate_mean_radps"]
    gyro_yaw_rate_std = df["gyro_yaw_rate_std_radps"].abs()

    stationary = (accel_forward_std <= ZUPT_MAX_ACCEL_MPS2) & (gyro_yaw_rate_std <= ZUPT_MAX_GYRO_RADPS)
    turning = gyro_yaw_rate_mean.abs() > TURNING_MIN_YAW_RATE_RADPS
    braking = accel_forward_mean < -LONGITUDINAL_ACCEL_THRESHOLD_MPS2
    accelerating = accel_forward_mean > LONGITUDINAL_ACCEL_THRESHOLD_MPS2

    remainder = (~stationary) & (~turning) & (~braking) & (~accelerating)
    remainder_std_median = accel_forward_std[remainder].median() if remainder.any() else 0.0
    cruising = remainder & (accel_forward_std <= remainder_std_median)

    conditions = [
        stationary,
        (~stationary) & turning,
        (~stationary) & (~turning) & braking,
        (~stationary) & (~turning) & (~braking) & accelerating,
        cruising,
    ]
    choices = ["Stationary", "Turning", "Braking", "Accelerating", "Cruising"]
    labels = np.select(conditions, choices, default="Moving")
    return pd.Series(labels, index=df.index)


def _report(name: str, y_true: np.ndarray, y_pred: np.ndarray) -> None:
    print(f"\n=== {name} ===")
    print(f"Accuracy: {accuracy_score(y_true, y_pred):.3f}")
    print(classification_report(y_true, y_pred, labels=MOTION_CLASSES, target_names=MOTION_CLASSES, zero_division=0))
    print(f"Confusion matrix (rows=true, cols=predicted), class order {MOTION_CLASSES}:")
    print(confusion_matrix(y_true, y_pred, labels=MOTION_CLASSES))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--features",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "data" / "processed" / "io_vnbd_features.parquet",
    )
    parser.add_argument(
        "--dataset-root",
        type=Path,
        default=Path(__file__).resolve().parent.parent
        / "data" / "raw" / "IO-VNBD" / "extracted"
        / "Synchronised V abd S datasets" / "Categorised IOVNB Dataset",
    )
    args = parser.parse_args()

    if not args.features.exists():
        print(f"Features file not found: {args.features}. Run ml/feature_extraction.py first.", file=sys.stderr)
        return 1
    if not args.dataset_root.exists():
        print(f"Dataset root not found: {args.dataset_root}", file=sys.stderr)
        return 1

    print(f"Loading features from {args.features} and deriving real vehicle-CAN motion labels ...")
    features = pd.read_parquet(args.features)
    dataset = build_labeled_dataset(features, args.dataset_root)
    print(f"Labeled rows: {len(dataset):,} across {dataset['trip_name'].nunique()} trips")
    print("\nLabel distribution (whole dataset):")
    print(dataset[LABEL_COLUMN].value_counts())

    train_trips, val_trips = split_trips(dataset, VAL_FRACTION_OF_TRIPS, RANDOM_SEED)
    print(f"\nTrips: {len(train_trips)} train, {len(val_trips)} val (seed={RANDOM_SEED})")
    print(f"Val trips: {val_trips}")

    train_df = dataset[dataset["trip_name"].isin(train_trips)]
    val_df = dataset[dataset["trip_name"].isin(val_trips)]
    y_true = val_df[LABEL_COLUMN].to_numpy()

    # REAL FINDING while building this script: class_weight="balanced"
    # alone (the first configuration tried) reported 30.5% accuracy --
    # BELOW simply always guessing the majority class (Cruising, 40.4%
    # of val rows). That is not a fair or complete comparison (CLAUDE.md
    # Rule 13) -- balanced weighting trades overall accuracy for
    # minority-class recall, it does not make the model worse in an
    # absolute sense. Both configurations are trained and reported here,
    # side by side, alongside the trivial majority-class baseline, so
    # the real tradeoff is visible instead of hidden behind one
    # cherry-picked setting.
    majority_class = train_df[LABEL_COLUMN].value_counts().idxmax()
    majority_pred = np.full(len(val_df), majority_class)

    unweighted_model = RandomForestClassifier(
        n_estimators=100, max_depth=12, random_state=RANDOM_SEED, n_jobs=-1,
    )
    unweighted_model.fit(train_df[FEATURE_COLUMNS], train_df[LABEL_COLUMN])
    unweighted_pred = unweighted_model.predict(val_df[FEATURE_COLUMNS])

    balanced_model = RandomForestClassifier(
        n_estimators=100, max_depth=12, random_state=RANDOM_SEED, n_jobs=-1, class_weight="balanced",
    )
    balanced_model.fit(train_df[FEATURE_COLUMNS], train_df[LABEL_COLUMN])
    balanced_pred = balanced_model.predict(val_df[FEATURE_COLUMNS])

    baseline_pred = deterministic_baseline_labels(val_df).to_numpy()

    _report(f'Trivial majority-class baseline (always "{majority_class}")', y_true, majority_pred)
    _report("Deterministic baseline (Python re-implementation of what ships on-device)", y_true, baseline_pred)
    _report("Trained RandomForestClassifier (unweighted)", y_true, unweighted_pred)
    _report("Trained RandomForestClassifier (class_weight=balanced)", y_true, balanced_pred)

    importances = sorted(zip(FEATURE_COLUMNS, unweighted_model.feature_importances_), key=lambda x: -x[1])
    print("\n=== Feature importances (unweighted trained model) ===")
    for name, importance in importances:
        print(f"  {name}: {importance:.3f}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
