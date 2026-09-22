package com.gone.ai.ui.components

import com.gone.ai.ui.components.MarkdownBlock.Bullet
import com.gone.ai.ui.components.MarkdownBlock.Code
import com.gone.ai.ui.components.MarkdownBlock.Gap
import com.gone.ai.ui.components.MarkdownBlock.Heading
import com.gone.ai.ui.components.MarkdownBlock.Numbered
import com.gone.ai.ui.components.MarkdownBlock.Paragraph
import com.gone.ai.ui.components.MarkdownBlock.Quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {

    @Test
    fun `headings, bullets, numbers and quotes become blocks`() {
        val blocks = MarkdownBlocks.parse(
            """
            ## Summary
            - first point
            * second point
            • third point
            1. step one
            2) step two
            > a quoted line
            Plain sentence.
            """.trimIndent()
        )
        assertEquals(
            listOf(
                Heading(2, "Summary"),
                Bullet("first point"),
                Bullet("second point"),
                Bullet("third point"),
                Numbered("1", "step one"),
                Numbered("2", "step two"),
                Quote("a quoted line"),
                Paragraph("Plain sentence.")
            ),
            blocks
        )
    }

    @Test
    fun `blank lines collapse to one gap and never lead or trail`() {
        val blocks = MarkdownBlocks.parse("\n\nFirst\n\n\n\nSecond\n\n")
        assertEquals(listOf(Paragraph("First"), Gap, Paragraph("Second")), blocks)
    }

    @Test
    fun `deep headings are capped at level three`() {
        assertEquals(listOf(Heading(3, "Deep")), MarkdownBlocks.parse("##### Deep ##"))
    }

    @Test
    fun `lab values are not mistaken for markup`() {
        val blocks = MarkdownBlocks.parse(">90 mL/min\n1.5 mg daily\n#5 on the list\n**Hemoglobin:** 13.2 g/dL")
        assertEquals(
            listOf(
                Paragraph(">90 mL/min"),
                Paragraph("1.5 mg daily"),
                Paragraph("#5 on the list"),
                Paragraph("**Hemoglobin:** 13.2 g/dL")
            ),
            blocks
        )
    }

    @Test
    fun `fenced code keeps its language and text`() {
        val blocks = MarkdownBlocks.parse("Before\n```kotlin\nval x = 1\nval y = 2\n```\nAfter")
        assertEquals(
            listOf(Paragraph("Before"), Code("kotlin", "val x = 1\nval y = 2"), Paragraph("After")),
            blocks
        )
    }

    @Test
    fun `an unclosed fence while streaming is still code`() {
        val blocks = MarkdownBlocks.parse("Here:\n```python\nprint(1)")
        assertEquals(listOf(Paragraph("Here:"), Code("python", "print(1)", closed = false)), blocks)
    }

    @Test
    fun `empty text gives one empty paragraph for the cursor`() {
        assertEquals(listOf(Paragraph("")), MarkdownBlocks.parse(""))
    }

    @Test
    fun `inline emphasis is split into styled runs`() {
        val spans = MarkdownBlocks.inline("A **bold**, *italic*, ***both***, `code` and ~~gone~~ end")
        assertEquals(
            listOf(
                InlineSpan("A ", InlineStyle.PLAIN),
                InlineSpan("bold", InlineStyle.BOLD),
                InlineSpan(", ", InlineStyle.PLAIN),
                InlineSpan("italic", InlineStyle.ITALIC),
                InlineSpan(", ", InlineStyle.PLAIN),
                InlineSpan("both", InlineStyle.BOLD_ITALIC),
                InlineSpan(", ", InlineStyle.PLAIN),
                InlineSpan("code", InlineStyle.CODE),
                InlineSpan(" and ", InlineStyle.PLAIN),
                InlineSpan("gone", InlineStyle.STRIKE),
                InlineSpan(" end", InlineStyle.PLAIN)
            ),
            spans
        )
    }

    @Test
    fun `arithmetic and identifiers are not italicised`() {
        assertEquals(listOf(InlineSpan("2*3*4 and snake_case_name", InlineStyle.PLAIN)), MarkdownBlocks.inline("2*3*4 and snake_case_name"))
    }

    @Test
    fun `plain text drops markers but keeps structure`() {
        val plain = MarkdownBlocks.plainText("## Result\n- **Heart rate** 72\n1. Rest\n\nDone")
        assertEquals("Result\n• Heart rate 72\n1. Rest\n\nDone", plain)
        assertFalse(plain.contains("*"))
        assertTrue(plain.contains("72"))
    }
}
