"""
Copies a few held-out recordings into the app, for the Model Lab screen to replay on the phone.

    python -m ml.export_samples

Only data whose licence allows it is bundled, with attribution in NOTICE:
    UMAFall (CC BY 4.0)        the fall model's golden recordings
    PAMAP2 (CC BY 4.0)         one person's session, movement and heart rate every 5 s
    Walch et al. (ODC-By 1.0)  one night's movement and the lab's sleep scoring, if prepared
WEDA-FALL states no licence, so none of it is bundled.
"""

import os
import pickle
import shutil

import numpy as np

ASSETS = "app/src/main/assets/ml/samples"


def fall():
    shutil.copyfile("app/src/test/resources/ml/fall_golden.txt", f"{ASSETS}/fall_samples.txt")


def hr_context():
    with open("ml/data/hr_context/readings.pkl", "rb") as f:
        data = pickle.load(f)
    session = next(s for s in data["people"]["pamap2:S102"] if s["session"] == "Protocol")
    r = session["readings"]
    lines = ["# PAMAP2 subject 102 (CC BY 4.0, Reiss & Stricker 2012): seconds, movement g, heart rate. Written by ml.export_samples.",
             f"resting {session['resting']:.2f}"]
    for t, motion, rate, _ in r:
        lines.append(f"{t:.0f} {'nan' if not np.isfinite(motion) else f'{motion:.3f}'} {'nan' if not np.isfinite(rate) else f'{rate:.0f}'}")
    with open(f"{ASSETS}/hr_context_sample.txt", "w", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def sleep():
    path = "ml/data/sleep/sample_night.txt"
    if os.path.exists(path):
        shutil.copyfile(path, f"{ASSETS}/sleep_sample.txt")


def main():
    os.makedirs(ASSETS, exist_ok=True)
    fall()
    hr_context()
    sleep()
    for name in sorted(os.listdir(ASSETS)):
        print(name, os.path.getsize(f"{ASSETS}/{name}"), "bytes")


if __name__ == "__main__":
    main()
