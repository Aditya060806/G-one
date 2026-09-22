package com.gone.ai.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizParserTest {

    @Test
    fun `reads the layout the prompt asks for`() {
        val quiz = QuizParser.parse(
            """
            Q1. What does SpO2 measure?
            A) Blood sugar
            B) Oxygen saturation
            C) Heart rhythm
            D) Body temperature
            Answer: B
            Why: SpO2 is the share of haemoglobin carrying oxygen.

            Q2. What is a normal resting heart rate range for adults?
            A) 20-40 bpm
            B) 40-50 bpm
            C) 60-100 bpm
            D) 120-160 bpm
            Answer: C
            Why: The text gives 60 to 100 beats per minute.
            """.trimIndent()
        )

        assertEquals(2, quiz.size)
        assertEquals(QuizQuestion(1, "What does SpO2 measure?",
            listOf("Blood sugar", "Oxygen saturation", "Heart rhythm", "Body temperature"), 1,
            "SpO2 is the share of haemoglobin carrying oxygen."), quiz[0])
        assertEquals(2, quiz[1].correctIndex)
        assertEquals("60-100 bpm", quiz[1].options[quiz[1].correctIndex])
    }

    @Test
    fun `accepts the variations a small model drifts into`() {
        val quiz = QuizParser.parse(
            """
            **Question 1:** Which organ filters blood?
            (a) Heart
            (b) Kidney
            (c) Lung
            (d) Skin
            **Correct answer is B**
            Explanation - The kidneys filter the blood.
            """.trimIndent()
        )

        assertEquals(1, quiz.size)
        assertEquals("Which organ filters blood?", quiz[0].question)
        assertEquals(listOf("Heart", "Kidney", "Lung", "Skin"), quiz[0].options)
        assertEquals(1, quiz[0].correctIndex)
        assertEquals("The kidneys filter the blood.", quiz[0].explanation)
    }

    @Test
    fun `splits options written on one line, but only in letter order`() {
        val inline = QuizParser.parse("1. Pick one\nA) red B) green C) blue D) grey\nAnswer: C")
        assertEquals(listOf("red", "green", "blue", "grey"), inline.single().options)

        val vitamin = QuizParser.parse("1. Which deficiency?\nA) Iron\nB) Vitamin A. deficiency\nAnswer: B")
        assertEquals("Vitamin A. deficiency", vitamin.single().options[1])
    }

    @Test
    fun `joins a question written over two lines`() {
        val quiz = QuizParser.parse("Q1. Which reading\nis taken from the fingertip?\nA) SpO2\nB) Blood pressure\nAnswer: A")
        assertEquals("Which reading is taken from the fingertip?", quiz.single().question)
        assertNull(quiz.single().explanation)
    }

    @Test
    fun `drops questions it cannot trust`() {
        val quiz = QuizParser.parse(
            """
            Q1. No answer given
            A) one
            B) two

            Q2. Answer names a missing option
            A) one
            B) two
            Answer: D

            Q3. Only one option
            A) one
            Answer: A

            Q4. Fine
            A) yes
            B) no
            Answer: A
            """.trimIndent()
        )
        assertEquals(listOf(4), quiz.map { it.number })
    }

    @Test
    fun `plain prose yields no questions`() {
        assertTrue(QuizParser.parse("Here are some thoughts about the text. It covers heart health.").isEmpty())
    }
}
