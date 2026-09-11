package com.infinity.ai.health.source

import com.infinity.ai.health.domain.VitalsSample

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
 * however many complete samples the new bytes completed: zero, one, or many.
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
     * @return complete samples in arrival order; empty when more bytes are needed.
     */
    fun feed(bytes: ByteArray, receivedAt: Long): List<VitalsSample>

    /** Drop buffered partial input. Call on reconnect so a half-line cannot merge
     *  with the first line of the new session and produce a corrupt reading. */
    fun reset()

    /** Diagnostics for the Device screen: lines seen, and lines rejected. */
    val stats: ParserStats
}

/** Rolling parser counters. Malformed input is expected, so it is measured. */
data class ParserStats(
    val linesAccepted: Long = 0,
    val linesRejected: Long = 0,
    val bytesBuffered: Int = 0
)

/**
 * Parser for newline-delimited `KEY:VALUE` records.
 *
 * Wire format, one reading per line:
 * ```
 * HR:78,SPO2:97,TEMP:36.8,MOT:1.02,ATEMP:41.2,HUM:38,AQI:210,TS:1725400000000
 * ```
 *
 * Why key-value and not positional CSV: firmware evolves. A positional format means
 * inserting a field silently shifts every later field, and the app would happily
 * read humidity as a heart rate. Named keys make an unknown field ignorable and a
 * missing field absent rather than wrong — which matters when the value in question
 * could trigger a medical alert.
 *
 * `TS` is honoured when present. This is not cosmetic: the wearable buffers to SD
 * while out of Bluetooth range and replays the backlog on reconnect. Stamping those
 * with arrival time would compress hours of history into a few seconds and make
 * every duration-based rule (`SpO2 < 92% for 10 minutes`) meaningless.
 */
class AsciiKeyValuePacketParser(
    /** Guards against a missing delimiter turning the buffer into a memory leak. */
    private val maxBufferedBytes: Int = 4096
) : SensorPacketParser {

    private val buffer = StringBuilder()
    private var accepted = 0L
    private var rejected = 0L

    override val stats: ParserStats
        get() = ParserStats(accepted, rejected, buffer.length)

    override fun reset() {
        buffer.setLength(0)
    }

    override fun feed(bytes: ByteArray, receivedAt: Long): List<VitalsSample> {
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

        val out = mutableListOf<VitalsSample>()
        while (true) {
            val nl = buffer.indexOf("\n")
            if (nl < 0) break
            val line = buffer.substring(0, nl).trim()   // trim also drops a trailing \r
            buffer.delete(0, nl + 1)
            if (line.isEmpty()) continue
            val sample = parseLine(line, receivedAt)
            if (sample != null) { accepted++; out += sample } else { rejected++ }
        }
        return out
    }

    /** @return null when the line yields no usable physiological signal. */
    private fun parseLine(line: String, receivedAt: Long): VitalsSample? {
        var hr: Int? = null
        var spo2: Int? = null
        var temp: Float? = null
        var motion: Float? = null
        var ambientTemp: Float? = null
        var humidity: Float? = null
        var aqi: Int? = null
        var ts: Long? = null

        for (field in line.split(',')) {
            val sep = field.indexOf(':')
            if (sep <= 0) continue                       // no key, or empty key
            val key = field.substring(0, sep).trim().uppercase()
            val raw = field.substring(sep + 1).trim()
            if (raw.isEmpty()) continue

            when (key) {
                "HR"    -> hr          = raw.toIntOrNull()?.takeIf   { it in 20..300 }
                "SPO2"  -> spo2        = raw.toIntOrNull()?.takeIf   { it in 50..100 }
                "TEMP"  -> temp        = raw.toFloatOrNull()?.takeIf { it in 25f..45f }
                "MOT"   -> motion      = raw.toFloatOrNull()?.takeIf { it in 0f..16f }
                "ATEMP" -> ambientTemp = raw.toFloatOrNull()?.takeIf { it in -30f..70f }
                "HUM"   -> humidity    = raw.toFloatOrNull()?.takeIf { it in 0f..100f }
                "AQI"   -> aqi         = raw.toIntOrNull()?.takeIf   { it in 0..1000 }
                "TS"    -> ts          = raw.toLongOrNull()?.takeIf  { it > 0 }
                else    -> Unit                          // forward compatibility
            }
        }

        // Range-rejecting individual fields above rather than the whole line is
        // deliberate: one bad optical SpO2 read should not discard a valid heart
        // rate measured in the same instant.

        val sample = VitalsSample(
            timestamp          = ts ?: receivedAt,
            heartRate          = hr,
            spo2               = spo2,
            bodyTempC          = temp,
            motionMagnitudeG   = motion,
            ambientTempC       = ambientTemp,
            ambientHumidityPct = humidity,
            aqi                = aqi
        )

        // An environment-only line carries no vitals; treat it as rejected so the
        // detection engine's windows are not padded with physiologically empty rows.
        return if (sample.hasVitals || motion != null) sample else null
    }
}
