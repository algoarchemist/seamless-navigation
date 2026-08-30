"""Test for ml/export_reacquisition_model.py's check_parity function — a
real, fast, end-to-end exercise of the sklearn LinearRegression -> ONNX ->
onnxruntime path (CLAUDE.md Rule 20) on tiny synthetic data, mirroring
tests/ml/test_export_model.py's own pattern for the velocity model.
"""

import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
from sklearn.linear_model import LinearRegression

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "ml"))

from export_reacquisition_model import PARITY_TOLERANCE_M, check_parity, export_to_onnx  # noqa: E402
from train_reacquisition_model import DRIFT_FEATURE_COLUMNS  # noqa: E402


@pytest.fixture
def tiny_trained_model_and_sample():
    rng = np.random.default_rng(0)
    n = 200
    X = pd.DataFrame({col: rng.uniform(0, 30, size=n) for col in DRIFT_FEATURE_COLUMNS})
    y = X[DRIFT_FEATURE_COLUMNS[0]] * 1.5 + X[DRIFT_FEATURE_COLUMNS[1]] * 2.0 + rng.normal(scale=0.1, size=n)
    model = LinearRegression()
    model.fit(X, y)
    return model, X


def test_exported_onnx_model_matches_sklearn_predictions(tmp_path, tiny_trained_model_and_sample):
    model, X = tiny_trained_model_and_sample
    onnx_path = tmp_path / "test_reacquisition_drift.onnx"

    export_to_onnx(model, onnx_path)
    assert onnx_path.exists()
    assert onnx_path.stat().st_size > 0

    parity = check_parity(model, onnx_path, X)
    assert parity["n_samples"] == len(X)
    assert parity["max_abs_diff_m"] <= PARITY_TOLERANCE_M
    assert parity["n_within_tolerance"] == len(X)


def test_check_parity_detects_a_real_mismatch(tmp_path, tiny_trained_model_and_sample):
    model_a, X = tiny_trained_model_and_sample
    onnx_path = tmp_path / "test_reacquisition_drift_a.onnx"
    export_to_onnx(model_a, onnx_path)

    rng = np.random.default_rng(999)
    y_different = X[DRIFT_FEATURE_COLUMNS[0]] * -5.0 + rng.normal(scale=0.1, size=len(X))
    model_b = LinearRegression()
    model_b.fit(X, y_different)

    parity = check_parity(model_b, onnx_path, X)
    assert parity["max_abs_diff_m"] > PARITY_TOLERANCE_M
    assert parity["n_within_tolerance"] < parity["n_samples"]
