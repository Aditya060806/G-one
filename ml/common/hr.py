"""
What the heart-rate-in-context model reads, built from the app's stored 5-second readings.

This file is the specification. `app/src/main/java/com/gone/ai/ml/HrContext.kt` repeats it, and
`hr_context_golden.txt` (written by `ml.hr_context.train`) proves the two agree.

A reading is (seconds, movement in g or nan, heart rate or nan). Movement is the largest MOT of
the 5 seconds, so 1.0 means still and anything above is movement on top of gravity.
"""

import numpy as np

REST_MOTION_G = 1.15        # VitalsSample.REST_MOTION_G
MOTION_CAP_G = 4.0          # the strap's MOT is clipped here before use; PAMAP2's wrist sensor reads further
SPANS_S = (30, 60, 120, 300)
MIN_READINGS_5_MIN = 48     # of a possible 60: four fifths of the last five minutes must be there
OUTPUT_SCALE = 50.0         # the model's outputs are beats a minute above resting, divided by this
FEATURES = 7


def activity(motion):
    """Movement above gravity, in g, never negative, capped."""
    return np.maximum(0.0, np.minimum(motion, MOTION_CAP_G) - 1.0)


def features(times, motion, now):
    """
    The 7 model inputs at time `now` (seconds), or None when there is too little movement data.

        mean activity over the last 30 s, 60 s, 120 s, 300 s
        largest activity over the last 60 s
        spread (population standard deviation) of activity over the last 60 s
        share of the last 300 s spent moving (movement above REST_MOTION_G)

    "The last 30 s" means readings with now - 30 < time <= now.
    """
    times = np.asarray(times, dtype=np.float64)
    motion = np.asarray(motion, dtype=np.float64)
    present = np.isfinite(motion)

    def chosen(span):
        return present & (times > now - span) & (times <= now)

    last5 = chosen(300)
    if last5.sum() < MIN_READINGS_5_MIN or chosen(30).sum() == 0:
        return None
    a = activity(np.where(present, motion, 1.0))
    out = [a[chosen(span)].mean() for span in SPANS_S]
    minute = a[chosen(60)]
    out.append(minute.max())
    out.append(minute.std())
    out.append((motion[last5] > REST_MOTION_G).mean())
    return np.array(out, dtype=np.float32)


def band(outputs, resting):
    """The model's three outputs to (low, middle, high) expected heart rate, in beats a minute."""
    low, mid, high = np.sort(np.asarray(outputs, dtype=np.float64)) * OUTPUT_SCALE
    return resting + low, resting + mid, resting + high
