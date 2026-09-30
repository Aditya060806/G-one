"""Builds output/pdf/G-one-Architecture-and-Hardware.pdf.

Companion to build_gone_profile.py, in the same visual language (Segoe UI, amber and cream,
footer rule). Where that document explains the project, this one describes how it is built: the
architecture, the wearable and its protocol, the Android application, the local model, all three
hardware prototypes and every 3D file.

Source of truth for the wording: docs/G-ONE-COMPLETE-DESCRIPTION.md. Run from the repository root:

    python tmp/pdfs/build_gone_architecture.py
"""
from pathlib import Path
from xml.sax.saxutils import escape

from PIL import Image as PILImage
from reportlab.lib.colors import HexColor, white
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (Image, PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table,
                                TableStyle)

root = Path.cwd()
out = root / "output/pdf/G-one-Architecture-and-Hardware.pdf"
cache = root / "tmp/pdfs/_scaled"
cache.mkdir(parents=True, exist_ok=True)
out.parent.mkdir(parents=True, exist_ok=True)

pdfmetrics.registerFont(TTFont("UI", "C:/Windows/Fonts/segoeui.ttf"))
pdfmetrics.registerFont(TTFont("UIB", "C:/Windows/Fonts/segoeuib.ttf"))
pdfmetrics.registerFont(TTFont("Mono", "C:/Windows/Fonts/consola.ttf"))
pdfmetrics.registerFontFamily("UI", normal="UI", bold="UIB", italic="UI", boldItalic="UIB")

ink = HexColor("#252622")
muted = HexColor("#62645c")
gold = HexColor("#FFD061")
cream = HexColor("#F8F8F3")
line = HexColor("#E1E2D9")
PAGE_W, PAGE_H = 595.2756, 841.8898
COL = 499.0

styles = {
    "body": ParagraphStyle("body", fontName="UI", fontSize=10, leading=15, textColor=ink, spaceAfter=9),
    "small": ParagraphStyle("small", fontName="UI", fontSize=8.5, leading=12, textColor=muted, spaceAfter=7),
    "h1": ParagraphStyle("h1", fontName="UIB", fontSize=27, leading=32, textColor=ink, spaceAfter=15),
    "h2": ParagraphStyle("h2", fontName="UIB", fontSize=13, leading=18, textColor=ink, spaceBefore=10, spaceAfter=7),
    "kicker": ParagraphStyle("kicker", fontName="UIB", fontSize=9, leading=12, textColor=muted, spaceAfter=9),
    "cell": ParagraphStyle("cell", fontName="UI", fontSize=9, leading=13, textColor=ink),
    "mono": ParagraphStyle("mono", fontName="Mono", fontSize=8.5, leading=12.5, textColor=ink),
    "caption": ParagraphStyle("caption", fontName="UI", fontSize=8.5, leading=12, textColor=muted, spaceBefore=4, spaceAfter=10),
    "quote": ParagraphStyle("quote", fontName="UIB", fontSize=15, leading=22, textColor=ink, spaceAfter=10),
}

story = []


def p(text, kind="body"):
    story.append(Paragraph(text, styles[kind]))


def h(text):
    p(text, "h2")


def bullets(items):
    for item in items:
        p("• " + item)


def page(kicker, title, deck):
    if story:
        story.append(PageBreak())
    p(kicker, "kicker")
    p(title, "h1")
    p(deck)


def table(head, rows, widths=None):
    data = [[Paragraph("<b>" + escape(c) + "</b>", styles["cell"]) for c in head]]
    data += [[Paragraph(c if "<" in c else escape(c), styles["cell"]) for c in row] for row in rows]
    t = Table(data, colWidths=widths or [140, COL - 140], repeatRows=1, hAlign="LEFT")
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), gold),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [white, cream]),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 10),
        ("RIGHTPADDING", (0, 0), (-1, -1), 10),
        ("TOPPADDING", (0, 0), (-1, -1), 8),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
        ("LINEBELOW", (0, 0), (-1, 0), 0.5, line),
        ("LINEBELOW", (0, -1), (-1, -1), 0.5, line),
    ]))
    story.append(t)
    story.append(Spacer(1, 10))


def call(text):
    t = Table([[Paragraph(text, styles["body"])]], colWidths=[COL])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), cream),
        ("BOX", (0, 0), (-1, -1), 0.7, line),
        ("LEFTPADDING", (0, 0), (-1, -1), 14),
        ("RIGHTPADDING", (0, 0), (-1, -1), 14),
        ("TOPPADDING", (0, 0), (-1, -1), 12),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
    ]))
    story.append(t)
    story.append(Spacer(1, 12))


