"""
Trains, tests and exports the fall detector.

    python -m ml.fall.train

Reads ml/data/fall/trials.pkl (from ml.fall.prepare). Writes:
    ml/reports/fall/metrics.json, roc.png          what was measured
    app/src/main/assets/ml/fall_v1.tflite          the INT8 model the app runs
    app/src/test/resources/ml/fall_golden.txt      inputs and expected outputs for the app's tests
    app/src/main/assets/ml/manifest.json           (the "fall" entry)

How it is tested
    Every split is by person: nobody is in both the training and the test part.
    An "event" is a whole recording. A fall recording counts as detected when the impact inside
    it is scored above the threshold; a daily-activity recording counts as a false alarm when any
    impact in it is.
    The same recordings are also scored by the rule G-one uses today (peak of 5 g or more), and by
    the best single threshold on the peak, so the model is compared with what it would replace.
    The rule is given the highest raw sample in each 1/25 s, as the strap keeps the peak between
    reports; the model is given the 25 Hz average, which is lower.
"""

import json
import os
import pickle

import numpy as np

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
import keras  # noqa: E402
import tensorflow as tf  # noqa: E402
from sklearn.metrics import roc_auc_score, roc_curve  # noqa: E402
from sklearn.model_selection import StratifiedGroupKFold  # noqa: E402

from ml.common import manifest, motion  # noqa: E402

SEED = 7
POSITIONS = ("wrist", "pocket", "waist")
RULE_G = 5.0
EPOCHS = 80
MARGIN = 3              # samples a training window may be shifted either way
BATCH = 64
FOLDS = 5
REPORT = "ml/reports/fall"
MODEL_OUT = "app/src/main/assets/ml/fall_v1.tflite"
GOLDEN_OUT = "app/src/test/resources/ml/fall_golden.txt"


# ─── Data ────────────────────────────────────────────────────────────────────

def load_trials():
    with open("ml/data/fall/trials.pkl", "rb") as f:
        trials = [t for t in pickle.load(f) if t["position"] in POSITIONS]
    for t in trials:
        t["mag"] = motion.magnitude(t["xyz"])
        t["cand"] = motion.candidates(t["mag"])
        t["peak_cand"] = motion.candidates(t["peak"])
    return trials


def fall_span(trial):
    """Sample range in which the fall's impact lies. UMAFall has no marks: the whole recording."""
    if trial["interval"] is None:
        return 0, len(trial["xyz"]) - 1
    return (trial["interval"][0] - 0.5) * motion.RATE_HZ, (trial["interval"][1] + 0.5) * motion.RATE_HZ


def labelled_impacts(trials):
    """(trial number, sample, label). One impact per fall: the hardest inside the fall's span."""
    rows = []
    for number, trial in enumerate(trials):
        if trial["fall"]:
            low, high = fall_span(trial)
            inside = [c for c in trial["cand"] if low <= c <= high]
            if inside:
                rows.append((number, max(inside, key=lambda c: trial["mag"][c]), 1))
        else:
            rows += [(number, c, 0) for c in trial["cand"]]
    return np.array(rows, dtype=np.int64)


def random_rotations(rng, n):
    """n rotation matrices, uniform over all orientations."""
    q, r = np.linalg.qr(rng.normal(size=(n, 3, 3)))
    q = q * np.sign(np.diagonal(r, axis1=1, axis2=2))[:, None, :]
    q[:, :, 0] *= np.linalg.det(q)[:, None]
    return q.astype(np.float32)


def wide_windows(trials, rows):
    """Each impact's window with MARGIN extra samples at both ends, so it can be shifted."""
    out = np.zeros((len(rows), motion.WINDOW + 2 * MARGIN, 3), dtype=np.float32)
    for k, (number, sample, _) in enumerate(rows):
        xyz = trials[number]["xyz"]
        index = np.clip(np.arange(sample - motion.PRE - MARGIN, sample - motion.PRE + motion.WINDOW + MARGIN),
                        0, len(xyz) - 1)
        out[k] = xyz[index]
    return out


def features(windows):
    """motion.features for a whole batch: (n, 100, 3) to (n, 100, 1, 4)."""
    windows = np.clip(windows, -motion.CLIP_G, motion.CLIP_G)
    mag = np.sqrt((windows ** 2).sum(axis=2, keepdims=True))
    return np.concatenate([windows / motion.CLIP_G, mag / motion.MAG_SCALE], axis=2)[:, :, None, :].astype(np.float32)


