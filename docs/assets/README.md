# Footprint chart methodology

The [SVG](model-footprint.svg) is a self-contained, editable vector graphic. Its source values are preserved in [footprint-data.json](footprint-data.json).

## Measured data

Inspected on 22 September 2026:

- APK: `app/build/outputs/apk/release/app-release.apk`, 1,182,249,970 bytes.
- ZIP entry: `assets/models/qwen.gguf`, 1,117,320,736 bytes, method 0 (stored).
- Remaining APK bytes: 64,929,234. This includes all other entries, ZIP metadata and signing overhead.
- Model share: `1,117,320,736 / 1,182,249,970 * 100 = 94.508%`.
- MiB means bytes / 1,048,576; GB means bytes / 1,000,000,000.

The chart describes this local artifact. It is not an installed-size, resident-memory or download-compression benchmark.

## Theoretical comparison

For a nominal 1,500,000,000 parameters, raw weights require `parameters * bits / 8` bytes: FP32 = 6 GB, FP16 = 3 GB, ideal 4-bit = 0.75 GB. These ignore architecture-specific parameter counts, metadata and mixed tensor types. Actual Q4_K_M size is shown as a separate measured bar, not a measured before/after comparison. No quality retention or empirical compression ratio is inferred.

## Reproduce the measurements

Run from the repository root after building an APK:

```python
from pathlib import Path
from zipfile import ZipFile

apk = Path("app/build/outputs/apk/release/app-release.apk")
with ZipFile(apk) as archive:
    model = archive.getinfo("assets/models/qwen.gguf")
    print("APK bytes:", apk.stat().st_size)
    print("Model file / archive bytes:", model.file_size, model.compress_size)
    print("ZIP compression method:", model.compress_type)
    print("Model share (%):", 100 * model.compress_size / apk.stat().st_size)
```

The README test count was independently summed from `app/build/test-results/testDebugUnitTest/TEST-*.xml` attributes (`tests`, `failures`, `errors`). Existing reports were inspected; documentation work did not rerun application tests. Historical firmware and phone observations are attributed to `VERIFICATION.md`.

## Embedded model metadata

Direct header inspection found `general.name: qwen2.5-1.5b-instruct`, `general.architecture: qwen2`, `general.file_type: 15` (Q4_K_M), and `general.size_label: 1.8B`. The nominal 1.5B figure in the theoretical bars is not a verified exact parameter count. Resolve provenance before using this artifact for model-to-model comparisons.

## Visual gallery provenance

The README gallery uses local files so it does not depend on remote image hosting. Screenshots and source renders were copied without changing their contents.

| Asset | Source | Interpretation |
|---|---|---|
| `readme-banner.svg` | Original editable vector banner created for the README | Architecture overview, not a product screenshot |
| `website-home.png` | `https://g--one.vercel.app/` | Public landing page captured 22 September 2026 |
| `website-demo.png` | `https://g--one.vercel.app/demo` | Public browser demonstration, not live patient data |
| `website-hardware.png` | `https://g--one.vercel.app/hardware` | Public interactive hardware viewer |
| `website-emergency.png` | `https://g--one.vercel.app/e` | Generic setup page; no private capability URL or wearer record captured |
| `phase1-worn.png` | `hardware/gone-phase1-strap/phase1-worn.png` | Existing Blender reconstruction |
| `pcb-concept.png` | `hardware/gone-pcb-3d/isometric.png` | Existing compact-PCB concept render |
| `core-exploded.png` | `hardware/jeevan-core/core-exploded.png` | Existing Jeevan Core concept render |
| `app-aqi.png` | `artifacts/companion-visuals/aqi.png` | Earlier Android capture; not current AQI |
| `app-wellness.png` | `artifacts/companion-visuals/wellness.png` | Earlier Android sleep journal empty state |
| `app-stress.png` | `artifacts/companion-visuals/stress.png` | Earlier Android stress journal empty state |

Public-site presentation labels are not implementation evidence. In particular, 250 Hz streaming, 72-hour buffering and encryption claims visible on the website must not override the audited Android/firmware description. Updating the website itself was outside this README task.

The gallery uses GitHub-supported tables, image links and collapsible details. It does not rely on JavaScript, hover-only content or custom CSS, which repository README renderers commonly strip.
