# Jeevan / G-one Core — final-phase concept visualization

## Deliverables

- `Jeevan-Core.blend`: editable native geometry, three cameras,
  organized hardware collections and layer animation.
- `Jeevan-Core.glb`: self-contained web asset, metres, glTF Y-up, 11 named layer
  parents, PBR materials and an `Explode` animation clip.
- `core-assembled.png`, `core-exploded.png`, `core-skin.png`: Cycles renders.
- `layers.json`: stable IDs, descriptions and explosion distances.
- `web/`: working React viewer, also usable as a Next.js client component.

Frame **1** is assembled; frame **121** is exploded. The liner is present in the
assembly and hidden only in the skin-side render. The eleven mechanical layers
are distinct from the six visual copper planes of the main PCB.

## Use the web viewer

From `web`, run:

```sh
npm install
npm run dev
```

`npm run build` creates the deployable static site in `web/dist`. Serve it over
HTTP; opening the HTML directly with `file://` cannot load module assets properly.
No external image CDN, model service, or Draco decoder is required.

Scroll through the sticky product section to separate the layers. A slider,
assembled/exploded buttons, a layer index, and drag-to-rotate are also available.
Reduced-motion preference switches to manual control. Label changes are announced
through an ARIA live region. All descriptions remain available if WebGL fails.

### Existing React or Next.js app

Install `three` in your existing app (React and React DOM should already exist).
Copy `web/src/CoreExplorer.jsx`, `web/src/layers.json`, and `web/src/style.css`
into your component folder, and copy `Jeevan-Core.glb` to your app's `public` folder.
Import the CSS once in your root layout / entry point, and render:

```jsx
import CoreExplorer from './components/CoreExplorer';

export default function Page() {
  return <CoreExplorer modelUrl="/Jeevan-Core.glb" />;
}
```

The component includes `'use client'` and creates WebGL only inside `useEffect`.
It cleans up listeners, animation frames, controls, materials and the renderer.
The standalone demo stylesheet includes body/root styles; scope those to your
page when integrating into a site with an existing design system.

### Custom Three.js integration

Each parent node name matches an ID in `layers.json`. Children are consolidated
by mechanical layer to reduce draw calls. For a linear scroll reveal, preserve
the initial position and set:

```js
layer.position.y = initialY + metadata.explodeDistanceM * scrollProgress;
```

`scrollProgress` is clamped to 0–1. Alternatively, use `AnimationMixer` to play
or scrub the `Explode` clip. Do not apply the clip and manual translations at the
same time. The supplied viewer uses manual translation and keeps the clip idle.

## Reference reconciliation and accuracy

The supplied sheets and written brief are concept references, not a frozen CAD
or electrical design. Their dimensions and annotations conflict. This model
uses a **provisional 30 × 50 × approximately 13 mm reusable envelope** and a
**24 × 40 × 1 mm main PCB** to accommodate the named module and a separate pouch
cell. The adhesive/liner and peel tab extend beyond the reusable enclosure.
The illustration's 8 mm overall thickness and 28 × 24 mm PCB are NOT asserted.

ESP32-S3-WROOM-1 is represented using an 18 × 25.5 mm module outline with shield,
castellations and antenna. See [Espressif's module page](https://www.espressif.com/en/module/esp32-s3-wroom-1-en).
Other package envelopes are plausible approximations. Exact footprints, the
component BOM, RF keep-outs and thermal/mechanical fit require real ECAD review.

Mainboard: ESP32-S3 N16R8, ADS1292R ECG AFE, INA333 EMG AFE, MPU6050, BME280,
BQ24074, TPS63070, microSD, USB-C, battery socket and 24-pin flex connector.
AD5940 represents the BioZ AFE option shown in the references; that circuit and
its return-electrode arrangement remain unvalidated.
MAX30102 and the DS18B20 interface are on the skin-side flex rather than duplicated
as unrelated optical modules. EDA, NFC and separate invented sensors are omitted.

Visible traces, six copper-layer edges, solder terminals and contact interfaces
support the visualization. They are NOT a verified netlist or manufacturing
stack-up. The model is not a fabrication deliverable, and no Gerbers are included.
The exposed electrode positions follow the concept image and do not establish
that this spacing works for ECG, EMG, BioZ or chest PPG.

Battery capacity/runtime, ingress protection, biocompatibility, adhesive lifetime
and physiological performance are not certified or established by this model.
The web copy deliberately avoids the unvalidated 5–7 day and IP67 image claims.

## Editing and rebuilding

Use the .blend as the editing source. `build_core.py` constructs this new model;
`refine_core.py` applies the final fit refinements and refreshes renders.
`export_core.py` evaluates editable geometry in memory and exports GLB without
changing the .blend. Do not rerun construction scripts over hand edits.
The earlier Phase-1 and custom-PCB assets are unchanged.
The three reference sheets were inspected from the supplied images; they are
not embedded in the final .blend. Their original Downloads paths became
unavailable during work. This does not affect the model or viewer assets.

Validation reports are saved alongside the model. Validation covers GLB format,
named layers, animation, embedded buffers, re-import and source preservation;
it is not an electrical, clinical or complete manufacturing-interference audit.

Viewer API references: [Three.js GLTFLoader](https://threejs.org/docs/pages/GLTFLoader.html),
[AnimationMixer](https://threejs.org/docs/pages/AnimationMixer.html),
[React effect lifecycle](https://react.dev/reference/react/useEffect).
