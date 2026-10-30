"""
Draws the G-one field-trial form: a fillable, printable A4 PDF for a pharmacy or medical retail
site to record what was tested on its premises, what it saw, and to sign and stamp it.

    python tools/forms/field_trial_form.py

Writes docs/field-trial/G-one-Field-Trial-Form.pdf. The "developer's reference" figures are the
measured lab results in docs/MODELS.md; every trial result is left blank for the site to fill in.
"""

import os

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfgen import canvas
from reportlab.platypus import Paragraph

OUT = "docs/field-trial/G-one-Field-Trial-Form.pdf"
W, H = A4
M = 40
CW = W - 2 * M
PAGES = 3

AMBER = colors.HexColor("#986000")
INK = colors.HexColor("#2B2B2B")
MUTED = colors.HexColor("#6B6B63")
RULE = colors.HexColor("#B9B3A6")
TINT = colors.HexColor("#F6F1E6")

BODY = ParagraphStyle("body", fontName="Helvetica", fontSize=8.2, leading=10.6, textColor=INK)
CELL = ParagraphStyle("cell", fontName="Helvetica", fontSize=7.4, leading=9, textColor=INK)
CELL_B = ParagraphStyle("cellb", parent=CELL, fontName="Helvetica-Bold")
CELL_M = ParagraphStyle("cellm", parent=CELL, textColor=MUTED, fontSize=7)
HEAD = ParagraphStyle("head", parent=CELL, fontName="Helvetica-Bold", fontSize=7, textColor=colors.white)


