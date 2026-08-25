"""Exports the trained velocity model to ONNX and runs an output-parity
check between the Python (scikit-learn) prediction path and the ONNX
Runtime inference path — CLAUDE.md Rule 20: "Any ML model gets an
output-parity check between the Python training environment and the
on-device (ONNX/LiteRT) inference path before being wired into a
downstream module." Nothing downstream consumes this yet (Kotlin
VelocityModel.kt is still PLANNED) — this only proves the ONNX export
itself is faithful, which must be true before that wiring is worth
doing at all.

Retrains on ALL 72 trips (not just the 58-trip train split
train_velocity_model.py used) — standard practice once train/val
evaluation has already validated the approach (see that script's
real measured result, PRD.md Section 28): the shipped model should use
every available labelled example, not withhold 20% forever. The
train/val MAE/RMSE numbers recorded in docs/PROJECT_MAP.md remain the
honest measure of expected accuracy; this script does not re-measure
that (a full-data model's training-set score would be meaningless/
optimistic to report as if it were held-out accuracy).
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

sys.path.insert(0, str(Path(__file__).resolve().parent))
from train_velocity_model import FEATURE_COLUMNS, LABEL_COLUMN, RANDOM_SEED  # noqa: E402

PARITY_TOLERANCE_MPS = 1e-3  # ONNX Runtime uses float32 internally; sklearn's RF is exact in float64 — small numerical drift is expected, not a bug, below this threshold.


def train_final_model(dataset: pd.DataFrame) -> RandomForestRegressor:
    dataset = dataset.dropna(subset=[LABEL_COLUMN])
    model = RandomForestRegressor(n_estimators=100, max_depth=12, random_state=RANDOM_SEED, n_jobs=-1)
    model.fit(dataset[FEATURE_COLUMNS], dataset[LABEL_COLUMN])
    return model


def export_to_onnx(model: RandomForestRegressor, output_path: Path) -> None:
    initial_type = [("input", FloatTensorType([None, len(FEATURE_COLUMNS)]))]
    onnx_model = to_onnx(model, initial_types=initial_type, target_opset=17)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(onnx_model.SerializeToString())


def check_parity(model: RandomForestRegressor, onnx_path: Path, sample: pd.DataFrame) -> dict[str, float]:
    """Runs the SAME input rows through both the original sklearn model
    and the exported ONNX model via onnxruntime, and reports how far
    apart the outputs are — the actual parity check, not just "it
    exported without an exception."""
    sklearn_pred = model.predict(sample[FEATURE_COLUMNS]).astype(np.float64)

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    onnx_input = sample[FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    onnx_pred = session.run(None, {input_name: onnx_input})[0].reshape(-1).astype(np.float64)

    abs_diff = np.abs(sklearn_pred - onnx_pred)
    return {
        "max_abs_diff_mps": float(abs_diff.max()),
        "mean_abs_diff_mps": float(abs_diff.mean()),
        "n_samples": len(sample),
        "n_within_tolerance": int((abs_diff <= PARITY_TOLERANCE_MPS).sum()),
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
        default=Path(__file__).resolve().parent.parent / "models" / "velocity_v1.onnx",
    )
    parser.add_argument("--parity-sample-size", type=int, default=5000)
    args = parser.parse_args()

    if not args.features.exists():
        print(f"Features file not found: {args.features}. Run ml/feature_extraction.py first.", file=sys.stderr)
        return 1

    dataset = pd.read_parquet(args.features)
    print(f"Training final model on all {dataset['trip_name'].nunique()} trips ({len(dataset):,} rows)...")
    model = train_final_model(dataset)

    print(f"Exporting to {args.output} ...")
    export_to_onnx(model, args.output)
    print(f"Written: {args.output} ({args.output.stat().st_size / 1024:.1f} KB)")

    clean_dataset = dataset.dropna(subset=[LABEL_COLUMN])
    rng = np.random.default_rng(RANDOM_SEED)
    sample_idx = rng.choice(len(clean_dataset), size=min(args.parity_sample_size, len(clean_dataset)), replace=False)
    sample = clean_dataset.iloc[sample_idx]

    parity = check_parity(model, args.output, sample)
    print(f"\n=== Output parity (sklearn vs ONNX Runtime, n={parity['n_samples']}) ===")
    print(f"Max abs diff:  {parity['max_abs_diff_mps']:.6f} m/s")
    print(f"Mean abs diff: {parity['mean_abs_diff_mps']:.6f} m/s")
    print(f"Within {PARITY_TOLERANCE_MPS} m/s tolerance: {parity['n_within_tolerance']}/{parity['n_samples']}")

    if parity["max_abs_diff_mps"] > PARITY_TOLERANCE_MPS:
        print(
            f"\nWARNING: max diff exceeds {PARITY_TOLERANCE_MPS} m/s tolerance — "
            "DO NOT wire this into the Android app until investigated (CLAUDE.md Rule 20).",
            file=sys.stderr,
        )
        return 1

    print("\nParity check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
