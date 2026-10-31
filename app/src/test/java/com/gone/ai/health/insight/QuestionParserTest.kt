package com.gone.ai.health.insight

import com.gone.ai.health.domain.AnomalyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuestionParserTest {

    private fun parse(q: String) = QuestionParser.parse(q) ?: error("not understood: $q")

    @Test fun `highest heart rate yesterday`() {
        val q = parse("What was my highest heart rate yesterday?")
        assertEquals(InsightMetric.HEART_RATE, q.metric)
        assertEquals(InsightOp.HIGHEST, q.op)
        assertEquals(TimeRange.Yesterday, q.range)
    }

    @Test fun `oxygen last night defaults to a trend`() {
        val q = parse("How was my oxygen last night")
        assertEquals(InsightMetric.SPO2, q.metric)
        assertEquals(InsightOp.TREND, q.op)
        assertEquals(TimeRange.LastNight, q.range)
    }

    @Test fun `numbered periods`() {
        assertEquals(TimeRange.LastDays(3), parse("average pulse over the last 3 days").range)
        assertEquals(TimeRange.LastHours(12), parse("skin temperature past 12 hours").range)
        assertEquals(TimeRange.LastDays(14), parse("sleep in 2 weeks").range)
        assertEquals(TimeRange.LastDays(TimeRange.MAX_DAYS), parse("heart rate last 400 days").range)
    }

    @Test fun `no period means the last week`() {
        assertEquals(QuestionParser.DEFAULT_RANGE, parse("show my heart rate").range)
    }

    @Test fun `how many falls is about fall alerts`() {
        val q = parse("How many times did I fall this week?")
        assertEquals(InsightMetric.ALERTS, q.metric)
        assertEquals(InsightOp.COUNT, q.op)
        assertEquals(AnomalyType.FALL_DETECTED, q.alertType)
        assertEquals(TimeRange.ThisWeek, q.range)
    }

    @Test fun `hardest fall stays a movement question`() {
        val q = parse("What was my hardest fall today")
        assertEquals(InsightMetric.MOVEMENT, q.metric)
        assertEquals(InsightOp.HIGHEST, q.op)
        assertNull(q.alertType)
    }

    @Test fun `heart rate compared with last week`() {
        val q = parse("Compare my heart rate this week vs last week")
        assertEquals(InsightOp.COMPARE, q.op)
        assertEquals(TimeRange.ThisWeek, q.range)
        assertEquals(TimeRange.PreviousWeek, q.compareTo)
    }

    @Test fun `counting a reading becomes a trend`() {
        assertEquals(InsightOp.TREND, parse("how many heart rate readings today").op)
    }

    @Test fun `min is not found inside minutes`() {
        assertEquals(InsightOp.TREND, parse("heart rate in the last 30 minutes").op)
    }

    @Test fun `unrelated question is not understood`() {
        assertNull(QuestionParser.parse("What is the capital of France?"))
    }

    // Hindi

    @Test fun `hindi highest heart rate yesterday`() {
        val q = parse("कल मेरी दिल की धड़कन सबसे ज़्यादा कितनी थी?")
        assertEquals(InsightMetric.HEART_RATE, q.metric)
        assertEquals(InsightOp.HIGHEST, q.op)
        assertEquals(TimeRange.Yesterday, q.range)
    }

    @Test fun `hindi last night is not yesterday`() {
        val q = parse("कल रात मेरा ऑक्सीजन कैसा था")
        assertEquals(InsightMetric.SPO2, q.metric)
        assertEquals(TimeRange.LastNight, q.range)
    }

    @Test fun `hindi sleep average this week`() {
        val q = parse("इस हफ़्ते मेरी नींद का औसत")
        assertEquals(InsightMetric.SLEEP, q.metric)
        assertEquals(InsightOp.AVERAGE, q.op)
        assertEquals(TimeRange.ThisWeek, q.range)
    }

    @Test fun `hindi falls count with devanagari digits`() {
        val q = parse("पिछले ३ दिन में मैं कितनी बार गिरा")
        assertEquals(InsightMetric.ALERTS, q.metric)
        assertEquals(AnomalyType.FALL_DETECTED, q.alertType)
        assertEquals(TimeRange.LastDays(3), q.range)
    }

    @Test fun `hindi heart rate word is not motion`() {
        assertEquals(InsightMetric.HEART_RATE, parse("आज हृदय गति दिखाओ").metric)
    }

    @Test fun `hindi temperature today`() {
        val q = parse("आज मेरा तापमान अभी कितना है")
        assertEquals(InsightMetric.SKIN_TEMPERATURE, q.metric)
        assertEquals(InsightOp.LATEST, q.op)
        assertEquals(TimeRange.Today, q.range)
    }
}
