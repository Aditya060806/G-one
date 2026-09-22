package com.gone.ai.health.source

import com.gone.ai.health.domain.VitalsSample

/**
 * Turns raw wearable bytes into [VitalsSample]s.
 *
 * PURE KOTLIN, DELIBERATELY STATEFUL.
 *
 * `feed` rather than `parse` because BLE does not deliver messages, it delivers
 * arbitrary byte chunks. With the default 23-byte MTU (20 usable) a single reading
 * easily spans two notifications, and conversely one notification may carry several
 * complete readings. A stateless `parse(bytes): Sample?` would silently drop every
 * fragmented reading — the classic BLE bug. So the parser owns a buffer and returns
 * however many complete records the new bytes completed: zero, one, or many.
 *
 * Implementations must be tolerant, never throwing on malformed input. A wearable
 * with a loose wire emits garbage; the correct response is to discard that line and
 * keep the stream alive, not to crash the monitoring service.
 */
interface SensorPacketParser {

    /**
     * Feed newly-received bytes.
     *
     * @param receivedAt wall-clock arrival time, used only when the packet carries
     *   no timestamp of its own.
     * @return complete records in arrival order; empty when more bytes are needed.
     */
    fun feedPackets(bytes: ByteArray, receivedAt: Long): List<WearablePacket>

    /** The samples in [feedPackets], for callers that do not need status or provenance. */
    fun feed(bytes: ByteArray, receivedAt: Long): List<VitalsSample> =
        feedPackets(bytes, receivedAt).mapNotNull { it.sample }

    /** Drop buffered partial input. Call on reconnect so a half-line cannot merge
     *  with the first line of the new session and produce a corrupt reading. */
    fun reset()

    /** Diagnostics for the Device screen: lines seen, and lines rejected. */
    val stats: ParserStats
}

/**
 * One complete line from the wearable.
 *
 * @property sample the reading, or null for a status-only line.
 * @property status the sensor self-report from the `ST` field, or null when the line
 *   did not carry one. Never set on a [backlog] line: a buffered line describes the
 *   device as it was when the reading was taken, not as it is now.
 * @property backlog true when the line was replayed from the wearable's SD card
 *   (`BUF:1`) rather than measured just now.
 * @property seq the stored record's number (`SEQ`), which the phone acknowledges once the
 *   reading is stored. A numbered record with no usable reading still arrives, with a null
 *   [sample], so it can be acknowledged and does not block the sync.
 * @property lost with [seq]: this many records ending at [seq] exist but carry no reading (`LOST`).
 * @property pending on a status line: records waiting on the wearable (`PEND`).
 * @property newestSeq on a status line: the newest record stored on the wearable (`LAST`).
 * @property caughtUp set by the link, not the parser: this status line shows the phone has
 *   received everything the wearable has stored.
 */
data class WearablePacket(
    val sample: VitalsSample?,
    val status: WearableStatus?,
    val backlog: Boolean,
    val seq: Long? = null,
    val lost: Int = 0,
    val pending: Long? = null,
    val newestSeq: Long? = null,
    val caughtUp: Boolean = false
)

/** Rolling parser counters. Malformed input is expected, so it is measured. */
data class ParserStats(
    val linesAccepted: Long = 0,
    val linesRejected: Long = 0,
    val bytesBuffered: Int = 0,
    /** Fields present on a line but dropped as malformed, out of range or in the wrong unit. */
    val fieldsRejected: Long = 0
)

