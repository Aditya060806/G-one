package com.gone.ai.ocr

import com.gone.ai.data.library.EntryType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentTaskTest {

    private class FakeEngine : DocumentTask.Engine {
        var prepareError: Exception? = null
        var truncated = false
        var tokens = listOf("Hello", " there", " friend")
        /** Throw instead of emitting the token at this index. */
        var failAt: Int? = null
        var neverAnswers = false

        override suspend fun prepare(instruction: String, document: String, tokenBudget: Int): AiTextProcessor.PreparedPrompt {
            prepareError?.let { throw it }
            return AiTextProcessor.PreparedPrompt("$instruction\n\n$document", document, truncated)
        }

        override fun generate(prompt: String): Flow<String> = flow {
            if (neverAnswers) awaitCancellation()
            tokens.forEachIndexed { i, token ->
                delay(10)
                if (i == failAt) throw IllegalStateException("decode failed")
                emit(token)
            }
        }
    }

    private val target = SaveTarget(EntryType.OCR, "OCR – Explain")
    private val saved = mutableListOf<Pair<SaveTarget, String>>()

    // The test scope itself, not backgroundScope: advanceUntilIdle() does not run background
    // work, and every run here ends on its own (or is stopped).
    private fun TestScope.task(engine: FakeEngine) =
        DocumentTask(this, engine) { t, text -> saved += t to text }

    @Test
    fun `a finished answer is saved once`() = runTest {
        val task = task(FakeEngine())
        assertTrue(task.start("Explain", "doc", target))
        advanceUntilIdle()

        assertEquals(TaskStatus.Finished(ResultStatus.DONE), task.status.value)
        assertEquals("Hello there friend", task.output.value)
        assertEquals(listOf(target to "Hello there friend"), saved)
        assertTrue(task.saved.value)

        task.saveNow()
        assertEquals(1, saved.size)
    }

    @Test
    fun `stopping keeps the text on screen and saves nothing until asked`() = runTest {
        val task = task(FakeEngine())
        task.start("Explain", "doc", target)
        advanceTimeBy(25)
        runCurrent()
        assertEquals(TaskStatus.Streaming(2), task.status.value)

        task.stop()
        advanceUntilIdle()

        assertEquals(TaskStatus.Finished(ResultStatus.STOPPED), task.status.value)
        assertEquals("Hello there", task.output.value)
        assertTrue(saved.isEmpty())

        task.saveNow()
        assertEquals(listOf(target.copy(title = "OCR – Explain (incomplete)") to "Hello there"), saved)
    }

    @Test
    fun `an error after some text is partial and not saved`() = runTest {
        val task = task(FakeEngine().apply { failAt = 2 })
        task.start("Explain", "doc", target)
        advanceUntilIdle()

        assertEquals(TaskStatus.Finished(ResultStatus.PARTIAL), task.status.value)
        assertEquals("Hello there", task.output.value)
        assertTrue(saved.isEmpty())
        assertFalse(task.saved.value)
    }

    @Test
    fun `an error before any text is a failure with the reason`() = runTest {
        val task = task(FakeEngine().apply { failAt = 0 })
        task.start("Explain", "doc", target)
        advanceUntilIdle()

        assertEquals(TaskStatus.Failed("Generation failed: decode failed"), task.status.value)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a model that never answers times out`() = runTest {
        val task = task(FakeEngine().apply { neverAnswers = true })
        task.start("Explain", "doc", target)
        advanceTimeBy(AiTextProcessor.FIRST_TOKEN_TIMEOUT_MS - 1)
        runCurrent()
        assertTrue(task.status.value.isRunning)

        advanceUntilIdle()
        assertEquals(TaskStatus.Failed("Model did not respond. Please try again."), task.status.value)
    }

    @Test
    fun `a model that cannot load says why`() = runTest {
        val task = task(FakeEngine().apply { prepareError = IllegalStateException("out of memory") })
        task.start("Explain", "doc", target)
        advanceUntilIdle()

        assertEquals(TaskStatus.Failed("AI engine unavailable: out of memory"), task.status.value)
    }

    @Test
    fun `a second start while one runs is refused`() = runTest {
        val task = task(FakeEngine())
        assertTrue(task.start("Explain", "doc", target))
        assertFalse(task.start("Summarize", "doc", target))
        advanceUntilIdle()
        assertEquals("Hello there friend", task.output.value)
    }

    @Test
    fun `reset forgets the result and ignores the run it replaced`() = runTest {
        val task = task(FakeEngine())
        task.start("Explain", "doc", target)
        advanceTimeBy(15)
        runCurrent()

        task.reset()
        advanceUntilIdle()

        assertEquals(TaskStatus.Idle, task.status.value)
        assertEquals("", task.output.value)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `truncation is reported and nothing is saved without a target`() = runTest {
        val task = task(FakeEngine().apply { truncated = true })
        task.start("Explain", "doc", saveAs = null)
        advanceUntilIdle()

        assertTrue(task.truncated.value)
        assertEquals(TaskStatus.Finished(ResultStatus.DONE), task.status.value)
        assertTrue(saved.isEmpty())
    }
}
