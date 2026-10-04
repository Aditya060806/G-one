"""
Movement windows for the fall model.

This file is the specification. `app/src/main/java/com/gone/ai/ml/FallWindow.kt` repeats it line
for line, and `fall_golden.json` (written by `ml.fall.train`) holds inputs and expected outputs
so a JVM test can prove the two agree.

Everything is in g, at 25 samples a second.
"""

import numpy as np

RATE_HZ = 25
WINDOW = 100            # 4 s
PRE = 25                # 1 s before the impact, 3 s after it
CLIP_G = 4.0            # per axis: the wrist recordings were made on a +/-4 g sensor
TRIGGER_G = 2.0         # below this nothing is scored at all
PEAK_HALF_WIDTH = 12    # an impact is the largest sample within about half a second each side
MAG_SCALE = 7.0         # the largest magnitude a +/-4 g sensor can report is 4 * sqrt(3) = 6.93


def clip(xyz):
    return np.clip(np.asarray(xyz, dtype=np.float32), -CLIP_G, CLIP_G)


def magnitude(xyz):
    return np.sqrt((np.asarray(xyz, dtype=np.float32) ** 2).sum(axis=1))


def pair_mean(xyz):
    """50 Hz to 25 Hz: the mean of each pair of samples."""
    xyz = np.asarray(xyz, dtype=np.float32)
    n = len(xyz) // 2
    return xyz[: n * 2].reshape(n, 2, 3).mean(axis=1)


def bin_mean(t_s, xyz, rate=RATE_HZ):
    """
    Irregular or fast samples onto a uniform grid: the mean of the samples in each 1/rate bin.
    An empty bin is filled by a straight line between its neighbours.
    """
    t_s = np.asarray(t_s, dtype=np.float64)
    xyz = np.asarray(xyz, dtype=np.float64)
    bins = np.floor((t_s - t_s[0]) * rate + 1e-9).astype(int)
    n = bins[-1] + 1
    sums = np.zeros((n, 3))
    counts = np.zeros(n)
    np.add.at(sums, bins, xyz)
    np.add.at(counts, bins, 1)
    filled = counts > 0
    out = np.zeros((n, 3))
    out[filled] = sums[filled] / counts[filled, None]
    if not filled.all():
        index = np.arange(n)
        for axis in range(3):
            out[~filled, axis] = np.interp(index[~filled], index[filled], out[filled, axis])
    return out.astype(np.float32)


def bin_peak(t_s, xyz, rate=RATE_HZ):
    """
    The largest magnitude among the samples in each 1/rate bin: what a sensor that keeps the peak
    between reports would send. An empty bin takes the value before it.
    """
    t_s = np.asarray(t_s, dtype=np.float64)
    mag = magnitude(xyz)
    bins = np.floor((t_s - t_s[0]) * rate + 1e-9).astype(int)
    out = np.full(bins[-1] + 1, -1.0, dtype=np.float32)
    np.maximum.at(out, bins, mag)
    for i in range(1, len(out)):
        if out[i] < 0:
            out[i] = out[i - 1]
    return out


def interpolate(t_s, xyz, rate=RATE_HZ):
    """A slower sensor (20 Hz) onto the grid by linear interpolation."""
    t_s = np.asarray(t_s, dtype=np.float64)
    xyz = np.asarray(xyz, dtype=np.float64)
    grid = np.arange(t_s[0], t_s[-1], 1.0 / rate)
    return np.stack([np.interp(grid, t_s, xyz[:, axis]) for axis in range(3)], axis=1).astype(np.float32)


def candidates(mag, trigger=TRIGGER_G):
    """
    Sample numbers of impacts worth scoring: at least `trigger`, larger than every sample in the
    half second before it and no smaller than any in the half second after.
    """
    mag = np.asarray(mag, dtype=np.float32)
    found = []
    for i in range(len(mag)):
        m = mag[i]
        if m < trigger:
            continue
        before = mag[max(0, i - PEAK_HALF_WIDTH): i]
        after = mag[i + 1: i + 1 + PEAK_HALF_WIDTH]
        if (before.size == 0 or before.max() < m) and (after.size == 0 or after.max() <= m):
            found.append(i)
    return found


def window(xyz, i):
    """WINDOW samples around impact `i`; past either end the nearest sample is repeated."""
    xyz = np.asarray(xyz, dtype=np.float32)
    index = np.clip(np.arange(i - PRE, i - PRE + WINDOW), 0, len(xyz) - 1)
    return xyz[index]


def features(win):
    """What the model reads: x, y, z over CLIP_G and the magnitude over MAG_SCALE. Shape (100, 1, 4)."""
    win = clip(win)
    mag = magnitude(win)
    out = np.concatenate([win / CLIP_G, (mag / MAG_SCALE)[:, None]], axis=1)
    return out.reshape(WINDOW, 1, 4).astype(np.float32)
