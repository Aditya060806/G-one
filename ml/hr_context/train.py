"""
Trains and tests the heart-rate-in-context model, and the rule the app builds on it.

    python -m ml.hr_context.train

Reads ml/data/hr_context/readings.pkl (from ml.hr_context.prepare). Writes:
    ml/reports/hr_context/metrics.json, band.png
    app/src/main/assets/ml/hr_context_v1.tflite
    app/src/test/resources/ml/hr_context_golden.txt
    app/src/main/assets/ml/manifest.json            (the "hr_context" entry)

The idea: from the last five minutes of wrist movement, say what heart rate is to be expected
(a low, middle and high value, as beats a minute above the person's resting rate). The app's
rule `ml.hrContext` then raises a LOW finding when the measured rate stays well above the high
value for three minutes, and MODERATE when it stays 30 beats above it for five.

How it is tested
    Leave one person out: the model never saw the person it is tested on.
    1. How close the expected rate is (error of the middle value) and how often the measured rate
       falls inside the band, against two plain baselines: "resting rate" and a straight-line fit.
    2. The rule on the held-out people's real recordings, which contain no known problem: every
       finding there is a false alarm. Reported per 24 hours, beside the app's fixed heart-rate rule.
    3. The rule after a sustained rise of 25 or 40 beats a minute is added to a held-out recording
       for 6 minutes. These rises are made up, and are reported as such.
"""

import json
import os
import pickle

import numpy as np

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
import keras  # noqa: E402
import tensorflow as tf  # noqa: E402
from sklearn.linear_model import LinearRegression  # noqa: E402

from ml.common import hr, manifest  # noqa: E402

SEED = 11
QUANTILES = (0.1, 0.5, 0.9)
EPOCHS = 150
REPORT = "ml/reports/hr_context"
MODEL_OUT = "app/src/main/assets/ml/hr_context_v1.tflite"
GOLDEN_OUT = "app/src/test/resources/ml/hr_context_golden.txt"

# The app's rule (HeartRateContextRule) and the fixed rule it must not duplicate (HighHeartRateRule).
LOW_EXCESS_BPM = 10.0
LOW_SPAN_S, LOW_MIN_READINGS = 180, 30
MODERATE_EXCESS_BPM = 30.0
MODERATE_SPAN_S, MODERATE_MIN_READINGS = 300, 48
SHARE = 0.9
HR_HIGH, HR_CRITICAL = 130, 150
COOLDOWN_S = 15 * 60
INJECT_S = 6 * 60


# ─── Data ────────────────────────────────────────────────────────────────────

def load():
    with open("ml/data/hr_context/readings.pkl", "rb") as f:
        data = pickle.load(f)
    sessions = []
    for person, items in data["people"].items():
        for item in items:
            r = item["readings"]
            sessions.append(dict(person=person, name=f"{person} {item['session']}", resting=item["resting"],
                                 t=r[:, 0], motion=r[:, 1], hr=r[:, 2], activity=r[:, 3].astype(int)))
    return sessions, data["activities"]


def session_features(s):
    """Features at every reading that has them; nan rows where there is too little movement data."""
    out = np.full((len(s["t"]), hr.FEATURES), np.nan, dtype=np.float32)
    for i, now in enumerate(s["t"]):
        f = hr.features(s["t"], s["motion"], now)
        if f is not None:
            out[i] = f
    return out


def dataset(sessions, people):
    xs, ys = [], []
    for s in sessions:
        if s["person"] not in people:
            continue
        ok = np.isfinite(s["x"]).all(axis=1) & np.isfinite(s["hr"])
        xs.append(s["x"][ok])
        ys.append(((s["hr"] - s["resting"]) / hr.OUTPUT_SCALE)[ok])
    return np.concatenate(xs).astype(np.float32), np.concatenate(ys).astype(np.float32)


# ─── Model ───────────────────────────────────────────────────────────────────

def pinball(y_true, y_pred):
    q = tf.constant(QUANTILES, dtype=tf.float32)
    error = y_true - y_pred
    return tf.reduce_mean(tf.maximum(q * error, (q - 1.0) * error))


def build():
    layers = keras.layers
    inputs = keras.Input((hr.FEATURES,), name="context")
    x = layers.Dense(24, activation="relu")(inputs)
    x = layers.Dense(24, activation="relu")(x)
    outputs = layers.Dense(len(QUANTILES), name="band")(x)
    return keras.Model(inputs, outputs, name="hr_context_v1")


def fit(x, y, seed):
    keras.utils.set_random_seed(seed)
    model = build()
    model.compile(keras.optimizers.Adam(3e-3), pinball)
    model.fit(x, np.repeat(y[:, None], len(QUANTILES), axis=1), batch_size=128, epochs=EPOCHS, verbose=0)
    return model