/**
 * Parser for newline-delimited `KEY:VALUE` records.
 *
 * Wire format, one reading per line:
 * ```
 * HR:78,SPO2:97,STEMP:33.9,MOT:1.02,EMG:1234,EMGPK:1810,EMGBITS:12,ST:7F,TS:1725400000000
 * ```
 *
 * | Key       | Meaning                                                     | Accepted            |
 * |-----------|-------------------------------------------------------------|---------------------|
 * | `HR`      | Heart rate, beats per minute                                | 20..300             |
 * | `SPO2`    | Blood oxygen saturation, %                                  | 50..100             |
 * | `TEMP`    | Core body temperature, °C                                   | 25..45              |
 * | `STEMP`   | Skin temperature, °C (never used as core temperature)       | 20..42              |
 * | `MOT`     | Peak accelerometer magnitude since the previous line, g     | 0..16               |
 * | `EMG`     | Mean EMG envelope since the previous line, ADC counts       | 0..4095             |
 * | `EMGPK`   | Highest EMG envelope since the previous line, ADC counts    | EMG..4095           |
 * | `EMGBITS` | ADC resolution the EMG values use                           | must be 12          |
 * | `ATEMP`   | Ambient temperature, °C                                     | -30..70             |
 * | `HUM`     | Relative humidity, %                                        | 0..100              |
 * | `AQI`     | Air quality index                                           | 0..1000             |
 * | `ST`      | Sensor status bitmask, hex — see [WearableStatus]           | 0..FFFF             |
 * | `BUF`     | `1` when replayed from the SD card                          | 0 or 1              |
 * | `TS`      | Epoch milliseconds the reading was taken                    | see below           |
 * | `SEQ`     | Stored record number, on `BUF:1` lines                      | 1 or more           |
 * | `LOST`    | Records ending at `SEQ` that carry no reading               | 1 or more           |
 * | `PEND`    | Status line: records waiting on the wearable                | 0 or more           |
 * | `LAST`    | Status line: newest record stored on the wearable           | 1 or more           |
 *
 * The sync fields are described in docs/WEARABLE_PROTOCOL.md.
 *
 * Why key-value and not positional CSV: firmware evolves. A positional format means
 * inserting a field silently shifts every later field, and the app would happily
 * read humidity as a heart rate. Named keys make an unknown field ignorable and a
 * missing field absent rather than wrong — which matters when the value in question
 * could trigger a medical alert.
 *
 * UNITS ARE CHECKED, NOT ASSUMED. A skin temperature sent in Fahrenheit lands outside
 * 20..42 and is dropped rather than read as a fever-range Celsius value. EMG counts are
 * only meaningful at the resolution the thresholds were written for: a board sending
 * `EMGBITS:10` has its EMG fields dropped, because 1023 on a 10-bit ADC is full scale
 * while on a 12-bit ADC it is a quarter of it.
 *
 * `TS` is honoured when present and plausible. This is not cosmetic: the wearable
 * buffers to SD while out of Bluetooth range and replays the backlog on reconnect.
 * Stamping those with arrival time would compress hours of history into a few seconds
 * and make every duration-based rule (`SpO2 < 92% for 10 minutes`) meaningless. So:
 *  - a timestamp more than [MAX_CLOCK_AHEAD_MILLIS] ahead of arrival, or older than
 *    [MAX_RECORD_AGE_MILLIS], comes from an unsynchronised clock and is not trusted;
 *  - a live line with an untrusted or missing timestamp uses its arrival time, which
 *    is accurate for a reading that was just taken;
 *  - a backlog line with an untrusted or missing timestamp is REJECTED. Its arrival
 *    time says nothing about when it was measured.
 */
