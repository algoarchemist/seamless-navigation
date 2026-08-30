"""Exports the reacquisition-drift LinearRegression model to ONNX and
runs an output-parity check between the Python (scikit-learn) prediction
path and the ONNX Runtime inference path — CLAUDE.md Rule 20, same
discipline as export_model.py.

Retrains the VELOCITY model on ALL 72 trips first (matching the actual
shipped models/velocity_v1.onnx from export_model.py — this script does
NOT reuse that file directly, to keep this pipeline self-contained and
reproducible from the features parquet alone, but the training call is
identical), then simulates outages across ALL 72 trips using that
production-quality model's own predictions, and trains the FINAL
LinearRegression drift model on the full simulated set. Same "standard
practice once train/val has already validated the approach" reasoning
export_model.py's own docstring gives — train_reacquisition_model.py's
held-out MAE/RMSE numbers remain the honest accuracy figure to quote, not
this script's (optimistic, full-data) training score.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import pandas as pd
from skl2onnx import to_onnx
from skl2onnx.common.data_types import FloatTensorType
from sklearn.ensemble import RandomForestRegressor
from sklearn.linear_model import LinearRegression

sys.path.insert(0, str(Path(__file__).resolve().parent))
from train_velocity_model import FEATURE_COLUMNS, LABEL_COLUMN, RANDOM_SEED  # noqa: E402
from train_reacquisition_model import (  # noqa: E402
    DRIFT_FEATURE_COLUMNS,
    DRIFT_LABEL_COLUMN,
    SAMPLES_PER_TRIP,
    build_drift_dataset,
)

PARITY_TOLERANCE_M = 1e-3  # same reasoning as export_model.py's PARITY_TOLERANCE_MPS


def train_final_velocity_model(dataset: pd.DataFrame) -> RandomForestRegressor:
    model = RandomForestRegressor(n_estimators=100, max_depth=12, random_state=RANDOM_SEED, n_jobs=-1)
    model.fit(dataset[FEATURE_COLUMNS], dataset[LABEL_COLUMN])
    return model


def export_to_onnx(model: LinearRegression, output_path: Path) -> None:
    initial_type = [("input", FloatTensorType([None, len(DRIFT_FEATURE_COLUMNS)]))]
    onnx_model = to_onnx(model, initial_types=initial_type, target_opset=17)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(onnx_model.SerializeToString())


def check_parity(model: LinearRegression, onnx_path: Path, sample: pd.DataFrame) -> dict[str, float]:
    sklearn_pred = model.predict(sample[DRIFT_FEATURE_COLUMNS]).astype(np.float64)

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    onnx_input = sample[DRIFT_FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    onnx_pred = session.run(None, {input_name: onnx_input})[0].reshape(-1).astype(np.float64)

    abs_diff = np.abs(sklearn_pred - onnx_pred)
    return {
        "max_abs_diff_m": float(abs_diff.max()),
        "mean_abs_diff_m": float(abs_diff.mean()),
        "n_samples": len(sample),
        "n_within_tolerance": int((abs_diff <= PARITY_TOLERANCE_M).sum()),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--features",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "data" / "processed" / "io_vnbd_features.parquet",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "models" / "reacquisition_drift_v1.onnx",
    )
    parser.add_argument("--parity-sample-size", type=int, default=500)
    args = parser.parse_args()

    if not args.features.exists():
        print(f"Features file not found: {args.features}. Run ml/feature_extraction.py first.", file=sys.stderr)
        return 1

    dataset = pd.read_parquet(args.features).dropna(subset=[LABEL_COLUMN])

    print(f"Training production velocity model on all {dataset['trip_name'].nunique()} trips ({len(dataset):,} rows)...")
    velocity_model = train_final_velocity_model(dataset)

    all_trips = sorted(dataset["trip_name"].unique().tolist())
    print(f"Simulating outages across all {len(all_trips)} trips ({SAMPLES_PER_TRIP} windows/trip)...")
    drift_dataset = build_drift_dataset(dataset, velocity_model, all_trips, RANDOM_SEED)
    print(f"Simulated outage samples: {len(drift_dataset)}")

    drift_model = LinearRegression()
    drift_model.fit(drift_dataset[DRIFT_FEATURE_COLUMNS], drift_dataset[DRIFT_LABEL_COLUMN])
    print(f"Coefficients: {dict(zip(DRIFT_FEATURE_COLUMNS, drift_model.coef_))}")
    print(f"Intercept: {drift_model.intercept_:.4f}")

    print(f"\nExporting to {args.output} ...")
    export_to_onnx(drift_model, args.output)
    print(f"Written: {args.output} ({args.output.stat().st_size / 1024:.2f} KB)")

    rng = np.random.default_rng(RANDOM_SEED)
    sample_idx = rng.choice(len(drift_dataset), size=min(args.parity_sample_size, len(drift_dataset)), replace=False)
    sample = drift_dataset.iloc[sample_idx]

    parity = check_parity(drift_model, args.output, sample)
    print(f"\n=== Output parity (sklearn vs ONNX Runtime, n={parity['n_samples']}) ===")
    print(f"Max abs diff:  {parity['max_abs_diff_m']:.6f} m")
    print(f"Mean abs diff: {parity['mean_abs_diff_m']:.6f} m")
    print(f"Within {PARITY_TOLERANCE_M} m tolerance: {parity['n_within_tolerance']}/{parity['n_samples']}")

    if parity["max_abs_diff_m"] > PARITY_TOLERANCE_M:
        print(
            f"\nWARNING: max diff exceeds {PARITY_TOLERANCE_M} m tolerance — "
            "DO NOT wire this into the Android app until investigated (CLAUDE.md Rule 20).",
            file=sys.stderr,
        )
        return 1

    print("\nParity check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
