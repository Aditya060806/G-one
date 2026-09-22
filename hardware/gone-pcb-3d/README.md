# G-one wearable PCB — enhanced editable assembly, Rev B

**Current file: `G-one-PCB.blend`.** This revision was made directly from the existing model. `G-one-PCB-before-enhancement.blend` preserves the original. The PCB outline, component arrangement, ESP32 module, optical sensor, IMU, temperature sensor and EMG section remain the foundation.

## Rev B enhancements

- Realistic pouch-cell assembly with sealed foil edges, terminal insulation and label; no invented capacity or supplier. The **30 × 26 × 5 mm cell envelope remains provisional**.
- Removable polymer carrier on four existing mounting holes, with insulating feet, spacers and screws. Battery remains above the electronics and away from the skin-facing sensors.
- Three smooth, editable battery harness curves terminate at the cell and a modeled mating plug: positive, negative and proposed NTC. Actual connector polarity and thermistor circuit require verification.
- TP4056 SOP-8 charger replaces the previous BQ24074 package, with separate DW01A / FS8205A protection package representations. TPS63070 remains the buck/boost section. The charger is not merely relabeled: body and lead geometry changed.
- Exposed FR-4 board edges, copper lands beneath solder feet, subtle soldermask relief, additional component markings, refined USB-C shell and true openings in the microSD lid.
- An inherited capacitor/regulator body overlap was corrected; the carrier frame is fused into one clean mesh.
- Eight 1800 × 1800 Cycles views, including assembled hardware, board-only top/isometric/bottom, side, sensor macro, ESP32/power detail and analog/USB detail.

The assembly follows the **integrated custom PCB architecture**. ESP32-S3 N16R8 and MPU6050 are present as integrated packages; entire DevKitC-1, GY-521 and microSD breakout boards are not stacked on top of this board. This preserves the reference and avoids duplicate circuitry.

## Current files and controls

| File | Purpose |
|---|---|
| `G-one-PCB.blend` | Finished editable Rev B assembly |
| `G-one-PCB-before-enhancement.blend` | Preserved original Rev A |
| `assembled.png` | Complete battery-equipped device |
| `isometric.png`, `top.png`, `bottom.png` | PCB inspection with removable battery assembly hidden |
| `side.png` | Assembled height, supports and wiring |
| `sensors.png`, `esp32-power.png`, `detail.png` | Component close-ups |
| `enhance_pcb.py` | Incremental geometry edits, run on original Rev A only |
| `finish_enhanced.py` | Clearance fixes, GPU setup and final renders, run on enhanced scene |
| `audit_enhanced.py`, `mechanical-audit.json` | Package-body clearance checks and results |

Collections **14, 15 and 16** contain the carrier, cell and harness. Hide these together to inspect the PCB. They are visible in the saved assembled state. Studio lights and ground are hidden only in the viewport, and remain enabled for rendering. Every component and text marking remains editable. Use the named cameras from the Outliner or Scene camera selector.

Small removable supports in the **Studio** collection hold the PCB above the tabletop for product photography; they are presentation fixtures, not added wearable components. They are omitted from underside renders and hidden in the modeling viewport.

The original `build_pcb.py` and `validate_model.py` below describe Rev A. **Do not run them over this finished revision**: the original builder starts a new scene. To reproduce Rev B, open the preserved Rev A backup, run `enhance_pcb.py`, then run `finish_enhanced.py` on its saved result.

## Remaining engineering limits

This is an enhanced mechanical/product visualization, not released manufacturing CAD. The visual tracks do not form a verified electrical netlist. Precise cell, connectors, EMG circuitry, charging current, board routing and enclosure dimensions still need engineering confirmation. TP4056 is a charger; battery protection is represented separately, and no validated power-path/load-sharing circuit is claimed. The manufacturer package and wiring implementation must be verified before hardware fabrication.

MAX30102 follows the latest request; the repository's existing MAX30100 driver still requires migration. No firmware changes were made. Package-body bounding-box checks and visual inspection provide useful clearance evidence, but do not constitute electrical DRC or exhaustive collision certification.

