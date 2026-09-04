"""Unit tests for scripts/analyze_drive_log.py's pure/deterministic
helpers (CLAUDE.md Rule 19) — synthetic data only, no dependency on a
real device-pulled drive log CSV.
"""

import sys
from pathlib import Path

import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "scripts"))

from analyze_drive_log import (  # noqa: E402
    GNSS_STATIONARY_SPEED_EPSILON_MPS,
    fresh_fix_intervals_ms,
    gnss_bearing_rate_samples,
    has_raw_columns,
    has_turning_columns,
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


class TestFreshFixIntervalsMs:
    def test_detects_drop_as_a_fresh_fix(self):
        # elapsedMs ticks every 100ms; fixAgeMs climbs with it (no new fix)
        # until row 3, where it drops back near zero - a fresh fix landed.
        df = pd.DataFrame(
            {
                "elapsedMs": [0, 100, 200, 300, 400, 500],
                "gnssFixAgeMs": [2000, 2100, 2200, 50, 150, 250],
            },
        )
        # Only one drop (row index 3) -> zero completed intervals between
        # fresh fixes (need at least two fresh-fix events to form one).
        assert fresh_fix_intervals_ms(df) == []

    def test_two_fresh_fixes_yield_one_interval(self):
        # Row 0 can never itself be detected as a "fresh fix" (diff() has no
        # prior row to compare against) - it only establishes the baseline
        # age that row 3's drop is measured against. So this has TWO
        # detectable fresh-fix events (rows 3 and 6), yielding one interval
        # between them.
        df = pd.DataFrame(
            {
                "elapsedMs": [0, 100, 200, 6300, 6400, 6500, 12700, 12800],
                "gnssFixAgeMs": [2000, 2100, 2200, 50, 150, 250, 60, 160],
            },
        )
        assert fresh_fix_intervals_ms(df) == [6400]

    def test_reproduces_the_real_window_drive_cadence(self):
        # Regression check against docs/gnss-indoor-window-degradation.md's
        # real capture: three fresh fixes ~6.3s apart, matching the
        # measured indoor refresh cadence that motivated raising
        # GnssQuality.DEFAULT_MAX_FIX_AGE_MS from 3000ms to 7000ms. Age
        # climbs between each fresh fix so every one after the first is a
        # genuine drop, not just a low value.
        df = pd.DataFrame(
            {
                "elapsedMs": [0, 100, 3300, 6417, 9700, 12734],
                "gnssFixAgeMs": [9999, 50, 3300, 50, 3383, 50],
            },
        )
        assert fresh_fix_intervals_ms(df) == [6317, 6317]

    def test_no_drops_yields_no_intervals(self):
        df = pd.DataFrame(
            {
                "elapsedMs": [0, 100, 200],
                "gnssFixAgeMs": [100, 200, 300],
            },
        )
        assert fresh_fix_intervals_ms(df) == []


class TestGnssBearingRateSamples:
    def test_missing_turning_columns_returns_empty(self):
        df = pd.DataFrame({"elapsedMs": [0, 100], "gnssFixAgeMs": [100, 50]})
        result = gnss_bearing_rate_samples(df)
        assert result.empty
        assert list(result.columns) == ["elapsedMs", "bearingRateDegPerSec", "isTurning"]

    def test_bearing_wraparound_reads_as_a_small_step_not_a_360deg_spin(self):
        # Regression test for the "moving in a straight line the app shows
        # i am turning" user report: bearing crossing 359deg -> 2deg is a
        # real (tiny) +3deg heading change, not the -357deg a naive
        # subtraction would compute. Two fresh-fix pairs, both near-zero
        # real turning rate, matching a genuinely straight drive.
        df = pd.DataFrame(
            {
                "elapsedMs": [0, 100, 3300, 5200, 8300, 10200],
                "gnssFixAgeMs": [9999, 50, 3300, 50, 3383, 50],
                "gnssBearingDeg": [None, 359.0, None, 2.0, None, 359.0],
                "isTurning": [False, False, False, True, False, False],
            },
        )
        samples = gnss_bearing_rate_samples(df)
        assert len(samples) == 2
        # elapsed 100 -> 5200 (5.1s): 359 -> 2 is +3deg, not -357deg.
        assert samples.iloc[0]["bearingRateDegPerSec"] == pytest.approx(3.0 / 5.1, abs=1e-6)
        assert bool(samples.iloc[0]["isTurning"]) is True  # paired with the SECOND fix (elapsed=5200)
        # elapsed 5200 -> 10200 (5.0s): 2 -> 359 is -3deg, not +357deg.
        assert samples.iloc[1]["bearingRateDegPerSec"] == pytest.approx(-3.0 / 5.0, abs=1e-6)
        assert bool(samples.iloc[1]["isTurning"]) is False

    def test_a_real_turn_produces_a_large_rate(self):
        df = pd.DataFrame(
            {
                "elapsedMs": [0, 100, 2100],
                "gnssFixAgeMs": [9999, 100, 50],
                "gnssBearingDeg": [None, 10.0, 55.0],
                "isTurning": [False, False, True],
            },
        )
        samples = gnss_bearing_rate_samples(df)
        assert len(samples) == 1
        # 45deg over 2.0s = 22.5 deg/s, well above TurningDetector's ~8.6 deg/s bar.
        assert samples.iloc[0]["bearingRateDegPerSec"] == pytest.approx(22.5, abs=1e-6)
        assert bool(samples.iloc[0]["isTurning"]) is True

    def test_fresh_fixes_without_a_bearing_are_skipped(self):
        # Bearing is commonly absent at low speed (Location.hasBearing()
        # false) - those fresh fixes must not be paired at all, not treated
        # as a 0deg/s sample.
        df = pd.DataFrame(
            {
                "elapsedMs": [0, 100, 5100, 10100],
                "gnssFixAgeMs": [9999, 50, 3300, 50],
                "gnssBearingDeg": [None, 10.0, None, 15.0],
                "isTurning": [False, False, False, False],
            },
        )
        samples = gnss_bearing_rate_samples(df)
        # Only the (elapsed=100, elapsed=10100) pair both have a real bearing.
        assert len(samples) == 1
        assert samples.iloc[0]["elapsedMs"] == 10100


class TestHasTurningColumns:
    def test_true_when_both_columns_present(self):
        df = pd.DataFrame({"isTurning": [True], "gnssBearingDeg": [10.0]})
        assert has_turning_columns(df) is True

    def test_false_on_a_pre_2026_09_04_log_missing_turning_columns(self):
        df = pd.DataFrame({"elapsedMs": [0], "gnssFixAgeMs": [100]})
        assert has_turning_columns(df) is False


class TestHasRawColumns:
    def test_true_when_both_raw_columns_present(self):
        df = pd.DataFrame({"rawLinearAccelMagnitudeMps2": [0.1], "rawGyroMagnitudeRadPerSec": [0.1]})
        assert has_raw_columns(df) is True

    def test_false_on_a_pre_2026_09_01_log_missing_raw_columns(self):
        df = pd.DataFrame({"linearAccelMagnitudeMps2": [0.1], "gyroMagnitudeRadPerSec": [0.1]})
        assert has_raw_columns(df) is False
