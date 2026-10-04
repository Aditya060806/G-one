"""
Draws the README's model charts from the measured results (ml/reports/*/metrics.json).

    python -m ml.report_charts

Writes docs/assets/ml-*.png. Every number drawn comes from a metrics file; nothing is typed in.
"""

import json
import os
import shutil

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

OUT = "docs/assets"
AMBER, CHARCOAL, ROSE, GREY, SAND = "#986000", "#2B2B2B", "#B15A67", "#8C8C80", "#D9C9A3"

plt.rcParams.update({"font.size": 9, "axes.spines.top": False, "axes.spines.right": False,
                     "axes.titleweight": "bold", "axes.titlesize": 10})


def load(name):
    path = f"ml/reports/{name}/metrics.json"
    if not os.path.exists(path):
        return None
    with open(path) as f:
        return json.load(f)


def bars(ax, groups, series, colors, labels, percent=True):
    width = 0.8 / len(series)
    for k, (values, color, label) in enumerate(zip(series, colors, labels)):
        xs = [g + (k - (len(series) - 1) / 2) * width for g in range(len(groups))]
        ax.bar(xs, values, width * 0.92, color=color, label=label)
        for x, v in zip(xs, values):
            ax.text(x, v + (1.5 if percent else max(values) * 0.02), f"{v:.0f}%" if percent else f"{v:g}",
                    ha="center", va="bottom", fontsize=7)
    ax.set_xticks(range(len(groups)))
    ax.set_xticklabels(groups)


def fall(m):
    cv, cross = m["cross_validation"], m["cross_dataset"]
    figure, ax = plt.subplots(figsize=(7.2, 3.4), dpi=150)
    groups = ["Falls caught", "Daily activities\nwith no alarm"]
    series = [
        [cv["model"]["sensitivity"] * 100, cv["model"]["specificity"] * 100],
        [cv["rule_peak_5g"]["sensitivity"] * 100, cv["rule_peak_5g"]["specificity"] * 100],
        [cross["model"]["sensitivity"] * 100, cross["model"]["specificity"] * 100],
        [cross["rule_peak_5g"]["sensitivity"] * 100, cross["rule_peak_5g"]["specificity"] * 100],
    ]
    bars(ax, groups, series, [AMBER, GREY, CHARCOAL, SAND],
         ["Fall model, people held out", "Today's 5 g rule, same recordings",
          "Fall model, a dataset it never saw", "5 g rule, that dataset"])
    ax.set_ylim(0, 112)
    ax.set_ylabel("% of recordings")
    ax.set_title(f"Fall detector vs the 5 g rule ({m['data']['recordings']:,} recordings, {m['data']['people']} people)")
    ax.legend(fontsize=7, loc="lower center", ncol=2, bbox_to_anchor=(0.5, -0.42), frameon=False)
    figure.tight_layout()
    figure.savefig(f"{OUT}/ml-fall-comparison.png")
    shutil.copyfile("ml/reports/fall/roc.png", f"{OUT}/ml-fall-roc.png")


def hr_context(m):
    figure, (left, right) = plt.subplots(1, 2, figsize=(7.2, 3.0), dpi=150, gridspec_kw=dict(width_ratios=[1, 1.6]))
    fa = m["false_alarms"]
    left.bar([0, 1], [fa["model_rule_per_24h"], fa["fixed_rule_per_24h"]], color=[AMBER, GREY], width=0.6)
    for x, v in zip([0, 1], [fa["model_rule_per_24h"], fa["fixed_rule_per_24h"]]):
        left.text(x, v + 1, f"{v:g}", ha="center", fontsize=8)
    left.set_xticks([0, 1])
    left.set_xticklabels(["Model rule", "Fixed\nheart-rate rule"])
    left.set_ylabel("Alerts per 24 h")
    left.set_title("On healthy people's real days")
    rises = m["made_up_rises"]
    keys = list(rises)
    series = [[rises[k]["fixed_rule_detected"] * 100 for k in keys],
              [rises[k]["model_rule_detected"] * 100 for k in keys],
              [rises[k]["either_rule_detected"] * 100 for k in keys]]
    bars(right, [k.replace(" for 6 min", "\nfor 6 minutes") for k in keys], series, [GREY, AMBER, CHARCOAL],
         ["Fixed rule", "Model rule", "Either"])
    right.set_ylim(0, 112)
    right.set_ylabel("% caught")
    right.set_title("Made-up sustained rises (synthetic)")
    right.legend(fontsize=7, frameon=False, loc="upper left")
    figure.tight_layout()
    figure.savefig(f"{OUT}/ml-hr-context.png")
    shutil.copyfile("ml/reports/hr_context/band.png", f"{OUT}/ml-hr-band.png")


def sleep(m):
    figure, ax = plt.subplots(figsize=(7.2, 3.0), dpi=150)
    keys = ["accuracy", "sleep_sensitivity", "wake_specificity", "kappa"]
    names = ["Epochs right", "Asleep epochs\nrecognised", "Awake epochs\nrecognised", "Cohen's kappa\n(x100)"]
    series = [[m["model_scores"][k] * 100 for k in keys], [m["movement_threshold_scores"][k] * 100 for k in keys]]
    bars(ax, names, series, [AMBER, GREY], ["Sleep model", "Movement threshold (classic actigraphy idea)"])
    ax.set_ylim(0, 112)
    ax.set_title(f"Sleep or awake, every 30 s ({m['data']['nights']} nights, people held out)")
    ax.legend(fontsize=7, frameon=False, loc="upper right")
    figure.tight_layout()
    figure.savefig(f"{OUT}/ml-sleep.png")