def code(lines):
    """A fixed-width block, for a wire line or a command."""
    body = "<br/>".join(escape(l).replace(" ", "&nbsp;") for l in lines)
    t = Table([[Paragraph(body, styles["mono"])]], colWidths=[COL])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), HexColor("#F2F2EC")),
        ("BOX", (0, 0), (-1, -1), 0.7, line),
        ("LEFTPADDING", (0, 0), (-1, -1), 12),
        ("RIGHTPADDING", (0, 0), (-1, -1), 12),
        ("TOPPADDING", (0, 0), (-1, -1), 10),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 10),
    ]))
    story.append(t)
    story.append(Spacer(1, 12))


def picture(relative, caption, width=COL):
    """Embed a render, downscaled so the document stays a sensible size."""
    src = root / relative
    scaled = cache / (src.stem + "-scaled.png")
    if not scaled.exists():
        im = PILImage.open(src).convert("RGB")
        if im.width > 1200:
            im = im.resize((1200, round(im.height * 1200 / im.width)), PILImage.LANCZOS)
        im.save(scaled, "PNG", optimize=True)
    im = PILImage.open(scaled)
    story.append(Image(str(scaled), width=width, height=width * im.height / im.width))
    p(caption, "caption")


def steps(items):
    """A left-to-right flow, drawn as one row of boxes."""
    cells = [Paragraph("<b>%s</b><br/>%s" % (escape(a), escape(b)), styles["cell"]) for a, b in items]
    t = Table([cells], colWidths=[COL / len(items)] * len(items), hAlign="LEFT")
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), cream),
        ("BOX", (0, 0), (-1, -1), 0.7, line),
        ("INNERGRID", (0, 0), (-1, -1), 0.5, line),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 9),
        ("RIGHTPADDING", (0, 0), (-1, -1), 9),
        ("TOPPADDING", (0, 0), (-1, -1), 10),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 10),
    ]))
    story.append(t)
    story.append(Spacer(1, 12))


# ── 01 Cover ────────────────────────────────────────────────────────────────
page("ARCHITECTURE AND HARDWARE  /  SEPTEMBER 2026", "G-one",
     "How the system is built: the wearable, the phone, the link between them and three generations of hardware.")
story.append(Image(str(root / "Logo.png"), width=170, height=170))
story.append(Spacer(1, 18))
p("A wearable senses. The phone decides and explains.<br/>Nothing is guessed, and nothing is hidden.", "quote")
p("This document describes what G-one actually does today, part by part. Where something is a design "
  "asset or a presentation claim rather than working behaviour, it says so in the same breath.")
call("<b>The rule everything follows.</b><br/>Deterministic rules decide health events; the language "
     "model only rewords them. If the model never loads, every alert still arrives with usable wording.")
h("Inside this document")
table(["Pages", "Contents"], [
    ["02 – 04", "System architecture, the wearable and its firmware, the Bluetooth link"],
    ["05 – 07", "The Android application, alerts and SOS, the on-device language model"],
    ["08 – 11", "The three hardware prototypes, and every 3D file listed"],
    ["12 – 14", "Tools, privacy, what is proven, what is not, and the next steps"],
], [90, COL - 90])
p("Written from the code, the recorded bench and phone evidence in VERIFICATION.md, and the README. "
  "The full text version is docs/G-ONE-COMPLETE-DESCRIPTION.md.", "small")

# ── 02 Architecture ─────────────────────────────────────────────────────────
page("02  /  ARCHITECTURE", "The three parts",
     "One device measures, one device thinks, and a few optional services reach outside the phone.")
table(["Part", "What it is responsible for"], [
    ["1 · Wearable", "An ESP32-S3 with four sensors. It measures, builds one line of text twice a second, and sends it. It stores nothing and decides nothing."],
    ["2 · Android phone", "Everything else: parsing, validation, storage, the 13 detection rules, alerts, sessions, reports, the local language model and every screen."],
    ["3 · Optional services", "Only if switched on: city air quality, a consented emergency snapshot behind an NFC or QR link, and SMS or calls to one saved contact."],
])
h("A reading becomes an alert")
steps([("1 · Store", "The reading is written to the database first"),
       ("2 · Detect", "Rules run over the recent window"),
       ("3 · Record", "The event is stored with its explanation"),
       ("4 · Alert", "The wearer is told immediately"),
       ("5 · Reword", "The model may improve it, later")])
p("The order is the design. Storing comes before detecting, detecting before alerting, and the model "
  "last and off to one side, where a slow or broken model cannot delay or weaken an alert.")