class Form:
    def __init__(self, path):
        self.c = canvas.Canvas(path, pagesize=A4)
        self.c.setTitle("G-one Field Trial: Evaluation and Acceptance Form")
        self.c.setAuthor("G-one")
        self.c.setSubject("On-site evaluation of G-one at a pharmacy or medical retail establishment")
        self.page = 0
        self.n = 0

    # ---------- page furniture ----------

    def new_page(self):
        if self.page:
            self.c.showPage()
        self.page += 1
        c = self.c
        c.setFillColor(AMBER)
        c.rect(0, H - 6, W, 6, stroke=0, fill=1)
        c.setFillColor(INK)
        c.setFont("Helvetica-Bold", 9)
        c.drawString(M, H - 28, "G-ONE")
        c.setFont("Helvetica", 7.5)
        c.setFillColor(MUTED)
        c.drawString(M + 34, H - 28, "Wearable health monitoring and emergency alerts")
        c.drawRightString(W - M - 120, H - 28, "Form ref. no.")
        self.text_field("ref_no_p%d" % self.page, W - M - 115, H - 32, 115, value_hint="ref")
        c.setStrokeColor(RULE)
        c.setLineWidth(0.6)
        c.line(M, H - 38, W - M, H - 38)
        # footer
        c.setFont("Helvetica", 6.8)
        c.setFillColor(MUTED)
        c.drawString(M, 24, "G-one field trial: evaluation and acceptance form  |  "
                            "G-one is a research prototype, not a certified medical device.")
        c.drawRightString(W - M, 24, "Page %d of %d" % (self.page, PAGES))
        c.line(M, 34, W - M, 34)
        return H - 52

    def section(self, y, letter, title, note=None):
        c = self.c
        c.setFillColor(INK)
        c.rect(M, y - 15, CW, 15, stroke=0, fill=1)
        c.setFillColor(AMBER)
        c.rect(M, y - 15, 20, 15, stroke=0, fill=1)
        c.setFillColor(colors.white)
        c.setFont("Helvetica-Bold", 8.5)
        c.drawCentredString(M + 10, y - 11, letter)
        c.drawString(M + 27, y - 11, title.upper())
        if note:
            c.setFont("Helvetica", 7)
            c.drawRightString(W - M - 6, y - 11, note)
        return y - 29

    def para(self, y, text, style=BODY, x=M, width=CW):
        p = Paragraph(text, style)
        _, h = p.wrap(width, 1000)
        p.drawOn(self.c, x, y - h)
        return y - h

    # ---------- fields ----------

    def _name(self, base):
        self.n += 1
        return "%s_%d" % (base, self.n)

    def text_field(self, name, x, y, w, h=13, multiline=False, value_hint=None, border="underlined"):
        self.c.acroForm.textfield(
            name=self._name(name), tooltip=name.replace("_", " "), x=x, y=y, width=w, height=h,
            borderStyle=border, borderColor=RULE, borderWidth=0.8, fillColor=colors.white,
            textColor=INK, fontName="Helvetica", fontSize=7.5 if multiline else 8.5,
            fieldFlags="multiline" if multiline else "", forceBorder=True)

    def line_field(self, x, y, w, label, name=None, label_w=None):
        """A label followed by an underlined fillable field, on one baseline."""
        c = self.c
        c.setFont("Helvetica", 7.6)
        c.setFillColor(MUTED)
        c.drawString(x, y, label)
        lw = label_w if label_w is not None else c.stringWidth(label, "Helvetica", 7.6) + 5
        self.text_field(name or label.lower().replace(" ", "_")[:24], x + lw, y - 3, w - lw)

    def row(self, y, items, gap=12):
        """items: list of (label, share). Draws them side by side on one line."""
        total = sum(s for _, s in items)
        x = M
        avail = CW - gap * (len(items) - 1)
        for label, share in items:
            w = avail * share / total
            self.line_field(x, y, w, label)
            x += w + gap
        return y - 21

    def checkbox(self, x, y, label, name, size=9, style=BODY, width=None):
        self.c.acroForm.checkbox(
            name=self._name(name), tooltip=label, x=x, y=y - 1, size=size, buttonStyle="check",
            borderColor=INK, borderWidth=0.8, fillColor=colors.white, textColor=INK, forceBorder=True)
        if width:
            p = Paragraph(label, style)
            _, h = p.wrap(width, 200)
            p.drawOn(self.c, x + size + 5, y + size - h + 1)
            return h
        self.c.setFont("Helvetica", 7.8)
        self.c.setFillColor(INK)
        self.c.drawString(x + size + 4, y + 1, label)
        return size

    def box(self, x, y_top, w, h, label=None, name="box"):
        c = self.c
        if label:
            c.setFont("Helvetica-Bold", 7.6)
            c.setFillColor(INK)
            c.drawString(x, y_top, label)
            y_top -= 5
        self.text_field(name, x, y_top - h, w, h, multiline=True, border="solid")
        return y_top - h - 12

    # ---------- tables ----------

    def table(self, y, cols, header, rows, row_h, fill_cols, name):
        """cols: widths. rows: list of cell contents (str or None for a fillable cell)."""
        c = self.c
        head = [Paragraph(t, HEAD) for t in header]
        hh = max(p.wrap(w - 6, 100)[1] for p, w in zip(head, cols)) + 8
        c.setFillColor(INK)
        c.rect(M, y - hh, CW, hh, stroke=0, fill=1)
        x = M
        for p, w in zip(head, cols):
            p.drawOn(c, x + 3, y - 4 - p.wrap(w - 6, 100)[1])
            x += w
        y -= hh
        for i, cells in enumerate(rows):
            paras = [Paragraph(t, s) if t else None for t, s in cells]
            h = max([row_h] + [p.wrap(w - 6, 200)[1] + 8 for p, w in zip(paras, cols) if p])
            if i % 2 == 1:
                c.setFillColor(TINT)
                c.rect(M, y - h, CW, h, stroke=0, fill=1)
            x = M
            for j, (p, w) in enumerate(zip(paras, cols)):
                if p:
                    p.drawOn(c, x + 3, y - 4 - p.wrap(w - 6, 200)[1])
                elif j in fill_cols:
                    kind = fill_cols[j]
                    if kind == "check":
                        c.acroForm.checkbox(
                            name=self._name("%s_r%d_c%d" % (name, i + 1, j)), x=x + w / 2 - 5, y=y - h / 2 - 5,
                            size=10, buttonStyle="check", borderColor=INK, borderWidth=0.8,
                            fillColor=colors.white, textColor=INK, forceBorder=True)
                    else:
                        self.text_field("%s_r%d_c%d" % (name, i + 1, j), x + 2, y - h + 2, w - 4, h - 4,
                                        multiline=(kind == "multi"), border="solid")
                x += w
            y -= h
            c.setStrokeColor(RULE)
            c.setLineWidth(0.4)
            c.line(M, y, W - M, y)
        # vertical rules
        top = y
        return y


