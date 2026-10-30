"""
One-page acknowledgement for a pharmacy that hosted a G-one field trial: a short abstract of G-one,
the pharmacy's statement, and space to sign and stamp. No metrics; the detailed results stay on
the field-trial form it refers to.

    python tools/forms/acknowledgement_letter.py

Writes docs/field-trial/G-one-Acknowledgement-Getwell-Medicos.pdf.
"""

import os

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas
from reportlab.platypus import Paragraph

OUT = "docs/field-trial/G-one-Acknowledgement-Getwell-Medicos.pdf"
LOGO = "docs/assets/g-one-logo.png"
W, H = A4
M = 54
CW = W - 2 * M

RED = colors.HexColor("#ED0F1A")
INK = colors.HexColor("#141414")
TEXT = colors.HexColor("#2A2A2A")
MUTED = colors.HexColor("#6E6E6E")
RULE = colors.HexColor("#D6D6D6")
PANEL = colors.HexColor("#F5F5F4")

SITE = {
    "name": "Getwell Medicos",
    "kind": "Chemist: allopathic medicines, cosmetics and surgical items",
    "address": "Booth No. 13, Sub City Centre, Sector 35-C, Chandigarh 160022",
    "signatory": "Hardik Khanduja",
    "designation": "Proprietor",
    "place": "Chandigarh",
    "ref": "G1-FT-CHD-001",
}

TRIED = [
    "Live readings from the wrist strap over Bluetooth",
    "Fall alert with the \"I'm OK\" window before the emergency text",
    "Trained models for falls, heart rate in context and sleep",
    "Plain-language explanations from the offline assistant",
    "District disaster alerts, local air quality and weather",
    "Health report and NFC emergency card",
]

BODY = ParagraphStyle("body", fontName="Helvetica", fontSize=9.8, leading=14.6, textColor=TEXT)
SMALL = ParagraphStyle("small", parent=BODY, fontSize=7.4, leading=10, textColor=MUTED)
CELL_L = ParagraphStyle("celll", fontName="Helvetica-Bold", fontSize=6.6, leading=8, textColor=MUTED)
CELL_V = ParagraphStyle("cellv", fontName="Helvetica-Bold", fontSize=8.8, leading=11.4, textColor=INK)
BULLET = ParagraphStyle("bullet", parent=BODY, fontSize=9, leading=12.4)


def para(c, y, text, style=BODY, x=M, width=CW):
    p = Paragraph(text, style)
    _, h = p.wrap(width, 1000)
    p.drawOn(c, x, y - h)
    return y - h