h("Who owns what, in the code")
table(["Responsibility", "Where it lives"], [
    ["Reading sensors and sending lines", "firmware/g_one_wearable"],
    ["Framing bytes, checking units and ranges", "health/source/SensorPacketParser.kt"],
    ["Connecting, reconnecting, clock sync", "health/source/ble/"],
    ["Storing, aggregating and calling the detector in order", "health/service/MonitoringPipeline.kt"],
    ["Deciding whether something is wrong", "health/detect/ and health/domain/AnomalyThresholds.kt"],
    ["Wording an alert, then improving it in the background", "health/explain/ and the explanation worker"],
    ["Sharing one language model between chat and tools", "ai/repository/"],
    ["Anything that leaves the phone", "health/sos/, health/emergency/, health/environment/"],
], [215, COL - 215])

# ── 03 Wearable ─────────────────────────────────────────────────────────────
page("03  /  THE WEARABLE", "What is on the strap",
     "Four sensors, two I²C buses and a firmware built from the owner's own working bench sketch.")
table(["Part", "Measures", "Connection"], [
    ["ESP32-S3-N16R8", "—", "Runs the firmware and Bluetooth LE"],
    ["MAX30100", "Pulse, blood oxygen", "I²C bus 0 · SDA 8, SCL 9 · 0x57"],
    ["MPU6050 (GY-521)", "Movement and impacts", "I²C bus 1 · SDA 6, SCL 7 · 0x68"],
    ["DS18B20", "Skin temperature", "1-Wire GPIO 4, 4.7 kΩ to 3V3"],
    ["EMG module", "Muscle activity", "Analog GPIO 1, must stay within 0–3.3 V"],
], [110, 120, COL - 230])
p("Two separate I²C buses are deliberate: the pulse sensor is read every millisecond on its own task, "
  "so giving the motion sensor its own bus means neither waits for the other.")
h("Three corrections the app needed")
table(["Found in the bench sketch", "Why it mattered", "Fix"], [
    ["Heart rate sent as a decimal", "The app reads whole numbers, so every reading would have been dropped", "Rounded"],
    ["Accelerometer at ±2 g", "The fall rule needs a harder impact than ±2 g can report at all", "±8 g"],
    ["A dead sensor kept its last value", "A stopped sensor would be shown as a live reading", "Dropped after 5 s"],
], [150, 230, COL - 380])
h("A stuck heart rate, and the guard against it")
p("The MAX30100 library clears its rate from only one internal state. On the bench it stuck after the "
  "board was knocked and reported <b>0.3 BPM with nothing on the sensor</b>; after a real pulse it would "
  "repeat that pulse indefinitely. The firmware now sends a rate only when the library's own beats back "
  "it up: five beats found, the newest within 2.5 seconds, and the rate within 20 % of what those beats imply.")
call("<b>Live only.</b> There is no SD card on this build. Nothing is kept on the board, so a reading "
     "made while the phone is out of range is lost and appears as a gap. The app still speaks the fuller "
     "store-and-forward protocol, so a wearable that keeps readings would work unchanged.")
h("Power settings, and the fix still outstanding")
p("Measuring its own I²C lines at start-up showed the MAX30100 module pulls that bus to its internal "
  "<b>1.8 V</b>, while the ESP32-S3 needs about 2.5 V to read a “1”. That is why the pulse sensor "
  "answered on some boots and not others, and why no heart rate has ever reached the app.")
table(["Setting", "Value, and why"], [
    ["Outstanding hardware fix", "2.2 kΩ from SDA to 3V3 and from SCL to 3V3. Until fitted, the firmware prints TOO LOW at start-up"],
    ["CPU speed", "80 MHz instead of 240 — less current and heat; sensors and Bluetooth are unaffected"],
    ["Bluetooth power", "3 dBm instead of 9 — smaller current spikes"],
    ["Pulse sensor bus", "100 kHz instead of 400 kHz — four times as long for a weak line to rise"],
    ["A sensor that does not answer", "Asked three times at start-up, then every 5 s, without a restart"],
], [150, COL - 150])

# ── 04 Protocol ─────────────────────────────────────────────────────────────
page("04  /  THE LINK", "One line, twice a second",
     "Bluetooth LE using the HM-10 serial profile: service FFE0, characteristic FFE1.")
