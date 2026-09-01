"""Unit tests for scripts/analyze_drive_log.py's pure/deterministic
helpers (CLAUDE.md Rule 19) — synthetic data only, no dependency on a
real device-pulled drive log CSV.
"""

import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "scripts"))

from analyze_drive_log import (  # noqa: E402
    GNSS_STATIONARY_SPEED_EPSILON_MPS,
    has_raw_columns,
    sweep_zupt_thresholds,
)


def _synthetic_log(
    stationary_accel: list[float],
    stationary_gyro: list[float],
    moving_accel: list[float],
    moving_gyro: list[float],
) -> pd.DataFrame:
    """GNSS_AIDED rows only — the sweep only ever looks at GNSS_AIDED rows
    with a real gnssSpeedMps, per report_zupt_validation's own ground-truth
    reasoning (GNSS speed while GNSS_AIDED is independent of the accel/gyro
    signal being swept, so it's a fair ground truth).
    """
    n_stationary = len(stationary_accel)
    n_moving = len(moving_accel)
    return pd.DataFrame(
        {
            "gnssMode": ["GNSS_AIDED"] * (n_stationary + n_moving),
            "gnssSpeedMps": [0.0] * n_stationary + [10.0] * n_moving,
            "linearAccelMagnitudeMps2": stationary_accel + moving_accel,
            "gyroMagnitudeRadPerSec": stationary_gyro + moving_gyro,
        },
    )


class TestSweepZuptThresholds:
    def test_perfectly_separable_classes_find_zero_error_combo(self):
        # Stationary samples all near-zero accel/gyro; moving samples all
        # well above the swept grid's lower end — a threshold should exist
        # with zero false negatives AND zero false positives.
        df = _synthetic_log(
            stationary_accel=[0.05, 0.08, 0.10],
            stationary_gyro=[0.01, 0.02, 0.01],
            moving_accel=[3.0, 3.5, 4.0],
            moving_gyro=[0.8, 0.9, 0.85],
        )
        sweep = sweep_zupt_thresholds(df, accel_grid=[0.25, 1.0], gyro_grid=[0.05, 0.5])
        assert (sweep["combined_error_rate"] == 0.0).any()

    def test_fully_overlapping_classes_cannot_reach_zero_error(self):
        # Stationary and moving accel/gyro distributions are IDENTICAL —
        # no threshold can separate them, so every swept combo must have a
        # positive combined error rate (this is the real ceiling this
        # drive's data showed, reproduced here on purpose so the sweep is
        # proven to detect "not separable," not just to always find a win).
        shared_accel = [1.0, 1.5, 2.0]
        shared_gyro = [0.1, 0.15, 0.2]
        df = _synthetic_log(
            stationary_accel=shared_accel,
            stationary_gyro=shared_gyro,
            moving_accel=shared_accel,
            moving_gyro=shared_gyro,
        )
        sweep = sweep_zupt_thresholds(df, accel_grid=[0.5, 1.0, 1.5, 2.0, 2.5], gyro_grid=[0.05, 0.1, 0.15, 0.2, 0.25])
        assert (sweep["combined_error_rate"] > 0.0).all()

    def test_ignores_dead_reckoning_rows(self):
        # A DEAD_RECKONING-mode row has no independent GNSS-speed ground
        # truth to check against — must be excluded, not treated as
        # "moving" or "stationary" by accident.
        aided = _synthetic_log(
            stationary_accel=[0.05],
            stationary_gyro=[0.01],
            moving_accel=[3.0],
            moving_gyro=[0.8],
        )
        dr_row = pd.DataFrame(
            {
                "gnssMode": ["DEAD_RECKONING"],
                "gnssSpeedMps": [None],
                "linearAccelMagnitudeMps2": [0.05],
                "gyroMagnitudeRadPerSec": [0.01],
            },
        )
        df = pd.concat([aided, dr_row], ignore_index=True)
        sweep_with_dr = sweep_zupt_thresholds(df, accel_grid=[0.25, 1.0], gyro_grid=[0.05, 0.5])
        sweep_without_dr = sweep_zupt_thresholds(aided, accel_grid=[0.25, 1.0], gyro_grid=[0.05, 0.5])
        pd.testing.assert_frame_equal(sweep_with_dr, sweep_without_dr)

    def test_stationary_epsilon_matches_module_constant(self):
        # A sanity check that this test file's synthetic ground truth
        # (0.0 vs 10.0 m/s) sits unambiguously on either side of the
        # module's own stationary/moving cutoff, not a coincidence.
        assert 0.0 < GNSS_STATIONARY_SPEED_EPSILON_MPS < 10.0


class TestHasRawColumns:
    def test_true_when_both_raw_columns_present(self):
        df = pd.DataFrame({"rawLinearAccelMagnitudeMps2": [0.1], "rawGyroMagnitudeRadPerSec": [0.1]})
        assert has_raw_columns(df) is True

    def test_false_on_a_pre_2026_09_01_log_missing_raw_columns(self):
        df = pd.DataFrame({"linearAccelMagnitudeMps2": [0.1], "gyroMagnitudeRadPerSec": [0.1]})
        assert has_raw_columns(df) is False