def heading(c, y, text):
    c.setFillColor(RED)
    c.rect(M, y - 9, 3, 11, stroke=0, fill=1)
    c.setFillColor(INK)
    c.setFont("Helvetica-Bold", 8.4)
    c.drawString(M + 10, y - 7, text.upper())
    return y - 20


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    c = canvas.Canvas(OUT, pagesize=A4)
    c.setTitle("G-one: Acknowledgement of Field Trial, %s" % SITE["name"])
    c.setAuthor("G-one")
    c.setSubject("Acknowledgement of an on-site trial of G-one at %s" % SITE["name"])

    # letterhead
    c.setFillColor(INK)
    c.rect(0, H - 8, W, 8, stroke=0, fill=1)
    c.setFillColor(RED)
    c.rect(0, H - 8, 90, 8, stroke=0, fill=1)

    logo = ImageReader(LOGO)
    lw, lh = logo.getSize()
    h_logo = 70
    c.drawImage(logo, M, H - 30 - h_logo, width=h_logo * lw / lh, height=h_logo, mask="auto")

    c.setFillColor(MUTED)
    c.setFont("Helvetica-Bold", 7.4)
    c.drawRightString(W - M, H - 40, "FIELD TRIAL ACKNOWLEDGEMENT")
    c.setFont("Helvetica", 8.6)
    c.setFillColor(TEXT)
    c.drawRightString(W - M, H - 56, "Reference:  %s" % SITE["ref"])
    c.drawRightString(W - M, H - 72, "Date:  ______________________")
    c.setFillColor(MUTED)
    c.setFont("Helvetica", 7.4)
    c.drawRightString(W - M, H - 88, "Wearable health monitoring and emergency alerts")

    y = H - 114
    c.setStrokeColor(INK)
    c.setLineWidth(1.2)
    c.line(M, y, W - M, y)
    c.setStrokeColor(RED)
    c.line(M, y, M + 60, y)

    # title
    y -= 34
    c.setFillColor(INK)
    c.setFont("Helvetica-Bold", 21)
    c.drawString(M, y, "Acknowledgement of Field Trial")
    y -= 17
    c.setFont("Helvetica", 10)
    c.setFillColor(MUTED)
    c.drawString(M, y, "On-site testing of G-one at %s, %s" % (SITE["name"], SITE["place"]))

    # details panel
    y -= 18
    ph = 46
    c.setFillColor(PANEL)
    c.rect(M, y - ph, CW, ph, stroke=0, fill=1)
    c.setFillColor(RED)
    c.rect(M, y - ph, 3, ph, stroke=0, fill=1)
    cols = [("ESTABLISHMENT", SITE["name"], 0.27), ("ADDRESS", SITE["address"], 0.46),
            ("SYSTEM TESTED", "G-one strap and app", 0.27)]
    x = M + 14
    for label, value, share in cols:
        w = (CW - 20) * share
        para(c, y - 8, label, CELL_L, x, w - 10)
        para(c, y - 19, value, CELL_V, x, w - 12)
        x += w
    y -= ph + 22

    # about
    y = heading(c, y, "About G-one")
    y = para(c, y, (
        "G-one is a wrist strap paired with an Android app. The strap streams movement and skin temperature to the "
        "phone over Bluetooth. The app watches the readings with fixed safety rules and three small trained models, "
        "explains each alert in plain language with an assistant that runs entirely on the phone without internet, "
        "and after a suspected fall gives the wearer a short \"I'm OK\" window before texting an emergency contact. "
        "It also shows official disaster alerts for the district, with local air quality and weather. "
        "All health data stays on the phone.")) - 18

    # statement
    y = heading(c, y, "Statement")
    y = para(c, y, "To whom it may concern,") - 7
    y = para(c, y, (
        "This is to confirm that the G-one system was demonstrated and tested at <b>%s</b>, %s. It was tried by our "
        "staff and our pharmacist, and by walk-in customers who volunteered to wear the strap and use the app at "
        "our counter and shop front. The following were shown and tried:" % (SITE["name"], SITE["address"]))) - 6

    half = (CW - 16) / 2
    yy = [y, y]
    for i, item in enumerate(TRIED):
        col = i % 2
        x = M + 6 + col * (half + 16)
        c.setFillColor(RED)
        c.circle(x + 2, yy[col] - 6.4, 1.9, stroke=0, fill=1)
        yy[col] = para(c, yy[col], item, BULLET, x + 10, half - 14) - 4
    y = min(yy) - 6

    y = para(c, y, (
        "In our observation the system worked as it was demonstrated to us. Our staff and customers found its "
        "alerts and explanations easy to understand, and we found it <b>helpful and promising</b>, particularly for "
        "elderly customers and for people who live alone. We are happy for the G-one team to refer to this trial at "
        "our establishment.")) - 7
    y = para(c, y, (
        "The detailed record of the trial is kept on the G-one Field Trial form, reference %s." % SITE["ref"])) - 26

    # signature and stamp
    top = y
    sw, sh = 168, 136
    sx = W - M - sw
    c.setStrokeColor(RED)
    c.setLineWidth(0.9)
    c.setDash(3, 3)
    c.roundRect(sx, top - sh, sw, sh, 6, stroke=1, fill=0)
    c.setDash()
    c.setFillColor(RED)
    c.setFont("Helvetica-Bold", 7.4)
    c.drawCentredString(sx + sw / 2, top - 15, "OFFICIAL STAMP / SEAL")

    c.setFillColor(MUTED)
    c.setFont("Helvetica-Bold", 7.4)
    c.drawString(M, top - 8, "FOR AND ON BEHALF OF")
    c.setFillColor(INK)
    c.setFont("Helvetica-Bold", 11)
    c.drawString(M, top - 23, SITE["name"].upper())
    line_w = sx - M - 34
    c.setStrokeColor(INK)
    c.setLineWidth(0.8)
    c.line(M, top - 84, M + line_w, top - 84)
    c.setFillColor(MUTED)
    c.setFont("Helvetica", 7.6)
    c.drawString(M, top - 95, "Authorised signature")
    c.setFillColor(INK)
    c.setFont("Helvetica-Bold", 10)
    c.drawString(M, top - 115, SITE["signatory"])
    c.setFont("Helvetica", 9)
    c.setFillColor(TEXT)
    c.drawString(M, top - 128, "%s, %s, %s" % (SITE["designation"], SITE["name"], SITE["place"]))

    # footer
    c.setStrokeColor(RULE)
    c.setLineWidth(0.6)
    c.line(M, 66, W - M, 66)
    para(c, 60, (
        "G-one is a research prototype. It is not a certified medical device and does not diagnose or treat any "
        "condition. This letter records a user trial at a retail pharmacy; it is not a medical, regulatory or CDSCO "
        "approval."), SMALL)
    c.setFillColor(INK)
    c.setFont("Helvetica-Bold", 7.4)
    c.drawString(M, 28, "G-ONE")
    c.setFillColor(MUTED)
    c.setFont("Helvetica", 7.4)
    c.drawString(M + 30, 28, "Private. Local. Always.")
    c.drawRightString(W - M, 28, "github.com/Aditya060806/G-one")
    c.setFillColor(INK)
    c.rect(0, 0, W, 5, stroke=0, fill=1)

    c.save()
    print("wrote", OUT)


if __name__ == "__main__":
    main()