def page_one(f):
    y = f.new_page()
    c = f.c
    c.setFillColor(INK)
    c.setFont("Helvetica-Bold", 17)
    c.drawString(M, y - 14, "Field Trial: Evaluation and Acceptance Form")
    c.setFont("Helvetica", 9)
    c.setFillColor(MUTED)
    c.drawString(M, y - 28, "On-site testing of G-one at a pharmacy or medical retail establishment, "
                            "by its staff, its pharmacist and its customers")
    y -= 40
    y = f.para(y, (
        "This form records an on-site trial of <b>G-one</b>, a wrist strap and Android app that watches movement "
        "and vital signs, explains them in plain language, and texts an emergency contact after a fall. Fill in "
        "<b>what was actually done and seen</b>. Where a capability was not tried, tick <i>Not tested</i> or leave the "
        "row blank. The <b>developer's reference</b> column on page 2 gives the G-one team's own laboratory results on "
        "public datasets, for comparison only; it is not a result of this trial.")) - 10

    y = f.section(y, "A", "Establishment details")
    y = f.row(y, [("Name of establishment", 1)])
    c.setFont("Helvetica", 7.6)
    c.setFillColor(MUTED)
    c.drawString(M, y, "Type")
    x = M + 30
    for label in ("Pharmacy / chemist", "Medical store", "Surgical & medical equipment retailer"):
        f.checkbox(x, y - 1, label, "type")
        x += c.stringWidth(label, "Helvetica", 7.8) + 26
    f.checkbox(x, y - 1, "Other:", "type_other")
    f.text_field("type_other_text", x + 42, y - 3, W - M - x - 42)
    y -= 21
    y = f.row(y, [("Drug licence / registration no.", 1.3), ("GSTIN (optional)", 1)])
    y = f.row(y, [("Address", 1)])
    y = f.row(y, [("City / town", 1.2), ("District", 1), ("State", 1), ("PIN", 0.6)])
    y = f.row(y, [("Contact person", 1.2), ("Designation", 1)])
    y = f.row(y, [("Phone", 1), ("Email", 1.4)])
    y = f.row(y, [("Pharmacist-in-charge", 1.2), ("Pharmacy Council registration no.", 1)]) - 4

    y = f.section(y, "B", "Trial details")
    y = f.row(y, [("Trial date: from", 1), ("to", 0.8), ("Start time", 0.8), ("End time", 0.8)])
    y = f.row(y, [("G-one app version", 1), ("Phone model(s) used", 1.4)])
    y = f.row(y, [("Strap ID / serial no.", 1), ("Strap firmware version", 1)])
    y = f.row(y, [("G-one evaluator(s) present", 1)])
    y = f.row(y, [("Where on the premises", 1), ("Total hours of testing", 0.7)]) - 4

    y = f.section(y, "C", "Who took part")
    y = f.table(
        y, [150, 80, CW - 230],
        ["Group", "Number of people", "How they took part (e.g. wore the strap, ran the app, watched a demonstration)"],
        [[("Establishment staff", CELL_B), (None, None), (None, None)],
         [("Pharmacist(s)", CELL_B), (None, None), (None, None)],
         [("Customers / walk-in volunteers", CELL_B), (None, None), (None, None)],
         [("Others (state)", CELL_B), (None, None), (None, None)]],
        row_h=20, fill_cols={1: "text", 2: "text"}, name="participants") - 9
    for label in (
            "Every participant was told what the trial is and agreed before taking part.",
            "Customers' names and health readings were not kept after the trial, or kept only with their written consent.",
            "Emergencies were simulated (staged falls, demo mode); no real emergency contact was messaged without "
            "that person's agreement.",
            "No participant was given medical advice on the basis of G-one readings."):
        h = f.checkbox(M, y - 8, label, "consent", width=CW - 20)
        y -= max(h, 9) + 6
    return y


