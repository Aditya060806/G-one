# G-one — Phase 1 physical strap prototype

Open **G-one-Phase-1-Strap.blend** in Blender. This is a separate model; neither of
the earlier custom-PCB Blender files is modified.

## Scenes and views

- **01 | Worn Phase-1 prototype**: broad black cuff around a forearm with a closed
  fist, inverted ESP32 development board, exposed yellow headers, prototype
  wiring, four axial resistors, insulation tape and temperature probe.
- **02 | Opened strap - sensor inspection**: the same sensor geometry displayed
  on the open fabric strap, following the flat reference arrangement. The ESP32
  is beside the strap as in the bench photographs. This is a separate inspection
  arrangement, not an exploded view of custom-PCB hardware.
- Cameras in the worn scene show the full assembly, controller detail, and inner
  sensor surface. Hide the Anatomy and Woven strap collections and studio floor
  when using the inner camera; this is an inspection view through the fabric.

Rendered files: `phase1-worn.png`, `phase1-hardware.png`, `phase1-inside.png`, and
`phase1-opened.png`. Preview files are earlier intermediate renders.

## Reference interpretation

All eleven supplied photographs are packed into the Blender file. Some are
duplicate views. The legible boards are:

- Red **Muscle BioAmp Patchy**, with two metal snap contacts.
- Green optical breakout marked **MAX30100/30102**. The photograph does not prove
  which chip variant is fitted; current firmware separately identifies MAX30100.
- Blue **MPU6050 / GY-521**, containing the accelerometer and gyroscope together.
- Black **ESP32-S3 development board**, two USB-C sockets, inverted in the worn
  photographs, with long exposed pins in yellow header carriers.
- A stainless temperature probe and hand-soldered resistor/jumper connections.

There is no new battery, microSD board, custom PCB, display, or separate invented
magnetometer. Personal bracelets and the surrounding desk equipment are not
part of the wearable model.

## Accuracy limits

The model is a photo-derived visualization, not a scan or manufacturing CAD.
The nominal 2.54 mm header pitch provides a scale cue. Strap dimensions, board
envelopes, hidden mounting details, resistor values and obscured wire routing
are approximations. Wire colors and loose looping construction follow the
photos, but the digital wiring is not a verified electrical netlist.

The complete closed fist is outside the supplied worn photographs. Its anatomy
is modeled approximately; it is not a reconstruction of the wearer's exact hand.
The body uses editable mesh geometry with smoothing/subdivision modifiers.
The wires remain editable Bezier curves; the markings remain editable text.

Collections separate anatomy, fabric, each module, harness, resistors, probe,
studio and photographic references. Both scenes use Cycles with denoising.

## Reproduction

The scripts are construction history. `build_phase1.py` starts a NEW scene;
`refine_phase1.py` and `finish_phase1.py` apply subsequent refinements. Do not
rerun them on a manually edited final scene; use the saved .blend for editing.

`final_check_phase1.py` corrects the final wire clearances. The sampled main
jumper centerlines pass the body penetration check in `geometry-audit.json`.
This is not a complete solid-interference or electrical check.
`render_final_phase1.py` refreshes the four presentation views.

## Web GLB exports

- `G-one-Phase-1-Strap-web.glb`: recommended for the web viewer; 21.1 MB,
  nine geometry groups, 694,586 triangles. Dense curved surfaces are simplified.
- `G-one-Phase-1-Strap.glb`: full geometry, 37.2 MB, 1,089 meshes.

Both contain the worn assembly with the forearm and closed fist, use metres and
glTF Y-up coordinates, and embed all required data. Studio lights, cameras,
reference photos, and the separate opened-strap scene are excluded. No Draco or
Meshopt decoder is needed. Use standard GLTFLoader or a GLB-compatible viewer.
Provide viewer lighting/environment for the metallic surfaces.

Portable base color, metallic, and roughness values are retained. Blender-only
procedural micro-bump and skin subsurface shading are omitted. Both exports were
re-imported to verify mesh counts and bounds, and the source .blend SHA-256 was
confirmed unchanged. See the two glb export reports for validation details.
