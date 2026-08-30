"""Unit tests for ml/train_reacquisition_model.py's pure/deterministic
outage-simulation helpers (CLAUDE.md Rule 19). Synthetic data only, no
dependency on the real gitignored IO-VNBD download.
"""

import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "ml"))

from train_reacquisition_model import (  # noqa: E402
    DRIFT_LABEL_COLUMN,
    MIN_SAMPLES_PER_WINDOW,
    simulate_outage_samples,
)
from train_velocity_model import LABEL_COLUMN  # noqa: E402


def _synthetic_trip(n_rows: int, hz: float = 10.0, true_speed_mps: float = 10.0) -> pd.DataFrame:
    """A trip at a constant sample rate and constant true speed — makes
    the expected drift for a PERFECTLY-predicted window trivially zero,
    and for a CONSTANT-OFFSET-predicted window trivially computable by
    hand, so tests can assert exact expected values.
    """
    time_ms = np.arange(n_rows) * (1000.0 / hz)
    return pd.DataFrame(
        {
            "time_since_start_ms": time_ms,
            LABEL_COLUMN: np.full(n_rows, true_speed_mps),
        }
    )


class TestSimulateOutageSamples:
    def test_perfect_prediction_gives_zero_drift(self):
        trip = _synthetic_trip(n_rows=600, hz=10.0, true_speed_mps=10.0)
        predicted_speed = trip[LABEL_COLUMN].to_numpy()  # exactly correct every row
        rng = np.random.default_rng(0)
        samples = simulate_outage_samples(trip, predicted_speed, rng, n_samples=20)
        assert len(samples) > 0
        assert np.allclose(samples[DRIFT_LABEL_COLUMN], 0.0, atol=1e-9)

    def test_constant_offset_prediction_gives_analytically_expected_drift(self):
        # True speed 10 m/s everywhere, predicted speed always 12 m/s ->
        # constant |error| = 2 m/s -> drift = 2 * outage_duration_s exactly.
        trip = _synthetic_trip(n_rows=600, hz=10.0, true_speed_mps=10.0)
        predicted_speed = np.full(len(trip), 12.0)
        rng = np.random.default_rng(1)
        samples = simulate_outage_samples(trip, predicted_speed, rng, n_samples=20)
        assert len(samples) > 0
        expected = 2.0 * samples["outage_duration_s"]
        # Loose tolerance: window boundaries snap to the nearest real
        # sample, so actual duration/drift differ slightly from the
        # randomly drawn target duration — not from any real error.
        np.testing.assert_allclose(samples[DRIFT_LABEL_COLUMN].to_numpy(), expected.to_numpy(), rtol=0.05)

    def test_every_window_has_at_least_the_minimum_row_count(self):
        trip = _synthetic_trip(n_rows=600, hz=10.0)
        predicted_speed = trip[LABEL_COLUMN].to_numpy()
        rng = np.random.default_rng(2)
        samples = simulate_outage_samples(trip, predicted_speed, rng, n_samples=30)
        # Reconstruct row counts isn't directly exposed, but duration *
        # ~10Hz should comfortably exceed MIN_SAMPLES_PER_WINDOW for
        # every accepted sample (min duration is 5s => 50 rows at 10Hz).
        assert (samples["outage_duration_s"] * 10.0 >= MIN_SAMPLES_PER_WINDOW).all()

    def test_a_trip_too_short_for_any_outage_window_yields_no_samples(self):
        trip = _synthetic_trip(n_rows=20, hz=10.0)  # only 2 seconds long, min outage is 5s
        predicted_speed = trip[LABEL_COLUMN].to_numpy()
        rng = np.random.default_rng(3)
        samples = simulate_outage_samples(trip, predicted_speed, rng, n_samples=10)
        assert len(samples) == 0

    def test_deterministic_with_a_fixed_rng_seed(self):
        trip = _synthetic_trip(n_rows=600, hz=10.0)
        predicted_speed = trip[LABEL_COLUMN].to_numpy() + 1.0
        samples_a = simulate_outage_samples(trip, predicted_speed, np.random.default_rng(42), n_samples=15)
        samples_b = simulate_outage_samples(trip, predicted_speed, np.random.default_rng(42), n_samples=15)
        pd.testing.assert_frame_equal(samples_a, samples_b)

    def test_output_columns_match_the_declared_feature_and_label_set(self):
        trip = _synthetic_trip(n_rows=600, hz=10.0)
        predicted_speed = trip[LABEL_COLUMN].to_numpy()
        rng = np.random.default_rng(4)
        samples = simulate_outage_samples(trip, predicted_speed, rng, n_samples=5)
        assert set(samples.columns) == {
            "outage_duration_s",
            "avg_predicted_speed_mps",
            "predicted_speed_std_mps",
            DRIFT_LABEL_COLUMN,
        }
