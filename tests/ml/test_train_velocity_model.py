"""Unit tests for ml/train_velocity_model.py's pure/deterministic
helpers (CLAUDE.md Rule 19) plus a training-reproducibility check
(PRD.md Section 27 — a required ML test, not just observed once).
Synthetic data only, no dependency on the real gitignored download.
"""

import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
from sklearn.ensemble import RandomForestRegressor

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "ml"))

from train_velocity_model import (  # noqa: E402
    FEATURE_COLUMNS,
    LABEL_COLUMN,
    RANDOM_SEED,
    ZUPT_MAX_ACCEL_MPS2,
    ZUPT_MAX_GYRO_RADPS,
    evaluate,
    evaluate_deduplicated_by_fix,
    physics_baseline_velocity,
    split_trips,
)


def _synthetic_dataset(trip_names: list[str], rows_per_trip: int = 5) -> pd.DataFrame:
    frames = []
    for name in trip_names:
        frames.append(pd.DataFrame({"trip_name": [name] * rows_per_trip}))
    return pd.concat(frames, ignore_index=True)


class TestSplitTrips:
    def test_no_overlap_between_train_and_val(self):
        trips = [f"trip{i}" for i in range(20)]
        dataset = _synthetic_dataset(trips)
        train, val = split_trips(dataset, val_fraction=0.2, seed=1)
        assert set(train).isdisjoint(set(val))

    def test_union_covers_every_trip(self):
        trips = [f"trip{i}" for i in range(20)]
        dataset = _synthetic_dataset(trips)
        train, val = split_trips(dataset, val_fraction=0.2, seed=1)
        assert set(train) | set(val) == set(trips)

    def test_deterministic_with_fixed_seed(self):
        trips = [f"trip{i}" for i in range(30)]
        dataset = _synthetic_dataset(trips)
        train1, val1 = split_trips(dataset, val_fraction=0.2, seed=RANDOM_SEED)
        train2, val2 = split_trips(dataset, val_fraction=0.2, seed=RANDOM_SEED)
        assert train1 == train2
        assert val1 == val2

    def test_different_seeds_can_produce_different_splits(self):
        trips = [f"trip{i}" for i in range(30)]
        dataset = _synthetic_dataset(trips)
        _, val_a = split_trips(dataset, val_fraction=0.2, seed=1)
        _, val_b = split_trips(dataset, val_fraction=0.2, seed=2)
        assert val_a != val_b

    def test_val_fraction_approximately_respected(self):
        trips = [f"trip{i}" for i in range(50)]
        dataset = _synthetic_dataset(trips)
        train, val = split_trips(dataset, val_fraction=0.2, seed=1)
        assert len(val) == 10  # round(50 * 0.2)
        assert len(train) == 40

    def test_at_least_one_val_trip_even_with_small_val_fraction(self):
        trips = [f"trip{i}" for i in range(3)]
        dataset = _synthetic_dataset(trips)
        train, val = split_trips(dataset, val_fraction=0.01, seed=1)
        assert len(val) >= 1