def export_int8(model, x, path):
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = lambda: ([row[None]] for row in x)
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    data = converter.convert()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)
    return data


def run_tflite(data, x):
    interpreter = tf.lite.Interpreter(model_content=data)
    interpreter.allocate_tensors()
    i = interpreter.get_input_details()[0]["index"]
    o = interpreter.get_output_details()[0]["index"]
    out = np.zeros((len(x), len(QUANTILES)), dtype=np.float32)
    for k, row in enumerate(x):
        interpreter.set_tensor(i, row[None])
        interpreter.invoke()
        out[k] = interpreter.get_tensor(o)[0]
    return out


# ─── The app's rules, replayed ───────────────────────────────────────────────

def bands_for(s, outputs):
    """(low, middle, high) expected heart rate at each reading; nan where there is no score."""
    out = np.full((len(s["t"]), 3), np.nan)
    for i in range(len(s["t"])):
        if np.isfinite(outputs[i]).all():
            out[i] = hr.band(outputs[i], s["resting"])
    return out


def context_rule(t, rate, high, i):
    """HeartRateContextRule at reading i: None, "LOW" or "MODERATE"."""
    if not np.isfinite(rate[i]) or not np.isfinite(high[i]):
        return None

    def held(span, need, excess):
        chosen = (t > t[i] - span) & (t <= t[i]) & np.isfinite(rate) & np.isfinite(high)
        if chosen.sum() < need:
            return False
        return ((rate[chosen] - high[chosen]) >= excess).mean() >= SHARE

    if held(MODERATE_SPAN_S, MODERATE_MIN_READINGS, MODERATE_EXCESS_BPM):
        return "MODERATE"
    if held(LOW_SPAN_S, LOW_MIN_READINGS, LOW_EXCESS_BPM):
        return "LOW"
    return None


def fixed_rule(rate, motion, i):
    """HighHeartRateRule: 150 or more at any time, 130 or more at rest."""
    if not np.isfinite(rate[i]):
        return None
    if rate[i] >= HR_CRITICAL:
        return "CRITICAL"
    at_rest = not np.isfinite(motion[i]) or motion[i] <= hr.REST_MOTION_G
    return "MODERATE" if rate[i] >= HR_HIGH and at_rest else None


RANK = {None: 0, "LOW": 1, "MODERATE": 2, "CRITICAL": 3}


def alerts(t, decide):
    """Times at which an alert is raised, with the detector's 15-minute cooldown and its escalation."""
    raised = []
    last_at, last_rank = None, 0
    for i in range(len(t)):
        level = decide(i)
        if level is None:
            continue
        rank = RANK[level]
        if last_at is not None and t[i] - last_at < COOLDOWN_S and rank <= last_rank:
            continue
        raised.append(t[i])
        last_at, last_rank = t[i], rank
    return raised


def run_rules(s, rate, high):
    """Alert times from the model rule (deferring to the fixed rule, as in the app) and the fixed rule."""
    t, motion = s["t"], s["motion"]

    def model_decide(i):
        if fixed_rule(rate, motion, i) is not None:
            return None
        return context_rule(t, rate, high, i)

    return alerts(t, model_decide), alerts(t, lambda i: fixed_rule(rate, motion, i))


# ─── Main ────────────────────────────────────────────────────────────────────

