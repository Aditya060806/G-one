"""
What the sleep model reads, built from the app's stored 5-second movement readings.

This file is the specification; `app/src/main/java/com/gone/ai/ml/Sleep.kt` repeats it and
`sleep_golden.txt` (written by `ml.sleep.train`) proves the two agree.

The night is cut into 30-second epochs, six readings each, counted from the first reading. Each
epoch gets three numbers: its mean and its largest movement above gravity (log-scaled), and
whether it has enough readings to count. The model sees the epoch and the ten on each side.
"""

import numpy as np

EPOCH_READINGS = 6          # 30 s of 5-second readings
MIN_READINGS = 4            # an epoch with fewer is "missing"
CONTEXT = 10                # epochs either side
CHANNELS = 3
FEATURES = (2 * CONTEXT + 1) * CHANNELS


def scaled(activity):
    """Movement above gravity, compressed: still nights differ by hundredths of a g."""
    return np.log1p(20.0 * activity)


def epochs(motion):
    """(n epochs, 3): mean, largest (both scaled), present. `motion` is 5-second readings in g, nan if missing."""
    motion = np.asarray(motion, dtype=np.float64)
    n = len(motion) // EPOCH_READINGS
    out = np.zeros((n, CHANNELS), dtype=np.float32)
    for e in range(n):
        m = motion[e * EPOCH_READINGS:(e + 1) * EPOCH_READINGS]
        m = m[np.isfinite(m)]
        if m.size < MIN_READINGS:
            continue
        a = np.maximum(0.0, m - 1.0)
        out[e] = (scaled(a.mean()), scaled(a.max()), 1.0)
    return out


def features(per_epoch, e):
    """The model's input for epoch e: epochs e-10 .. e+10, zeros past either end of the night."""
    n = len(per_epoch)
    out = np.zeros((2 * CONTEXT + 1, CHANNELS), dtype=np.float32)
    for k, j in enumerate(range(e - CONTEXT, e + CONTEXT + 1)):
        if 0 <= j < n:
            out[k] = per_epoch[j]
    return out.reshape(-1)
