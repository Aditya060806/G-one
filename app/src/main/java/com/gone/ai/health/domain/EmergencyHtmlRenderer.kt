package com.gone.ai.health.domain

/**
 * Renders a completely self-contained offline emergency HTML page from an [EmergencyPayload].
 *
 * THE OFFLINE CONTRACT
 *
 * The rendered string encodes into a `data:text/html;base64,...` URI that any modern phone
 * browser can open from a QR code scan — with zero network access. The page must therefore:
 *   - Use no external fonts (system-ui only).
 *   - Load no images, no scripts from any URL.
 *   - Use `<a href="tel:...">` for call buttons — these work offline via the phone dialer.
 *   - Compute staleness in the single inline <script> block using the embedded `ts` field
 *     and `Date.now()`, so the banner reflects *when the responder opens the page*, not
 *     when the QR was generated.
 *
 * COLOUR RULE
 *
 * Emergency medical pages use white backgrounds. No dark mode — maximum contrast in
 * daylight, under fluorescent lighting, and for responders with colour vision deficiency.
 * Red is used *only* for the emergency header and critical banners — nowhere else.
 *
 * ANDROID INDEPENDENCE
 *
 * This renderer is pure Kotlin. No Android imports. Template is a string constant
 * in this file. Directly unit-testable without Robolectric.
 */
object EmergencyHtmlRenderer {

    /**
     * Render [payload] into a complete, standalone HTML document.
     *
     * [nowMillis] is injected (rather than reading System.currentTimeMillis() inside)
     * so the rendered staleness state is deterministic in tests.
     *
     * The returned string is ready to be base64-encoded and prepended with
     * `data:text/html;charset=utf-8;base64,` for inclusion in a QR code.
     */
    fun render(payload: EmergencyPayload, nowMillis: Long): String {
        val staleness = payload.staleness(nowMillis)

        // ── Staleness banner HTML ─────────────────────────────────────────────
        val staleBanner = when (staleness) {
            Staleness.STALE     -> """
                <div class="banner amber">
                    ⚠️ <strong>Vitals may be outdated</strong> — recorded between 1 and 24 hours ago.
                    Treat as background information, not current state.
                </div>""".trimIndent()
            Staleness.VERY_STALE -> """
                <div class="banner red">
                    🔴 <strong>Snapshot is old (more than 24 hours)</strong> — these vitals are
                    historical. Do not rely on them for triage decisions.
                </div>""".trimIndent()
            else                -> ""
        }

        // ── Block A — Personal section ────────────────────────────────────────
        val bloodGroupHtml = payload.bloodGroup?.let { bg ->
            """<div class="blood-group-box"><span class="blood-label">Blood Group</span>
               <span class="blood-value">$bg</span></div>"""
        } ?: ""

        val allergiesHtml = payload.allergies?.let {
            infoRow("⚠️ Allergies", it, rowClass = "allergy-row")
        } ?: ""

        val conditionsHtml = payload.chronicConditions?.let {
            infoRow("Chronic Conditions", it)
        } ?: ""

        val medicationsHtml = payload.medications?.let {
            infoRow("Current Medications", it)
        } ?: ""

        val implantsHtml = payload.implantedDevices?.let {
            infoRow("⚡ Implanted Devices", it, rowClass = "implant-row")
        } ?: ""

        // ── Block B — Vitals section (only when shareLiveVitals = true) ───────
        val vitalsHtml = buildString {
            val hasVitals = listOf(payload.heartRate, payload.spo2, payload.bodyTempC,
                payload.motionStatus, payload.riskStatus).any { it != null }

            if (!hasVitals) return@buildString

            append("""<div class="section"><div class="section-title">❤️ Last Known Vitals</div>""")

            if (staleBanner.isNotEmpty()) {
                // Already shown at top; add a smaller inline note here too
                append("""<p class="vitals-note">These readings are from the embedded snapshot timestamp.</p>""")
            }

            payload.heartRate?.let {
                append(vitalRow("Heart Rate", "$it BPM"))
            }
            payload.spo2?.let {
                val cls = if (it < 92) "vital-value warn" else "vital-value"
                append(vitalRow("SpO₂", "$it%", valueClass = cls))
            }
            payload.bodyTempC?.let {
                val cls = if (it >= 38.0) "vital-value warn" else "vital-value"
                append(vitalRow("Temperature", Temperature.fahrenheitText(it), valueClass = cls))
            }
            payload.motionStatus?.let {
                val displayStatus = if (it == "fall_detected") "⚠️ Fall detected" else it.replaceFirstChar { c -> c.uppercase() }
                val cls = if (it == "fall_detected") "vital-value critical" else "vital-value"
                append(vitalRow("Motion Status", displayStatus, valueClass = cls))
            }
            payload.riskStatus?.let {
                val displayRisk = it.replaceFirstChar { c -> c.uppercase() }
                val cls = when (it.lowercase()) {
                    "critical" -> "vital-value critical"
                    "elevated" -> "vital-value warn"
                    else       -> "vital-value"
                }
                append(vitalRow("Risk Status", displayRisk, valueClass = cls))
            }

            // Timestamp — always shown if any vitals present
            payload.readingTimestamp?.let { ts ->
                // JS in the page will format this; we embed the epoch millis as a data attribute
                append("""<div class="vital-row timestamp-row">
                    <span class="vital-label">Last Reading</span>
                    <span class="vital-value" id="readingTs" data-ts="$ts">—</span>
                </div>""")
            }

            append("</div>")
        }

        // ── Emergency contacts ────────────────────────────────────────────────
        val contactHtml = buildString {
            val contact = payload.emergencyContact
            if (contact.isNullOrBlank()) return@buildString
            append("""<div class="section"><div class="section-title">📞 Emergency Contact</div>""")
            append("""<a href="tel:$contact" class="call-btn">📞 Call Emergency Contact<br>
                <span class="call-sub">$contact</span></a>""")
            append("""<a href="tel:112" class="call-btn secondary">🚑 Call 112 — Emergency Services</a>""")
            append("</div>")
        }

        // ── Patient identity ──────────────────────────────────────────────────
        val nameHtml = payload.name?.let { "<div class=\"patient-name\">$it</div>" } ?: ""
        val ageHtml  = payload.age?.let  { "<div class=\"patient-age\">Age: $it</div>" } ?: ""

        // ── Inline JS: format timestamps + re-evaluate staleness on open ──────
        // Using embedded epoch ms so the page is self-contained.
        val snapshotTs  = payload.snapshotTimestamp
        val readingTs   = payload.readingTimestamp ?: 0L

        return TEMPLATE
            .replace("{{STALE_BANNER}}",   staleBanner)
            .replace("{{NAME}}",           nameHtml)
            .replace("{{AGE}}",            ageHtml)
            .replace("{{BLOOD_GROUP}}",    bloodGroupHtml)
            .replace("{{ALLERGIES}}",      allergiesHtml)
            .replace("{{CONDITIONS}}",     conditionsHtml)
            .replace("{{MEDICATIONS}}",    medicationsHtml)
            .replace("{{IMPLANTS}}",       implantsHtml)
            .replace("{{VITALS}}",         vitalsHtml)
            .replace("{{CONTACTS}}",       contactHtml)
            .replace("{{SNAPSHOT_TS}}",    snapshotTs.toString())
            .replace("{{READING_TS}}",     readingTs.toString())
    }