def main():
    os.makedirs(REPORT, exist_ok=True)
    sessions, activities = load()
    for s in sessions:
        s["x"] = session_features(s)
    people = sorted({s["person"] for s in sessions})
    print(f"{len(people)} people, {sum(len(s['t']) for s in sessions)} readings, "
          f"{sum(np.isfinite(s['x']).all(axis=1).sum() for s in sessions)} with enough movement data")

    rng = np.random.default_rng(SEED)
    errors = {"model": [], "resting rate": [], "straight line": []}
    inside = []
    clean_hours = 0.0
    false_model, false_fixed = [], []
    injected = {25: [], 40: []}
    by_activity = {}
    for fold, person in enumerate(people):
        train = [p for p in people if p != person]
        x, y = dataset(sessions, train)
        model = fit(x, y, SEED + fold)
        line = LinearRegression().fit(x, y)
        for s in [s for s in sessions if s["person"] == person]:
            ok = np.isfinite(s["x"]).all(axis=1)
            outputs = np.full((len(s["t"]), 3), np.nan, dtype=np.float32)
            if ok.any():
                outputs[ok] = model.predict(s["x"][ok], verbose=0)
            b = bands_for(s, outputs)
            measured = np.isfinite(s["hr"]) & ok
            if measured.any():
                errors["model"] += list(np.abs(s["hr"][measured] - b[measured, 1]))
                errors["resting rate"] += list(np.abs(s["hr"][measured] - s["resting"]))
                errors["straight line"] += list(np.abs(s["hr"][measured] - (s["resting"] + line.predict(s["x"][measured]) * hr.OUTPUT_SCALE)))
                inside += list((s["hr"][measured] >= b[measured, 0]) & (s["hr"][measured] <= b[measured, 2]))
                for a in np.unique(s["activity"][measured]):
                    chosen = measured & (s["activity"] == a)
                    by_activity.setdefault(activities.get(int(a), str(a)), []).extend(np.abs(s["hr"][chosen] - b[chosen, 1]))
            hours = len(s["t"]) * 5 / 3600
            clean_hours += hours
            model_alerts, fixed_alerts = run_rules(s, s["hr"], b[:, 2])
            false_model.append(len(model_alerts))
            false_fixed.append(len(fixed_alerts))

            # Made-up sustained rises, one every 20 minutes, never overlapping.
            for start in np.arange(s["t"][0] + 300, s["t"][-1] - INJECT_S, 20 * 60):
                start = start + rng.uniform(0, 120)
                window = (s["t"] >= start) & (s["t"] < start + INJECT_S)
                if window.sum() < 60:
                    continue
                for rise in injected:
                    rate = s["hr"].copy()
                    rate[window] = rate[window] + rise
                    m_new, f_new = run_rules(s, rate, b[:, 2])
                    m_old, f_old = model_alerts, fixed_alerts
                    in_window = lambda times: [a for a in times if start <= a < start + INJECT_S]
                    model_hit = [a for a in in_window(m_new) if a not in m_old]
                    fixed_hit = [a for a in in_window(f_new) if a not in f_old]
                    injected[rise].append(dict(model=bool(model_hit), fixed=bool(fixed_hit),
                                               delay=float(model_hit[0] - start) if model_hit else None))
        print(f"held out {person}: middle-value error so far {np.mean(errors['model']):.1f} bpm")

    def per_day(counts):
        return round(float(sum(counts) / clean_hours * 24), 2)

    metrics = dict(
        model="hr_context_v1",
        data=dict(people=len(people), hours=round(clean_hours, 2), dataset="PAMAP2 (wrist movement, chest-strap heart rate)"),
        test="leave one person out",
        expected_rate=dict(
            mean_error_bpm={k: round(float(np.mean(v)), 2) for k, v in errors.items()},
            median_error_bpm={k: round(float(np.median(v)), 2) for k, v in errors.items()},
            share_inside_band=round(float(np.mean(inside)), 4), band="10th to 90th percentile",
            mean_error_by_activity_bpm={k: round(float(np.mean(v)), 1) for k, v in sorted(by_activity.items()) if len(v) >= 30},
        ),
        false_alarms=dict(
            note="Held-out recordings of healthy people doing ordinary activities; every alert here is unwanted.",
            hours=round(clean_hours, 2),
            model_rule_alerts=int(sum(false_model)), model_rule_per_24h=per_day(false_model),
            fixed_rule_alerts=int(sum(false_fixed)), fixed_rule_per_24h=per_day(false_fixed),
        ),
        made_up_rises={
            f"+{rise} bpm for 6 min": dict(
                trials=len(v),
                model_rule_detected=round(float(np.mean([e["model"] for e in v])), 4),
                fixed_rule_detected=round(float(np.mean([e["fixed"] for e in v])), 4),
                either_rule_detected=round(float(np.mean([e["model"] or e["fixed"] for e in v])), 4),
                only_model_rule_detected=round(float(np.mean([e["model"] and not e["fixed"] for e in v])), 4),
                model_median_delay_s=round(float(np.median([e["delay"] for e in v if e["delay"] is not None])), 1)
                if any(e["delay"] is not None for e in v) else None,
            ) for rise, v in injected.items()
        },
    )

    # The model that ships: everyone.
    x, y = dataset(sessions, people)
    final = fit(x, y, SEED + 100)
    data = export_int8(final, x[rng.permutation(len(x))[:2000]], MODEL_OUT)
    float_out = final.predict(x, verbose=0)
    int8_out = run_tflite(data, x)
    metrics["export"] = dict(
        file=MODEL_OUT, bytes=len(data), parameters=int(final.count_params()),
        largest_difference_bpm=round(float(np.abs(float_out - int8_out).max() * hr.OUTPUT_SCALE), 2),
        mean_difference_bpm=round(float(np.abs(float_out - int8_out).mean() * hr.OUTPUT_SCALE), 3),
    )
    with open(f"{REPORT}/metrics.json", "w") as f:
        json.dump(metrics, f, indent=2)
    plot(sessions, final, f"{REPORT}/band.png")
    write_golden(sessions, data)
    manifest.update("hr_context", dict(
        version=1, file="hr_context_v1.tflite", sha256=manifest.sha256(MODEL_OUT), bytes=len(data),
        parameters=int(final.count_params()), quantisation="INT8 weights and activations, float input and output",
        input=dict(features=hr.FEATURES, spans_s=list(hr.SPANS_S), min_readings_5_min=hr.MIN_READINGS_5_MIN,
                   motion_cap_g=hr.MOTION_CAP_G, rest_motion_g=hr.REST_MOTION_G, output_scale=hr.OUTPUT_SCALE,
                   low_excess_bpm=LOW_EXCESS_BPM, moderate_excess_bpm=MODERATE_EXCESS_BPM),
        trained_on="PAMAP2 (9 people, wrist movement and heart rate, 18 activities)",
        tested="leave one person out",
        metrics=dict(
            mean_error_bpm=metrics["expected_rate"]["mean_error_bpm"]["model"],
            resting_rate_error_bpm=metrics["expected_rate"]["mean_error_bpm"]["resting rate"],
            straight_line_error_bpm=metrics["expected_rate"]["mean_error_bpm"]["straight line"],
            share_inside_band=metrics["expected_rate"]["share_inside_band"],
            false_alarms_per_24h=metrics["false_alarms"]["model_rule_per_24h"],
            made_up_rise_25_detected=metrics["made_up_rises"]["+25 bpm for 6 min"]["model_rule_detected"],
            made_up_rise_40_detected=metrics["made_up_rises"]["+40 bpm for 6 min"]["model_rule_detected"],
        ),
    ))
    print(json.dumps(metrics, indent=2))


