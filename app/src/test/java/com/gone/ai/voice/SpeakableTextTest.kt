package com.gone.ai.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakableTextTest {

    @Test
    fun `finished sentences are spoken as the reply streams, unfinished ones wait`() {
        val speech = SpeakableText()
        assertEquals(emptyList<String>(), speech.update("Your heart rate"))
        assertEquals(listOf("Your heart rate was 72 bpm."), speech.update("Your heart rate was 72 bpm. That is"))
        assertEquals(emptyList<String>(), speech.update("Your heart rate was 72 bpm. That is within"))
        assertEquals(listOf("That is within the usual range."), speech.finish("Your heart rate was 72 bpm. That is within the usual range."))
    }

    @Test
    fun `decimals are not sentence ends`() {
        val speech = SpeakableText()
        assertEquals(listOf("Skin temperature was 33.4 °C today."), speech.update("Skin temperature was 33.4 °C today. "))
    }

    @Test
    fun `markup is removed and lines become sentences`() {
        val speech = SpeakableText()
        val said = speech.finish("## Summary\n- **Heart rate** stayed steady\n1. Rest well\n> quoted note")
        assertEquals(listOf("Summary", "Heart rate stayed steady", "Rest well", "quoted note"), said)
    }

    @Test
    fun `code is mentioned once, not read out`() {
        val speech = SpeakableText()
        val said = speech.finish("Here it is:\n```kotlin\nval x = 1\nval y = 2\n```\nDone.")
        assertEquals(listOf("Here it is:", "There is some code in the chat.", "Done."), said)
    }

    @Test
    fun `starting a new reply starts again`() {
        val speech = SpeakableText()
        speech.update("First reply. ")
        assertTrue(speech.update("New. ").contains("New."))
    }

    @Test
    fun `SpO2 is spelled out for the voice`() {
        assertEquals("S P O 2 was 97%", SpeakableText.clean("**SpO₂** was 97%"))
    }
}