    // ── Helper builders ───────────────────────────────────────────────────────

    private fun infoRow(label: String, value: String, rowClass: String = "info-row"): String =
        """<div class="$rowClass"><span class="info-label">$label</span>
           <span class="info-value">${value.replace("<", "&lt;").replace(">", "&gt;")}</span></div>"""

    private fun vitalRow(
        label: String,
        value: String,
        valueClass: String = "vital-value"
    ): String =
        """<div class="vital-row">
            <span class="vital-label">$label</span>
            <span class="$valueClass">$value</span>
           </div>"""

    // ── Self-contained HTML template ──────────────────────────────────────────
    //
    // Design principles enforced here:
    //   - White background, no dark mode
    //   - Red used ONLY for the emergency header and critical/fall banners
    //   - Amber for warnings, green for normal vitals
    //   - Large tap targets on call buttons (min 56 dp equivalent)
    //   - No external resources (fonts loaded from system-ui, zero img tags)
    //   - Inline JS only; computes staleness from embedded epoch millis vs Date.now()
    //
    private val TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no"/>
<title>Emergency Medical ID — G-one</title>
<style>
  *{box-sizing:border-box;margin:0;padding:0}
  body{font-family:system-ui,-apple-system,sans-serif;background:#fff;color:#111;max-width:480px;margin:0 auto;padding:0 0 32px}
  /* Header */
  .header{background:#c0392b;color:#fff;padding:20px 16px 16px;text-align:center}
  .header-title{font-size:20px;font-weight:700;letter-spacing:.5px}
  .header-sub{font-size:12px;opacity:.85;margin-top:4px}
  /* Patient identity */
  .identity{padding:16px;border-bottom:1px solid #eee;background:#fafafa}
  .patient-name{font-size:22px;font-weight:700;color:#111}
  .patient-age{font-size:14px;color:#555;margin-top:4px}
  /* Blood group — the most critical field */
  .blood-group-box{display:flex;align-items:center;gap:12px;margin-top:12px;background:#fff5f5;border:2px solid #c0392b;border-radius:10px;padding:12px 16px}
  .blood-label{font-size:13px;font-weight:600;color:#c0392b;text-transform:uppercase;letter-spacing:.6px}
  .blood-value{font-size:36px;font-weight:800;color:#c0392b;margin-left:auto}
  /* Banners */
  .banner{padding:12px 16px;margin:0;font-size:14px;line-height:1.5}
  .banner.amber{background:#fffbeb;border-left:4px solid #d97706;color:#78350f}
  .banner.red{background:#fff5f5;border-left:4px solid #c0392b;color:#7f1d1d}
  /* Sections */
  .section{padding:16px;border-bottom:1px solid #eee}
  .section-title{font-size:13px;font-weight:700;text-transform:uppercase;letter-spacing:.8px;color:#555;margin-bottom:10px}
  /* Info rows (allergies, conditions, meds) */
  .info-row{display:flex;gap:8px;flex-direction:column;margin-bottom:8px;background:#f7f8fa;border-radius:8px;padding:10px 12px}
  .info-label{font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.6px;color:#777}
  .info-value{font-size:15px;color:#111;line-height:1.4}
  .allergy-row{background:#fffbeb;border-left:3px solid #d97706}
  .allergy-row .info-label{color:#92400e}
  .implant-row{background:#eff6ff;border-left:3px solid #2563eb}
  .implant-row .info-label{color:#1e40af}
  /* Vital rows */
  .vital-row{display:flex;justify-content:space-between;align-items:center;padding:8px 0;border-bottom:1px solid #f0f0f0}
  .vital-row:last-child{border-bottom:none}
  .vital-label{font-size:14px;color:#444}
  .vital-value{font-size:16px;font-weight:700;color:#111;font-variant-numeric:tabular-nums}
  .vital-value.warn{color:#b45309}
  .vital-value.critical{color:#c0392b}
  .vitals-note{font-size:12px;color:#888;margin-bottom:8px}
  .timestamp-row{padding-top:10px;margin-top:4px;border-top:1px dashed #eee;border-bottom:none}
  .timestamp-row .vital-label{font-size:12px;color:#888}
  .timestamp-row .vital-value{font-size:12px;color:#888;font-weight:500}
  /* Call buttons */
  .call-btn{display:block;width:100%;padding:16px;margin-bottom:10px;background:#16a34a;color:#fff;text-decoration:none;border-radius:12px;font-size:16px;font-weight:700;text-align:center;line-height:1.4}
  .call-btn:last-child{margin-bottom:0}
  .call-btn.secondary{background:#1d4ed8}
  .call-sub{font-size:13px;font-weight:400;opacity:.9}
  /* Footer */
  .footer{padding:20px 16px 0;text-align:center;font-size:11px;color:#aaa;line-height:1.6}
  .footer strong{color:#888}
  /* Snapshot timestamp */
  .snapshot-time{text-align:center;font-size:11px;color:#bbb;padding:8px 16px 0}
</style>
</head>
<body>
<div class="header">
  <div class="header-title">🚨 Emergency Medical ID</div>
  <div class="header-sub">G-one Health — Offline Snapshot</div>
</div>

{{STALE_BANNER}}

<div class="identity">
  {{NAME}}
  {{AGE}}
  {{BLOOD_GROUP}}
</div>

<div class="section">
  <div class="section-title">⚕️ Medical Information</div>
  {{ALLERGIES}}
  {{CONDITIONS}}
  {{MEDICATIONS}}
  {{IMPLANTS}}
</div>

{{VITALS}}

{{CONTACTS}}

<div class="snapshot-time" id="snapshotTime"></div>

<div class="footer">
  <strong>G-one Offline Emergency Snapshot</strong><br/>
  For live data, tap the NFC tag when online.<br/>
  This page works without internet. Call buttons open your phone dialer.
</div>

<script>
(function(){
  var SNAPSHOT_TS={{SNAPSHOT_TS}};
  var READING_TS={{READING_TS}};

  function fmt(ms){
    if(!ms||ms===0)return"—";
    var d=new Date(ms);
    return d.toLocaleString(undefined,{month:'short',day:'numeric',year:'numeric',hour:'2-digit',minute:'2-digit'});
  }

  // Format reading timestamp inline element
  var rEl=document.getElementById('readingTs');
  if(rEl&&READING_TS>0){rEl.textContent=fmt(READING_TS);}

  // Snapshot timestamp
  var sEl=document.getElementById('snapshotTime');
  if(sEl&&SNAPSHOT_TS>0){sEl.textContent='Snapshot generated: '+fmt(SNAPSHOT_TS);}

  // Re-evaluate staleness from the device's current clock
  var now=Date.now();
  var age=READING_TS>0?(now-READING_TS):Number.MAX_VALUE;
  var ONE_HOUR=3600000;
  var ONE_DAY=86400000;

  function insertBanner(cls,html){
    var existing=document.querySelector('.banner');
    if(existing)return; // server-rendered banner already present; JS one would duplicate it
    var b=document.createElement('div');
    b.className='banner '+cls;
    b.innerHTML=html;
    document.body.insertBefore(b,document.body.children[1]);
  }

  if(READING_TS===0){
    // No vitals in snapshot — nothing to add
  } else if(age>=ONE_DAY){
    insertBanner('red','&#x1F534; <strong>Snapshot is old (more than 24 hours)</strong> — these vitals are historical. Do not rely on them for triage decisions.');
  } else if(age>=ONE_HOUR){
    insertBanner('amber','&#x26A0;&#xFE0F; <strong>Vitals may be outdated</strong> — recorded between 1 and 24 hours ago. Treat as background information, not current state.');
  }
})();
</script>
</body>
</html>"""
}
