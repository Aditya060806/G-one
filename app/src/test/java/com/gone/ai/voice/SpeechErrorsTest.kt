package com.gone.ai.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechErrorsTest {

    @Test
    fun `a missing offline pack falls back to the phone's service`() {
        val described = SpeechErrors.describe(SpeechErrors.ERROR_LANGUAGE_UNAVAILABLE, onDevice = true)
        assertTrue(described.tryDefaultService)
        assertTrue(described.message.contains("offline speech pack"))
    }

    @Test
    fun `the phone's own service not supporting the language does not loop`() {
        assertFalse(SpeechErrors.describe(SpeechErrors.ERROR_LANGUAGE_NOT_SUPPORTED, onDevice = false).tryDefaultService)
    }

    @Test
    fun `hearing nothing is quiet, not a failure`() {
        assertTrue(SpeechErrors.describe(SpeechErrors.ERROR_NO_MATCH, onDevice = true).quiet)
        assertTrue(SpeechErrors.describe(SpeechErrors.ERROR_SPEECH_TIMEOUT, onDevice = false).quiet)
    }

    @Test
    fun `a permission problem says so`() {
        assertTrue(SpeechErrors.describe(SpeechErrors.ERROR_INSUFFICIENT_PERMISSIONS, onDevice = true).needsPermission)
    }

    @Test
    fun `network errors explain the offline pack`() {
        assertTrue(SpeechErrors.describe(SpeechErrors.ERROR_NETWORK, onDevice = false).message.contains("offline language pack"))
    }

    @Test
    fun `unknown codes still give a message`() {
        assertTrue(SpeechErrors.describe(99, onDevice = true).message.contains("99"))
    }
}