class TestPhysicsBaselineVelocity:
    def test_constant_acceleration_above_zupt_threshold_integrates(self):
        # accel well above the ZUPT threshold every tick, dt=0.1s, so ZUPT
        # never fires and this should match plain Euler integration.
        n = 5
        accel = 1.0  # m/s^2, above ZUPT_MAX_ACCEL_MPS2
        trip = pd.DataFrame(
            {
                "time_since_start_ms": [i * 100 for i in range(n)],
                "accel_forward_mps2": [accel] * n,
                "gyro_yaw_rate_radps": [0.0] * n,  # below ZUPT gyro threshold, but accel alone keeps it moving
            }
        )
        velocity = physics_baseline_velocity(trip)
        assert velocity[0] == 0.0
        expected = [0.0, 0.1, 0.2, 0.3, 0.4]
        assert velocity.tolist() == pytest.approx(expected, abs=1e-9)

    def test_zupt_zeroes_velocity_when_stationary(self):
        # Non-zero accel/gyro for the first two ticks (builds up velocity),
        # then both drop below threshold — ZUPT should zero it out.
        trip = pd.DataFrame(
            {
                "time_since_start_ms": [0, 100, 200, 300],
                "accel_forward_mps2": [2.0, 2.0, 0.0, 0.0],
                "gyro_yaw_rate_radps": [0.0, 0.0, 0.0, 0.0],
            }
        )
        velocity = physics_baseline_velocity(trip)
        assert velocity[1] == pytest.approx(0.2, abs=1e-9)  # built up velocity
        assert velocity[2] == 0.0  # ZUPT fires: accel and gyro both below threshold
        assert velocity[3] == 0.0

    def test_high_gyro_alone_prevents_zupt(self):
        # velocity[i] is built from accel[i] (the row's OWN acceleration,
        # not the previous row's) — tick 1 builds up velocity 0.2 m/s
        # (accel=2.0 alone already exceeds the accel threshold, so ZUPT
        # can't fire there regardless of gyro). Tick 2 has accel back
        # below the ZUPT accel threshold (would satisfy that half of the
        # AND on its own), but gyro stays high — since ZUPT requires BOTH
        # accel AND gyro below threshold, it must NOT fire, and the
        # velocity built up in tick 1 must be preserved into tick 2.
        trip = pd.DataFrame(
            {
                "time_since_start_ms": [0, 100, 200],
                "accel_forward_mps2": [0.0, 2.0, 0.0],
                "gyro_yaw_rate_radps": [0.0, ZUPT_MAX_GYRO_RADPS + 1.0, ZUPT_MAX_GYRO_RADPS + 1.0],
            }
        )
        velocity = physics_baseline_velocity(trip)
        assert velocity[1] == pytest.approx(0.2, abs=1e-9)
        assert velocity[2] == pytest.approx(0.2, abs=1e-9)  # preserved, ZUPT did NOT fire

    def test_nonpositive_dt_holds_previous_velocity(self):
        trip = pd.DataFrame(
            {
                "time_since_start_ms": [0, 100, 100],  # duplicate timestamp -> dt=0 for row 2
                "accel_forward_mps2": [2.0, 2.0, 5.0],
                "gyro_yaw_rate_radps": [0.0, 0.0, 0.0],
            }
        )
        velocity = physics_baseline_velocity(trip)
        assert velocity[2] == velocity[1]  # held, accel[2]=5.0 must not have been integrated


class TestEvaluate:
    def test_mae_and_rmse_known_values(self):
        y_true = np.array([1.0, 2.0, 3.0, 4.0])
        y_pred = np.array([1.0, 2.0, 3.0, 6.0])  # one error of 2.0
        result = evaluate(y_true, y_pred)
        assert result["mae_mps"] == pytest.approx(0.5, abs=1e-9)  # (0+0+0+2)/4
        assert result["rmse_mps"] == pytest.approx(1.0, abs=1e-9)  # sqrt((0+0+0+4)/4)


class TestEvaluateDeduplicatedByFix:
    def test_only_evaluates_at_label_change_rows(self):
        trip = pd.DataFrame({LABEL_COLUMN: [5.0, 5.0, 5.0, 8.0, 8.0, 3.0]})
        y_pred = np.array([5.0, 99.0, 99.0, 8.0, 99.0, 3.0])  # wrong on non-change rows
        result = evaluate_deduplicated_by_fix(trip, y_pred)
        # Changes at indices 0, 3, 5 — predictions there are all exact.
        assert result is not None
        assert result["mae_mps"] == pytest.approx(0.0, abs=1e-9)

    def test_returns_none_when_label_never_changes(self):
        trip = pd.DataFrame({LABEL_COLUMN: [5.0, 5.0, 5.0]})
        y_pred = np.array([1.0, 2.0, 3.0])
        assert evaluate_deduplicated_by_fix(trip, y_pred) is None


class TestReproducibility:
    def test_random_forest_predictions_reproducible_with_fixed_seed(self):
        # PRD.md Section 27: training reproducibility is a required ML
        # test, not just an observed property of one run.
        rng = np.random.default_rng(0)
        n = 200
        X = pd.DataFrame(
            {col: rng.normal(size=n) for col in FEATURE_COLUMNS}
        )
        y = X[FEATURE_COLUMNS[0]] * 2.0 + rng.normal(scale=0.1, size=n)

        model_a = RandomForestRegressor(n_estimators=20, max_depth=5, random_state=RANDOM_SEED, n_jobs=-1)
        model_a.fit(X, y)
        model_b = RandomForestRegressor(n_estimators=20, max_depth=5, random_state=RANDOM_SEED, n_jobs=-1)
        model_b.fit(X, y)

        pred_a = model_a.predict(X)
        pred_b = model_b.predict(X)
        assert pred_a.tolist() == pytest.approx(pred_b.tolist(), abs=1e-12)