code(["HR:72,SPO2:97,STEMP:33.90,MOT:1.02,EMG:1234,", "EMGPK:1810,EMGBITS:12,ST:5D,TS:1726470000000"])
table(["Key", "Meaning", "Accepted"], [
    ["HR", "Heart rate, whole beats per minute", "20 – 300"],
    ["SPO2", "Blood oxygen, %", "50 – 100"],
    ["STEMP", "Skin temperature, °C", "20 – 42"],
    ["TEMP", "Core temperature — not sent by this hardware", "25 – 45"],
    ["MOT", "Hardest movement since the last line, g", "0 – 16"],
    ["EMG / EMGPK", "Muscle level and peak, raw ADC counts", "0 – 4095"],
    ["EMGBITS", "Resolution of those counts", "must be 12"],
    ["ST", "Status bits, hex", "see below"],
    ["TS", "When the reading was taken", "2 min ahead / 30 days back"],
], [80, 250, COL - 330])
h("Status bits, and what the app says about them")
table(["Bit", "Meaning", "When clear, the app says"], [
    ["0x01", "Pulse sensor found", "“Heart-rate and oxygen sensor not detected”"],
    ["0x02", "A pulse is being found", "“Heart-rate sensor is not touching skin”"],
    ["0x04", "Thermometer found", "“Skin temperature sensor not detected”"],
    ["0x08", "Motion sensor found", "“Motion sensor not detected — falls cannot be detected”"],
    ["0x10", "EMG signal looks connected", "“Muscle sensor pads look detached”"],
    ["0x20", "Card working", "Always clear on this build"],
    ["0x40", "Clock set by the phone", "“Wearable clock not set yet”"],
], [50, 155, COL - 205])
call("<b>Units are checked, not assumed.</b> A skin temperature sent in Fahrenheit lands outside 20–42 "
     "and is dropped rather than read as a fever. EMG counts are refused unless the line says they are "
     "12-bit. A missing field means “not measured” and never becomes a zero.")
p("Phone to wearable: <b>T:&lt;epoch ms&gt;</b> sets the clock on every connection. ACK and STOP belong "
  "to the store-and-forward protocol and are ignored by this firmware. Framing is newline-terminated "
  "ASCII, and notifications split or merged by Bluetooth are reassembled before parsing.")

# ── 05 The app ──────────────────────────────────────────────────────────────
page("05  /  THE APPLICATION", "Where everything is decided",
     "Kotlin and Jetpack Compose, package com.gone.ai, with the detection engine kept free of Android.")
table(["Package", "What it holds"], [
    ["health/source/ · ble/", "Parsing, validation, 5-second aggregation, the Bluetooth link and its watchdog"],
    ["health/service/", "Foreground monitoring service, the ordered pipeline, preferences, reminders"],
    ["health/detect/ · domain/", "The 13 rules, thresholds, baselines and risk scores — plain Kotlin, no Android"],
    ["health/explain/", "Written explanations, tiers, and validation of any model rewrite"],
    ["health/session/", "Sessions, report building, PDF layout and writing"],
    ["health/sos/", "Automatic emergency texting and calling"],
    ["health/emergency/ · nfc/", "Consented snapshot, NFC tag writing, QR codes"],
    ["health/environment/", "Optional city air quality"],
    ["ai/ · chat/", "Shared model runtime, prompts, context budgets"],
    ["ocr/ · pdf/ · circle/", "Text from images and documents, screen capture tools"],
    ["data/library/", "Room database and the Memory Vault"],
    ["voice/", "Speech in and out, with TalkBack labels throughout the app"],
], [150, COL - 150])
h("The 13 rules")
table(["Rule", "Watches for"], [
    ["spo2.critical · spo2.sustained", "Critically low oxygen, and a sustained low pattern"],
    ["hr.high · hr.low", "Sustained fast or slow heart rate"],
    ["temp.fever", "Raised core temperature, when a core sensor exists"],
    ["env.heatStress · env.dehydrationRisk", "Combined heat and dehydration indicators"],
    ["resp.distress · cardio.strain", "Combined respiratory and cardiovascular patterns"],
    ["motion.fall", "A hard impact, with supporting conditions"],
    ["wellness.fatigue · emg.sustainedHigh", "A fatigue pattern; muscles held tense"],
    ["baseline.deviation", "A departure from this person's own baseline"],
], [190, COL - 190])
p("Skin temperature never feeds a fever rule. Risk scores organise findings; they are not probabilities "
  "of illness. The fall threshold is <b>5.0 g</b>, raised from 2.5 g so ordinary knocks do not occupy the "
  "cooldown before a harder impact — an engineering setting, not a validated fall classifier.", "small")

# ── 06 Alerts, SOS, reports ─────────────────────────────────────────────────
page("06  /  ALERTS AND REPORTS", "Telling someone, and writing it down",
     "An alert always arrives with usable wording. An SOS only ever follows a confirmed live reading.")
