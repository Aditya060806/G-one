"""
Rebuilds every trained model from the public datasets, in order.

    python -m ml.run_all              # prepare (downloads where the script can), train, test, export
    python -m ml.run_all --skip-prepare

See ml/README.md for the datasets, their licences, and the one manual step (WEDA-FALL and UMAFall
are fetched by the fall preparation instructions in that file).
"""

import subprocess
import sys

STEPS = [
    ("ml.fall.prepare", True),
    ("ml.fall.train", False),
    ("ml.hr_context.prepare", True),
    ("ml.hr_context.train", False),
    ("ml.sleep.prepare", True),
    ("ml.sleep.train", False),
    ("ml.export_samples", False),
]


def main():
    skip_prepare = "--skip-prepare" in sys.argv
    for module, is_prepare in STEPS:
        if is_prepare and skip_prepare:
            continue
        print(f"=== {module}", flush=True)
        subprocess.run([sys.executable, "-m", module], check=True)
    subprocess.run([sys.executable, "-m", "pytest", "-q", "ml/tests"], check=True)


if __name__ == "__main__":
    main()
