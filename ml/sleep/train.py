"""
Trains and tests the sleep-or-awake model.

    python -m ml.sleep.train

Reads ml/data/sleep/nights.pkl (from ml.sleep.prepare). Writes:
    ml/reports/sleep/metrics.json, night.png
    app/src/main/assets/ml/sleep_v1.tflite
    app/src/test/resources/ml/sleep_golden.txt
    ml/data/sleep/sample_night.txt                  (bundled into the app by ml.export_samples)
    app/src/main/assets/ml/manifest.json            (the "sleep" entry)

How it is tested
    Five folds split by person: nobody's night is in both training and testing.
    Every 30-second epoch the lab scored (polysomnography) is compared: asleep (any stage) or awake.
    Reported: share of epochs right, how many asleep and awake epochs were recognised, Cohen's
    kappa, and the error in total sleep time per night, beside a plain movement threshold (the
    idea behind classic actigraphy). Both cut-offs are chosen for the best kappa on the training
    folds only.
"""

import json
import os
import pickle

import numpy as np

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
import keras  # noqa: E402
import tensorflow as tf  # noqa: E402
from sklearn.metrics import cohen_kappa_score  # noqa: E402
from sklearn.model_selection import GroupKFold  # noqa: E402

from ml.common import manifest, sleep  # noqa: E402

SEED = 5
EPOCHS = 60
REPORT = "ml/reports/sleep"
MODEL_OUT = "app/src/main/assets/ml/sleep_v1.tflite"
GOLDEN_OUT = "app/src/test/resources/ml/sleep_golden.txt"
SAMPLE_OUT = "ml/data/sleep/sample_night.txt"


