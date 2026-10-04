"""
Reads the raw fall recordings and writes one compact file of trials at 25 Hz, in g.

    python -m ml.fall.prepare

Needs (see ml/README.md for where each comes from):
    ml/data/raw/weda/dataset/50Hz/...        WEDA-FALL, wrist, 50 Hz, m/s^2
    ml/data/raw/weda/dataset/fall_timestamps.csv
    ml/data/raw/umafall.zip                  UMAFall, five body positions, g
"""

import os
import pickle
import re
import zipfile

import numpy as np
import pandas as pd

from ml.common import motion

RAW = "ml/data/raw"
OUT = "ml/data/fall/trials.pkl"
G = 9.80665

UMA_POSITIONS = {"RIGHTPOCKET": "pocket", "LEFTPOCKET": "pocket", "WRIST": "wrist", "WAIST": "waist",
                 "CHEST": "chest", "ANKLE": "ankle"}


def weda():
    root = f"{RAW}/weda/dataset"
    stamps = pd.read_csv(f"{root}/fall_timestamps.csv", encoding="utf-8-sig").set_index("filename")
    trials = []
    for activity in sorted(os.listdir(f"{root}/50Hz")):
        for name in sorted(os.listdir(f"{root}/50Hz/{activity}")):
            match = re.fullmatch(r"U(\d+)_R(\d+)_accel\.csv", name)
            if not match:
                continue
            user = int(match.group(1))
            table = pd.read_csv(f"{root}/50Hz/{activity}/{name}").values
            raw = table[:, 1:4] / G
            duration = table[-1, 0] - table[0, 0]
            if len(raw) < 50 or duration <= 0:
                continue
            # The file's times are when each batch of 4 or 5 samples reached the logger, so they
            # come in clumps; the samples themselves are evenly spaced. Recordings ran at 40 to
            # 50 Hz, so each is spread evenly over its own length and then put on the 25 Hz grid.
            even = np.linspace(0.0, duration, len(raw))
            xyz = motion.clip(motion.bin_mean(even, raw))
            peak = motion.bin_peak(even, raw)
            key = f"{activity}/{name[:-len('_accel.csv')]}"
            interval = None
            if activity.startswith("F"):
                if key not in stamps.index:
                    continue
                interval = (float(stamps.loc[key, "start_time"]), float(stamps.loc[key, "end_time"]))
            trials.append(dict(
                dataset="weda", subject=f"weda:U{user:02d}", elderly=user >= 21, position="wrist",
                activity=activity, fall=activity.startswith("F"), interval=interval,
                trial=f"weda/{key}", xyz=xyz, peak=peak))
    return trials


def umafall():
    trials = []
    archive = zipfile.ZipFile(f"{RAW}/umafall.zip")
    for name in sorted(archive.namelist()):
        match = re.search(r"Subject_(\d+)_(ADL|Fall)_([A-Za-z_]+?)_(\d+)_", name)
        if not name.endswith(".csv") or not match:
            continue
        lines = archive.read(name).decode("latin-1").splitlines()
        positions = {}
        rows = []
        for line in lines:
            text = line.strip()
            if not text:
                continue
            if text.startswith("%"):
                parts = [p.strip() for p in text.lstrip("%").split(";")]
                if len(parts) >= 3 and parts[1].isdigit() and parts[2] in UMA_POSITIONS:
                    positions[int(parts[1])] = UMA_POSITIONS[parts[2]]
                continue
            parts = text.split(";")
            if len(parts) >= 7:
                rows.append(parts[:7])
        if not rows:
            continue
        data = np.array(rows, dtype=np.float64)
        accel = data[data[:, 5] == 0]
        for sensor, position in positions.items():
            stream = accel[accel[:, 6] == sensor]
            if len(stream) < 40:
                continue
            order = np.argsort(stream[:, 0], kind="stable")
            t = stream[order, 0] / 1000.0
            xyz = stream[order, 2:5]
            duration = t[-1] - t[0]
            if duration < 4:
                continue
            rate = len(stream) / duration
            # The phone logs at about 200 Hz, the SensorTags at about 20 Hz.
            if rate > 40:
                resampled = motion.bin_mean(t, xyz)
                peak = motion.bin_peak(t, xyz)
            else:
                resampled = interpolate_unique(t, xyz)
                peak = motion.magnitude(resampled)      # 20 Hz: nothing finer to take a peak from
            trials.append(dict(
                dataset="umafall", subject=f"uma:S{int(match.group(1)):02d}", elderly=False, position=position,
                activity=match.group(3), fall=match.group(2) == "Fall", interval=None,
                trial=f"uma/{os.path.basename(name)[:-4]}/{position}", xyz=motion.clip(resampled), peak=peak))
    return trials


def interpolate_unique(t, xyz):
    """Several samples can share a millisecond stamp; keep the first of each before interpolating."""
    keep = np.concatenate([[True], np.diff(t) > 0])
    return motion.interpolate(t[keep], xyz[keep])


def main():
    trials = weda() + umafall()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as f:
        pickle.dump(trials, f)
    table = pd.DataFrame([{k: v for k, v in t.items() if k not in ("xyz", "peak")} | {"seconds": len(t["xyz"]) / motion.RATE_HZ}
                          for t in trials])
    print(table.groupby(["dataset", "position", "fall"]).agg(trials=("trial", "count"), subjects=("subject", "nunique"),
                                                             hours=("seconds", lambda s: s.sum() / 3600)).round(2))
    print(f"wrote {len(trials)} trials to {OUT}")


if __name__ == "__main__":
    main()
