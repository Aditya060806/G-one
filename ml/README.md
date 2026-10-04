# ml/ — the trained models G-one runs on the phone

Three small models, each a few kilobytes, each trained on a public dataset and tested on people it
never saw. They run in the app through LiteRT (TensorFlow Lite) on the CPU.

**Models are sensors. Rules decide. The language model only rewords.** A model produces a score;
a named rule with fixed thresholds turns it into an event (or, for sleep, an estimate the wearer
can accept or ignore). Model-only findings never go above MODERATE and never send an SOS.

| Model | Learns | From | Ships as | Used by |
|---|---|---|---|---|
| `fall_v1` | Fall or not, from 4 s of movement around an impact | WEDA-FALL + UMAFall | 15 KB INT8 | Model Lab (replay and live phone sensor). The strap cannot send a movement window yet, so strap falls still use the 5 g rule |
| `hr_context_v1` | The heart rate expected for the last 5 min of movement | PAMAP2 | 5 KB INT8 | Rule `ml.hrContext` in the monitoring pipeline; Model Lab |
| `sleep_v1` | Asleep or awake, every 30 s, from wrist movement | Walch et al. (PhysioNet) | 6.5 KB INT8 | "Estimated sleep" on the Sleep card |

Results, plots and limits: [docs/MODELS.md](../docs/MODELS.md). Raw numbers: `ml/reports/*/metrics.json`.

## Layout

```
common/       the feature specifications the app copies line for line (motion.py, hr.py, sleep.py)
              and the manifest writer
fall/         prepare.py (WEDA-FALL, UMAFall → 25 Hz trials), train.py
hr_context/   prepare.py (PAMAP2 → the app's 5-second readings), train.py
sleep/        prepare.py (PhysioNet nights, one at a time → 5-second readings), train.py
aihub/        profile.py — time each model on hosted Snapdragon phones (Qualcomm AI Hub)
tests/        pytest checks of the specifications and the shipped files' checksums
export_samples.py   bundles a few licence-compatible recordings for the Model Lab screen
run_all.py    everything, in order
data/         downloads and prepared data (gitignored)
reports/      metrics.json and plots per model (committed)
```

Every training script writes, besides the `.tflite`:
- `app/src/main/assets/ml/manifest.json`: file, SHA-256, size, input settings, threshold, test
  results. The app refuses a model whose checksum does not match.
- `app/src/test/resources/ml/<model>_golden.txt`: real inputs and what the Python code computed
  from them. The app's JVM tests (`FallWindowTest`, `HrContextTest`, `SleepTest`) require the Kotlin
  code to compute the same.

## Reproduce

Python 3.11 with the packages in `requirements.txt` (TensorFlow 2.20 on the CPU is enough;
training each model takes minutes).

```powershell
python -m ml.run_all            # prepare, train, test, export — downloads what it can
python -m pytest ml/tests
```

Datasets, where each script expects them, and their terms:

| Dataset | Get it | Licence | Bundled in the app? |
|---|---|---|---|
| UMAFall (Casilari et al. 2017) | `curl -L -o ml/data/raw/umafall.zip https://ndownloader.figshare.com/files/43076140` | CC BY 4.0 | 8 short recordings, for Model Lab and tests |
| WEDA-FALL (Marques & Moreno 2023) | `git clone --depth 1 https://github.com/joaojtmarques/WEDA-FALL ml/data/raw/weda` | none stated | No |
| PAMAP2 (Reiss & Stricker 2012) | `curl -L -o ml/data/raw/pamap2.zip "https://archive.ics.uci.edu/static/public/231/pamap2+physical+activity+monitoring.zip"` | CC BY 4.0 | One session, for Model Lab |
| Sleep (Walch et al. 2019) | `ml/sleep/prepare.py` reads PhysioNet's open-data mirror night by night | ODC-By 1.0 | One night, for Model Lab |

## Snapdragon timing (Qualcomm AI Hub)

`ml/aihub/profile.py` uploads each `.tflite` to Qualcomm AI Hub, which runs it on hosted Snapdragon
phones with the TensorFlow Lite runtime and Qualcomm's NPU delegate, and writes the measured
inference time, peak memory and the share of layers on the NPU into the manifest. The Model Lab
screen shows those beside the time it measures on the phone in hand. Run on 4 October 2026: all
three models execute entirely on the NPU, 41–55 µs per run on Snapdragon 8 Gen 3 and 8 Elite
(`ml/reports/aihub/`). It needs your own AI Hub account; configure it yourself:

```powershell
ml\.venv\Scripts\python -m pip install qai-hub
ml\.venv\Scripts\qai-hub configure --api_token <token from aihub.qualcomm.com>
ml\.venv\Scripts\python -m ml.aihub.profile --list-devices
ml\.venv\Scripts\python -m ml.aihub.profile
```

`ml/.venv` is created with `--system-site-packages`, so it reuses the installed TensorFlow.
