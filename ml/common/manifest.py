"""The list of models the app ships, at app/src/main/assets/ml/manifest.json. One entry per model."""

import hashlib
import json
import os

PATH = "app/src/main/assets/ml/manifest.json"


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def load():
    if not os.path.exists(PATH):
        return {"models": {}}
    with open(PATH, encoding="utf-8") as f:
        return json.load(f)


def update(model_id, entry):
    """Replaces one model's entry, keeping anything other scripts added to it (the Snapdragon figures)."""
    document = load()
    kept = {k: v for k, v in document["models"].get(model_id, {}).items() if k == "snapdragon"}
    document["models"][model_id] = {**entry, **kept}
    os.makedirs(os.path.dirname(PATH), exist_ok=True)
    with open(PATH, "w", encoding="utf-8") as f:
        json.dump(document, f, indent=2, sort_keys=True)
        f.write("\n")
