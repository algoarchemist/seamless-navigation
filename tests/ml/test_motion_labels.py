"""Unit tests for ml/motion_labels.py's pure label-derivation logic
(CLAUDE.md Rule 19) — synthetic data only, no dependency on the real
gitignored IO-VNBD download.
"""

import sys
from pathlib import Path

import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "ml"))

from motion_labels import (  # noqa: E402
    ACCEL_PEDAL_MIN_PERCENT,
    CRUISING_MAX_VELOCITY_ROLLING_STD_KMH,
    STATIONARY_MAX_VELOCITY_KMH,
    TURNING_MIN_ABS_YAW_RATE_DEGPS,
    derive_motion_labels,
    vehicle_csv_path_for,
)


def _row(velocity_kmh=20.0, yaw_rate_degps=0.0, brake_position=0, accelerator_pct=0.0):
    """One-row synthetic vehicle dataframe with every other required
    column present but neutral, so a test only needs to vary the ONE
    signal it's actually checking."""
    return pd.DataFrame(
        {
            "velocity_kmh": [velocity_kmh],
            "yaw_rate_degps": [yaw_rate_degps],
            "brake_position": [brake_position],
            "accelerator_pedal_position_percent": [accelerator_pct],
        },
    )


class TestEachConditionInIsolation:
    def test_stationary_fires_below_the_velocity_threshold(self):
        df = _row(velocity_kmh=STATIONARY_MAX_VELOCITY_KMH - 0.1)
        assert derive_motion_labels(df, window_samples=10).iloc[0] == "Stationary"

    def test_not_stationary_at_or_above_the_velocity_threshold(self):
        df = _row(velocity_kmh=STATIONARY_MAX_VELOCITY_KMH)
        assert derive_motion_labels(df, window_samples=10).iloc[0] != "Stationary"

    def test_turning_fires_above_the_yaw_rate_threshold(self):
        df = _row(velocity_kmh=20.0, yaw_rate_degps=TURNING_MIN_ABS_YAW_RATE_DEGPS + 1.0)
        assert derive_motion_labels(df, window_samples=10).iloc[0] == "Turning"

    def test_turning_fires_on_negative_yaw_rate_too(self):
        # abs() is applied -- a left turn must be caught the same as a right turn.
        df = _row(velocity_kmh=20.0, yaw_rate_degps=-(TURNING_MIN_ABS_YAW_RATE_DEGPS + 1.0))
        assert derive_motion_labels(df, window_samples=10).iloc[0] == "Turning"

    def test_braking_fires_on_real_brake_pedal_ground_truth(self):
        df = _row(velocity_kmh=20.0, brake_position=1)
        assert derive_motion_labels(df, window_samples=10).iloc[0] == "Braking"

    def test_accelerating_fires_above_the_throttle_percent_threshold(self):
        df = _row(velocity_kmh=20.0, accelerator_pct=ACCEL_PEDAL_MIN_PERCENT + 1.0)
        assert derive_motion_labels(df, window_samples=10).iloc[0] == "Accelerating"

    def test_not_accelerating_at_or_below_the_throttle_percent_threshold(self):
        # ACCEL_PEDAL_MIN_PERCENT itself is exclusive (> not >=) — the
        # empirically-picked cutoff, see motion_labels.py's docstring.
        df = _row(velocity_kmh=20.0, accelerator_pct=ACCEL_PEDAL_MIN_PERCENT)
        assert derive_motion_labels(df, window_samples=10).iloc[0] != "Accelerating"


class TestPrecedenceOrder:
    def test_stationary_beats_turning_when_both_conditions_hold(self):
        df = _row(velocity_kmh=0.0, yaw_rate_degps=90.0)
        assert derive_motion_labels(df, window_samples=10).iloc[0] == "Stationary"

    def test_stationary_beats_braking_and_accelerating(self):
        df = _row(velocity_kmh=0.0, brake_position=1, accelerator_pct=99.0)
        assert derive_motion_labels(df, window_samples=10).iloc[0] == "Stationary"

    def test_turning_beats_braking(self):
        df = _row(velocity_kmh=20.0, yaw_rate_degps=90.0, brake_position=1)
        assert derive_motion_labels(df, window_samples=10).iloc[0] == "Turning"

    def test_turning_beats_accelerating(self):
        df = _row(velocity_kmh=20.0, yaw_rate_degps=90.0, accelerator_pct=99.0)
        assert derive_motion_labels(df, window_samples=10).iloc[0] == "Turning"

    def test_braking_beats_accelerating_when_both_true(self):
        # Shouldn't happen on a real, non-glitchy drive, but the
        # precedence must still resolve deterministically (braking is
        # the safety-relevant signal) rather than being undefined.
        df = _row(velocity_kmh=20.0, brake_position=1, accelerator_pct=99.0)
        assert derive_motion_labels(df, window_samples=10).iloc[0] == "Braking"


class TestCruisingVsMovingSplit:
    def test_steady_velocity_is_cruising(self):
        n = 12
        df = pd.DataFrame(
            {
                "velocity_kmh": [30.0] * n,  # zero rolling std -- perfectly steady
                "yaw_rate_degps": [0.0] * n,
                "brake_position": [0] * n,
                "accelerator_pedal_position_percent": [0.0] * n,
            },
        )
        labels = derive_motion_labels(df, window_samples=10)
        assert labels.iloc[-1] == "Cruising"

    def test_fluctuating_velocity_is_moving_not_cruising(self):
        n = 12
        # Alternates sharply -- rolling std across the window is well
        # above CRUISING_MAX_VELOCITY_ROLLING_STD_KMH.
        velocities = [10.0, 40.0] * (n // 2)
        df = pd.DataFrame(
            {
                "velocity_kmh": velocities,
                "yaw_rate_degps": [0.0] * n,
                "brake_position": [0] * n,
                "accelerator_pedal_position_percent": [0.0] * n,
            },
        )
        labels = derive_motion_labels(df, window_samples=10)
        assert labels.iloc[-1] == "Moving"

    def test_window_samples_parameter_is_respected_not_hardcoded(self):
        # A short, noisy burst that has already left a small window
        # (so rolling std over that small window reads as steady again)
        # must behave differently than the same data read through a
        # longer window that still spans the noisy burst.
        velocities = [30.0, 30.0, 30.0, 10.0, 50.0, 30.0, 30.0, 30.0, 30.0, 30.0]
        df = pd.DataFrame(
            {
                "velocity_kmh": velocities,
                "yaw_rate_degps": [0.0] * len(velocities),
                "brake_position": [0] * len(velocities),
                "accelerator_pedal_position_percent": [0.0] * len(velocities),
            },
        )
        short_window = derive_motion_labels(df, window_samples=2).iloc[-1]
        long_window = derive_motion_labels(df, window_samples=len(velocities)).iloc[-1]
        assert short_window == "Cruising"
        assert long_window == "Moving"


class TestVehicleCsvPathFor:
    def test_replaces_the_s_prefix_with_v(self):
        assert vehicle_csv_path_for(Path("/data/S-Vw14b.csv")) == Path("/data/V-Vw14b.csv")

    def test_only_the_first_s_dash_occurrence_is_replaced(self):
        # A trip name that happens to contain "S-" later in the filename
        # must not get a second, unintended replacement.
        result = vehicle_csv_path_for(Path("/data/S-S-weird.csv"))
        assert result == Path("/data/V-S-weird.csv")

    def test_preserves_the_parent_directory(self):
        result = vehicle_csv_path_for(Path("/some/nested/dir/S-M.csv"))
        assert result.parent == Path("/some/nested/dir")
