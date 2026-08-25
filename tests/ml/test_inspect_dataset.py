"""Unit tests for ml/inspect_dataset.py's pure parsing/computation
helpers (CLAUDE.md Rule 19) — synthetic in-memory data only, no
dependency on the real (gitignored, multi-hundred-MB) IO-VNBD download,
so these run anywhere without the dataset present.
"""

import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "ml"))

from inspect_dataset import (  # noqa: E402
    _count_satellite_parse_failures,
    _observed_gps_fix_interval_s,
    _observed_hz,
)


def test_observed_hz_from_regular_100ms_deltas():
    df = pd.DataFrame({"time_since_start_ms": [1000, 1100, 1200, 1300, 1400]})
    hz, duration_s = _observed_hz(df)
    assert abs(hz - 10.0) < 1e-9
    assert abs(duration_s - 0.4) < 1e-9


def test_observed_hz_uses_median_not_mean_against_one_outlier_gap():
    # Four normal 100ms gaps plus one large 5000ms gap (e.g. a GPS/logging
    # stall) — the median should still read as ~10 Hz, not be dragged
    # down by the single outlier the way a mean would be.
    timestamps = [0, 100, 200, 300, 400, 5400]
    df = pd.DataFrame({"time_since_start_ms": timestamps})
    hz, _ = _observed_hz(df)
    assert abs(hz - 10.0) < 1e-9


def test_observed_hz_empty_or_single_row_is_zero():
    assert _observed_hz(pd.DataFrame({"time_since_start_ms": [1000]}))[0] == 0.0
    assert _observed_hz(pd.DataFrame({"time_since_start_ms": []}))[0] == 0.0


def test_satellite_parse_failures_counts_only_non_matching_rows():
    df = pd.DataFrame(
        {
            "gps_satellites_in_range": [
                "27 / 28",
                "5/6",
                " 10 / 11 ",
                "Dec-14",  # Excel date-mangling artifact, confirmed in real data
                "N/A",
            ]
        }
    )
    assert _count_satellite_parse_failures(df) == 2


def test_satellite_parse_failures_zero_when_column_absent():
    df = pd.DataFrame({"other_column": [1, 2, 3]})
    assert _count_satellite_parse_failures(df) == 0


def test_gps_fix_interval_measures_time_between_value_changes():
    # Value changes at rows 0 (first row always counts, per the NaN-shift
    # comparison), 3, and 5 -> change timestamps 0ms, 9000ms, 18000ms ->
    # two 9.0s gaps, held constant in between — matches the real
    # held-value pattern found in the actual dataset.
    df = pd.DataFrame(
        {
            "time_since_start_ms": [0, 1000, 2000, 9000, 10000, 18000],
            "gps_latitude_deg": [52.1, 52.1, 52.1, 52.2, 52.2, 52.3],
        }
    )
    assert abs(_observed_gps_fix_interval_s(df) - 9.0) < 1e-9


def test_gps_fix_interval_zero_when_fix_never_changes():
    # e.g. a stationary/parked recording — expected, not a measurement failure.
    df = pd.DataFrame(
        {
            "time_since_start_ms": [0, 1000, 2000, 3000],
            "gps_latitude_deg": [52.1, 52.1, 52.1, 52.1],
        }
    )
    assert _observed_gps_fix_interval_s(df) == 0.0