CAPABILITIES = [
    ("Live readings from the strap over Bluetooth (movement, skin temperature)",
     "Designed for 2 readings a second"),
    ("Fall alert from the strap (impact above 5 g)",
     "Rule on lab recordings: 50.7% of falls caught; 89.6% of daily activities with no alarm"),
    ("Fall model, replayed in the app's Model Lab",
     "96.2% of falls caught, 95.9% of daily activities with no alarm (people held out); "
     "64.8% / 98.1% on a dataset it never saw"),
    ("\"I'm OK\" cancel window before the SOS text",
     "20 s by default (10, 20 or 30 s in Settings)"),
    ("SOS text message to the emergency contact",
     "Sent after the window; never sent for a model-only or city-weather alert"),
    ("Heart rate in context model",
     "2.24 false alerts per 24 h vs 51.58 for a fixed rule (PAMAP2). Strap pulse sensor not yet active: "
     "shown with recorded data"),
    ("Sleep or awake model",
     "91.9% of 30-s periods right; Cohen's kappa 0.46 vs 0.37 for a movement threshold"),
    ("On-phone AI assistant (works offline)",
     "Rewords alerts in plain language; does not decide them"),
    ("Official disaster alerts for the district (NDMA SACHET)",
     "District matched on the phone; no location is sent"),
    ("City weather in the heat rules, and India AQI",
     "City-based warnings capped at moderate; never an SOS"),
    ("Health report PDF and NFC emergency card", "-"),
    ("Model speed on this phone (Model Lab)",
     "9-27 microseconds per run on the CPU of a Samsung SM-S721B"),
]


def page_two(f):
    y = f.new_page()
    y = f.section(y, "D", "Capabilities tested", note="Reference figures: G-one lab results, not this trial")
    y = f.para(y, (
        "For each capability: tick <b>Tested</b> or <b>Not tested</b>, write how many times it was tried and how many "
        "times it behaved as described, and note anything seen. A staged fall means a safe, deliberate jolt or a "
        "replayed recording, never a real fall."), CELL_M) - 6
    cols = [16, 128, 136, 34, 34, 36, 40, CW - 424]
    rows = []
    for i, (name, ref) in enumerate(CAPABILITIES, 1):
        rows.append([("%d" % i, CELL_B), (name, CELL_B), (ref, CELL_M),
                     (None, None), (None, None), (None, None), (None, None), (None, None)])
    y = f.table(y, cols,
                ["#", "Capability", "Developer's reference (lab, not this trial)", "Tested", "Not tested",
                 "Times tried", "Times it worked", "What was seen / remarks"],
                rows, row_h=33, fill_cols={3: "check", 4: "check", 5: "text", 6: "text", 7: "multi"},
                name="capability") - 14

    y = f.section(y, "E", "Ratings by group", note="1 = poor, 3 = acceptable, 5 = excellent; leave blank if not asked")
    qs = ["Easy to put on and set up",
          "Readings and alerts were easy to understand",
          "Fall alert and the \"I'm OK\" cancel worked as shown",
          "Assistant explanations were clear and in plain language",
          "Comfortable to wear for the time tested",
          "Would suggest it for customers who live alone or are elderly"]
    y = f.table(y, [CW - 270, 90, 90, 90],
                ["Statement", "Staff (average)", "Pharmacist", "Customers (average)"],
                [[(q, CELL), (None, None), (None, None), (None, None)] for q in qs],
                row_h=22, fill_cols={1: "text", 2: "text", 3: "text"}, name="rating")
    return y