def tensor(trials, rows):
    """Model inputs for the given impacts, exactly as the app builds them."""
    return features(wide_windows(trials, rows)[:, MARGIN: MARGIN + motion.WINDOW])


def augmented(wide, rng):
    """The same impacts turned to a random orientation, scaled by up to 10 % and shifted a little."""
    n = len(wide)
    start = rng.integers(0, 2 * MARGIN + 1, n)
    windows = wide[np.arange(n)[:, None], start[:, None] + np.arange(motion.WINDOW)[None]]
    windows = np.einsum("nkj,nij->nki", windows, random_rotations(rng, n))
    return features(windows * rng.uniform(0.9, 1.1, (n, 1, 1)).astype(np.float32))


# ─── Model ───────────────────────────────────────────────────────────────────

def build():
    layers = keras.layers
    inputs = keras.Input((motion.WINDOW, 1, 4), name="window")
    x = layers.Conv2D(16, (5, 1), strides=(2, 1), padding="same", use_bias=False)(inputs)
    x = layers.BatchNormalization()(x)
    x = layers.ReLU(6.0)(x)
    for filters, stride in ((24, 2), (32, 2), (48, 1)):
        x = layers.DepthwiseConv2D((5, 1), strides=(stride, 1), padding="same", use_bias=False)(x)
        x = layers.BatchNormalization()(x)
        x = layers.ReLU(6.0)(x)
        x = layers.Conv2D(filters, (1, 1), use_bias=False)(x)
        x = layers.BatchNormalization()(x)
        x = layers.ReLU(6.0)(x)
    x = layers.GlobalAveragePooling2D()(x)
    x = layers.Dropout(0.2)(x)
    outputs = layers.Dense(1, activation="sigmoid", name="fall")(x)
    return keras.Model(inputs, outputs, name="fall_v1")


def fit(trials, rows, seed):
    keras.utils.set_random_seed(seed)
    rng = np.random.default_rng(seed)
    model = build()
    model.compile(keras.optimizers.Adam(2e-3), "binary_crossentropy")
    labels = rows[:, 2].astype(np.float32)
    positive = labels.mean()
    weights = np.where(labels == 1, 0.5 / positive, 0.5 / (1 - positive)).astype(np.float32)
    wide = wide_windows(trials, rows)
    for epoch in range(EPOCHS):
        if epoch == int(EPOCHS * 0.7):
            model.optimizer.learning_rate = 5e-4
        x = augmented(wide, rng)
        order = rng.permutation(len(rows))
        model.fit(x[order], labels[order], sample_weight=weights[order], batch_size=BATCH, epochs=1, verbose=0)
    return model


# ─── Scoring ─────────────────────────────────────────────────────────────────

def event_scores(trials, numbers, score_of, impacts="cand"):
    """
    Per recording: (is a fall, highest score that counts, every score that counts, hours).
    `score_of(trial number, sample)` returns the score of one impact; `impacts` names the list
    of impacts to score ("cand" from the 25 Hz average, "peak_cand" from the held peak).
    """
    events = []
    for number in numbers:
        trial = trials[number]
        if trial["fall"]:
            low, high = fall_span(trial)
            scores = [score_of(number, c) for c in trial[impacts] if low <= c <= high]
        else:
            scores = [score_of(number, c) for c in trial[impacts]]
        events.append((trial["fall"], max(scores) if scores else -1.0, scores, len(trial["xyz"]) / motion.RATE_HZ / 3600))
    return events


def summarise(events, threshold):
    falls = [e for e in events if e[0]]
    daily = [e for e in events if not e[0]]
    hit = sum(e[1] >= threshold for e in falls)
    alarms = sum(e[1] >= threshold for e in daily)
    alarm_count = sum(sum(s >= threshold for s in e[2]) for e in daily)
    hours = sum(e[3] for e in daily)
    sensitivity = hit / len(falls) if falls else None
    specificity = 1 - alarms / len(daily) if daily else None
    precision = hit / (hit + alarms) if hit + alarms else None
    f1 = 2 * precision * sensitivity / (precision + sensitivity) if precision and sensitivity else None
    return dict(falls=len(falls), daily=len(daily), sensitivity=r(sensitivity), specificity=r(specificity),
                f1=r(f1), false_alarms_per_hour=r(alarm_count / hours) if hours else None,
                daily_hours=r(hours))


def r(value):
    return None if value is None else round(float(value), 4)


