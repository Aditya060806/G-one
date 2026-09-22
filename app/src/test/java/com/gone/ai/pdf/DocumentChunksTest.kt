package com.gone.ai.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentChunksTest {

    private fun paragraph(n: Int) = "Paragraph $n says something about blood pressure readings over a week. " +
        "It has a second sentence with a number such as ${120 + n}/80 mmHg. " +
        "And a third sentence so the paragraph has some length to it."

    @Test
    fun `a short document is one part`() {
        val plan = DocumentChunks.plan("  One short note.  ")
        assertEquals(listOf("One short note."), plan.parts)
        assertFalse(plan.truncated)
    }

    @Test
    fun `an empty document has no parts`() {
        assertEquals(DocumentChunks.Plan(emptyList(), truncated = false), DocumentChunks.plan("   "))
    }

    @Test
    fun `parts stay within the target and end at paragraph breaks`() {
        val text = (1..40).joinToString("\n\n") { paragraph(it) }
        val plan = DocumentChunks.plan(text, targetChars = 1_000, maxParts = 100)

        assertTrue(plan.parts.size > 1)
        assertFalse(plan.truncated)
        plan.parts.forEach { part ->
            assertTrue("too long: ${part.length}", part.length <= 1_000)
            assertTrue("does not end a paragraph: …${part.takeLast(30)}", part.endsWith("to it."))
        }
        // Nothing lost and nothing reordered.
        val words = { s: String -> s.split(Regex("\\s+")).filter { it.isNotEmpty() } }
        assertEquals(words(text), plan.parts.flatMap(words))
    }

    @Test
    fun `without paragraph breaks a part ends at a sentence`() {
        val text = (1..30).joinToString(" ") { paragraph(it) }
        val plan = DocumentChunks.plan(text, targetChars = 700, maxParts = 100)
        plan.parts.dropLast(1).forEach { assertTrue(it.endsWith(".")) }
    }

    @Test
    fun `text with no spaces is cut at the limit`() {
        val plan = DocumentChunks.plan("x".repeat(2_500), targetChars = 1_000, maxParts = 100)
        assertEquals(listOf(1_000, 1_000, 500), plan.parts.map { it.length })
    }

    @Test
    fun `a document longer than the part limit says it was cut`() {
        val text = (1..60).joinToString("\n\n") { paragraph(it) }
        val plan = DocumentChunks.plan(text, targetChars = 800, maxParts = 3)
        assertEquals(3, plan.parts.size)
        assertTrue(plan.truncated)
    }
}
