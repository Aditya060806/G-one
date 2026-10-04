"""
Checks on the feature specifications the app copies, and on the shipped model files.

    python -m pytest ml/tests
"""

import json
import os

import numpy as np

from ml.common import hr, manifest, motion, sleep


# ── Fall windows ────────────────────────────────────────────────────────────

def test_a_flat_top_counts_once_at_its_first_sample():
    mag = np.ones(40, dtype=np.float32)
    mag[20:23] = 4.0
    assert motion.candidates(mag) == [20]


def test_nothing_below_two_g_is_an_impact():
    assert motion.candidates(np.full(50, 1.9, dtype=np.float32)) == []


def test_windows_repeat_the_edge_and_clip_at_four_g():
    xyz = np.array([[0.1, 0, 1], [0.2, 0, 1], [7.5, -9, 0]], dtype=np.float32)
    f = motion.features(motion.window(xyz, 2)).reshape(motion.WINDOW, 4)
    assert f.shape == (100, 4)
    assert np.isclose(f[0, 0], 0.1 / 4)                       # before the start: the first sample
    assert np.isclose(f[-1, 0], 1.0) and np.isclose(f[-1, 1], -1.0)   # clipped to +/-4 g
    assert np.isclose(f[-1, 3], np.sqrt(32) / 7)


def test_bin_mean_averages_fast_samples_onto_25_hz():
    t = np.arange(0, 1, 0.005)                                  # 200 Hz for one second
    xyz = np.stack([np.ones_like(t), np.zeros_like(t), np.zeros_like(t)], axis=1)
    out = motion.bin_mean(t, xyz)
    assert out.shape == (25, 3) and np.allclose(out[:, 0], 1.0)


# ── Heart rate in context ───────────────────────────────────────────────────

def test_too_little_movement_data_gives_no_features():
    t = np.arange(0, 200, 5.0)                                  # 40 readings of a possible 60
    assert hr.features(t, np.ones_like(t), now=t[-1]) is None


def test_still_and_moving_features():
    t = np.arange(0, 300, 5.0)
    still = hr.features(t, np.ones_like(t), now=t[-1])
    moving = hr.features(t, np.full_like(t, 1.8), now=t[-1])
    assert still[0] == 0 and np.isclose(moving[0], 0.8) and moving[6] == 1.0


def test_band_is_sorted_around_resting():
    low, mid, high = hr.band([0.4, -0.1, 0.1], resting=70)
    assert (low, mid, high) == (65, 75, 90)


# ── Sleep ───────────────────────────────────────────────────────────────────

def test_epochs_need_four_readings_and_context_pads_with_zeros():
    m = np.array([1.0, 1.0, 1.0, 1.0, np.nan, np.nan] + [1.0, np.nan, np.nan, np.nan, np.nan, 1.0])
    per = sleep.epochs(m)
    assert per.shape == (2, 3)
    assert per[0, 2] == 1 and per[1, 2] == 0
    f = sleep.features(per, 0)
    assert f.shape == (sleep.FEATURES,)
    assert np.all(f[: sleep.CONTEXT * 3] == 0)


# ── Shipped files ───────────────────────────────────────────────────────────

def test_every_model_in_the_manifest_matches_its_file():
    with open(manifest.PATH, encoding="utf-8") as f:
        models = json.load(f)["models"]
    assert {"fall", "hr_context"} <= set(models)
    for model_id, entry in models.items():
        path = os.path.join(os.path.dirname(manifest.PATH), entry["file"])
        assert os.path.getsize(path) == entry["bytes"], model_id
        assert manifest.sha256(path) == entry["sha256"], model_id
