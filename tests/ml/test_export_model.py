"""Test for ml/export_model.py's check_parity function — a real,
fast, end-to-end exercise of the sklearn -> ONNX -> onnxruntime path
(CLAUDE.md Rule 20) on a tiny synthetic model, so this doesn't depend
on the real gitignored IO-VNBD download or the multi-minute full
training run.
"""

import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
from sklearn.ensemble import RandomForestRegressor

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "ml"))

from export_model import PARITY_TOLERANCE_MPS, check_parity, export_to_onnx  # noqa: E402
from train_velocity_model import FEATURE_COLUMNS  # noqa: E402


@pytest.fixture
def tiny_trained_model_and_sample():
    rng = np.random.default_rng(0)
    n = 300
    X = pd.DataFrame({col: rng.normal(size=n) for col in FEATURE_COLUMNS})
    y = X[FEATURE_COLUMNS[0]] * 2.0 - X[FEATURE_COLUMNS[1]] + rng.normal(scale=0.05, size=n)
    model = RandomForestRegressor(n_estimators=15, max_depth=6, random_state=42, n_jobs=-1)
    model.fit(X, y)
    return model, X


def test_exported_onnx_model_matches_sklearn_predictions(tmp_path, tiny_trained_model_and_sample):
    model, X = tiny_trained_model_and_sample
    onnx_path = tmp_path / "test_velocity.onnx"

    export_to_onnx(model, onnx_path)
    assert onnx_path.exists()
    assert onnx_path.stat().st_size > 0

    parity = check_parity(model, onnx_path, X)
    assert parity["n_samples"] == len(X)
    assert parity["max_abs_diff_mps"] <= PARITY_TOLERANCE_MPS
    assert parity["n_within_tolerance"] == len(X)


def test_check_parity_detects_a_real_mismatch(tmp_path, tiny_trained_model_and_sample):
    # Sanity-check the CHECK itself: if the sklearn model changes after
    # export (simulating an ONNX export that silently diverged), parity
    # must report a nonzero, tolerance-exceeding difference rather than
    # a false pass. Trains a deliberately different second model and
    # compares it against the FIRST model's ONNX export.
    model_a, X = tiny_trained_model_and_sample
    onnx_path = tmp_path / "test_velocity_a.onnx"
    export_to_onnx(model_a, onnx_path)

    rng = np.random.default_rng(999)
    y_different = X[FEATURE_COLUMNS[0]] * -5.0 + rng.normal(scale=0.05, size=len(X))
    model_b = RandomForestRegressor(n_estimators=15, max_depth=6, random_state=42, n_jobs=-1)
    model_b.fit(X, y_different)

    parity = check_parity(model_b, onnx_path, X)  # compare model_b against model_a's export — must mismatch
    assert parity["max_abs_diff_mps"] > PARITY_TOLERANCE_MPS
    assert parity["n_within_tolerance"] < parity["n_samples"]