---

## Original Rev A notes (historical)

Open **G-one-PCB.blend** in Blender 5.2 or later. The board and all modeled parts are native, editable meshes and curves. The supplied reference is packed inside the file. The complete generator is also embedded as a Blender Text datablock.

## Files

- `G-one-PCB.blend`: native assembly, materials, lighting, four cameras and embedded notes.
- `isometric.png`, `top.png`, `bottom.png`, `detail.png`: actual Blender Cycles renders of the same assembly.
- `build_pcb.py`: reproducible geometry and render script.
- `layout-reference.png`: original generated image used for placement.
- `model-inventory.json`: object count and collection inventory.

## Editing

One Blender unit represents **one millimetre**; the main PCB is 44 × 64 × 1 mm. The ESP32 antenna extends past the main board. Expand the numbered collections in the Outliner to select individual bodies, terminals, solder joints, silkscreen text, optical windows, and connector contacts. Select the root empty to move the assembly as a unit. Use the named cameras for consistent top, bottom, isometric and detail views. Hide the studio collection for unobstructed modeling.

The optional battery envelope is hidden in both the viewport and renders. Enable collection 11 only to study clearance; its 30 × 25 × 5 mm size is provisional, not a selected battery.

Rebuild from PowerShell in the repository root:

```powershell
& 'D:\Blender\blender.exe' --background --factory-startup --python 'hardware\gone-pcb-3d\build_pcb.py'
```

Rebuilding replaces generated outputs in this directory. Save your edited Blender file under another name first.

## Reconstruction scope

This is a detailed **visual/mechanical concept**, not a fabricated-board digital twin or a released electrical design. The reference is an AI-generated illustration, not Gerbers or a measured assembly. It contains inconsistent hole counts between views and inaccurate package depictions. The model uses one consistent assembly with six mounting holes. The lower pair is shifted upward 4 mm to clear the button and battery connector, while reasonable package envelopes replace oversized illustrated chips.

| Section | Representation |
|---|---|
| Controller | ESP32-S3-WROOM-1-N16R8, nominal 18 × 25.5 mm module, shield, castellations, antenna pattern approximation |
| Optical | MAX30102EFD+, nominal 5.6 × 3.3 × 1.55 mm body, cover glass, LED and photodiode windows |
| Motion | MPU6050, nominal 4 × 4 mm body with contacts |
| Temperature | DS18B20 µSOP-style body on a slotted thermal island |
| EMG | INA333 and two opamp bodies, supporting visual passives, three-electrode connector; analog design unresolved |
| Storage | Modeled microSD socket, folded shield, contacts and card lip; not a vendor-qualified mechanical model |
| Power | BQ24074, TPS63070, inductor, rail-regulator envelope models, battery connector |
| Interfaces | Hollow USB-C receptacle with tongue and contacts, reset/boot switches, PWR/CHG LEDs, programming pads |

**MAX30102 is the user's latest requested sensor.** The repository currently initializes a **MAX30100** using `MAX30100_PulseOximeter.h`. The sensor substitution requires a different driver and validation; no firmware was changed here. [MAX30102 manufacturer specifications](https://www.analog.com/en/products/max30102.html).

Exact connector part numbers, opamp choices, passive values, electrode protection circuit, battery specifications and enclosure clearances were not supplied. Those details require verification. Package contacts and markings are modeled for appearance and editing, not for deriving footprints. Visible copper relief does not represent an electrical netlist. The antenna meander is illustrative, not RF manufacturing artwork. No unrequested sensors, display, buzzer or external Bluetooth board were added.

Electrical schematics, actual routed layers, charge/protection design, antenna clearance review, clinical sensing validation, DRC and fabrication files remain separate work. This model must not be used to order PCB fabrication.

## Blender integration

Created directly with Blender 5.2.2's Python API and Cycles, using the installed Blender executable. The downloaded Blender Lab MCP 1.0.3 add-on declares Blender 5.1+ compatibility, but no MCP server is connected to this task. No add-on installation or preference changes were needed or performed.
