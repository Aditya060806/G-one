"""
Turns PAMAP2 into the readings the app would have stored for the same people.

    python -m ml.hr_context.prepare

Needs ml/data/raw/pamap2.zip (see ml/README.md). Writes ml/data/hr_context/readings.pkl.

PAMAP2 has a wrist ("hand") accelerometer at 100 Hz and a heart-rate monitor at about 9 Hz. The
app never sees data like that. It sees what the strap sends and the phone stores:

    strap, twice a second     MOT = the hardest movement since the last line, in g
    phone, every 5 seconds    the largest MOT and the median heart rate of that 5 seconds

So that is what is built here, one row per 5 seconds, and the model is trained on rows the app
can reproduce exactly.
"""

import io
import os
import pickle
import re
import zipfile

import numpy as np
import pandas as pd

RAW = "ml/data/raw/pamap2.zip"
OUT = "ml/data/hr_context/readings.pkl"
G = 9.80665
RATE_HZ = 100
BUCKET_SECONDS = 5
REST_MOTION_G = 1.15        # VitalsSample.REST_MOTION_G

ACTIVITIES = {
    0: "transition", 1: "lying", 2: "sitting", 3: "standing", 4: "walking", 5: "running", 6: "cycling",
    7: "nordic walking", 9: "watching TV", 10: "computer work", 11: "car driving", 12: "ascending stairs",
    13: "descending stairs", 16: "vacuum cleaning", 17: "ironing", 18: "folding laundry", 19: "house cleaning",
    20: "playing soccer", 24: "rope jumping",
}


def subject_readings(table):
    """One row per 5 s: seconds from the start, largest movement in g, median heart rate, activity."""
    t = table[:, 0]
    activity = table[:, 1].astype(int)
    heart = table[:, 2]
    acc = table[:, 4:7] / G                       # hand IMU, +/-16 g accelerometer
    mag = np.sqrt((acc ** 2).sum(axis=1))
    bucket = np.floor((t - t[0]) / BUCKET_SECONDS).astype(int)
    rows = []
    for b in range(bucket[-1] + 1):
        chosen = bucket == b
        if chosen.sum() < RATE_HZ * BUCKET_SECONDS * 0.8:
            rows.append((b * BUCKET_SECONDS, np.nan, np.nan, -1))       # a gap in the recording
            continue
        movement = mag[chosen]
        movement = movement[np.isfinite(movement)]
        beats = heart[chosen]
        beats = beats[np.isfinite(beats)]
        ids, counts = np.unique(activity[chosen], return_counts=True)
        rows.append((
            b * BUCKET_SECONDS,
            float(movement.max()) if movement.size else np.nan,
            float(np.rint(np.median(beats))) if beats.size else np.nan,   # the strap sends whole beats
            int(ids[np.argmax(counts)]),
        ))
    return np.array(rows, dtype=np.float64)


def main():
    outer = zipfile.ZipFile(RAW)
    inner = zipfile.ZipFile(io.BytesIO(outer.read("PAMAP2_Dataset.zip")))
    people = {}
    for name in sorted(inner.namelist()):
        match = re.search(r"(Protocol|Optional)/subject(\d+)\.dat$", name)
        if not match:
            continue
        with inner.open(name) as f:
            table = pd.read_csv(f, sep=" ", header=None, usecols=range(7), dtype=np.float64).values
        readings = subject_readings(table)
        key = f"pamap2:S{int(match.group(2))}"
        people.setdefault(key, []).append(dict(session=match.group(1), readings=readings))
        valid = np.isfinite(readings[:, 1]) & np.isfinite(readings[:, 2])
        print(f"{key} {match.group(1):8s} {len(readings) * BUCKET_SECONDS / 60:6.1f} min, "
              f"{valid.mean() * 100:5.1f}% of readings complete, heart rate "
              f"{np.nanmin(readings[:, 2]):.0f} to {np.nanmax(readings[:, 2]):.0f}")
    for key, sessions in people.items():
        at_rest = np.concatenate([s["readings"][(s["readings"][:, 1] <= REST_MOTION_G) & np.isfinite(s["readings"][:, 2]), 2]
                                  for s in sessions])
        # PatientBaseline.fromSamples: the mean heart rate of the readings taken at rest.
        resting = float(at_rest.mean()) if at_rest.size else float("nan")
        for s in sessions:
            s["resting"] = resting
        print(f"{key}: resting heart rate {resting:.1f} from {at_rest.size} readings at rest")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as f:
        pickle.dump(dict(people=people, activities=ACTIVITIES, bucket_seconds=BUCKET_SECONDS), f)
    print("wrote", OUT)


if __name__ == "__main__":
    main()
