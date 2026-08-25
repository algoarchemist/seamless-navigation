"""Unit tests for ml/feature_extraction.py's pure vehicle-frame math
(CLAUDE.md Rule 19) — synthetic data only, no dependency on the real
gitignored IO-VNBD download.
"""

import sys
from math import cos, radians, sin
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "ml"))

from feature_extraction import (  # noqa: E402
    _elapsed_since_last_gps_fix_s,
    rotate_to_vehicle_frame,
)

TOL = 1e-9


def test_flat_phone_pure_forward_vector_is_all_forward():
    # Android's accelerometer/gravity convention reports the REACTION
    # force, which already points UP for a stationary flat phone — so
    # gravity=(0,0,+9.8) means up=(0,0,1) directly (no negation; see
    # _vehicle_frame_axes' docstring for the real bug this correction
    # fixed). The forward axis is -device-Y (empirically corrected —
    # see module docstring's "SIGN CORRECTION" note), which is already
    # horizontal here, so Gram-Schmidt is a no-op.
    gravity = np.array([[0.0, 0.0, 9.8]])
    vec = np.array([[0.0, -1.0, 0.0]])  # -device-Y == forward
    forward, lateral, up = rotate_to_vehicle_frame(vec, gravity)
    assert forward[0] == pytest.approx(1.0, abs=TOL)
    assert lateral[0] == pytest.approx(0.0, abs=TOL)
    assert up[0] == pytest.approx(0.0, abs=TOL)


def test_flat_phone_pure_up_vector_is_all_up():
    gravity = np.array([[0.0, 0.0, 9.8]])
    vec = np.array([[0.0, 0.0, 1.0]])
    forward, lateral, up = rotate_to_vehicle_frame(vec, gravity)
    assert up[0] == pytest.approx(1.0, abs=TOL)
    assert forward[0] == pytest.approx(0.0, abs=TOL)
    assert lateral[0] == pytest.approx(0.0, abs=TOL)


def test_tilted_phone_gram_schmidt_correction():
    # Phone tilted 30 degrees so -device-Y (raw forward axis) has picked
    # up a vertical component. gravity (= up directly, per the
    # reaction-force convention) = (0, -sin(30deg), cos(30deg)).
    theta = radians(30)
    gravity = np.array([[0.0, -sin(theta), cos(theta)]]) * 9.8
    # By hand (forward_raw = (0,-1,0)): dot(forward_raw, up) = sin(theta);
    # forward_unnorm = (0,-1,0) - sin(theta)*(0,-sin,cos)
    #                = (0, -cos^2(theta), -sin(theta)cos(theta))
    #                = -cos(theta) * (0, cos(theta), sin(theta))
    # normalizing (dividing by |.| = cos(theta)) flips the sign:
    expected_forward_axis = np.array([0.0, -cos(theta), -sin(theta)])

    # Feed the EXPECTED corrected forward axis itself through — since
    # forward IS defined as the Gram-Schmidt-corrected device-Y, this
    # must project to (1, 0, 0): all forward, none lateral/up.
    forward, lateral, up = rotate_to_vehicle_frame(
        expected_forward_axis.reshape(1, 3), gravity,
    )
    assert forward[0] == pytest.approx(1.0, abs=1e-6)
    assert lateral[0] == pytest.approx(0.0, abs=1e-6)
    assert up[0] == pytest.approx(0.0, abs=1e-6)


def test_tilted_phone_raw_device_y_has_a_leaked_up_component():
    # The RAW (uncorrected) device-Y axis, fed through the same tilted
    # basis, must show a nonzero "up" component — this is exactly the
    # leakage Gram-Schmidt exists to remove from the FORWARD AXIS
    # DEFINITION (not from arbitrary input vectors, which correctly
    # decompose however they actually point).
    theta = radians(30)
    gravity = np.array([[0.0, -sin(theta), cos(theta)]]) * 9.8
    # up = normalize(gravity) = (0, -sin(theta), cos(theta)), so
    # dot(raw device-Y, up) = dot((0,1,0), (0,-sin,cos)) = -sin(theta).
    raw_device_y = np.array([[0.0, 1.0, 0.0]])
    forward, lateral, up = rotate_to_vehicle_frame(raw_device_y, gravity)
    assert up[0] == pytest.approx(-sin(theta), abs=1e-6)


def test_lateral_axis_is_orthogonal_to_forward_and_up():
    gravity = np.array([[1.5, -2.0, -9.5]])
    # A vector known to be pure lateral for THIS basis: cross(forward, up)
    # is antiparallel/parallel to lateral by construction — verify the
    # basis is at least self-consistent (unit, orthogonal) rather than
    # asserting a specific numeric lateral value by hand for a skew case.
    from feature_extraction import _vehicle_frame_axes

    up, forward, lateral = _vehicle_frame_axes(gravity)
    assert np.dot(forward[0], up[0]) == pytest.approx(0.0, abs=1e-9)
    assert np.dot(lateral[0], up[0]) == pytest.approx(0.0, abs=1e-9)
    assert np.dot(lateral[0], forward[0]) == pytest.approx(0.0, abs=1e-9)
    assert np.linalg.norm(forward[0]) == pytest.approx(1.0, abs=1e-9)
    assert np.linalg.norm(up[0]) == pytest.approx(1.0, abs=1e-9)
    assert np.linalg.norm(lateral[0]) == pytest.approx(1.0, abs=1e-9)


def test_elapsed_since_last_gps_fix_matches_real_dataset_pattern():
    # Mirrors the real ~9s held-value pattern found in IO-VNBD.
    df = pd.DataFrame(
        {
            "time_since_start_ms": [0, 1000, 2000, 9000, 10000],
            "gps_latitude_deg": [52.1, 52.1, 52.1, 52.2, 52.2],
        }
    )
    elapsed = _elapsed_since_last_gps_fix_s(df)
    assert elapsed.tolist() == pytest.approx([0.0, 1.0, 2.0, 0.0, 1.0], abs=TOL)