def load():
    with open("ml/data/sleep/nights.pkl", "rb") as f:
        nights = pickle.load(f)
    rows = []
    for n in nights:
        per_epoch = sleep.epochs(n["motion"])
        stage_epoch = (n["stage_t"] // 30).astype(int)
        for e, stage in zip(stage_epoch, n["stage"]):
            if stage < 0 or e >= len(per_epoch) or per_epoch[e, 2] == 0:
                continue
            rows.append((n["subject"], e, int(stage != 0), sleep.features(per_epoch, e), per_epoch[e, 0]))
        n["per_epoch"] = per_epoch
    subjects = np.array([r[0] for r in rows])
    x = np.stack([r[3] for r in rows]).astype(np.float32)
    y = np.array([r[2] for r in rows], dtype=np.float32)
    epoch = np.array([r[1] for r in rows])
    movement = np.array([r[4] for r in rows], dtype=np.float32)
    return nights, subjects, epoch, x, y, movement


def build():
    layers = keras.layers
    inputs = keras.Input((sleep.FEATURES,), name="epochs")
    x = layers.Dense(32, activation="relu")(inputs)
    x = layers.Dropout(0.2)(x)
    x = layers.Dense(16, activation="relu")(x)
    outputs = layers.Dense(1, activation="sigmoid", name="asleep")(x)
    return keras.Model(inputs, outputs, name="sleep_v1")


def fit(x, y, seed):
    keras.utils.set_random_seed(seed)
    model = build()
    model.compile(keras.optimizers.Adam(1e-3), "binary_crossentropy")
    weights = np.where(y == 1, 0.5 / y.mean(), 0.5 / (1 - y.mean())).astype(np.float32)
    model.fit(x, y, sample_weight=weights, batch_size=256, epochs=EPOCHS, verbose=0)
    return model


def best_cut(truth, probability):
    """The probability cut-off with the best Cohen's kappa."""
    candidates = np.quantile(probability, np.linspace(0.02, 0.6, 59))
    return float(max(candidates, key=lambda c: cohen_kappa_score(truth, (probability >= c).astype(int))))


def scores(truth, asleep):
    truth = np.asarray(truth).astype(int)
    asleep = np.asarray(asleep).astype(int)
    return dict(
        epochs=int(len(truth)),
        accuracy=round(float((truth == asleep).mean()), 4),
        sleep_sensitivity=round(float(asleep[truth == 1].mean()), 4),
        wake_specificity=round(float(1 - asleep[truth == 0].mean()), 4),
        kappa=round(float(cohen_kappa_score(truth, asleep)), 4),
    )


def sleep_time_error(subjects, truth, asleep):
    """Mean absolute error of total sleep time a night, minutes."""
    errors = []
    for s in np.unique(subjects):
        chosen = subjects == s
        errors.append(abs(asleep[chosen].sum() - truth[chosen].sum()) * 0.5)
    return round(float(np.mean(errors)), 1)


def run_tflite(data, x):
    interpreter = tf.lite.Interpreter(model_content=data)
    interpreter.allocate_tensors()
    i = interpreter.get_input_details()[0]["index"]
    o = interpreter.get_output_details()[0]["index"]
    out = np.zeros(len(x), dtype=np.float32)
    for k, row in enumerate(x):
        interpreter.set_tensor(i, row[None])
        interpreter.invoke()
        out[k] = interpreter.get_tensor(o)[0, 0]
    return out


def main():
    os.makedirs(REPORT, exist_ok=True)
    nights, subjects, epoch, x, y, movement = load()
    people = np.unique(subjects)
    print(f"{len(people)} nights, {len(y)} scored epochs, {100 * (1 - y.mean()):.1f}% awake")

    oof = np.zeros(len(y), dtype=np.float32)
    model_pred = np.zeros(len(y), dtype=int)
    rule_pred = np.zeros(len(y), dtype=int)
    fold_thresholds = []
    for fold, (train, test) in enumerate(GroupKFold(n_splits=5).split(x, y, subjects)):
        model = fit(x[train], y[train], SEED + fold)
        oof[test] = model.predict(x[test], batch_size=1024, verbose=0)[:, 0]
        # Both the model and the movement threshold get their cut-off the same way: the one with
        # the best kappa on the training folds, then applied unchanged to the held-out nights.
        cut = best_cut(y[train], model.predict(x[train], batch_size=1024, verbose=0)[:, 0])
        fold_thresholds.append(cut)
        model_pred[test] = (oof[test] >= cut).astype(int)
        # Movement threshold: the epoch and its neighbours' mean movement, below which it is "asleep".
        smoothed = x[:, [3 * k for k in range(sleep.CONTEXT - 2, sleep.CONTEXT + 3)]].mean(axis=1)
        candidates = np.quantile(smoothed[train], np.linspace(0.05, 0.95, 37))
        best = max(candidates, key=lambda c: cohen_kappa_score(y[train], (smoothed[train] <= c).astype(int)))
        rule_pred[test] = (smoothed[test] <= best).astype(int)
        print(f"fold {fold}: {len(np.unique(subjects[test]))} nights held out")

    metrics = dict(
        model="sleep_v1",
        data=dict(nights=int(len(people)), scored_epochs=int(len(y)), awake_share=round(float(1 - y.mean()), 4),
                  dataset="Walch et al. 2019, PhysioNet (Apple Watch movement, lab sleep scoring)"),
        test="5-fold, split by person",
        fold_thresholds=[round(t, 4) for t in fold_thresholds],
        model_scores=scores(y, model_pred) | dict(total_sleep_error_min=sleep_time_error(subjects, y, model_pred)),
        movement_threshold_scores=scores(y, rule_pred) | dict(total_sleep_error_min=sleep_time_error(subjects, y, rule_pred)),
        always_asleep_accuracy=round(float(y.mean()), 4),
    )

    final = fit(x, y, SEED + 100)
    threshold = best_cut(y, final.predict(x, batch_size=1024, verbose=0)[:, 0])
    metrics["threshold"] = round(threshold, 4)
    converter = tf.lite.TFLiteConverter.from_keras_model(final)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    rng = np.random.default_rng(SEED)
    sample = x[rng.permutation(len(x))[:2000]]
    converter.representative_dataset = lambda: ([row[None]] for row in sample)
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    data = converter.convert()
    with open(MODEL_OUT, "wb") as f:
        f.write(data)
    float_out = final.predict(x, batch_size=1024, verbose=0)[:, 0]
    int8_out = run_tflite(data, x[:5000])
    metrics["export"] = dict(file=MODEL_OUT, bytes=len(data), parameters=int(final.count_params()),
                             largest_difference_from_float=round(float(np.abs(float_out[:5000] - int8_out).max()), 4),
                             decisions_changed_by_8bit=int(((float_out[:5000] >= threshold) != (int8_out >= threshold)).sum()),
                             of_epochs=5000)
    with open(f"{REPORT}/metrics.json", "w") as f:
        json.dump(metrics, f, indent=2)

    write_golden(nights, data)
    write_sample(nights, data, threshold)
    m = metrics["model_scores"]
    manifest.update("sleep", dict(
        version=1, file="sleep_v1.tflite", sha256=manifest.sha256(MODEL_OUT), bytes=len(data),
        parameters=int(final.count_params()), quantisation="INT8 weights and activations, float input and output",
        input=dict(epoch_readings=sleep.EPOCH_READINGS, min_readings=sleep.MIN_READINGS, context=sleep.CONTEXT,
                   channels=sleep.CHANNELS),
        threshold=round(threshold, 4),
        trained_on="Walch et al. 2019, PhysioNet (31 nights, wrist movement, lab sleep scoring)",
        tested="5-fold, split by person",
        metrics=dict(accuracy=m["accuracy"], sleep_sensitivity=m["sleep_sensitivity"],
                     wake_specificity=m["wake_specificity"], kappa=m["kappa"],
                     total_sleep_error_min=m["total_sleep_error_min"]),
    ))
    print(json.dumps(metrics, indent=2))


def write_golden(nights, data):
    """Plain text: `night <id> readings <n>`, n movement lines ("nan" when missing), then per tested epoch `epoch <e> score <s>` and its features."""
    n = nights[0]
    # Rounded first, so the app starts from exactly the numbers written here.
    motion = np.round(n["motion"][: 6 * 120].astype(np.float64), 5).astype(np.float32)
    per_epoch = sleep.epochs(motion)
    lines = ["# Walch et al. 2019 (PhysioNet, ODC-By 1.0). Written by ml.sleep.train; do not edit.",
             f"night {n['subject']} readings {len(motion)}"]
    lines += ["nan" if not np.isfinite(v) else f"{v:.5f}" for v in motion]
    for e in (0, 5, 37, 60, len(per_epoch) - 1):
        f = sleep.features(per_epoch, e)
        lines.append(f"epoch {e} score {run_tflite(data, f[None])[0]:.5f}")
        lines.append("features " + " ".join(f"{v:.6f}" for v in f))
    lines.append("end")
    with open(GOLDEN_OUT, "w", newline=chr(10)) as f:
        f.write(chr(10).join(lines) + chr(10))


def write_sample(nights, data, threshold):
    """One night for the Model Lab: per epoch, the lab's stage and the movement readings."""
    n = max(nights, key=lambda n: np.isfinite(n["motion"]).mean())
    lines = ["# Walch et al. 2019 (PhysioNet, ODC-By 1.0): one night. stage per 30 s (-1 unscored, 0 awake, 1-3 NREM, 5 REM), then 5-s movement.",
             f"threshold {threshold:.4f}",
             "stages " + " ".join(str(int(s)) for s in n["stage"]),
             "motion " + " ".join("nan" if not np.isfinite(v) else f"{v:.4f}" for v in n["motion"])]
    with open(SAMPLE_OUT, "w", newline=chr(10)) as f:
        f.write(chr(10).join(lines) + chr(10))


def golden_only():
    """Rewrites the expected-output file from the model already exported, without training."""
    with open("ml/data/sleep/nights.pkl", "rb") as f:
        nights = pickle.load(f)
    with open(MODEL_OUT, "rb") as f:
        write_golden(nights, f.read())
    print("wrote", GOLDEN_OUT)


if __name__ == "__main__":
    import sys
    golden_only() if "--golden" in sys.argv else main()
