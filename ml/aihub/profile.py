"""
Times G-one's small models on real Snapdragon phones through Qualcomm AI Hub.

    python -m ml.aihub.profile                     # every model in the manifest, default devices
    python -m ml.aihub.profile --list-devices      # what AI Hub offers
    python -m ml.aihub.profile --device "Samsung Galaxy S24 (Family)" --model fall

Needs the `qai-hub` package and an AI Hub account. Configure it yourself, once:

    python -m pip install qai-hub
    qai-hub configure --api_token <your token from aihub.qualcomm.com>

This uploads the .tflite files (a few kilobytes each, trained on public datasets) to Qualcomm's
service, which runs them on hosted phones with the TensorFlow Lite runtime and Qualcomm's NPU
delegate, exactly as shipped in the app. Nothing about any person leaves this computer.

Writes ml/reports/aihub/<model>__<device>.json and adds a "snapdragon" list to each model's entry
in app/src/main/assets/ml/manifest.json, which the app's Model Lab screen shows.
"""

import argparse
import json
import os
import re

from ml.common import manifest

DEFAULT_DEVICES = ["Samsung Galaxy S24 (Family)", "Samsung Galaxy S25"]
REPORT = "ml/reports/aihub"


def chipset_of(device):
    for attribute in getattr(device, "attributes", []) or []:
        if str(attribute).startswith("chipset:"):
            return str(attribute).split(":", 1)[1]
    return ""


def runs_on(profile):
    """'NPU (14 of 14 layers)' and the like, from the per-layer detail."""
    layers = profile.get("execution_detail") or []
    units = [str(layer.get("compute_unit", "")).upper() for layer in layers]
    if not units:
        return "not reported"
    counts = {u: units.count(u) for u in sorted(set(units))}
    main = max(counts, key=counts.get)
    return f"{main} ({counts[main]} of {len(units)} layers)"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", action="append", help="AI Hub device name; repeatable")
    parser.add_argument("--model", action="append", help="model id from the manifest; repeatable")
    parser.add_argument("--list-devices", action="store_true")
    args = parser.parse_args()

    import qai_hub as hub  # imported here so --help works without it

    available = hub.get_devices()
    if args.list_devices:
        for d in available:
            print(f"{d.name:45s} {chipset_of(d)}")
        return

    wanted = args.device or DEFAULT_DEVICES
    # AI Hub lists a phone once per Android version; one of each name is enough.
    devices = list({d.name: d for d in available if d.name in wanted}.values())
    missing = set(wanted) - {d.name for d in devices}
    if missing:
        print("Not offered by AI Hub right now (see --list-devices):", ", ".join(sorted(missing)))
    if not devices:
        raise SystemExit("No device to profile on.")

    os.makedirs(REPORT, exist_ok=True)
    document = manifest.load()
    for model_id, entry in document["models"].items():
        if args.model and model_id not in args.model:
            continue
        path = os.path.join(os.path.dirname(manifest.PATH), entry["file"])
        runs = []
        for device in devices:
            print(f"profiling {model_id} on {device.name} ...")
            job = hub.submit_profile_job(model=path, device=device, name=f"g-one {model_id}")
            profile = job.download_profile()
            safe = re.sub(r"[^A-Za-z0-9]+", "_", device.name).strip("_")
            with open(f"{REPORT}/{model_id}__{safe}.json", "w") as f:
                json.dump(profile, f, indent=2, default=str)
            summary = profile.get("execution_summary", {})
            micros = summary.get("estimated_inference_time")
            if micros is None:
                print("  no inference time in the profile; see the saved JSON")
                continue
            runs.append(dict(device=device.name, chipset=chipset_of(device), inference_micros=int(micros),
                             peak_memory_bytes=summary.get("estimated_inference_peak_memory"),
                             runs_on=runs_on(profile), job=job.job_id))
            print(f"  {int(micros)} µs, {runs[-1]['runs_on']}")
        entry["snapdragon"] = runs
    with open(manifest.PATH, "w", encoding="utf-8") as f:
        json.dump(document, f, indent=2, sort_keys=True)
        f.write("\n")
    print("updated", manifest.PATH)


if __name__ == "__main__":
    main()