def best_threshold(events):
    """The threshold with the largest sensitivity + specificity."""
    truth = np.array([e[0] for e in events])
    score = np.array([e[1] for e in events])
    fpr, tpr, thresholds = roc_curve(truth, score)
    return float(thresholds[np.argmax(tpr - fpr)])


def breakdown(trials, numbers, events, threshold):
    out = {}
    index = {number: event for number, event in zip(numbers, events)}
    keys = sorted({(trials[n]["dataset"], trials[n]["position"]) for n in numbers})
    for dataset, position in keys:
        chosen = [index[n] for n in numbers if trials[n]["dataset"] == dataset and trials[n]["position"] == position]
        out[f"{dataset}/{position}"] = summarise(chosen, threshold)
    elderly = [index[n] for n in numbers if trials[n]["elderly"]]
    if elderly:
        out["weda/wrist, people aged 77 to 95 (daily activities only)"] = summarise(elderly, threshold)
    return out


# ─── Export ──────────────────────────────────────────────────────────────────

def export_int8(model, sample_inputs, path):
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = lambda: ([x[None]] for x in sample_inputs)
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    # Float in and out: the app hands over plain numbers; everything between is 8-bit.
    data = converter.convert()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)
    return data


def run_tflite(data, inputs):
    interpreter = tf.lite.Interpreter(model_content=data)
    interpreter.allocate_tensors()
    i = interpreter.get_input_details()[0]["index"]
    o = interpreter.get_output_details()[0]["index"]
    out = np.zeros(len(inputs), dtype=np.float32)
    for k, x in enumerate(inputs):
        interpreter.set_tensor(i, x[None])
        interpreter.invoke()
        out[k] = interpreter.get_tensor(o)[0, 0]
    return out


# ─── Main ────────────────────────────────────────────────────────────────────

