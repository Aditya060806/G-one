package com.gone.ai.chat

import com.gone.ai.ai.prompts.PromptFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPromptTest {

    @Test
    fun `with nothing added it is the base prompt`() {
        assertEquals(PromptFormatter.DEFAULT_SYSTEM_PROMPT, ChatPrompt.system())
        assertEquals(PromptFormatter.DEFAULT_SYSTEM_PROMPT, ChatPrompt.system(healthContext = "  ", attachmentText = ""))
    }

    @Test
    fun `personal facts are fenced and come with their rules`() {
        val prompt = ChatPrompt.system(healthContext = "Age: 40.")
        assertTrue(prompt.startsWith(PromptFormatter.DEFAULT_SYSTEM_PROMPT))
        assertTrue(prompt.contains(ChatPrompt.HEALTH_RULES))
        assertTrue(prompt.contains("--- PERSON START ---\nAge: 40.\n--- PERSON END ---"))
        assertFalse(prompt.contains("DOCUMENT START"))
    }

    @Test
    fun `a document comes after the personal facts`() {
        val prompt = ChatPrompt.system(attachmentTitle = "Blood test", attachmentText = "Hb 13.2", healthContext = "Age: 40.")
        assertTrue(prompt.indexOf("PERSON END") < prompt.indexOf("titled \"Blood test\""))
        assertTrue(prompt.contains("--- DOCUMENT START ---\nHb 13.2\n--- DOCUMENT END ---"))
    }
}
