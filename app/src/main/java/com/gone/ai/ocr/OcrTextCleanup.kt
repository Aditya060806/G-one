package com.gone.ai.ocr

/** Preserve recognized values and symbols; cleanup must never reinterpret a report. */
object OcrTextCleanup {
    fun clean(raw: String): String = raw
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
        .lineSequence()
        .map { it.trimEnd() }
        .joinToString("\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
