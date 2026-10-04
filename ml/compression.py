"""
Measures what 8-bit quantisation saves: each model's architecture exported as float32 and as full
INT8, and both files' sizes recorded.

    python -m ml.compression

Writes ml/reports/compression.json. The weights here are freshly initialised, which does not
change how many there are. The shipped INT8 files (sizes in the manifest) are 1–1.3 KB larger than
the INT8 exports here because they carry the calibration from real data; compare float32 with the
manifest's size for what the app actually saves.

For models this small, the file is mostly graph structure, not weights, so 8-bit saves little
space. The reason to quantise is that integer models run on NPUs such as Qualcomm's Hexagon.
"""

import json
import os
import tempfile

import numpy as np

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
import tensorflow as tf  # noqa: E402

from ml.fall import train as fall  # noqa: E402
from ml.hr_context import train as hr  # noqa: E402
from ml.sleep import train as sleep  # noqa: E402


def sizes(model, shape):
    rng = np.random.default_rng(0)
    plain = tf.lite.TFLiteConverter.from_keras_model(model).convert()
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = lambda: ([rng.normal(size=(1,) + shape).astype(np.float32)] for _ in range(50))
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    int8 = converter.convert()
    return dict(parameters=int(model.count_params()), float32_bytes=len(plain), int8_bytes=len(int8),
                ratio=round(len(plain) / len(int8), 2))


def main():
    out = {
        "fall": sizes(fall.build(), (100, 1, 4)),
        "hr_context": sizes(hr.build(), (7,)),
        "sleep": sizes(sleep.build(), (63,)),
    }
    os.makedirs("ml/reports", exist_ok=True)
    with open("ml/reports/compression.json", "w") as f:
        json.dump(out, f, indent=2)
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    main()