h("Automatic SOS")
table(["Question", "Answer"], [
    ["What sends one?", "Every confirmed live anomaly from the wearable, including low and moderate ones. Motion additionally needs a finite impact of at least 5.0 g"],
    ["What never sends one?", "Simulated readings, replayed history, air quality"],
    ["Is there a countdown?", "No. Alerts raised within 1.5 s are gathered into one message"],
    ["Does it call as well?", "Optional, separately permissioned; the text goes first"],
    ["What does it need?", "Opt-in, a saved contact, SMS permission, a working SIM and signal"],
    ["What does it prove?", "Nothing about receipt: a successful send is not proof the contact read it"],
], [140, COL - 140])
h("Sessions and the PDF report")
p("A session records a stretch of monitoring. Ending it builds a report from what was actually stored: "
  "duration, reading count, the range of each channel, five representative points, charts, and the "
  "alerts raised. It states plainly when a channel received nothing, and counts gaps longer than a minute.")
call("<b>Checked end to end on the hardware.</b> A 19-minute session on the real wearable produced a "
     "two-page PDF whose every figure matched the stored readings: skin temperature 29.8 – 32.5 °C, "
     "hardest movement 9.5 g, EMG average 422 and peak 4095, one 85-second gap, one alert.")
p("The last section of the report is the local model's summary of the deterministic observations above "
  "it, labelled as such. One limit found in testing: the validation forbids new numbers, advice and "
  "diagnoses, but does not catch a statement that is simply wrong in words.", "small")

# ── 07 Local model ──────────────────────────────────────────────────────────
page("07  /  LOCAL INTELLIGENCE", "A language model that cannot cause harm",
     "It runs on the phone's CPU, it is shared between every tool, and it is never allowed to decide anything.")
table(["Item", "Value"], [
    ["Model", "Qwen2.5-1.5B-Instruct, GGUF Q4_K_M, 1,117,320,736 bytes"],
    ["Runtime", "Vendored llama.cpp / ggml through a JNI bridge, CPU only, 4 threads"],
    ["Context", "4,096 tokens — 3,568 for the prompt, 512 reserved for the answer"],
    ["Sharing", "One shared instance; chat and tools take leases and requests are serialised"],
    ["Release", "Freed 60 seconds after the last lease closes"],
    ["Delivery", "Bundled in the APK, copied to app-private storage on first use"],
    ["Recorded speed", "28.9 tokens/s prompt processing, once, on a Samsung SM-S721B"],
], [110, COL - 110])
p("Bundling the model is why the release APK is about <b>1.13 GiB</b>, of which the model is roughly "
  "94 %. It buys a first run with no download, on a phone with no connection.")
h("What the model is allowed to do")
bullets([
    "<b>Health explanations:</b> reword an explanation that already exists. The result is validated — no new numbers, no advice, no diagnoses — and rejected text leaves the original in place.",
    "<b>Documents and tools:</b> summarise, explain or build a quiz from text the wearer chose.",
    "<b>Assist:</b> general chat, which is <b>not</b> covered by that strict validation and can be wrong.",
])
call("<b>It never decides.</b> The model is not asked whether a reading is dangerous, never sets "
     "severity and never chooses what the wearer should do. Every one of those decisions belongs to a "
     "named rule with a reviewable threshold.")

# ── 08 Prototype 1 ──────────────────────────────────────────────────────────
page("08  /  HARDWARE · ONE OF THREE", "Phase 1 — the strap that exists",
     "The prototype the firmware actually runs on, reconstructed in Blender from eleven photographs.")
picture("docs/assets/phase1-worn.png",
        "Blender reconstruction of the worn Phase-1 strap: inverted ESP32-S3 board, exposed headers, "
        "prototype wiring, four axial resistors and the temperature probe.")
table(["Item", "Detail"], [
    ["Scenes", "01 Worn Phase-1 prototype · 02 Opened strap, sensor inspection"],
    ["Modules modelled", "ESP32-S3 board, Muscle BioAmp Patchy, MAX30100/30102 breakout, MPU6050 GY-521, temperature probe"],
    ["Size", "1,684 objects, 11 packed reference photographs"],
    ["Renders", "phase1-worn, phase1-hardware, phase1-inside, phase1-opened"],
    ["Scale cue", "Nominal 2.54 mm header pitch"],
], [110, COL - 110])
p("<b>Limits:</b> photo-derived, not a scan. Strap dimensions, hidden mounting, resistor values and "
  "obscured routing are approximations, and the closed fist is modelled approximately because the "
  "photographs do not show it. The digital wiring is not a verified electrical netlist.", "small")