def plot(sessions, model, path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    s = max(sessions, key=lambda s: len(s["t"]))
    ok = np.isfinite(s["x"]).all(axis=1)
    outputs = np.full((len(s["t"]), 3), np.nan, dtype=np.float32)
    outputs[ok] = model.predict(s["x"][ok], verbose=0)
    b = bands_for(s, outputs)
    minutes = (s["t"] - s["t"][0]) / 60
    figure, (top, bottom) = plt.subplots(2, 1, figsize=(9, 5), dpi=140, sharex=True,
                                         gridspec_kw=dict(height_ratios=[3, 1]))
    top.fill_between(minutes, b[:, 0], b[:, 2], color="tab:blue", alpha=0.2, label="expected (10th to 90th percentile)")
    top.plot(minutes, b[:, 1], color="tab:blue", lw=1, label="expected (middle)")
    top.plot(minutes, s["hr"], color="black", lw=0.8, label="measured")
    top.set_ylabel("Heart rate, bpm")
    top.set_title(f"{s['name']}: expected heart rate from wrist movement alone (this person was in training)")
    top.legend(fontsize=7, loc="upper left")
    bottom.plot(minutes, s["motion"], color="tab:gray", lw=0.6)
    bottom.set_ylabel("Movement, g")
    bottom.set_xlabel("Minutes")
    figure.tight_layout()
    figure.savefig(path)


def write_golden(sessions, data):
    """
    Plain text:  threshold-free cases of
        case <name> resting <bpm> now <seconds>
        readings <n>        then n lines of "seconds movement" (movement "nan" when missing)
        features <values...>
        outputs <three values>
        end
    """
    rng = np.random.default_rng(SEED)
    lines = ["# PAMAP2 (CC BY 4.0). Written by ml.hr_context.train; do not edit."]
    s = max(sessions, key=lambda s: len(s["t"]))
    ok = np.where(np.isfinite(s["x"]).all(axis=1))[0]
    for i in sorted(rng.choice(ok, 6, replace=False)):
        now = s["t"][i]
        chosen = (s["t"] > now - 300) & (s["t"] <= now)
        t = np.round(s["t"][chosen], 3)
        m = np.round(s["motion"][chosen], 4)
        f = hr.features(t, m, now)
        out = run_tflite(data, f[None])[0]
        lines.append(f"case {s['name'].replace(' ', '_')} resting {s['resting']:.3f} now {now:.3f}")
        lines.append(f"readings {len(t)}")
        lines += [f"{a:.3f} {'nan' if not np.isfinite(b) else f'{b:.4f}'}" for a, b in zip(t, m)]
        lines.append("features " + " ".join(f"{v:.7f}" for v in f))
        lines.append("outputs " + " ".join(f"{v:.6f}" for v in out))
        lines.append("end")
    with open(GOLDEN_OUT, "w", newline=chr(10)) as f:
        f.write(chr(10).join(lines) + chr(10))


if __name__ == "__main__":
    main()
