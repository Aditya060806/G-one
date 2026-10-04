"""
Downloads the PhysioNet wrist sleep dataset night by night and turns each night into what the
app would have stored for it.

    python -m ml.sleep.prepare

Source: "Motion and heart rate from a wrist-worn wearable and labeled sleep from polysomnography"
(Walch et al., 2019), PhysioNet, Open Data Commons Attribution License v1.0. Files are read from
PhysioNet's open-data mirror (physionet-open on AWS), one night at a time; the raw movement file
(about 60 MB) is reduced at once and deleted, so the whole set never sits on disk.

For every night this keeps, every 5 seconds, what the phone stores from the strap: the largest
movement magnitude in g (the strap's MOT, as SampleAggregator keeps it) and the median heart rate.
And every 30 seconds, the sleep stage scored from polysomnography.

Writes ml/data/sleep/nights.pkl.
"""

import io
import os
import pickle
import re
import urllib.request

import numpy as np

MIRROR = "https://physionet-open.s3.amazonaws.com/sleep-accel/1.0.0/"
OUT = "ml/data/sleep/nights.pkl"
BUCKET_S = 5


def fetch(path):
    with urllib.request.urlopen(MIRROR + path, timeout=120) as response:
        return response.read()


def subjects():
    xml =urllib.request.urlopen(
        "https://physionet-open.s3.amazonaws.com/?prefix=sleep-accel/1.0.0/labels/", timeout=60).read().decode()
    return sorted(set(re.findall(r"labels/(\d+)_labeled_sleep\.txt", xml)))


def night(subject):
    labels = np.loadtxt(io.BytesIO(fetch(f"labels/{subject}_labeled_sleep.txt")))
    heart = np.loadtxt(io.BytesIO(fetch(f"heart_rate/{subject}_heartrate.txt")), delimiter=",")
    raw = fetch(f"motion/{subject}_acceleration.txt")
    motion = np.loadtxt(io.BytesIO(raw))
    del raw

    start, end = labels[0, 0], labels[-1, 0] + 30
    t = motion[:, 0]
    keep = (t >= start) & (t < end)
    t, xyz = t[keep], motion[keep, 1:4]
    mag = np.sqrt((xyz ** 2).sum(axis=1))
    buckets = int((end - start) // BUCKET_S)
    index = ((t - start) // BUCKET_S).astype(int)
    peak = np.full(buckets, np.nan)
    counts = np.bincount(index, minlength=buckets)[:buckets]
    np.fmax.at(peak, index, mag)
    peak[counts < 50] = np.nan               # fewer than ~10 Hz worth of samples: treat as missing

    hr_t, hr_v = heart[:, 0], heart[:, 1]
    rate = np.full(buckets, np.nan)
    chosen = (hr_t >= start) & (hr_t < end)
    hb = ((hr_t[chosen] - start) // BUCKET_S).astype(int)
    for b in np.unique(hb):
        rate[b] = np.rint(np.median(hr_v[chosen][hb == b]))
    return dict(subject=subject, start=float(start), motion=peak.astype(np.float32), heart=rate.astype(np.float32),
                stage=labels[:, 1].astype(int), stage_t=(labels[:, 0] - start).astype(np.float32))


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    nights = []
    if os.path.exists(OUT):
        with open(OUT, "rb") as f:
            nights = pickle.load(f)
    done = {n["subject"] for n in nights}
    ids = subjects()
    print(f"{len(ids)} nights listed, {len(done)} already prepared")
    for subject in ids:
        if subject in done:
            continue
        try:
            n = night(subject)
        except Exception as e:  # one bad night must not lose the others
            print(f"{subject}: skipped ({e})")
            continue
        nights.append(n)
        with open(OUT, "wb") as f:
            pickle.dump(nights, f)
        present = np.isfinite(n["motion"]).mean()
        print(f"{subject}: {len(n['stage']) / 120:.1f} h scored, movement present {present * 100:.0f}%, "
              f"wake epochs {np.mean(n['stage'] == 0) * 100:.0f}%")
    print("wrote", OUT, len(nights), "nights")


if __name__ == "__main__":
    main()