# ── 09 Prototype 2 ──────────────────────────────────────────────────────────
page("09  /  HARDWARE · TWO OF THREE", "Later phase — the custom PCB",
     "What the strap could become as one integrated board, rather than development boards stacked together.")
picture("hardware/gone-pcb-3d/assembled.png",
        "Rev B assembled: pouch cell on its removable carrier above the electronics, away from the "
        "skin-facing sensors.", width=430)
table(["Item", "Detail"], [
    ["Board", "44 × 64 × 1 mm, six mounting holes; one Blender unit = 1 mm"],
    ["Contents", "905 objects in 12 collections: laminate and plating, ESP32-S3-WROOM-1-N16R8, MPU6050, microSD socket, EMG front end, charger and buck-boost, USB-C and switches, skin-side MAX30102, DS18B20 thermal island, routing relief, optional battery, studio"],
    ["Rev B added", "Pouch-cell assembly and removable carrier, three editable harness curves, TP4056 charger with separate DW01A / FS8205A protection, exposed FR-4 edges and copper lands, a corrected capacitor overlap"],
    ["Renders", "Eight 1800 × 1800 Cycles views, from assembled down to sensor macro"],
    ["Rev A kept", "G-one-PCB-before-enhancement.blend, unchanged"],
], [95, COL - 95])
call("<b>This model must not be used to order PCB fabrication.</b> Visible copper is surface detail, not "
     "routed nets. The cell envelope is provisional, connector polarity and the thermistor circuit need "
     "checking, and the EMG analog design is unresolved. It also shows a MAX30102 while the firmware "
     "drives a MAX30100 — that substitution needs a different driver.")

# ── 10 Prototype 3 ──────────────────────────────────────────────────────────
page("10  /  HARDWARE · THREE OF THREE", "Final phase — the Jeevan Core patch",
     "A reusable body patch built as eleven mechanical layers that pull apart, with a working web viewer.")
picture("hardware/jeevan-core/core-exploded.png",
        "The eleven layers exploded: enclosure and light guide at the top, electronics and battery in "
        "the middle, sensor flex, electrodes, adhesive and peel liner at the skin side.", width=430)
table(["#", "Layer", "What it is"], [
    ["01", "Protective enclosure", "Pearl polymer shell, identity panel, USB-C opening"],
    ["02", "Status light guide", "Carries the PCB LED to the front indicator"],
    ["03", "Perimeter gasket", "Elastomer seal — sealing performance untested"],
    ["04", "Main electronics", "ESP32-S3 N16R8, ECG and EMG front ends, MPU6050, BME280, charging, microSD"],
    ["05", "Rechargeable cell", "Protected pouch envelope; capacity awaits cell selection"],
    ["06", "Inner support frame", "Battery pocket, board supports, screw bosses, flex passage"],
    ["07", "Flexible sensor interface", "Polyimide substrate, optical and temperature sensing"],
    ["08", "Skin-side spacer", "Silicone carrier with optical and electrode apertures"],
    ["09", "Electrodes and optics", "Two ECG, two EMG, one BioZ and one temperature contact"],
    ["10", "Replaceable adhesive", "Die-cut layer; material qualification pending"],
    ["11", "Peel-away liner", "Protects the adhesive before use"],
], [26, 120, COL - 146])
p("Provisional 30 × 50 × ~13 mm body over a 24 × 40 × 1 mm board. Frame 1 is assembled, frame 121 "
  "exploded, exported as an <b>Explode</b> animation clip. The React and three.js viewer in web/ offers "
  "drag, a slider, a layer index, keyboard and screen-reader support, and a full text fallback if WebGL fails.")
p("<b>Limits:</b> the illustration's 8 mm thickness and 28 × 24 mm PCB are not asserted. Electrode "
  "spacing follows the concept image and is not established as workable for ECG, EMG or BioZ. Battery "
  "life, ingress protection, biocompatibility and adhesive lifetime are not established, and the web "
  "copy deliberately avoids the unvalidated “5–7 day” and “IP67” claims.", "small")

# ── 11 Every 3D file ────────────────────────────────────────────────────────
page("11  /  3D ASSETS", "Every file, listed",
     "Three models, each with an editable Blender source, a self-contained GLB, renders, build scripts and validation reports.")