def page_three(f):
    y = f.new_page()
    y = f.section(y, "F", "Observations")
    y = f.box(M, y, CW, 78, "Problems, failures or false alarms seen (what happened, when, how often)", "problems")
    y = f.box(M, y, CW, 62, "Comments and suggestions from staff, the pharmacist and customers", "suggestions") - 2

    y = f.section(y, "G", "Outcome of the trial")
    for label in ("G-one performed as described above and is acceptable for continued use or trial at this establishment.",
                  "Acceptable, with the conditions or changes written below.",
                  "Not acceptable at this stage (reasons below)."):
        h = f.checkbox(M, y - 8, label, "outcome", width=CW - 20)
        y -= max(h, 9) + 7
    y = f.box(M, y - 2, CW, 40, "Conditions, changes needed or reasons", "conditions") - 2

    y = f.section(y, "H", "Declaration and signatures")
    y = f.para(y, (
        "We confirm that the trial described in this form took place at our premises on the dates stated, and that "
        "the results, ratings and remarks recorded here are what we and the participants observed. We understand that "
        "G-one is a research prototype: it is <b>not a certified medical device</b>, it does not diagnose or treat any "
        "condition, and this form is a record of a user trial, <b>not</b> a medical, regulatory or CDSCO approval."),
        BODY) - 12

    c = f.c
    left_w = CW - 165
    top = y
    c.setFont("Helvetica-Bold", 8)
    c.setFillColor(INK)
    c.drawString(M, y, "For the establishment (authorised signatory)")
    y -= 18
    for label in ("Name", "Designation", "Date", "Place"):
        f.line_field(M, y, left_w, label, label_w=62)
        y -= 20
    c.setFont("Helvetica", 7.6)
    c.setFillColor(MUTED)
    c.drawString(M, y - 26, "Signature")
    c.setStrokeColor(INK)
    c.setLineWidth(0.8)
    c.line(M + 62, y - 28, M + left_w, y - 28)
    y -= 46

    # stamp box
    sx, sw, sh = W - M - 150, 150, 132
    c.setStrokeColor(AMBER)
    c.setLineWidth(1)
    c.setDash(4, 3)
    c.rect(sx, top - sh + 8, sw, sh, stroke=1, fill=0)
    c.setDash()
    c.setFillColor(AMBER)
    c.setFont("Helvetica-Bold", 8)
    c.drawCentredString(sx + sw / 2, top - 6, "ESTABLISHMENT STAMP / SEAL")
    c.setFillColor(MUTED)
    c.setFont("Helvetica", 6.8)
    c.drawCentredString(sx + sw / 2, top - sh + 15, "Stamp across the box edge")

    # two lower signature blocks
    y -= 4
    half = (CW - 24) / 2
    for i, (title, fields) in enumerate((
            ("Pharmacist-in-charge", ("Name", "Registration no.", "Date")),
            ("G-one evaluator", ("Name", "Role", "Date")))):
        x = M + i * (half + 24)
        yy = y
        c.setFont("Helvetica-Bold", 8)
        c.setFillColor(INK)
        c.drawString(x, yy, title)
        yy -= 18
        for label in fields:
            f.line_field(x, yy, half, label, label_w=70)
            yy -= 20
        c.setFont("Helvetica", 7.6)
        c.setFillColor(MUTED)
        c.drawString(x, yy - 22, "Signature")
        c.setStrokeColor(INK)
        c.setLineWidth(0.8)
        c.line(x + 70, yy - 24, x + half, yy - 24)
    return yy - 40


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    f = Form(OUT)
    page_one(f)
    page_two(f)
    page_three(f)
    f.c.save()
    print("wrote", OUT)


if __name__ == "__main__":
    main()
