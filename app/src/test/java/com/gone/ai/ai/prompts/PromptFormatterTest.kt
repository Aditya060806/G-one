package com.gone.ai.ai.prompts

import com.gone.ai.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptFormatterTest {

    /** One "token" per character keeps the arithmetic in these tests obvious. */
    private val charTokens: (String) -> Int = { it.length }

    private fun message(id: Long, text: String, isUser: Boolean) = ChatMessage(id, text, isUser)

    // ── History budget ────────────────────────────────────────────────────────

    @Test
    fun `history is trimmed oldest first to fit the budget`() {
        val history = (1L..10L).map { message(it, "message-$it " + "x".repeat(200), isUser = it % 2 == 1L) }
        val system = "system"
        val input = "what now?"
        val budget = 1_000

        val prompt = PromptFormatter.buildPromptWithinBudget(history, input, system, budget, charTokens)

        assertTrue("prompt must fit: ${prompt.length}", prompt.length <= budget)
        assertTrue("newest message must be kept", prompt.contains("message-10 "))
        assertFalse("oldest message must be dropped", prompt.contains("message-1 "))
        assertTrue(prompt.contains(input))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `history that already fits is left alone`() {
        val history = listOf(message(1, "hello", true), message(2, "hi there", false))
        val prompt = PromptFormatter.buildPromptWithinBudget(history, "and?", "sys", 10_000, charTokens)
        assertEquals(PromptFormatter.buildPrompt(history, "and?", "sys"), prompt)
    }

    /** With no model loaded the count is unknown; the engine reports overflow itself. */
    @Test
    fun `unknown token counts leave the prompt untrimmed`() {
        val history = (1L..6L).map { message(it, "y".repeat(500), isUser = true) }
        val prompt = PromptFormatter.buildPromptWithinBudget(history, "q", "sys", 10) { -1 }
        assertEquals(PromptFormatter.buildPrompt(history, "q", "sys"), prompt)
    }

    /** The user's own question is never dropped or rewritten to make room. */
    @Test
    fun `the new input survives even when it alone exceeds the budget`() {
        val input = "z".repeat(5_000)
        val history = listOf(message(1, "earlier", true))
        val prompt = PromptFormatter.buildPromptWithinBudget(history, input, "sys", 100, charTokens)
        assertTrue(prompt.contains(input))
        assertFalse(prompt.contains("earlier"))
    }

    // ── Document budget ───────────────────────────────────────────────────────

    @Test
    fun `text within budget is not truncated`() {
        val fitted = PromptFormatter.truncateToTokenBudget("short text", 100, charTokens)
        assertEquals("short text", fitted.text)
        assertFalse(fitted.truncated)
    }

    @Test
    fun `truncation fits the budget and ends on a word boundary`() {
        val fitted = PromptFormatter.truncateToTokenBudget("alpha beta gamma delta", 12, charTokens)
        assertTrue(fitted.truncated)
        assertEquals("alpha beta", fitted.text)
        assertTrue(charTokens(fitted.text) <= 12)
    }

    @Test
    fun `truncation never splits a surrogate pair`() {
        val text = "ab😀cd"   // "ab😀cd"
        val fitted = PromptFormatter.truncateToTokenBudget(text, 3, charTokens)
        assertTrue(fitted.truncated)
        assertEquals("ab", fitted.text)
    }

    @Test
    fun `truncation uses the real count rather than a character guess`() {
        // A tokenizer where every character of debris costs a token and words cost one each.
        val debrisHeavy: (String) -> Int = { s -> s.split(' ').sumOf { w -> if (w.all(Char::isLetter)) 1 else w.length } }
        val text = "words words words ###############"
        val fitted = PromptFormatter.truncateToTokenBudget(text, 4, debrisHeavy)
        assertTrue(fitted.truncated)
        assertTrue(debrisHeavy(fitted.text) <= 4)
        assertTrue(fitted.text.startsWith("words words words"))
    }
}