h("Phase 1 strap")
table(["File", "Size", "Detail"], [
    ["G-one-Phase-1-Strap.blend", "6.7 MB", "Editable source, both scenes, 11 packed photos"],
    ["G-one-Phase-1-Strap.glb", "36.3 MB", "1,089 meshes · 1,483,516 triangles · 28 materials"],
    ["G-one-Phase-1-Strap-web.glb", "20.6 MB", "9 groups · 694,586 triangles, simplified for the web"],
    ["4 final renders + 2 previews", "~1–3 MB each", "worn, hardware, inside, opened"],
    ["7 scripts", "1–23 KB", "build → refine → finish → final check → render, plus two GLB exporters"],
    ["4 JSON reports", "< 1 KB", "model, geometry audit, both GLB exports"],
], [175, 70, COL - 245])
h("Custom PCB")
table(["File", "Size", "Detail"], [
    ["G-one-PCB.blend", "2.9 MB", "Rev B, 905 objects in 12 collections"],
    ["G-one-PCB.glb", "15.5 MB", "1,157 meshes · 324,162 triangles · 24 materials"],
    ["G-one-PCB-before-enhancement.blend", "2.8 MB", "Preserved Rev A"],
    ["G-one-PCB-before-enhancement.glb", "11.1 MB", "892 meshes · 233,574 triangles · 15 materials"],
    ["8 renders + 1 reference", "~2.3–3.9 MB each", "assembled, isometric, top, bottom, side, sensors, esp32-power, detail"],
    ["8 scripts", "1–22 KB", "Rev A builder, Rev B enhancement chain, cameras, exporter, audits"],
    ["5 JSON reports", "< 1 KB", "inventory, validation, enhancement, mechanical audit, GLB export"],
], [175, 70, COL - 245])
h("Jeevan Core")
table(["File", "Size", "Detail"], [
    ["Jeevan-Core.blend", "483 KB", "Editable source, three cameras, layer animation"],
    ["Jeevan-Core.glb", "18.6 MB", "11 named layer parents · 333,846 triangles · Explode clip"],
    ["3 renders", "~2.2 MB each", "assembled, exploded, skin side"],
    ["layers.json", "2 KB", "Stable IDs, titles, descriptions, explode distances"],
    ["4 scripts", "1–17 KB", "build, refine, export, render"],
    ["2 JSON reports", "< 8 KB", "export report and glTF validation"],
    ["web/", "—", "React viewer: CoreExplorer.jsx, layers.json, style.css"],
], [175, 70, COL - 245])
call("<b>Common to every GLB:</b> metres, glTF Y-up, self-contained buffers, no Draco or Meshopt decoder "
     "needed, portable colour/metallic/roughness materials. Each export was re-imported to confirm mesh "
     "counts and bounds, and each source .blend's SHA-256 was checked unchanged afterwards.")
p("Rebuilding: the construction scripts start a new scene. Never run them over a hand-edited final "
  "file — open the saved .blend, or follow the chain documented in that model's README.", "small")

# ── 12 Tools and privacy ────────────────────────────────────────────────────
page("12  /  TOOLS AND PRIVACY", "Testing without hardware, and what leaves the phone",
     "Two tools stand in for the missing half of the link; every outbound path is listed.")
table(["Tool", "What it does"], [
    ["tools/wearable-emulator/emulate.py", "Plays the wearable from a PC. --selftest runs the whole sync protocol against a simulated phone on a virtual clock: 10 scenarios, including going out of range, the app being killed mid-replay, a restart before the clock is set, and a flaky link. No hardware needed"],
    ["tools/wearable-check/check_wearable.py", "The opposite: the PC acts as the phone against the real board, checking every field against the app's own rules and comparing each line received over Bluetooth with the line the board printed on USB"],
], [175, COL - 175])
p("The emulator's self-test found a real protocol bug during development: a status line sent before the "
  "records it described meant catch-up could never end.", "small")
h("What can leave the phone")
table(["Path", "Local by default", "What can leave"], [
    ["Detection and the language model", "Yes", "Nothing"],
    ["OCR, Vault, sessions and reports", "Yes", "Only what the wearer exports"],
    ["Sleep and stress journal", "Yes", "Nothing"],
    ["Air quality, if switched on", "No", "City or centre coordinates; the request reveals an IP"],
    ["Emergency snapshot, if switched on", "No", "The fields the wearer selected"],
    ["Automatic SOS, if switched on", "Carrier", "SMS and optional call to the saved contact"],
    ["Voice recognition", "Depends", "May use online recognition if no offline pack exists"],
], [175, 85, COL - 260])
call("<b>Say it accurately.</b> The database is app-private SQLite and is <b>not encrypted</b>. Cloud "
     "backup is switched off, so readings, chats and reports are not copied to a cloud account. It is "
     "wrong to say nothing ever leaves the phone in every configuration.")

