package com.gone.ai.health.insight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryInterpreterTest {

    @Test fun `a well formed line is accepted`() {
        val q = QueryInterpreter.parseModelLine("metric=spo2; op=trend; range=last_night")!!
        assertEquals(InsightMetric.SPO2, q.metric)
        assertEquals(InsightOp.TREND, q.op)
        assertEquals(TimeRange.LastNight, q.range)
    }

    @Test fun `chatter around the line is ignored`() {
        val q = QueryInterpreter.parseModelLine("Sure!\n`metric=sleep; op=average; range=days:5`\n")!!
        assertEquals(TimeRange.LastDays(5), q.range)
    }

    @Test fun `hour periods are capped`() {
        val q = QueryInterpreter.parseModelLine("metric=heart_rate; op=highest; range=hours:9999")!!
        assertEquals(TimeRange.LastHours(TimeRange.MAX_DAYS * 24), q.range)
    }

    @Test fun `none and unknown words are rejected`() {
        assertNull(QueryInterpreter.parseModelLine("none"))
        assertNull(QueryInterpreter.parseModelLine("metric=blood_sugar; op=trend; range=today"))
        assertNull(QueryInterpreter.parseModelLine("metric=spo2; op=guess; range=today"))
        assertNull(QueryInterpreter.parseModelLine("metric=spo2; op=trend; range=forever"))
        assertNull(QueryInterpreter.parseModelLine("metric=spo2; op=trend; range=days:0"))
    }

    @Test fun `a value smuggled in an extra field is rejected`() {
        assertNull(QueryInterpreter.parseModelLine("metric=spo2; op=latest; range=today; value=98"))
    }

    @Test fun `count on a reading becomes a trend`() {
        assertEquals(InsightOp.TREND, QueryInterpreter.parseModelLine("metric=spo2; op=count; range=today")!!.op)
    }

    @Test fun `the prompt carries the question on one line`() {
        val prompt = QueryInterpreter.prompt("how was\nmy pulse")
        assertTrue(prompt.contains("Question: how was my pulse"))
    }
}
