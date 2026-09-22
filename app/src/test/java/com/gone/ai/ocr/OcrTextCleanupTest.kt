package com.gone.ai.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTextCleanupTest {
    @Test fun preservesStandaloneValuesAndMedicalSymbols() {
        val report = "Grade\n1\nFlag\n+\n<\n5 mg/L\n±\n0.2\nµg\n°C\n%"
        assertEquals(report, OcrTextCleanup.clean(report))
    }

    @Test fun preservesColumnSpacingAndLineOrder() {
        assertEquals("Test    Value    Unit\nHb      12.5     g/dL", OcrTextCleanup.clean("Test    Value    Unit\r\nHb      12.5     g/dL  "))
    }

    @Test fun removesControlsWithoutRemovingContent() {
        assertEquals("A\n\nB", OcrTextCleanup.clean("\u0000A\r\n\r\n\r\nB\u0007"))
    }
}