# ── 13 Evidence ─────────────────────────────────────────────────────────────
page("13  /  EVIDENCE", "What is proven, and what is not",
     "Software checks, bench observations and clinical validation are three different things.")
h("Measured or observed")
table(["Evidence", "Result"], [
    ["JVM unit tests", "590 tests in 66 classes, 0 failures"],
    ["Android lint", "0 errors"],
    ["Instrumented tests", "19 of 19 on a Samsung SM-S721B"],
    ["Firmware build", "Compiles with no warnings from the sketch; about 21 % of the 3 MB app partition"],
    ["Protocol self-test", "10 of 10 scenarios"],
    ["On the phone and board", "Connects and sets the clock within seconds; 112 stored values checked against the board's own log with none disagreeing; Stop reaches the board in about 2.5 s; a 9.47 g jolt raised a fall alert; a 19-minute session produced a PDF matching the stored readings"],
], [140, COL - 140])
h("Not established")
bullets([
    "<b>A heart rate from the wearable has never reached the app.</b> The pulse sensor's bus sits at 1.8 V and the 2.2 kΩ pull-ups are not yet fitted.",
    "<b>Board power is unstable:</b> repeated brown-outs, a hot board and sensors that vary from boot to boot point to a wiring fault rather than firmware.",
    "A real SOS text and call have not been sent from the phone.",
    "Fall sensitivity, false-positive rates, battery life, sustained generation speed, memory use and thermal behaviour are unmeasured.",
    "The PCB and Jeevan Core designs have had no electrical review or fabrication.",
])

# ── 14 Website and next steps ───────────────────────────────────────────────
page("14  /  PRESENTATION AND NEXT STEPS", "Website claims, and the road ahead",
     "The public site presents the intended product; this document describes the built one.")
picture("docs/assets/website-home.png", "The public site at g--one.vercel.app, captured 22 September 2026.", width=430)
table(["The website says", "This build actually does"], [
    ["250 Hz sampling", "Two readings a second over Bluetooth; one stored reading every five seconds"],
    ["72-hour offline buffer", "Live only — nothing is stored on the wearable; out-of-range readings are lost"],
    ["Encrypted local SQLite", "App-private SQLite, not encrypted"],
    ["HRV, arrhythmia triggers, siren, multi-contact SOS", "Heart rate, SpO₂, skin temperature, motion and EMG; 13 rules; SMS and an optional call to one saved contact"],
], [175, COL - 175])
h("Next meaningful steps")
table(["Limitation", "Next step"], [
    ["Pulse sensor never delivers a rate", "Fit the 2.2 kΩ pull-ups, then confirm a rate against a manual count"],
    ["Unstable board power", "Find the heat source, then decoupling and firm power rails"],
    ["Live-only transport", "Implement and verify buffering and gap recovery on the board"],
    ["Engineering thresholds", "Evaluate sensitivity and false positives with proper oversight"],
    ["1.13 GiB APK", "Pin the model's provenance; weigh smaller models against quality"],
    ["Unencrypted database", "Evaluate encryption, key lifecycle and migration"],
    ["Concept hardware", "Electrical review, tolerances, fabrication and assembly testing"],
], [175, COL - 175])
p("No claim is made for certification, guaranteed fall detection, diagnostic accuracy, waterproofing, "
  "multi-day battery life or universal device compatibility. G-one records sensor readings and explains "
  "them; it does not diagnose.", "small")
p("<b>Source basis:</b> the repository code as committed, docs/G-ONE-COMPLETE-DESCRIPTION.md, "
  "VERIFICATION.md, the three hardware READMEs and their export reports, and the public website as "
  "captured on 22 September 2026.", "small")


def footer(c, d):
    c.saveState()
    c.setFillColor(gold)
    c.rect(0, PAGE_H - 8, PAGE_W, 8, fill=1, stroke=0)
    c.setStrokeColor(line)
    c.line(48, 42, PAGE_W - 48, 42)
    c.setFont("UI", 8)
    c.setFillColor(muted)
    c.drawString(48, 28, "G-one  /  Architecture and hardware  /  September 2026")
    c.drawRightString(PAGE_W - 48, 28, f"{d.page:02d}")
    c.restoreState()


doc = SimpleDocTemplate(str(out), pagesize=(PAGE_W, PAGE_H), rightMargin=48, leftMargin=48,
                        topMargin=40, bottomMargin=56,
                        title="G-one | Architecture and Hardware",
                        author="G-one",
                        subject="How G-one is built: wearable, protocol, Android application and three hardware prototypes")
doc.build(story, onFirstPage=footer, onLaterPages=footer)
print(out, out.stat().st_size, "bytes")
