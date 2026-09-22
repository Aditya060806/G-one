package com.gone.ai.health.domain

/**
 * The compact data snapshot written to an NFC tag's second NDEF record and encoded into a
 * QR code as the offline fallback.
 *
 * WHAT THIS IS NOT
 *
 * This is not a live-streaming view of the patient's state. It is a point-in-time snapshot
 * produced by [toCompactJson] and then physically written onto a passive medium (NFC tag /
 * printed QR sticker). The snapshot can only be refreshed when the phone deliberately rewrites
 * the tag or generates a new QR. The [staleness] method and the inline JS in the rendered
 * HTML page make this explicit to a first responder, so stale vitals can never be silently
 * mistaken for current readings.
 *
 * FIELD PRESENCE CONTRACT
 *
 * Every field that corresponds to a `share_*` toggle in [EmergencyProfileEntity] is set to
 * null by [EmergencyRepository.buildPayload] when the toggle is off. A null field is
 * **omitted from the JSON output** — not transmitted as `null`, not transmitted as an empty
 * string. The responder's page therefore never even receives a field the patient opted out of.
 * This is a stronger guarantee than hiding it in the UI: nothing is filtered client-side.
 *
 * ANDROID INDEPENDENCE
 *
 * This class lives in the domain package. No Android imports. Directly unit-testable.
 */
