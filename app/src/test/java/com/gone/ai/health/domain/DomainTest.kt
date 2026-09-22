package com.gone.ai.health.domain

import com.gone.ai.health.TestVitals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnomalyEvidenceJsonTest {

    private fun evidence(
        spo2: Int? = 89,
        hr: Int? = 108,
        temp: Float? = 37.4f,
        duration: Int? = 12,
        trend: Trend? = Trend.FALLING
    ) = AnomalyEvidence(
        type = AnomalyType.LOW_SPO2,
        severity = Severity.CRITICAL,
        triggeredAt = TestVitals.T0,
        ruleId = "spo2.critical",
        riskScores = RiskScores(30, 70, 45),
        heartRate = hr,
        spo2 = spo2,
        bodyTempC = temp,
        durationMinutes = duration,
        trend = trend
    )

    @Test
    fun `emits the core fields`() {
        val json = evidence().toJson()
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains("\"event\":\"low_spo2\""))
        assertTrue(json.contains("\"severity\":\"CRITICAL\""))
        assertTrue(json.contains("\"spo2\":89"))
        assertTrue(json.contains("\"heart_rate\":108"))
        assertTrue(json.contains("\"duration_minutes\":12"))
        assertTrue(json.contains("\"trend\":\"falling\""))
        assertTrue(json.contains("\"risk\":{\"heat\":30,\"respiratory\":70,\"cardiovascular\":45}"))
    }

    @Test
    fun `confirmed fall keeps the original impact for SOS`() {
        val json = evidence().copy(
            type = AnomalyType.FALL_DETECTED,
            ruleId = "motion.fall",
            fallImpactG = 5.4f
        ).toJson()
        assertTrue(json.contains("\"fall_impact_g\":5.4"))
        assertFalse(evidence().toJson().contains("\"fall_impact_g\""))
    }

    /**
     * Nulls must be absent, not `null`. Showing the model `"spo2": null` invites it
     * to reason about a reading that was never taken.
     */
    @Test
    fun `omits absent values entirely`() {
        val json = evidence(spo2 = null, hr = null, duration = null, trend = null).toJson()

        assertFalse("literal null leaked into the payload", json.contains("null"))

        // Assert on the JSON *key*, not the bare word. "spo2" also appears inside
        // the event name ("low_spo2") and the rule id ("spo2.critical"), so a
        // substring check would fail even though the field is correctly absent.
        assertFalse(json.contains("\"spo2\":"))
        assertFalse(json.contains("\"heart_rate\":"))
        assertFalse(json.contains("\"duration_minutes\":"))
        assertFalse(json.contains("\"trend\":"))

        // Values that WERE present must survive the same pass.
        assertTrue(json.contains("\"body_temp_c\":37.4"))
        assertTrue(json.contains("\"event\":\"low_spo2\""))
    }

    @Test
    fun `formats floats with at most one decimal`() {
        assertEquals("37.4", jsonFloat(37.44f))
        assertEquals("37.5", jsonFloat(37.45f))
        assertEquals("38", jsonFloat(38.0f))
        assertEquals("41", jsonFloat(41.02f))
    }

    /** NaN and infinity are not valid JSON numbers and would break every consumer. */
    @Test
    fun `collapses non-finite floats to zero`() {
        assertEquals("0", jsonFloat(Float.NaN))
        assertEquals("0", jsonFloat(Float.POSITIVE_INFINITY))
        assertEquals("0", jsonFloat(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `escapes strings to valid JSON`() {
        assertEquals("\"a\\\"b\"", jsonString("a\"b"))
        assertEquals("\"a\\\\b\"", jsonString("a\\b"))
        assertEquals("\"a\\nb\"", jsonString("a\nb"))
        assertEquals("\"a\\tb\"", jsonString("a\tb"))
        // Control characters below 0x20 must be \u-escaped, not emitted raw.
        assertTrue(jsonString("a\u0001b").contains("\\u0001"))
    }
}

class SeverityTest {

    @Test
    fun `ranks ascend`() {
        assertTrue(Severity.CRITICAL.rank > Severity.MODERATE.rank)
        assertTrue(Severity.MODERATE.rank > Severity.LOW.rank)
    }

    @Test
    fun `atLeast compares by rank`() {
        assertTrue(Severity.CRITICAL.atLeast(Severity.LOW))
        assertTrue(Severity.MODERATE.atLeast(Severity.MODERATE))
        assertFalse(Severity.LOW.atLeast(Severity.MODERATE))
    }

    @Test
    fun `highest picks the worst`() {
        assertEquals(
            Severity.CRITICAL,
            Severity.highest(listOf(Severity.LOW, Severity.CRITICAL, Severity.MODERATE))
        )
        assertEquals(Severity.LOW, Severity.highest(emptyList()))
    }

    @Test
    fun `unknown wire name degrades to LOW rather than throwing`() {
        assertEquals(Severity.LOW, Severity.fromWireName("NOT_A_SEVERITY"))
    }
}

class AnomalyTypeTest {

    @Test
    fun `wire names are unique`() {
        val names = AnomalyType.entries.map { it.wireName }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `round trips through wire name`() {
        AnomalyType.entries.forEach {
            assertEquals(it, AnomalyType.fromWireName(it.wireName))
        }
    }

    @Test
    fun `unknown wire name returns null`() {
        assertNull(AnomalyType.fromWireName("not_a_type"))
    }
}

class RiskScoresTest {

    @Test
    fun `overall is the worst axis`() {
        assertEquals(70, RiskScores(30, 70, 45).overall)
        assertEquals(0, RiskScores.ZERO.overall)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects out of range high`() {
        RiskScores(101, 0, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects out of range negative`() {
        RiskScores(0, -1, 0)
    }
}

class AnomalyThresholdsTest {

    @Test
    fun `defaults are internally consistent`() {
        // The init block asserts the invariants; constructing is the test.
        assertNotNull(AnomalyThresholds.DEFAULT)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects critical spo2 above sustained spo2`() {
        // If critical (90) were above sustained (92) the critical rule could never be
        // the more severe of the two, which would silently invert alert urgency.
        AnomalyThresholds(spo2CriticalBelow = 95, spo2SustainedBelow = 92)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects inverted heart rate band`() {
        AnomalyThresholds(hrLowBpm = 140, hrHighBpm = 130)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects fever above critical fever`() {
        AnomalyThresholds(tempFeverC = 40f, tempCriticalFeverC = 39f)
    }
}

class PatientBaselineTest {

    @Test
    fun `averages at-rest samples`() {
        val samples = listOf(
            TestVitals.sample(0.0, hr = 60, spo2 = 98, temp = 36.6f),
            TestVitals.sample(1.0, hr = 70, spo2 = 96, temp = 36.8f)
        )
        val b = PatientBaseline.fromSamples(samples)
        assertEquals(65f, b.restingHeartRate!!, 0.01f)
        assertEquals(97f, b.typicalSpo2!!, 0.01f)
        assertEquals(36.7f, b.typicalBodyTempC!!, 0.01f)
        assertEquals(2, b.sampleCount)
    }

    /**
     * The important one. Folding exercise heart rates into a *resting* baseline would
     * inflate it until real tachycardia looked normal — the deviation rule would
     * quietly stop working, with no error anywhere.
     */
    @Test
    fun `excludes samples taken while moving`() {
        val samples = listOf(
            TestVitals.sample(0.0, hr = 60, motion = TestVitals.REST),
            TestVitals.sample(1.0, hr = 150, motion = TestVitals.MOVING),
            TestVitals.sample(2.0, hr = 62, motion = TestVitals.REST)
        )
        val b = PatientBaseline.fromSamples(samples)
        assertEquals(61f, b.restingHeartRate!!, 0.01f)
        assertEquals(2, b.sampleCount)
    }

    @Test
    fun `treats unknown motion as at rest`() {
        val b = PatientBaseline.fromSamples(listOf(TestVitals.sample(0.0, hr = 66, motion = null)))
        assertEquals(66f, b.restingHeartRate!!, 0.01f)
    }

    @Test
    fun `is unreliable until enough samples accumulate`() {
        val t = AnomalyThresholds.DEFAULT
        assertFalse(PatientBaseline(restingHeartRate = 70f, sampleCount = 5).isReliable(t))
        assertTrue(
            PatientBaseline(restingHeartRate = 70f, sampleCount = t.baselineMinSamples)
                .isReliable(t)
        )
    }

    @Test
    fun `empty input yields an empty baseline`() {
        assertEquals(PatientBaseline.EMPTY, PatientBaseline.fromSamples(emptyList()))
    }

    @Test
    fun `all-moving input yields an empty baseline`() {
        val samples = listOf(TestVitals.sample(0.0, hr = 150, motion = TestVitals.MOVING))
        assertEquals(PatientBaseline.EMPTY, PatientBaseline.fromSamples(samples))
    }
}

class TrendMathTest {

    @Test
    fun `slope is null for fewer than two points`() {
        assertNull(linearSlope(emptyList()))
        assertNull(linearSlope(listOf(5f)))
    }

    @Test
    fun `detects rising and falling`() {
        assertEquals(Trend.RISING, classifyTrend(listOf(1f, 2f, 3f, 4f), 0.1f))
        assertEquals(Trend.FALLING, classifyTrend(listOf(4f, 3f, 2f, 1f), 0.1f))
    }

    /** Sensor jitter must not read as a real trend. */
    @Test
    fun `noise inside the stable band reads as stable`() {
        assertEquals(Trend.STABLE, classifyTrend(listOf(70f, 71f, 70f, 71f), 2f))
    }

    @Test
    fun `single point is stable rather than an exception`() {
        assertEquals(Trend.STABLE, classifyTrend(listOf(70f), 0.1f))
    }
}
