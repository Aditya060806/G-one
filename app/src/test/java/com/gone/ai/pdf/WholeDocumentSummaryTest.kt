package com.gone.ai.pdf

import com.gone.ai.ocr.AiTextProcessor
import com.gone.ai.ocr.DocumentTask
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WholeDocumentSummaryTest {

    private data class Call(val instruction: String, val document: String, val visible: Boolean, val budget: Int)

    private class FakeSteps(private val results: (Call) -> DocumentTask.StepResult) : DocumentTask.Steps {
        val calls = mutableListOf<Call>()
        val stages = mutableListOf<String?>()
        override fun stage(label: String?) { stages += label }
        override suspend fun generate(instruction: String, document: String, visible: Boolean, tokenBudget: Int) =
            Call(instruction, document, visible, tokenBudget).also { calls += it }.let(results)
    }

    private fun done(text: String) = DocumentTask.StepResult(text, AiTextProcessor.Outcome.Done, truncated = false)

    @Test
    fun `each part is noted out of sight, then one summary is written from the notes in order`() = runTest {
        val steps = FakeSteps { call -> done(if (call.visible) "- whole summary" else "- note on ${call.document}") }

        val outcome = WholeDocumentSummary.run(steps, listOf("alpha", "beta", "gamma"))

        assertEquals(AiTextProcessor.Outcome.Done, outcome)
        assertEquals(4, steps.calls.size)
        assertEquals(listOf(false, false, false, true), steps.calls.map { it.visible })
        assertEquals(listOf("alpha", "beta", "gamma"), steps.calls.take(3).map { it.document })
        val combine = steps.calls.last()
        assertEquals(WholeDocumentSummary.COMBINE_PROMPT, combine.instruction)
        assertEquals(WholeDocumentSummary.NOTES_TOKEN_BUDGET, combine.budget)
        assertEquals("Part 1:\n- note on alpha\n\nPart 2:\n- note on beta\n\nPart 3:\n- note on gamma", combine.document)
        assertEquals(listOf("Reading part 1 of 3", "Reading part 2 of 3", "Reading part 3 of 3", "Writing the summary"), steps.stages)
    }

    @Test
    fun `a single part is summarised directly`() = runTest {
        val steps = FakeSteps { done("- summary") }
        assertEquals(AiTextProcessor.Outcome.Done, WholeDocumentSummary.run(steps, listOf("only part")))
        assertEquals(listOf(Call(WholeDocumentSummary.QUICK_PROMPT, "only part", true, AiTextProcessor.DOCUMENT_TOKEN_BUDGET)), steps.calls)
    }

    @Test
    fun `a part that fails stops the run and says which`() = runTest {
        val steps = FakeSteps { call ->
            if (call.document == "beta") DocumentTask.StepResult("", AiTextProcessor.Outcome.Failed("Model did not respond. Please try again."), false)
            else done("- note")
        }
        val outcome = WholeDocumentSummary.run(steps, listOf("alpha", "beta", "gamma"))
        assertEquals(AiTextProcessor.Outcome.Failed("Part 2 of 3 could not be read: Model did not respond. Please try again."), outcome)
        assertEquals(2, steps.calls.size)
    }

    @Test
    fun `a part cut short makes the finished summary incomplete`() = runTest {
        val steps = FakeSteps { call ->
            if (call.document == "alpha") DocumentTask.StepResult("- half a note", AiTextProcessor.Outcome.Partial, false)
            else done("- text")
        }
        assertEquals(AiTextProcessor.Outcome.Partial, WholeDocumentSummary.run(steps, listOf("alpha", "beta")))
        assertTrue(steps.calls.last().document.contains("- half a note"))
    }

    @Test
    fun `no parts is a failure, not an empty summary`() = runTest {
        val outcome = WholeDocumentSummary.run(FakeSteps { done("") }, emptyList())
        assertTrue(outcome is AiTextProcessor.Outcome.Failed)
    }
}