def main():
    os.makedirs(REPORT, exist_ok=True)
    trials = load_trials()
    rows = labelled_impacts(trials)
    subjects = np.array([trials[n]["subject"] for n in rows[:, 0]])
    all_subjects = sorted({t["subject"] for t in trials})
    print(f"{len(trials)} recordings, {len(rows)} impacts to score ({int(rows[:, 2].sum())} falls), "
          f"{len(all_subjects)} people")

    # 1. Five folds, split by person.
    scores = {}
    folds = StratifiedGroupKFold(n_splits=FOLDS, shuffle=True, random_state=SEED)
    fold_of_subject = {}
    for fold, (train, test) in enumerate(folds.split(rows, rows[:, 2], subjects)):
        model = fit(trials, rows[train], SEED + fold)
        held_out = set(subjects[test])
        for subject in held_out:
            fold_of_subject[subject] = fold
        todo = [(n, c) for n, t in enumerate(trials) if t["subject"] in held_out for c in t["cand"]]
        if todo:
            x = tensor(trials, [(n, c, 0) for n, c in todo])
            for key, value in zip(todo, model.predict(x, batch_size=256, verbose=0)[:, 0]):
                scores[key] = float(value)
        print(f"fold {fold}: trained on {len(train)}, held out {len(held_out)} people")

    scored_people = set(fold_of_subject)
    numbers = [n for n, t in enumerate(trials) if t["subject"] in scored_people]
    model_events = event_scores(trials, numbers, lambda n, c: scores[(n, c)])
    peak_events = event_scores(trials, numbers, lambda n, c: float(trials[n]["peak"][c]), "peak_cand")
    threshold = best_threshold(model_events)
    peak_threshold = best_threshold(peak_events)

    per_fold = []
    for fold in range(FOLDS):
        chosen = [e for n, e in zip(numbers, model_events) if fold_of_subject[trials[n]["subject"]] == fold]
        per_fold.append(summarise(chosen, threshold))

    truth = np.array([e[0] for e in model_events])
    metrics = dict(
        model="fall_v1",
        data=dict(recordings=len(trials), people=len(all_subjects), positions=list(POSITIONS),
                  impacts_scored=int(len(rows)), falls=int(rows[:, 2].sum()),
                  sample_rate_hz=motion.RATE_HZ, window_samples=motion.WINDOW),
        threshold=round(threshold, 4),
        cross_validation=dict(
            folds=FOLDS, split="by person",
            model=summarise(model_events, threshold),
            model_auc=r(roc_auc_score(truth, [e[1] for e in model_events])),
            rule_peak_5g=summarise(peak_events, RULE_G),
            best_peak_threshold_g=round(peak_threshold, 2),
            rule_best_peak_threshold=summarise(peak_events, peak_threshold),
            peak_auc=r(roc_auc_score(truth, [e[1] for e in peak_events])),
            per_fold=per_fold,
            sensitivity_spread=r(np.std([f["sensitivity"] for f in per_fold if f["sensitivity"] is not None])),
            specificity_spread=r(np.std([f["specificity"] for f in per_fold if f["specificity"] is not None])),
            by_dataset_and_position=breakdown(trials, numbers, model_events, threshold),
            rule_5g_by_dataset_and_position=breakdown(trials, numbers, peak_events, RULE_G),
        ),
    )

    # 2. Across datasets: learn from WEDA-FALL only, test on UMAFall, which it never saw.
    weda_rows = rows[[trials[n]["dataset"] == "weda" for n in rows[:, 0]]]
    cross_model = fit(trials, weda_rows, SEED + 100)
    uma = [n for n, t in enumerate(trials) if t["dataset"] == "umafall"]
    todo = [(n, c) for n in uma for c in trials[n]["cand"]]
    cross_scores = dict(zip(todo, cross_model.predict(tensor(trials, [(n, c, 0) for n, c in todo]), batch_size=256,
                                                      verbose=0)[:, 0].astype(float)))
    cross_events = event_scores(trials, uma, lambda n, c: cross_scores[(n, c)])
    cross_peak = event_scores(trials, uma, lambda n, c: float(trials[n]["peak"][c]), "peak_cand")
    metrics["cross_dataset"] = dict(
        trained_on="weda (wrist only)", tested_on="umafall (never seen)", threshold=round(threshold, 4),
        model=summarise(cross_events, threshold),
        model_auc=r(roc_auc_score([e[0] for e in cross_events], [e[1] for e in cross_events])),
        rule_peak_5g=summarise(cross_peak, RULE_G),
        by_position=breakdown(trials, uma, cross_events, threshold),
    )

    # 3. The model that ships: every recording, then 8-bit.
    final = fit(trials, rows, SEED + 200)
    plain = tensor(trials, rows)
    rng = np.random.default_rng(SEED)
    calibration = np.concatenate([plain, augmented(wide_windows(trials, rows), rng)])
    calibration = calibration[rng.permutation(len(calibration))[:2000]]
    data = export_int8(final, calibration, MODEL_OUT)
    float_out = final.predict(plain, batch_size=256, verbose=0)[:, 0]
    int8_out = run_tflite(data, plain)
    metrics["export"] = dict(
        file=MODEL_OUT, bytes=len(data), parameters=int(final.count_params()),
        largest_difference_from_float=r(np.abs(float_out - int8_out).max()),
        mean_difference_from_float=r(np.abs(float_out - int8_out).mean()),
        decisions_changed_by_8bit=int(((float_out >= threshold) != (int8_out >= threshold)).sum()),
        of_impacts=int(len(plain)),
    )

    with open(f"{REPORT}/metrics.json", "w") as f:
        json.dump(metrics, f, indent=2)
    plot_roc(model_events, peak_events, threshold, f"{REPORT}/roc.png")
    write_golden(trials, rows, data, threshold)
    cv = metrics["cross_validation"]
    manifest.update("fall", dict(
        version=1, file="fall_v1.tflite", sha256=manifest.sha256(MODEL_OUT), bytes=len(data),
        parameters=int(final.count_params()), quantisation="INT8 weights and activations, float input and output",
        input=dict(shape=[1, motion.WINDOW, 1, 4], rate_hz=motion.RATE_HZ, clip_g=motion.CLIP_G,
                   trigger_g=motion.TRIGGER_G, pre_samples=motion.PRE, peak_half_width=motion.PEAK_HALF_WIDTH,
                   magnitude_scale=motion.MAG_SCALE),
        threshold=round(threshold, 4),
        trained_on="WEDA-FALL (wrist) and UMAFall (wrist, pocket, waist)",
        tested="5-fold, split by person",
        metrics=dict(sensitivity=cv["model"]["sensitivity"], specificity=cv["model"]["specificity"],
                     f1=cv["model"]["f1"], false_alarms_per_hour=cv["model"]["false_alarms_per_hour"],
                     rule_5g_sensitivity=cv["rule_peak_5g"]["sensitivity"],
                     rule_5g_specificity=cv["rule_peak_5g"]["specificity"],
                     unseen_dataset_sensitivity=metrics["cross_dataset"]["model"]["sensitivity"],
                     unseen_dataset_specificity=metrics["cross_dataset"]["model"]["specificity"]),
    ))
    print(json.dumps({k: metrics[k] for k in ("threshold", "export")}, indent=2))
    print("cross-validation, model:", cv["model"])
    print("cross-validation, 5 g rule:", cv["rule_peak_5g"])
    print("cross-validation, best peak threshold", cv["best_peak_threshold_g"], cv["rule_best_peak_threshold"])
    print("unseen dataset, model:", metrics["cross_dataset"]["model"])
    print("unseen dataset, 5 g rule:", metrics["cross_dataset"]["rule_peak_5g"])


