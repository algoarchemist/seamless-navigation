"""Unit tests for ml/train_motion_classifier.py's pure/deterministic
helpers (CLAUDE.md Rule 19) — synthetic data only, no dependency on the
real gitignored IO-VNBD download. Vehicle/smartphone CSV pairing is
exercised against small REAL temporary files (via pytest's tmp_path),
since build_labeled_dataset's whole job is real file IO + a row-position
join — that's the thing worth testing directly, not mocking away.
"""

import sys
from pathlib import Path

import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "ml"))

from motion_labels import VEHICLE_COLUMNS_29  # noqa: E402
from train_motion_classifier import (  # noqa: E402
    LABEL_COLUMN,
    LONGITUDINAL_ACCEL_THRESHOLD_MPS2,
    TURNING_MIN_YAW_RATE_RADPS,
    build_labeled_dataset,
    deterministic_baseline_labels,
    split_trips,
)
from train_velocity_model import ZUPT_MAX_ACCEL_MPS2, ZUPT_MAX_GYRO_RADPS  # noqa: E402


def _write_vehicle_csv(path: Path, n_rows: int) -> None:
    """A minimal, valid 29-column vehicle CSV -- neutral values
    everywhere (stationary, no yaw, no pedal input) since these tests
    care about ROW COUNT / truncation behavior, not label content."""
    row = {name: 0.0 for name in VEHICLE_COLUMNS_29}
    df = pd.DataFrame([row] * n_rows)
    df.to_csv(path, header=VEHICLE_COLUMNS_29, index=False)


def _features(trip_name: str, n_rows: int) -> pd.DataFrame:
    return pd.DataFrame({"trip_name": [trip_name] * n_rows, "dummy_feature": range(n_rows)})


class TestBuildLabeledDatasetTruncation:
    def test_truncates_to_the_shorter_side_when_smartphone_is_longer(self, tmp_path: Path):
        (tmp_path / "S-Trip1.csv").write_text("placeholder\n")  # only needs to exist for find_smartphone_trips
        _write_vehicle_csv(tmp_path / "V-Trip1.csv", n_rows=3)
        features = _features("S-Trip1", n_rows=5)

        result = build_labeled_dataset(features, tmp_path)

        assert len(result) == 3
        assert LABEL_COLUMN in result.columns

    def test_truncates_to_the_shorter_side_when_vehicle_is_longer(self, tmp_path: Path):
        (tmp_path / "S-Trip2.csv").write_text("placeholder\n")
        _write_vehicle_csv(tmp_path / "V-Trip2.csv", n_rows=5)
        features = _features("S-Trip2", n_rows=3)

        result = build_labeled_dataset(features, tmp_path)

        assert len(result) == 3

    def test_a_trip_with_no_paired_vehicle_file_is_skipped_not_crashed(self, tmp_path: Path, capsys):
        (tmp_path / "S-HasVehicle.csv").write_text("placeholder\n")
        _write_vehicle_csv(tmp_path / "V-HasVehicle.csv", n_rows=4)
        (tmp_path / "S-NoVehicle.csv").write_text("placeholder\n")
        # deliberately no V-NoVehicle.csv written

        features = pd.concat(
            [_features("S-HasVehicle", 4), _features("S-NoVehicle", 4)],
            ignore_index=True,
        )

        result = build_labeled_dataset(features, tmp_path)

        assert set(result["trip_name"].unique()) == {"S-HasVehicle"}
        assert "S-NoVehicle" in capsys.readouterr().out  # reported, not silently dropped

    def test_raises_when_no_trip_can_be_labeled_at_all(self, tmp_path: Path):
        (tmp_path / "S-Orphan.csv").write_text("placeholder\n")  # no paired vehicle file
        features = _features("S-Orphan", n_rows=4)

        with pytest.raises(RuntimeError):
            build_labeled_dataset(features, tmp_path)


class TestDeterministicBaselineLabels:
    def _row(self, accel_forward_mean=0.0, accel_forward_std=0.01, gyro_yaw_rate_mean=0.0, gyro_yaw_rate_std=0.01):
        return pd.DataFrame(
            {
                "accel_forward_mean_mps2": [accel_forward_mean],
                "accel_forward_std_mps2": [accel_forward_std],
                "gyro_yaw_rate_mean_radps": [gyro_yaw_rate_mean],
                "gyro_yaw_rate_std_radps": [gyro_yaw_rate_std],
            },
        )

    def test_low_accel_and_gyro_energy_is_stationary(self):
        df = self._row(accel_forward_std=ZUPT_MAX_ACCEL_MPS2 - 0.01, gyro_yaw_rate_std=ZUPT_MAX_GYRO_RADPS - 0.01)
        assert deterministic_baseline_labels(df).iloc[0] == "Stationary"

    def test_high_yaw_rate_mean_is_turning(self):
        df = self._row(
            accel_forward_std=ZUPT_MAX_ACCEL_MPS2 + 1.0,  # not stationary
            gyro_yaw_rate_mean=TURNING_MIN_YAW_RATE_RADPS + 0.1,
        )
        assert deterministic_baseline_labels(df).iloc[0] == "Turning"

    def test_strongly_negative_forward_accel_is_braking(self):
        df = self._row(
            accel_forward_mean=-(LONGITUDINAL_ACCEL_THRESHOLD_MPS2 + 0.5),
            accel_forward_std=ZUPT_MAX_ACCEL_MPS2 + 1.0,
        )
        assert deterministic_baseline_labels(df).iloc[0] == "Braking"

    def test_strongly_positive_forward_accel_is_accelerating(self):
        df = self._row(
            accel_forward_mean=LONGITUDINAL_ACCEL_THRESHOLD_MPS2 + 0.5,
            accel_forward_std=ZUPT_MAX_ACCEL_MPS2 + 1.0,
        )
        assert deterministic_baseline_labels(df).iloc[0] == "Accelerating"

    def test_remainder_pool_splits_on_the_median_into_cruising_and_moving(self):
        # Five rows, none stationary/turning/braking/accelerating -- the
        # remainder pool. Median std here is 0.5 (the middle value);
        # rows at/below it are Cruising, the one clearly above it (0.9)
        # is Moving.
        df = pd.DataFrame(
            {
                "accel_forward_mean_mps2": [0.0] * 5,
                "accel_forward_std_mps2": [0.1, 0.3, 0.5, 0.7, 0.9],
                "gyro_yaw_rate_mean_radps": [0.0] * 5,
                "gyro_yaw_rate_std_radps": [ZUPT_MAX_GYRO_RADPS + 1.0] * 5,  # keep all out of "Stationary"
            },
        )
        labels = deterministic_baseline_labels(df)
        assert labels.iloc[0] == "Cruising"  # 0.1 <= median
        assert labels.iloc[4] == "Moving"  # 0.9 > median


class TestSplitTripsReuse:
    def test_imported_split_trips_still_returns_a_no_leakage_split(self):
        dataset = pd.DataFrame({"trip_name": [f"trip{i}" for i in range(10)]})
        train, val = split_trips(dataset, val_fraction=0.2, seed=42)
        assert set(train).isdisjoint(set(val))
        assert set(train) | set(val) == set(dataset["trip_name"])
