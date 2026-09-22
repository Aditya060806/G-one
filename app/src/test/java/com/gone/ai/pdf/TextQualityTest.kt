package com.gone.ai.pdf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextQualityTest {

    @Test
    fun `ordinary prose is readable`() {
        assertTrue(TextQuality.isReadable(
            "The patient was admitted with a fever and a dry cough. Blood tests were taken on arrival."
        ))
    }

    @Test
    fun `a lab table with numbers and units is readable`() {
        assertTrue(TextQuality.isReadable(
            """
            Haemoglobin 13.2 g/dL (12.0 - 15.5)
            White cell count 6.1 x10^9/L (4.0 - 11.0)
            Platelets 250 x10^9/L (150 - 400)
            Fasting glucose 5.4 mmol/L
            """.trimIndent()
        ))
    }

    @Test
    fun `Devanagari with vowel signs is readable`() {
        assertTrue(TextQuality.isReadable("रोगी को तेज़ बुखार और सूखी खांसी थी। रक्त जांच आने पर की गई।"))
    }

    @Test
    fun `glyph codes from an unmapped font are not readable`() {
        assertFalse(TextQuality.isReadable("  abc def ghi jkl mno pqr stu vwx"))
    }

    @Test
    fun `words glued together without spaces are not readable`() {
        assertFalse(TextQuality.isReadable("Thepatientwasadmittedwithafeverandadrycough Bloodtestsweretakenonarrival"))
    }

    @Test
    fun `a page number or stray header is not enough`() {
        assertFalse(TextQuality.isReadable("Page 12"))
        assertFalse(TextQuality.isReadable(""))
    }

    @Test
    fun `symbol soup is not readable`() {
        assertFalse(TextQuality.isReadable("%%EOF ÿØÿà ÐÏà¡± ¶¶¶ ¤¤¤ ØØØ þþþ ÇÇÇ ÆÆÆ ßßß ÑÑÑ ¿¿¿ ¦¦¦ ¬¬¬ ±±±"))
    }
}
