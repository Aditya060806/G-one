package com.gone.ai.pdf

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

/**
 * PdfTextExtractor — the legacy content-stream parser.
 *
 * Kept only as a fallback for Android 14 and older, which have no text-layer API. It reads
 * string operands out of uncompressed and Flate-compressed content streams. It does not
 * understand embedded font encodings, so for many modern PDFs it returns glyph codes or
 * words run together; [PdfTextSource] therefore uses its output only when [TextQuality]
 * says it is readable, and reads the pages as images otherwise.
 */
object PdfTextExtractor {

    private const val TAG = "PdfTextExtractor"

    /** Files larger than this are not loaded into memory for parsing; pages are read as images. */
    private const val MAX_PARSE_BYTES = 20L * 1024 * 1024

    private val PDF_CONTENT_RE = Regex("""\(([^)]*)\)|<([0-9A-Fa-f]{4,})>""")
    // PDF operator keywords that leak into extracted text — strip them.
    // Uses (?<![A-Za-z]) / (?![A-Za-z]) instead of \b so that operators
    // adjacent to digits (e.g. "720Td", "12cm") are also matched.
    // \b does NOT fire between a digit and a letter, both being \w chars.
    private val PDF_OPERATORS = Regex(
        """(?<![A-Za-z])(BT|ET|Tf|Td|TD|Tm|T\*|Tj|TJ|Tw|Tc|Tz|TL|Tr|Ts|cm|re|gs|Do|BI|EI|BMC|BDC|EMC|MP|DP|sh|SCN|scn|RG|rg|CS|cs)(?![A-Za-z])"""
    )

    /** The text of every content stream in the file, or null when it could not be parsed. */
    fun parseContentStreams(context: Context, uri: Uri): String? = try {
        val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        if (size > MAX_PARSE_BYTES) {
            null
        } else {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?.let { extractFromContentStreams(it) }
                ?.takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Content-stream parse failed: ${e.javaClass.simpleName}")
        null
    }

    // ── Raw PDF content-stream parser ─────────────────────────────────────────

    /**
     * Walks all stream...endstream blocks in the raw PDF bytes looking for
     * BT...ET (Begin Text / End Text) sections, then extracts string operands
     * from Tj / TJ / ' / " operators.
     */
    private fun extractFromContentStreams(bytes: ByteArray): String {
        val pdf = String(bytes, Charsets.ISO_8859_1)
        val sb  = StringBuilder()

        var searchFrom = 0
        while (true) {
            val streamStart = pdf.indexOf("stream", searchFrom).takeIf { it >= 0 } ?: break
            val dataStart = when {
                pdf.getOrNull(streamStart + 6) == '\r' &&
                pdf.getOrNull(streamStart + 7) == '\n' -> streamStart + 8
                pdf.getOrNull(streamStart + 6) == '\n' -> streamStart + 7
                else                                   -> streamStart + 6
            }
            val streamEnd = pdf.indexOf("endstream", dataStart).takeIf { it >= 0 } ?: break
            searchFrom = streamEnd + 9

            // Check if this stream object uses FlateDecode compression by scanning
            // backwards from "stream" to find the stream dictionary (<<...>>).
            val dictEnd   = streamStart
            val dictStart = pdf.lastIndexOf("<<", dictEnd).takeIf { it >= 0 } ?: continue
            val dict      = pdf.substring(dictStart, dictEnd)
            val isFlate   = dict.contains("FlateDecode") || dict.contains("Fl ")

            val block: String = if (isFlate) {
                val rawBytes = bytes.copyOfRange(
                    dataStart.coerceAtMost(bytes.size),
                    streamEnd.coerceAtMost(bytes.size)
                )
                tryInflate(rawBytes) ?: continue
            } else {
                pdf.substring(dataStart, streamEnd)
            }

            if (!block.contains("BT")) continue   // not a text content stream
            parseBtEtBlocks(block, sb)
        }

        // Fallback: plain string literals outside streams (simple/flat PDFs)
        if (sb.isBlank()) {
            Regex("""\(([^)]{4,})\)""").findAll(pdf).forEach { mr ->
                val s = mr.groupValues[1].decodePdfLiteral()
                if (s.isPrintableLine()) sb.append(s).append(' ')
            }
        }

        return sb.toString().clean()
    }

    /**
     * Clean raw PDF-extracted text: control characters, operator keywords that leaked
     * through, runs of whitespace, and lines that are only coordinate debris.
     */
    private fun String.clean(): String = this
        .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
        .replace(Regex("[\\x80-\\x9F]"), "")
        .replace(PDF_OPERATORS, " ")
        .replace(Regex("[ \t]{2,}"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .split("\n")
        .filter { line ->
            val t = line.trim()
            t.isEmpty() || (t.length >= 3 && t.any { it.isLetter() })
        }
        .joinToString("\n")
        .trim()

    private fun parseBtEtBlocks(block: String, sb: StringBuilder) {
        var i = 0
        while (i < block.length) {
            val bt = block.indexOf("BT", i).takeIf { it >= 0 } ?: break
            val et = block.indexOf("ET", bt + 2).takeIf { it >= 0 } ?: break
            extractStrings(block.substring(bt + 2, et), sb)
            i = et + 2
        }
    }

    private fun extractStrings(section: String, sb: StringBuilder) {
        PDF_CONTENT_RE.findAll(section).forEach { mr ->
            val literal = mr.groupValues[1]
            val hex     = mr.groupValues[2]
            val text = when {
                literal.isNotEmpty() -> literal.decodePdfLiteral()
                hex.isNotEmpty()     -> hex.decodeHex()
                else                 -> ""
            }
            if (text.isPrintableLine()) sb.append(text)
        }
        sb.append(' ')
    }

    private fun String.decodePdfLiteral() = replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\(", "(")
        .replace("\\)", ")")
        .replace("\\\\", "\\")

    private fun String.decodeHex(): String = try {
        chunked(2).map { it.toInt(16).toChar() }.joinToString("").filter { it.code in 32..126 }
    } catch (_: Exception) { "" }

    private fun String.isPrintableLine() = isNotBlank() && any { it.isLetterOrDigit() }

    /** zlib-decompress a FlateDecode stream; null for anything that is not one. */
    private fun tryInflate(data: ByteArray): String? = try {
        InflaterInputStream(ByteArrayInputStream(data))
            .use { it.readBytes() }
            .let { String(it, Charsets.ISO_8859_1) }
    } catch (_: Exception) {
        null
    }
}