def plot_roc(model_events, peak_events, threshold, path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    truth = [e[0] for e in model_events]
    figure, axis = plt.subplots(figsize=(5, 5), dpi=140)
    for events, label in ((model_events, "fall model"), (peak_events, "peak size alone")):
        fpr, tpr, _ = roc_curve(truth, [e[1] for e in events])
        axis.plot(fpr, tpr, label=f"{label} (AUC {roc_auc_score(truth, [e[1] for e in events]):.3f})")
    rule = summarise(peak_events, RULE_G)
    axis.scatter([1 - rule["specificity"]], [rule["sensitivity"]], marker="x", color="black", zorder=3,
                 label="today's rule: peak of 5 g")
    chosen = summarise(model_events, threshold)
    axis.scatter([1 - chosen["specificity"]], [chosen["sensitivity"]], marker="o", color="tab:blue", zorder=3,
                 label="model at its threshold")
    axis.set_xlabel("Daily-activity recordings that raised an alarm")
    axis.set_ylabel("Falls detected")
    axis.set_title("Fall recordings, people held out of training")
    axis.legend(loc="lower right", fontsize=8)
    axis.grid(alpha=0.3)
    figure.tight_layout()
    figure.savefig(path)


def write_golden(trials, rows, data, threshold):
    """
    A few short real recordings with what the app must compute from them, as plain text so the
    app's tests need no JSON library:

        threshold <number>
        case <source> fall <0|1> impact <sample> score <number>
        candidates <sample> <sample> ...
        stream <n>          then n lines of "x y z" in g
        features <n>        then n lines of the four model inputs
        end
    """
    rng = np.random.default_rng(SEED)
    falls = [k for k in range(len(rows)) if rows[k, 2] == 1 and trials[rows[k, 0]]["dataset"] == "umafall"]
    daily = [k for k in range(len(rows)) if rows[k, 2] == 0 and trials[rows[k, 0]]["dataset"] == "umafall"]
    lines = ["# UMAFall recordings (CC BY 4.0, Universidad de Malaga). Written by ml.fall.train; do not edit.",
             f"threshold {threshold:.4f}"]
    for k in list(rng.choice(falls, 4, replace=False)) + list(rng.choice(daily, 4, replace=False)):
        number, sample, label = rows[k]
        trial = trials[number]
        start = max(0, sample - 60)
        # Rounded first, so the app starts from exactly the numbers written here.
        stream = np.round(trial["xyz"][start: sample + 110], 5).astype(np.float32)
        found = motion.candidates(motion.magnitude(stream))
        local = int(sample - start)
        feats = motion.features(motion.window(stream, local))
        score = float(run_tflite(data, feats[None])[0])
        lines.append(f"case {trial['trial'].replace(' ', '_')} fall {int(label)} impact {local} score {score:.5f}")
        lines.append("candidates " + " ".join(str(int(c)) for c in found))
        lines.append(f"stream {len(stream)}")
        lines += [" ".join(f"{v:.5f}" for v in row) for row in stream]
        lines.append(f"features {motion.WINDOW}")
        lines += [" ".join(f"{v:.7f}" for v in row) for row in feats.reshape(motion.WINDOW, 4)]
        lines.append("end")
    os.makedirs(os.path.dirname(GOLDEN_OUT), exist_ok=True)
    with open(GOLDEN_OUT, "w", newline=chr(10)) as f:
        f.write(chr(10).join(lines) + chr(10))


def golden_only():
    """Rewrites the expected-output file from the model already exported, without training."""
    trials = load_trials()
    with open(MODEL_OUT, "rb") as f:
        data = f.read()
    with open(f"{REPORT}/metrics.json") as f:
        threshold = json.load(f)["threshold"]
    write_golden(trials, labelled_impacts(trials), data, threshold)
    print("wrote", GOLDEN_OUT)


if __name__ == "__main__":
    import sys
    golden_only() if "--golden" in sys.argv else main()