class AsciiKeyValuePacketParser(
    /** Guards against a missing delimiter turning the buffer into a memory leak. */
    private val maxBufferedBytes: Int = 4096
) : SensorPacketParser {

    companion object {
        /** Clock skew tolerated between the wearable and the phone. */
        const val MAX_CLOCK_AHEAD_MILLIS = 2L * 60 * 1000
        /** Anything older did not come from a clock that was ever set correctly. */
        const val MAX_RECORD_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
        /** The only EMG resolution the thresholds are written for. */
        const val EMG_ADC_BITS = 12
    }

    private val buffer = StringBuilder()
    /** Set by [parseLine] for a numbered record passed on only so it can be acknowledged. */
    private var unusable = false
    private var accepted = 0L
    private var rejected = 0L
    private var badFields = 0L

    override val stats: ParserStats
        get() = ParserStats(accepted, rejected, buffer.length, badFields)

    override fun reset() {
        buffer.setLength(0)
    }

    override fun feedPackets(bytes: ByteArray, receivedAt: Long): List<WearablePacket> {
        if (bytes.isEmpty()) return emptyList()

        buffer.append(String(bytes, Charsets.US_ASCII))

        // Runaway guard: if we never see a delimiter the buffer must not grow without
        // bound. Keep the tail — a delimiter is likelier to arrive next than to be
        // hiding in bytes we already decided are junk.
        if (buffer.length > maxBufferedBytes) {
            val keep = buffer.substring(buffer.length - maxBufferedBytes / 2)
            buffer.setLength(0)
            buffer.append(keep)
            rejected++
        }

        val out = mutableListOf<WearablePacket>()
        while (true) {
            val nl = buffer.indexOf("\n")
            if (nl < 0) break
            val line = buffer.substring(0, nl).trim()   // trim also drops a trailing \r
            buffer.delete(0, nl + 1)
            if (line.isEmpty()) continue
            unusable = false
            val packet = parseLine(line, receivedAt)
            if (packet != null && !unusable) accepted++ else rejected++
            if (packet != null) out += packet
        }
        return out
    }

    /** @return null when the line carries neither a usable signal nor a status report. */
    private fun parseLine(line: String, receivedAt: Long): WearablePacket? {
        var hr: Int? = null
        var spo2: Int? = null
        var temp: Float? = null
        var skinTemp: Float? = null
        var motion: Float? = null
        var emg: Int? = null
        var emgPeak: Int? = null
        var emgBits: Int? = null
        var ambientTemp: Float? = null
        var humidity: Float? = null
        var aqi: Int? = null
        var status: WearableStatus? = null
        var backlog = false
        var ts: Long? = null
        var seq: Long? = null
        var lost = 0
        var pending: Long? = null
        var newestSeq: Long? = null

        for (field in line.split(',')) {
            val sep = field.indexOf(':')
            if (sep <= 0) continue                       // no key, or empty key
            val key = field.substring(0, sep).trim().uppercase()
            val raw = field.substring(sep + 1).trim()
            if (raw.isEmpty()) continue

            when (key) {
                "HR"      -> hr          = checked(raw.toIntOrNull()?.takeIf   { it in 20..300 })
                "SPO2"    -> spo2        = checked(raw.toIntOrNull()?.takeIf   { it in 50..100 })
                "TEMP"    -> temp        = checked(raw.toFloatOrNull()?.takeIf { it in 25f..45f })
                "STEMP"   -> skinTemp    = checked(raw.toFloatOrNull()?.takeIf { it in 20f..42f })
                "MOT"     -> motion      = checked(raw.toFloatOrNull()?.takeIf { it in 0f..16f })
                "EMG"     -> emg         = checked(raw.toIntOrNull()?.takeIf   { it in 0..VitalsSample.EMG_ADC_MAX })
                "EMGPK"   -> emgPeak     = checked(raw.toIntOrNull()?.takeIf   { it in 0..VitalsSample.EMG_ADC_MAX })
                "EMGBITS" -> emgBits     = checked(raw.toIntOrNull())
                "ATEMP"   -> ambientTemp = checked(raw.toFloatOrNull()?.takeIf { it in -30f..70f })
                "HUM"     -> humidity    = checked(raw.toFloatOrNull()?.takeIf { it in 0f..100f })
                "AQI"     -> aqi         = checked(raw.toIntOrNull()?.takeIf   { it in 0..1000 })
                "ST"      -> status      = checked(WearableStatus.parse(raw))
                "BUF"     -> when (raw) {
                    "1" -> backlog = true
                    "0" -> backlog = false
                    else -> badFields++
                }
                "TS"      -> ts          = checked(raw.toLongOrNull()?.takeIf  { it > 0 })
                "SEQ"     -> seq         = checked(raw.toLongOrNull()?.takeIf  { it > 0 })
                "LOST"    -> lost        = checked(raw.toIntOrNull()?.takeIf   { it > 0 }) ?: 0
                "PEND"    -> pending     = checked(raw.toLongOrNull()?.takeIf  { it >= 0 })
                "LAST"    -> newestSeq   = checked(raw.toLongOrNull()?.takeIf  { it > 0 })
                else      -> Unit                        // forward compatibility
            }
        }

        // Range-rejecting individual fields above rather than the whole line is
        // deliberate: one bad optical SpO2 read should not discard a valid heart
        // rate measured in the same instant.

        if (emgBits != null && emgBits != EMG_ADC_BITS) {
            if (emg != null) badFields++
            if (emgPeak != null) badFields++
            emg = null
            emgPeak = null
        }
        // A peak below the mean it summarises is internally inconsistent: keep the mean,
        // which is what the rules read, and drop the peak.
        if (emgPeak != null && (emg == null || emgPeak < emg)) {
            badFields++
            emgPeak = null
        }

        val trustedTs = ts?.takeIf {
            it <= receivedAt + MAX_CLOCK_AHEAD_MILLIS && it >= receivedAt - MAX_RECORD_AGE_MILLIS
        }
        if (ts != null && trustedTs == null) badFields++

        val sample = VitalsSample(
            timestamp          = trustedTs ?: receivedAt,
            heartRate          = hr,
            spo2               = spo2,
            bodyTempC          = temp,
            motionMagnitudeG   = motion,
            ambientTempC       = ambientTemp,
            ambientHumidityPct = humidity,
            aqi                = aqi,
            skinTempC          = skinTemp,
            emgMean            = emg,
            emgMax             = emgPeak ?: emg
        ).takeIf {
            // An environment-only line carries no physiology; rejecting it keeps the
            // detection engine's windows from being padded with empty rows.
            it.hasVitals || motion != null
        }

        if (backlog) {
            val numbered = seq
            // Placed in time by its own timestamp or not at all.
            if (sample == null || trustedTs == null || lost > 0) {
                if (numbered == null) return null
                // Still delivered, so it can be acknowledged instead of blocking the sync.
                if (lost == 0) unusable = true
                return WearablePacket(null, status = null, backlog = true, seq = numbered, lost = lost)
            }
            return WearablePacket(sample, status = null, backlog = true, seq = numbered)
        }

        if (sample == null && status == null && pending == null && newestSeq == null) return null
        return WearablePacket(sample, status, backlog = false, pending = pending, newestSeq = newestSeq)
    }

    /** Counts a field that was present but unusable. */
    private fun <T> checked(value: T?): T? {
        if (value == null) badFields++
        return value
    }
}