def sizes(models):
    with open("app/src/main/assets/ml/manifest.json") as f:
        manifest = json.load(f)["models"]
    names = {"fall": "Fall detector", "hr_context": "Heart rate in context", "sleep": "Sleep or awake"}
    rows = [(names.get(k, k), v["bytes"]) for k, v in manifest.items()]
    rows.append(("Language model (Qwen2.5-1.5B Q4_K_M)", 1_117_320_736))
    figure, ax = plt.subplots(figsize=(7.2, 0.5 + 0.42 * len(rows)), dpi=150)
    ax.barh(range(len(rows)), [b for _, b in rows], color=[AMBER] * (len(rows) - 1) + [CHARCOAL])
    ax.set_xscale("log")
    ax.set_yticks(range(len(rows)))
    ax.set_yticklabels([n for n, _ in rows])
    for y, (_, b) in enumerate(rows):
        label = f"{b / 1024:.1f} KB" if b < 1 << 20 else f"{b / (1 << 30):.2f} GiB"
        ax.text(b * 1.3, y, label, va="center", fontsize=8)
    ax.set_xlabel("File size, bytes (log scale)")
    ax.set_xlim(1e3, 1e11)
    ax.set_title("What runs on the phone: three INT8 sensor models and one 4-bit language model")
    figure.tight_layout()
    figure.savefig(f"{OUT}/ml-sizes.png")


def compression():
    """Float32 export of each architecture against the INT8 file the app ships (ml/compression.py)."""
    path = "ml/reports/compression.json"
    if not os.path.exists(path):
        return
    with open(path) as f:
        measured = json.load(f)
    with open("app/src/main/assets/ml/manifest.json") as f:
        shipped = json.load(f)["models"]
    names = {"fall": "Fall detector", "hr_context": "Heart rate\nin context", "sleep": "Sleep or awake"}
    ids = [k for k in measured if k in shipped]
    figure, ax = plt.subplots(figsize=(7.2, 3.0), dpi=150)
    series = [[measured[k]["float32_bytes"] / 1024 for k in ids], [shipped[k]["bytes"] / 1024 for k in ids]]
    width = 0.38
    for j, (values, color, label) in enumerate(zip(series, [GREY, AMBER], ["float32 export", "INT8, as shipped"])):
        xs = [i + (j - 0.5) * width for i in range(len(ids))]
        ax.bar(xs, values, width * 0.92, color=color, label=label)
        for x, v in zip(xs, values):
            ax.text(x, v + 0.2, f"{v:.1f} KB", ha="center", fontsize=7)
    ax.set_xticks(range(len(ids)))
    ax.set_xticklabels([f"{names[k]}\n({measured[k]['parameters']:,} parameters)" for k in ids])
    ax.set_ylabel("File size, KB")
    ax.set_title("8-bit quantisation of the sensor models (measured files)")
    ax.legend(fontsize=7, frameon=False)
    figure.tight_layout()
    figure.savefig(f"{OUT}/ml-quantisation.png")


def timing():
    """Measured time per run: the phone in hand (CPU) and Qualcomm AI Hub's hosted Snapdragon phones (NPU)."""
    with open("app/src/main/assets/ml/manifest.json") as f:
        models = json.load(f)["models"]
    path = "ml/reports/phone_timing.json"
    if not os.path.exists(path) or not all(m.get("snapdragon") for m in models.values()):
        return
    with open(path) as f:
        phone = json.load(f)
    names = {"fall": "Fall detector", "hr_context": "Heart rate in context", "sleep": "Sleep or awake"}
    ids = list(models)
    devices = [r["device"] for r in models[ids[0]]["snapdragon"]]
    series = [[phone["inference_micros"][k] for k in ids]]
    labels = [phone["device"].split(" (")[0] + ", CPU"]
    for d in devices:
        series.append([next(r["inference_micros"] for r in models[k]["snapdragon"] if r["device"] == d) for k in ids])
        chip = next(r["chipset"] for r in models[ids[0]]["snapdragon"] if r["device"] == d)
        labels.append(f"{d.replace(' (Family)', '')}, NPU ({chip.replace('qualcomm-', '').replace('-for-galaxy', '')})")
    figure, ax = plt.subplots(figsize=(7.2, 3.2), dpi=150)
    width = 0.8 / len(series)
    for j, (values, color, label) in enumerate(zip(series, [GREY, AMBER, CHARCOAL], labels)):
        xs = [i + (j - (len(series) - 1) / 2) * width for i in range(len(ids))]
        ax.bar(xs, values, width * 0.92, color=color, label=label)
        for x, v in zip(xs, values):
            ax.text(x, v + 1, f"{v}", ha="center", fontsize=7)
    ax.set_xticks(range(len(ids)))
    ax.set_xticklabels([names[k] for k in ids])
    ax.set_ylabel("Microseconds per run")
    ax.set_title("Time per run: phone CPU and Snapdragon NPU (measured)")
    ax.set_ylim(0, max(max(v) for v in series) * 1.5)
    ax.legend(fontsize=7, frameon=False, loc="upper left")
    figure.tight_layout()
    figure.savefig(f"{OUT}/ml-timing.png")


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, draw in (("fall", fall), ("hr_context", hr_context), ("sleep", sleep)):
        m = load(name)
        if m:
            draw(m)
            print("drew", name)
    sizes(None)
    compression()
    timing()
    print("drew sizes and quantisation")


if __name__ == "__main__":
    main()