data class EmergencyPayload(
    // ── Block A — Personal details (static; rewritten rarely) ──────────────────
    val name: String?,
    val age: Int?,
    val bloodGroup: String?,
    /** Null when shareAllergies = false. */
    val allergies: String?,
    /** Null when shareConditions = false. */
    val chronicConditions: String?,
    /** Null when shareMedications = false. */
    val medications: String?,
    val implantedDevices: String?,
    /** Primary contact phone number. One number for the offline snapshot. */
    val emergencyContact: String?,

    // ── Block B — Last session vitals (dynamic; rewritten every QR/NFC write) ──
    /** Null when shareLiveVitals = false. */
    val heartRate: Int?,
    /** Null when shareLiveVitals = false. */
    val spo2: Int?,
    /** Null when shareLiveVitals = false. */
    val bodyTempC: Float?,
    /**
     * "fall_detected" or "normal". Null when shareLiveVitals = false or no reading.
     * Directly relevant to a first responder finding someone unresponsive.
     */
    val motionStatus: String?,
    /**
     * Pre-computed detector severity label: "normal" | "elevated" | "critical".
     * Null when shareLiveVitals = false or no recent event.
     * Expressed as the detector's own label so the responder reads a conclusion,
     * not raw numbers they have to interpret.
     */
    val riskStatus: String?,
    /**
     * Unix epoch millis of the most recent vitals reading included in this payload.
     * NON-NEGOTIABLE: without this, a first responder cannot tell if the vitals are
     * 5 minutes old or 5 days old. Always included regardless of share_* flags.
     */
    val readingTimestamp: Long?,

    /**
     * Unix epoch millis when this payload object was constructed (i.e. when the phone
     * last wrote the tag or generated the QR). Not the reading time — the build time.
     * Baked into the rendered HTML for the staleness JS to use.
     */
    val snapshotTimestamp: Long
) {

    // ── Compact JSON serialisation ──────────────────────────────────────────────

    /**
     * Produce the minimal JSON payload.
     *
     * Keys are intentionally short (single-letter) to minimise the byte count on the tag
     * and keep the QR code data-density low enough for reliable camera decoding under stress.
     *
     * Target: ~200 bytes for a full payload. NTAG215 allows ~504 bytes; a Version-10 QR
     * code at error-correction M holds ~272 bytes of binary. Even wrapped in a data: URI
     * (×1.35 base64 overhead) the full self-contained HTML renders in QR without trouble.
     *
     * Null fields are omitted entirely — the consumer must treat absence as "not provided,"
     * not as a zero.
     */
    fun toCompactJson(): String = buildString {
        append("{")
        var first = true
        fun field(key: String, value: Any?) {
            if (value == null) return
            if (!first) append(",")
            first = false
            append("\"$key\":")
            when (value) {
                is String  -> append("\"${value.replace("\"", "\\\"")}\"")
                is Int     -> append(value)
                is Float   -> append(String.format(java.util.Locale.US, "%.1f", value))
                is Long    -> append(value)
                is Boolean -> append(value)
                else       -> append("\"$value\"")
            }
        }
        field("n",  name)
        field("ag", age)
        field("bg", bloodGroup)
        field("al", allergies)
        field("cc", chronicConditions)
        field("md", medications)
        field("im", implantedDevices)
        field("ec", emergencyContact)
        field("hr", heartRate)
        field("sp", spo2)
        field("tm", bodyTempC)
        field("mo", motionStatus)
        field("rs", riskStatus)
        field("ts", readingTimestamp)
        field("ss", snapshotTimestamp)
        append("}")
    }

    // ── Human-readable Offline Text for NFC & Responders ─────────────────────────

    /**
     * Formats the emergency payload into human-readable offline medical text
     * suitable for writing directly into an NFC tag (NDEF RTD_TEXT record) or displaying
     * on any NFC reader without internet connectivity.
     */
    fun toOfflineText(): String = buildString {
        appendLine("🚨 EMERGENCY MEDICAL ID — G-ONE")
        val identity = buildList {
            name?.let { add(it) }
            age?.let { add("$it yrs") }
        }.joinToString(", ")
        if (identity.isNotBlank()) appendLine("Patient: $identity")
        bloodGroup?.let { appendLine("Blood Group: $it") }
        allergies?.let { appendLine("⚠️ Allergies: $it") }
        chronicConditions?.let { appendLine("Conditions: $it") }
        medications?.let { appendLine("Medications: $it") }
        implantedDevices?.let { appendLine("⚡ Implants: $it") }
        emergencyContact?.let { appendLine("📞 Emergency Contact: $it") }

        val vitals = buildList {
            heartRate?.let { add("$it BPM") }
            spo2?.let { add("SpO2 $it%") }
            bodyTempC?.let { add(String.format(java.util.Locale.US, "%.1f°C", it)) }
            motionStatus?.let { add(if (it == "fall_detected") "⚠️ FALL DETECTED" else it) }
        }
        if (vitals.isNotEmpty()) {
            appendLine("Last Vitals: ${vitals.joinToString(" | ")}")
        }
        readingTimestamp?.let { ts ->
            appendLine("Recorded: ${java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.US).format(java.util.Date(ts))}")
        }
        appendLine("Snapshot: ${java.text.SimpleDateFormat("dd MMM yyyy HH:mm z", java.util.Locale.US).format(java.util.Date(snapshotTimestamp))}. Not live; rewrite tag to update.")
    }.trimEnd()

    /**
     * Ultra-compact format specifically sized to fit very small NFC tags (e.g. NTAG213, ~144 bytes).
     */
    fun toCompactOfflineText(): String = buildString {
        append("🚨EMERGENCY ID: ")
        name?.let { append("$it ") }
        age?.let { append("($it) ") }
        bloodGroup?.let { append("[$it]\n") } ?: append("\n")
        allergies?.let { append("Allergies: $it\n") }
        chronicConditions?.let { append("Cond: $it\n") }
        medications?.let { append("Meds: $it\n") }
        implantedDevices?.let { append("Implants: $it\n") }
        emergencyContact?.let { append("ICE: $it\n") }
        // A tiny chip cannot safely carry time-sensitive readings without their timestamps.
        // Compact fallback contains static details only. Never silently truncate medical fields.
        append("Saved:${java.text.SimpleDateFormat("yyMMdd", java.util.Locale.US).format(java.util.Date(snapshotTimestamp))}")
    }.trim()

    /**
     * Formats into a standard vCard 3.0 string for NFC MIME record (text/vcard).
     * Viewing/importing this requires compatible NFC/contact software; universal
     * automatic handling by Android/iPhone is not guaranteed.
     */
    fun toVCard(): String = buildString {
        val displayName = name ?: "Emergency Patient"
        val bgSuffix = bloodGroup?.let { " ($it)" } ?: ""
        appendLine("BEGIN:VCARD")
        appendLine("VERSION:3.0")
        appendLine("FN:ICE - $displayName$bgSuffix")
        appendLine("N:$displayName;ICE;;;")
        emergencyContact?.let { appendLine("TEL;TYPE=CELL,VOICE,PREF:$it") }
        val notes = buildList {
            bloodGroup?.let { add("Blood Group: $it") }
            allergies?.let { add("ALLERGIES: $it") }
            chronicConditions?.let { add("Conditions: $it") }
            medications?.let { add("Medications: $it") }
            implantedDevices?.let { add("Implants: $it") }
            if ((heartRate != null || spo2 != null) && readingTimestamp != null) {
                add("Last Vitals: ${listOfNotNull(heartRate?.let { "$it BPM" }, spo2?.let { "SpO2 $it%" }).joinToString(", ")}; recorded ${java.util.Date(readingTimestamp)}; NOT LIVE")
            }
        }.joinToString(" | ")
        if (notes.isNotBlank()) {
            appendLine("NOTE:$notes")
        }
        appendLine("ORG:G-one Emergency Medical ID")
        appendLine("END:VCARD")
    }.trimEnd()

    // ── Staleness classification ────────────────────────────────────────────────

    /**
     * Classify how fresh the vitals reading in this snapshot is, relative to [nowMillis].
     *
     * The classification is computed from [readingTimestamp] (when the wearable produced
     * the reading) rather than [snapshotTimestamp] (when the phone wrote the tag/QR). The
     * snapshot can be generated immediately after monitoring stops; what matters to a
     * responder is how old the underlying measurement is.
     *
     * [Staleness.NO_DATA] when no reading exists — e.g. shareLiveVitals = false, or
     * monitoring was never started. The responder sees no vitals section, not stale vitals.
     */
    fun staleness(nowMillis: Long): Staleness = when {
        readingTimestamp == null                                          -> Staleness.NO_DATA
        nowMillis - readingTimestamp < 60L * 60 * 1000                   -> Staleness.FRESH
        nowMillis - readingTimestamp < 24L * 60 * 60 * 1000              -> Staleness.STALE
        else                                                              -> Staleness.VERY_STALE
    }
}

/**
 * How old the vitals reading embedded in an [EmergencyPayload] is.
 *
 * The boundary values are intentional clinical choices:
 * - FRESH: < 1 hour — likely reflects the patient's current state well enough for triage.
 * - STALE: 1–24 hours — shows the data but warns the responder to treat it as historical.
 * - VERY_STALE: > 24 hours — prominent warning; the responder must not rely on these values.
 * - NO_DATA: vitals were not shared, or monitoring has never run.
 */
enum class Staleness {
    FRESH,
    STALE,
    VERY_STALE,
    NO_DATA
}
